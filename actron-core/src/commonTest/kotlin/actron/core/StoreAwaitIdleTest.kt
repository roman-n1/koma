@file:OptIn(InternalActronApi::class)

package actron.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * `awaitIdle` on a Store running on [Dispatchers.Default]: it waits for the launches of `enter {}`
 * and `action {}` and for what they dispatch or transact, not for a `subscribe {}`; it reports
 * what is pending on a timeout; it refuses to run inside the Store; a closed Store is idle.
 *
 * ```
 * Counter --Add--> Counter          launch { delay; effects++ }
 * Counter --Chain--> Counter        launch { delay; dispatch(Add) }
 * Counter --Transact--> Counter     launch { delay; transaction { count++ } }
 * Counter --Subscribe--> Counter    subscribe { awaitCancellation() }
 * Counter --Block--> Counter        launch { gate.await() }
 * Counter --Finish--> Done
 * ```
 */
class StoreAwaitIdleTest {

    data class Counter(val count: Int = 0) : State

    data object Done : State

    sealed interface Act : Action {
        data object Add : Act
        data object Chain : Act
        data object Transact : Act
        data object Subscribe : Act
        data object Block : Act
        data object Finish : Act
    }

    private class Fixture {
        var effects = 0
        val gate = CompletableDeferred<Unit>()
        val handled = mutableListOf<Throwable>()
        lateinit var store: Store<State, Act, Nothing>

        init {
            store = Store(Counter()) {
            coroutineContext(Dispatchers.Default)
            exceptionHandler(ExceptionHandler { handled += it })
            state<Counter> {
                enter { subscribe { awaitCancellation() } }
                action<Act.Add> { launch { delay(20); effects++ } }
                action<Act.Chain> { launch { delay(10); store.dispatch(Act.Add) } }
                action<Act.Transact> { launch { delay(10); transaction { nextState { Counter(state.count + 1) } } } }
                action<Act.Subscribe> { subscribe { awaitCancellation() } }
                action<Act.Block> { launch { gate.await() } }
                action<Act.Finish> { nextState { Done } }
            }
            state<Done> { }
            }
        }
    }

    // The Store runs on real dispatchers, so the wait runs on one too: under runTest's own
    // dispatcher the timeout would be virtual and expire as soon as the body suspends.
    @Suppress("UNCHECKED_CAST")
    private suspend fun Store<State, Act, Nothing>.awaitIdleForTest(timeout: Duration = 5.seconds): StorePendingWork =
        withContext(Dispatchers.Default) { (this@awaitIdleForTest as StoreInternalApi<State, Act, Nothing>).awaitIdle(timeout) }

    @Test
    fun awaitIdle_waitsForAFireAndForgetLaunch_andForWhatALaunchDispatchesOrTransacts() = runTest {
        val f = Fixture()
        f.store.dispatchAndAwaitForTest(Act.Add)
        f.store.dispatchAndAwaitForTest(Act.Chain)
        f.store.dispatchAndAwaitForTest(Act.Transact)

        val pending = f.store.awaitIdleForTest()

        assertEquals(StorePendingWork(0, 0), pending)
        assertEquals(2, f.effects, "the Add launch and the one the Chain launch dispatched")
        assertEquals(Counter(1), f.store.currentState, "the transaction the launch queued was decided")
        assertTrue(f.handled.isEmpty(), f.handled.toString())
        f.store.close()
    }

    @Test
    fun aSubscription_isNotWaitedFor_andCountsAsNoLaunch() = runTest {
        val f = Fixture()
        f.store.dispatchAndAwaitForTest(Act.Subscribe)

        val pending = f.store.awaitIdleForTest(timeout = 500.milliseconds)

        assertEquals(StorePendingWork(0, 0), pending, "the enter's and the action's subscriptions run until the state exits")
        f.store.close()
    }

    @Test
    fun awaitIdle_timesOut_andReportsWhatIsPending_thenIsIdleOnceTheLaunchEnds() = runTest {
        val f = Fixture()
        f.store.dispatchAndAwaitForTest(Act.Block)

        val pending = f.store.awaitIdleForTest(timeout = 100.milliseconds)

        assertEquals(StorePendingWork(inputs = 0, launches = 1), pending)
        f.gate.complete(Unit)
        assertEquals(StorePendingWork(0, 0), f.store.awaitIdleForTest())
        f.store.close()
    }

    @Test
    fun launchesOfAnExitedState_areCancelled_soTheStoreIsIdleAtOnce() = runTest {
        val f = Fixture()
        f.store.dispatchAndAwaitForTest(Act.Block)
        f.store.dispatchAndAwaitForTest(Act.Finish)

        val pending = f.store.awaitIdleForTest(timeout = 1.seconds)

        assertEquals(StorePendingWork(0, 0), pending, "the blocked launch died with its state")
        f.store.close()
    }

    @Test
    fun awaitIdle_fromInsideTheStore_failsFast() = runTest {
        val handled = mutableListOf<Throwable>()
        lateinit var store: Store<State, Act, Nothing>
        store = Store(Counter()) {
            coroutineContext(Dispatchers.Default)
            exceptionHandler(ExceptionHandler { handled += it })
            state<Counter> {
                action<Act.Add> { store.awaitIdleForTest() }
            }
        }

        store.dispatchAndAwaitForTest(Act.Add)

        val failure = handled.single()
        assertTrue(failure is IllegalStateException && "awaitIdle must not be called from a handler" in failure.message.orEmpty(), failure.toString())
        store.close()
    }

    @Test
    fun aClosedStore_isIdle() = runTest {
        val f = Fixture()
        f.store.dispatchAndAwaitForTest(Act.Block)
        f.store.close()

        assertEquals(StorePendingWork(0, 0), f.store.awaitIdleForTest(timeout = 2.seconds), "the launch was cancelled with the Store")
    }

    @Test
    fun underAStorm_awaitIdle_returnsOnlyWhenEveryLaunchedEffectHappened() = runTest {
        repeat(10) { round ->
            val effects = MutableStateFlow(0)
            lateinit var store: Store<State, Act, Nothing>
            store = Store(Counter()) {
                coroutineContext(Dispatchers.Default)
                state<Counter> {
                    action<Act.Add> { launch(Dispatchers.Default) { delay(Random.nextLong(0, 3)); effects.update { it + 1 } } }
                    action<Act.Chain> { launch { delay(Random.nextLong(0, 2)); store.dispatch(Act.Add) } }
                }
            }
            val senders = 8
            val perSender = 100
            withContext(Dispatchers.Default) {
                coroutineScope {
                    repeat(senders) { sender ->
                        launch { repeat(perSender) { index -> store.dispatch(if ((sender + index) % 3 == 0) Act.Chain else Act.Add) } }
                    }
                }
            }

            val pending = store.awaitIdleForTest(timeout = 30.seconds)

            assertEquals(StorePendingWork(0, 0), pending, "round $round")
            assertEquals(senders * perSender, effects.value, "round $round: every launched effect, including those the Chain launches dispatched, happened before awaitIdle returned")
            store.close()
        }
    }
}

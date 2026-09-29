package koma.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Soak test on [Dispatchers.Default] that runs on every target in CI (no reflection, unlike
 * `StoreSoakJvmTest`): several producers dispatch through variant changes, a recover handler,
 * cancelled launches and transactions, and every increment must survive with
 * [PendingActionPolicy.Keep]; then a storm is closed mid-work and nothing runs afterwards.
 * On JS and Wasm the default dispatcher is single-threaded, so the test is smaller there.
 */
class StoreSoakTest {

    sealed interface S : State {
        val count: Int
        data class Idle(override val count: Int = 0, val results: Int = 0) : S
        data class Busy(override val count: Int = 0, val ticks: Int = 0) : S
        data class Failed(override val count: Int = 0) : S
    }

    sealed interface A : Action {
        data object Inc : A
        data object Go : A
        data object Finish : A
        data object Fail : A
        data object Recover : A
        data class Query(val q: Int) : A
    }

    data object Ev : Event

    private fun soakStore(handled: MutableList<Throwable>, policy: PendingActionPolicy): Store<S, A, Ev> = Store(S.Idle()) {
        coroutineContext(Dispatchers.Default)
        pendingActionPolicy(policy)
        exceptionHandler(ExceptionHandler { handled += it })
        plugin(Plugin(onState = { _, _ -> }, onEvent = { _, _ -> }))
        state<S.Idle> {
            action<A.Go> { nextState { S.Busy(state.count) } }
            action<A.Fail> { throw IllegalStateException("fail") }
            recover<IllegalStateException> { nextState { S.Failed(state.count) } }
            action<A.Query> {
                launch(control = LaunchControl.CancelPrevious()) {
                    delay(1)
                    transaction { nextState { state.copy(results = state.results + 1) } }
                }
            }
        }
        state<S.Busy> {
            enter {
                launch {
                    while (isActive) {
                        transaction { nextState { state.copy(ticks = state.ticks + 1) } }
                        delay(1)
                    }
                }
            }
            action<A.Finish> {
                event(Ev)
                nextState { S.Idle(state.count) }
            }
        }
        state<S.Failed> {
            action<A.Recover> { nextState { S.Idle(state.count) } }
        }
        state<S> {
            action<A.Inc> {
                nextState {
                    when (val s = state) {
                        is S.Idle -> s.copy(count = s.count + 1)
                        is S.Busy -> s.copy(count = s.count + 1)
                        is S.Failed -> s.copy(count = s.count + 1)
                    }
                }
            }
            action<A> { }
        }
    }

    @Test
    fun soak_keepPolicy_losesNoIncrement() = runTest {
        val handled = mutableListOf<Throwable>()
        val store = soakStore(handled, PendingActionPolicy.Keep)
        val producers = 4
        val perProducer = 500
        var incs = 0
        withContext(Dispatchers.Default) {
            withTimeout(30_000) {
                coroutineScope {
                    repeat(producers) { p ->
                        launch {
                            val random = Random(p)
                            repeat(perProducer) { i ->
                                store.dispatch(
                                    when (random.nextInt(8)) {
                                        0 -> A.Go
                                        1 -> A.Finish
                                        2 -> A.Fail
                                        3 -> A.Recover
                                        4 -> A.Query(i)
                                        else -> A.Inc
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        // Inc is deterministic per producer seed: count them the same way.
        repeat(producers) { p ->
            val random = Random(p)
            repeat(perProducer) { if (random.nextInt(8) >= 5) incs++ }
        }
        withContext(Dispatchers.Default) {
            withTimeout(30_000) { store.state.first { it.count == incs } }
        }
        assertEquals(incs, store.currentState.count)
        assertEquals(emptyList(), handled.map { it.toString() })
        store.close()
    }

    @Test
    fun soak_closeMidWork_stopsEverything() = runTest {
        val handled = mutableListOf<Throwable>()
        val store = soakStore(handled, PendingActionPolicy.ClearOnStateExit)
        try {
            withContext(Dispatchers.Default) {
                withTimeout(30_000) {
                    // Start the state-owned ticker before racing close against queued actions.
                    store.dispatchAndAwaitForTest(A.Go)
                    val queued = CompletableDeferred<Unit>()
                    coroutineScope {
                        val storm = launch {
                            var i = 0
                            while (isActive) {
                                store.dispatch(if (i % 50 == 0) A.Go else if (i % 50 == 25) A.Finish else A.Inc)
                                i++
                                if (i % 100 == 0) {
                                    queued.complete(Unit)
                                    delay(1)
                                }
                            }
                        }
                        // ClearOnStateExit may discard every increment in a batch on a
                        // single-threaded dispatcher, so a count threshold is not a barrier.
                        queued.await()
                        store.close()
                        storm.cancelAndJoin()
                    }
                    val frozen = store.currentState
                    delay(100)
                    assertEquals(frozen, store.currentState)
                    store.dispatch(A.Inc)
                    delay(50)
                    assertEquals(frozen, store.currentState)
                }
            }
        } finally {
            store.close()
        }
        assertEquals(emptyList(), handled.map { it.toString() })
    }
}

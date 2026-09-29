package koma.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Regression tests for issues found in review.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StoreRegressionTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    sealed interface AppState : State {
        data class Active(val value: Int = 0) : AppState
        data object Done : AppState
    }

    sealed interface AppAction : Action {
        data object Finish : AppAction
        data object LaunchIncrement : AppAction
        data object FollowUp : AppAction
    }

    /**
     * When `exit {}` throws, the transition is aborted and the Store stays in the old state, so the
     * state's runtime must stay usable: later `launch {}` and `cancelLaunch()` calls work and
     * coroutines started from its `enter {}` keep running.
     *
     * ```
     * Active --Finish--> (exit throws) --recover--> Active   // runtime of Active is kept
     * ```
     */
    @Test
    fun exitException_recoveredInPlace_keepsTheStateRuntimeUsable() = runTest(testDispatcher) {
        val handled = mutableListOf<Throwable>()
        val store: Store<AppState, AppAction, Nothing> = Store(AppState.Active()) {
            coroutineContext(Dispatchers.Unconfined)
            exceptionHandler(ExceptionHandler { handled += it })
            state<AppState.Active> {
                action<AppAction.Finish> { nextState { AppState.Done } }
                action<AppAction.LaunchIncrement> {
                    launch { transaction { nextState { AppState.Active(state.value + 1) } } }
                }
                exit { throw IllegalStateException("exit failed") }
                recover<IllegalStateException> { }
            }
        }

        store.dispatchAndAwaitForTest(AppAction.Finish)
        store.dispatchAndAwaitForTest(AppAction.LaunchIncrement)

        assertEquals(emptyList(), handled.map { it.message })
        assertEquals(AppState.Active(value = 1), store.currentState)
    }

    /**
     * With [PendingActionPolicy.ClearOnStateExit], pending actions are cleared before the new state
     * is committed, so an action a plugin dispatches from `onState` in reaction to the new state is
     * kept.
     *
     * ```
     * Active --Finish--> Done --FollowUp--> Active(100)   // FollowUp comes from plugin.onState
     * ```
     */
    @Test
    fun clearOnStateExit_keepsActionsDispatchedByPluginsForTheNewState() = runTest(testDispatcher) {
        val store: Store<AppState, AppAction, Nothing> = Store(AppState.Active()) {
            coroutineContext(Dispatchers.Unconfined)
            plugin(
                Plugin(
                    onState = { prevState, state ->
                        if (prevState is AppState.Active && state is AppState.Done) dispatch(AppAction.FollowUp)
                    },
                ),
            )
            state<AppState.Active> {
                action<AppAction.Finish> { nextState { AppState.Done } }
            }
            state<AppState.Done> {
                action<AppAction.FollowUp> { nextState { AppState.Active(value = 100) } }
            }
        }

        store.dispatchAndAwaitForTest(AppAction.Finish)

        assertEquals(AppState.Active(value = 100), store.currentState)
    }

    /**
     * Operators that stop collecting [Store.state] early, such as `first()`, `first {}` and
     * `take(n)`, return. They used to abort only an internal child coroutine and hang forever.
     *
     * Runs on [Dispatchers.Default] with a real timeout so a regression fails instead of hanging.
     */
    @Test
    fun stateFirst_returnsOnceThePredicateMatches() = runTest(testDispatcher) {
        val store: Store<AppState, AppAction, Nothing> = Store(AppState.Active()) {
            coroutineContext(Dispatchers.Default)
            state<AppState.Active> {
                action<AppAction.Finish> { nextState { AppState.Done } }
            }
        }

        val results = withContext(Dispatchers.Default) {
            withTimeoutOrNull(2_000) {
                val initial = store.state.first()
                val taken = store.state.take(1).toList()
                store.dispatch(AppAction.Finish)
                val done = store.state.first { it is AppState.Done }
                listOf(initial, taken.single(), done)
            }
        }

        assertEquals(listOf(AppState.Active(), AppState.Active(), AppState.Done), results)
        store.close()
    }

    data class Search(val query: String = "", val log: List<String> = emptyList()) : State

    data class Query(val query: String) : Action

    /**
     * A transaction queued by a launch that [LaunchControl.CancelPrevious] then cancels must not
     * commit. The transaction runs in the Store's root scope (so it is atomic once started), so
     * it has to check that its caller is still active when it gets the lock.
     *
     * ```
     * Query(a): launch L1 --> transaction T1 waits for the lock
     * Query(b): (holds the lock) cancels L1, launches L2
     * T1 gets the lock: caller L1 is cancelled --> skipped; T2 commits b
     * ```
     */
    @Test
    fun cancelPrevious_skipsATransactionQueuedByTheCancelledLaunch() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store: Store<Search, Query, Nothing> = Store(Search()) {
            coroutineContext(dispatcher)
            state<Search> {
                action<Query> {
                    // Holds the lock long enough for the previous launch's transaction to queue.
                    delay(50)
                    nextState { state.copy(query = action.query, log = state.log + "query:${action.query}") }
                    launch(control = LaunchControl.CancelPrevious()) {
                        delay(10)
                        transaction {
                            nextState { state.copy(log = state.log + "result:${action.query}") }
                        }
                    }
                }
            }
        }

        store.dispatch(Query("a"))
        testScheduler.advanceTimeBy(51)
        store.dispatch(Query("b"))
        advanceUntilIdle()

        assertEquals(listOf("query:a", "query:b", "result:b"), store.currentState.log)
        store.close()
    }

    sealed interface EnterState : State {
        data object Start : EnterState
        data class Entered(val entered: Boolean = false, val launched: Boolean = false) : EnterState
    }

    sealed interface EnterAction : Action {
        data object Go : EnterAction
        data object LaunchInEntered : EnterAction
    }

    private fun enterStore(
        handled: MutableList<Throwable>,
        stateSaver: StateSaver<EnterState> = StateSaver.Noop(),
        plugin: Plugin<EnterState, EnterAction, Nothing>? = null,
    ): Store<EnterState, EnterAction, Nothing> = Store(EnterState.Start) {
        coroutineContext(Dispatchers.Unconfined)
        exceptionHandler(ExceptionHandler { handled += it })
        stateSaver(stateSaver)
        plugin?.let { plugin(it) }
        state<EnterState.Start> {
            action<EnterAction.Go> { nextState { EnterState.Entered() } }
        }
        state<EnterState.Entered> {
            enter { nextState { state.copy(entered = true) } }
            action<EnterAction.LaunchInEntered> {
                launch { transaction { nextState { state.copy(launched = true) } } }
            }
        }
    }

    /**
     * When [StateSaver.save] throws while committing a new state variant, the state is already
     * committed, so the transition must still finish: the new state's `enter {}` runs and its
     * runtime exists, so later `launch {}` calls work. The error is reported once.
     *
     * ```
     * Start --Go--> Entered (save throws) --enter--> Entered(entered = true)
     * ```
     */
    @Test
    fun stateSaverException_onVariantChange_stillEntersTheNewState() = runTest(testDispatcher) {
        val handled = mutableListOf<Throwable>()
        val store = enterStore(
            handled = handled,
            stateSaver = StateSaver(
                save = { if (it is EnterState.Entered && !it.entered) throw IllegalStateException("disk full") },
                restore = { null },
            ),
        )

        store.dispatchAndAwaitForTest(EnterAction.Go)
        store.dispatchAndAwaitForTest(EnterAction.LaunchInEntered)

        assertEquals(EnterState.Entered(entered = true, launched = true), store.currentState)
        assertEquals(listOf("disk full"), handled.map { it.message })
    }

    /**
     * Same for a plugin whose `onState` throws: reported, but the transition finishes.
     */
    @Test
    fun pluginOnStateException_onVariantChange_stillEntersTheNewState() = runTest(testDispatcher) {
        val handled = mutableListOf<Throwable>()
        val store = enterStore(
            handled = handled,
            plugin = Plugin(onState = { _, state -> if (state == EnterState.Entered()) throw IllegalStateException("plugin failed") }),
        )

        store.dispatchAndAwaitForTest(EnterAction.Go)
        store.dispatchAndAwaitForTest(EnterAction.LaunchInEntered)

        assertEquals(EnterState.Entered(entered = true, launched = true), store.currentState)
        assertEquals(listOf("plugin failed"), handled.map { it.message })
    }

    data class Emitting(val done: Boolean = false) : State

    data object EmitThenFinish : Action

    data object Emitted : Event

    /**
     * A plugin whose `onEvent` throws is reported; the handler that emitted the event still
     * completes and its next state is committed.
     */
    @Test
    fun pluginOnEventException_doesNotAbortTheEmittingHandler() = runTest(testDispatcher) {
        val handled = mutableListOf<Throwable>()
        val store: Store<Emitting, EmitThenFinish, Emitted> = Store(Emitting()) {
            coroutineContext(Dispatchers.Unconfined)
            exceptionHandler(ExceptionHandler { handled += it })
            plugin(Plugin(onEvent = { _, _ -> throw IllegalStateException("onEvent failed") }))
            state<Emitting> {
                action<EmitThenFinish> {
                    event(Emitted)
                    nextState { state.copy(done = true) }
                }
            }
        }

        store.dispatchAndAwaitForTest(EmitThenFinish)

        assertEquals(Emitting(done = true), store.currentState)
        assertEquals(listOf("onEvent failed"), handled.map { it.message })
    }

    data class Booting(val ready: Boolean = false) : State

    data object Poke : Action

    /**
     * A failing initial `enter {}` that no `recover {}` handles is reported once; the Store counts
     * as started, so the action that triggered startup and later ones are processed, and the
     * plugins are not started again. Startup is not retried: every later dispatch would otherwise
     * run the failing handler again and drop its action.
     */
    @Test
    fun failedInitialEnter_isReportedOnceAndTheStoreProcessesActions() = runTest(testDispatcher) {
        var enterCalls = 0
        var pluginStarts = 0
        var pokes = 0
        val handled = mutableListOf<Throwable>()
        val store: Store<Booting, Poke, Nothing> = Store(Booting()) {
            coroutineContext(Dispatchers.Unconfined)
            exceptionHandler(ExceptionHandler { handled += it })
            plugin(Plugin(onStart = { pluginStarts++ }))
            state<Booting> {
                enter {
                    enterCalls++
                    throw IllegalStateException("boot failed")
                }
                action<Poke> { pokes++ }
            }
        }

        store.dispatchAndAwaitForTest(Poke)
        store.dispatchAndAwaitForTest(Poke)

        assertEquals(1, enterCalls)
        assertEquals(1, pluginStarts)
        assertEquals(2, pokes)
        assertEquals(listOf("boot failed"), handled.map { it.message })
    }

    sealed interface Net : State {
        data object Idle : Net
        data class Done(val result: String) : Net
        data class Failed(val error: String) : Net
    }

    sealed interface NetAction : Action {
        data object Load : NetAction
        data object LoadInLaunch : NetAction
        data object LoadInTransaction : NetAction
        data object Ping : NetAction
    }

    private fun netStore(handled: MutableList<Throwable>, dispatcher: TestDispatcher, plugin: Plugin<Net, NetAction, Nothing>? = null, policy: PluginExecutionPolicy = PluginExecutionPolicy.Concurrent): Store<Net, NetAction, Nothing> = Store(Net.Idle) {
        coroutineContext(dispatcher)
        exceptionHandler(ExceptionHandler { handled += it })
        pluginExecutionPolicy(policy)
        plugin?.let { plugin(it) }
        state<Net.Idle> {
            action<NetAction.Load> {
                withTimeout(10) { delay(10_000) }
                nextState { Net.Done("loaded") }
            }
            action<NetAction.LoadInLaunch> {
                launch {
                    withTimeout(10) { delay(10_000) }
                    transaction { nextState { Net.Done("loaded") } }
                }
            }
            action<NetAction.LoadInTransaction> {
                launch {
                    transaction {
                        withTimeout(10) { delay(10_000) }
                        nextState { Net.Done("loaded") }
                    }
                }
            }
            action<NetAction.Ping> { nextState { Net.Done("pong") } }
            recover<TimeoutCancellationException> { nextState { Net.Failed("timeout") } }
        }
    }

    /**
     * A `withTimeout {}` that expires inside a handler is a failure of that handler, not the
     * Store being cancelled: it reaches `recover {}` like any other exception. The same holds in
     * a launched coroutine and inside a transaction.
     */
    @Test
    fun expiredWithTimeout_inHandlerLaunchAndTransaction_isRecovered() = runTest {
        for (action in listOf(NetAction.Load, NetAction.LoadInLaunch, NetAction.LoadInTransaction)) {
            val handled = mutableListOf<Throwable>()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = netStore(handled, dispatcher)
            store.dispatch(action)
            advanceUntilIdle()
            assertEquals(Net.Failed("timeout"), store.currentState, "$action")
            assertEquals(emptyList(), handled, "$action")
            store.close()
        }
    }

    /**
     * An expired `withTimeout {}` in the initial `enter {}` is recovered too, and the Store
     * starts.
     */
    @Test
    fun expiredWithTimeout_inInitialEnter_isRecoveredAndTheStoreStarts() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val handled = mutableListOf<Throwable>()
        var pings = 0
        val store: Store<Net, NetAction, Nothing> = Store(Net.Idle) {
            coroutineContext(dispatcher)
            exceptionHandler(ExceptionHandler { handled += it })
            state<Net.Idle> {
                enter { withTimeout(10) { delay(10_000) } }
                action<NetAction.Ping> { pings++ }
                recover<TimeoutCancellationException> { }
            }
        }

        store.dispatch(NetAction.Ping)
        store.dispatch(NetAction.Ping)
        advanceUntilIdle()

        assertEquals(2, pings)
        assertEquals(emptyList(), handled)
        store.close()
    }

    /**
     * A plugin hook whose `withTimeout {}` expires fails the step under both execution policies;
     * with [PluginExecutionPolicy.Concurrent] it used to end its own coroutine silently.
     */
    @Test
    fun pluginHookTimingOut_failsTheActionUnderBothPolicies() = runTest {
        for (policy in PluginExecutionPolicy.entries) {
            val handled = mutableListOf<Throwable>()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = netStore(handled, dispatcher, plugin = Plugin(onAction = { _, _ -> withTimeout(10) { delay(10_000) } }), policy = policy)
            store.dispatch(NetAction.Ping)
            advanceUntilIdle()
            assertEquals(Net.Idle, store.currentState, "$policy")
            assertIs<TimeoutCancellationException>(handled.singleOrNull(), "$policy: $handled")
            store.close()
        }
    }

    /**
     * When `recover {}` itself throws, the error it was handling is kept as a suppressed exception
     * of the reported one.
     */
    @Test
    fun recoverThatThrows_keepsTheOriginalErrorAsSuppressed() = runTest(testDispatcher) {
        val handled = mutableListOf<Throwable>()
        val store: Store<Booting, Poke, Nothing> = Store(Booting()) {
            coroutineContext(Dispatchers.Unconfined)
            exceptionHandler(ExceptionHandler { handled += it })
            state<Booting> {
                action<Poke> { throw IllegalStateException("original") }
                recover<IllegalStateException> { throw IllegalArgumentException("recover failed") }
            }
        }

        store.dispatchAndAwaitForTest(Poke)

        val reported = assertIs<IllegalArgumentException>(handled.single())
        assertEquals(listOf("original"), reported.suppressedExceptions.map { it.message })
    }

    sealed interface Loop : State {
        data object Start : Loop
        data class Left(val hop: Int) : Loop
        data class Right(val hop: Int) : Loop
    }

    data object Enter : Action

    /**
     * `enter {}` handlers that move to each other forever would overflow the stack (or spin while
     * holding the lock, when they suspend). The chain is cut with an error that names the problem,
     * and the Store keeps working afterwards.
     */
    @Test
    fun enterLoop_failsWithAnErrorInsteadOfOverflowingTheStack() = runTest(testDispatcher) {
        val handled = mutableListOf<Throwable>()
        var pokes = 0
        val store: Store<Loop, Action, Nothing> = Store(Loop.Start) {
            coroutineContext(Dispatchers.Unconfined)
            exceptionHandler(ExceptionHandler { handled += it })
            state<Loop.Start> { action<Enter> { nextState { Loop.Left(0) } } }
            state<Loop.Left> { enter { nextState { Loop.Right(state.hop + 1) } } }
            state<Loop.Right> { enter { nextState { Loop.Left(state.hop + 1) } } }
            state<Loop> { action<Poke> { pokes++ } }
        }

        store.dispatchAndAwaitForTest(Enter)
        store.dispatchAndAwaitForTest(Poke)

        val error = assertIs<IllegalStateException>(handled.single())
        assertTrue("loop" in error.message.orEmpty(), error.message)
        assertEquals(1, pokes)
    }

    data object Nested : Action

    /**
     * Awaiting the Store from inside one of its own handlers or plugin hooks used to deadlock it
     * silently (the wait is for the lock the caller holds). It now fails fast with an error that
     * names the problem, and the Store keeps working. From a `launch {}` it is allowed.
     */
    @Test
    fun awaitingTheStoreFromInsideItsOwnHandler_failsFastInsteadOfDeadlocking() = runTest(testDispatcher) {
        val handled = mutableListOf<Throwable>()
        val recovered = mutableListOf<String>()
        var pokes = 0
        lateinit var store: Store<Booting, Action, Nothing>
        store = Store(Booting()) {
            coroutineContext(Dispatchers.Unconfined)
            exceptionHandler(ExceptionHandler { handled += it })
            plugin(Plugin(onAction = { _, action -> if (action is Enter) store.dispatchAndAwaitForTest(Poke) }))
            state<Booting> {
                enter { store.startAndAwaitForTest() }
                action<Nested> { store.dispatchAndAwaitForTest(Poke) }
                action<Poke> { pokes++ }
                recover<IllegalStateException> { recovered += error.message.orEmpty().substringBefore(":") }
            }
        }

        store.dispatchAndAwaitForTest(Nested)
        store.dispatchAndAwaitForTest(Enter)
        store.dispatchAndAwaitForTest(Poke)

        assertEquals(1, pokes)
        assertEquals(listOf("[Koma] startAndAwait must not be called from a handler, plugin hook or transaction of the same Store", "[Koma] dispatchAndAwait must not be called from a handler, plugin hook or transaction of the same Store"), recovered)
        // The plugin hook's failure bypasses recover {} and reaches the exception handler.
        assertEquals(listOf("dispatchAndAwait"), handled.map { it.message.orEmpty().substringAfter("[Koma] ").substringBefore(" ") })
    }
}

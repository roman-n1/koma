package koma.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals

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
     * When the initial `enter {}` fails, startup is retried on the next dispatch, but the plugins
     * are not started again: a message plugin would otherwise subscribe twice and a recorder would
     * record the start state twice.
     */
    @Test
    fun retriedStartup_doesNotStartPluginsAgain() = runTest(testDispatcher) {
        var enterCalls = 0
        var pluginStarts = 0
        val store: Store<Booting, Poke, Nothing> = Store(Booting()) {
            coroutineContext(Dispatchers.Unconfined)
            exceptionHandler(ExceptionHandler.Ignore)
            plugin(Plugin(onStart = { pluginStarts++ }))
            state<Booting> {
                enter {
                    if (++enterCalls == 1) throw IllegalStateException("transient")
                    nextState { state.copy(ready = true) }
                }
                action<Poke> { }
            }
        }

        store.dispatchAndAwaitForTest(Poke)
        store.dispatchAndAwaitForTest(Poke)

        assertEquals(Booting(ready = true), store.currentState)
        assertEquals(2, enterCalls)
        assertEquals(1, pluginStarts)
    }
}

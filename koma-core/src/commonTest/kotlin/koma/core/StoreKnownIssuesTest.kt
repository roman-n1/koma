package koma.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Reproductions of known issues. Each test describes the expected behavior and is ignored until
 * the issue is fixed; remove `@Ignore` together with the fix.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StoreKnownIssuesTest {

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
     * Known issue: when `exit {}` throws, the state's runtime is cancelled and removed even though
     * the transition is aborted and the Store stays in the old state. Every later `launch {}` or
     * `cancelLaunch()` in that state then fails with "State scope is not found", and coroutines
     * started from its `enter {}` are already cancelled.
     *
     * ```
     * Active --Finish--> (exit throws) --recover--> Active   // runtime of Active is gone
     * ```
     */
    @Ignore
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
     * Known issue: with [PendingActionPolicy.ClearOnStateExit], pending actions are cleared after
     * plugins have been notified through `onState`. An action a plugin dispatches in reaction to
     * the new state is therefore cancelled together with the stale ones.
     *
     * ```
     * Active --Finish--> Done   // plugin.onState dispatches FollowUp, which is then dropped
     * ```
     */
    @Ignore
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
     * Known issue: [Store.state] runs the collector in a child coroutine and then waits in
     * `awaitCancellation()`. Operators that stop collecting early, such as `first()`, `first {}`
     * and `take(n)`, abort only that child, so the call never returns.
     *
     * Runs on [Dispatchers.Default] with a real timeout so the test fails instead of hanging.
     */
    @Ignore
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
}

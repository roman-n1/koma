package koma.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a transition keeps when its `exit {}` fails, and when pending actions are cleared.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StoreTransitionTest {

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
}

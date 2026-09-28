package koma.test

import koma.core.Action
import koma.core.Event
import koma.core.State
import koma.core.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ```
 * Loading --enter--> Main(0)
 * Main    --Increment--> Main(count + 1)
 * Main    --Reset--> Main(0)          (specific handler, registered first)
 * any     --Reset--> Loading          (broad fallback, registered last)
 * Error   (no handler for Increment)
 * ```
 */
class ActionMatchDiagnosticsTest {

    sealed interface AppState : State {
        data object Loading : AppState
        data class Main(val count: Int) : AppState
        data object Error : AppState
    }

    sealed interface AppAction : Action {
        data object Increment : AppAction
        data object Reset : AppAction
    }

    sealed interface AppEvent : Event

    private fun createStore(): Store<AppState, AppAction, AppEvent> {
        return Store(AppState.Loading) {
            coroutineContext(Dispatchers.Unconfined)
            state<AppState.Loading> {
                enter {
                    nextState { AppState.Main(count = 0) }
                }
            }
            state<AppState.Main> {
                action<AppAction.Increment> {
                    nextState { state.copy(count = state.count + 1) }
                }
                action<AppAction.Reset> {
                    nextState { AppState.Main(count = 0) }
                }
            }
            state<AppState> {
                action<AppAction.Reset> {
                    nextState { AppState.Loading }
                }
            }
        }
    }

    @Test
    fun listsAllActionHandlersInFirstMatchOrder() {
        val diagnostics = createStore().diagnoseActionMatches(AppState.Main(0), AppAction.Increment)

        assertEquals(
            listOf(
                ActionHandlerDescription(0, AppState.Main::class, AppAction.Increment::class),
                ActionHandlerDescription(1, AppState.Main::class, AppAction.Reset::class),
                ActionHandlerDescription(2, AppState::class, AppAction.Reset::class),
            ),
            diagnostics.handlers,
        )
    }

    @Test
    fun oneMatchIsSelected() {
        val diagnostics = createStore().diagnoseActionMatches(AppState.Main(0), AppAction.Increment)

        assertEquals(listOf(0), diagnostics.matchedHandlerIndices)
        assertEquals(1, diagnostics.matchedHandlerCount)
        assertEquals(0, diagnostics.selectedHandlerIndex)
    }

    @Test
    fun noMatchMeansUnhandled() {
        val diagnostics = createStore().diagnoseActionMatches(AppState.Error, AppAction.Increment)

        assertTrue(diagnostics.matchedHandlerIndices.isEmpty())
        assertEquals(0, diagnostics.matchedHandlerCount)
        assertNull(diagnostics.selectedHandlerIndex)
        assertEquals(
            "ActionMatchDiagnostics(state=Error, action=Increment: no handler matches)",
            diagnostics.toString(),
        )
    }

    @Test
    fun severalMatchesShowSelectedAndShadowedHandlers() {
        val diagnostics = createStore().diagnoseActionMatches(AppState.Main(0), AppAction.Reset)

        assertEquals(listOf(1, 2), diagnostics.matchedHandlerIndices)
        assertEquals(1, diagnostics.selectedHandlerIndex)
        assertEquals(
            "ActionMatchDiagnostics(state=Main(count=0), action=Reset: " +
                "selected #1 state<Main> / action<Reset>, shadowed #2 state<AppState> / action<Reset>)",
            diagnostics.toString(),
        )
    }

    @Test
    fun broadHandlerIsTheOnlyMatchOutsideSpecificState() {
        val diagnostics = createStore().diagnoseActionMatches(AppState.Error, AppAction.Reset)

        assertEquals(listOf(2), diagnostics.matchedHandlerIndices)
    }

    @Test
    fun diagnosingDoesNotRunHandlersOrStartTheStore() {
        val store = createStore()

        store.diagnoseActionMatches(AppState.Main(5), AppAction.Increment)

        assertEquals(AppState.Loading, store.currentState)
    }

    @Test
    fun suspendingOverloadUsesStateAfterStartup() = runTest {
        val store = createStore()

        val diagnostics = store.diagnoseActionMatches(AppAction.Increment)

        assertEquals(AppState.Main(0), diagnostics.state)
        assertEquals(0, diagnostics.selectedHandlerIndex)
    }
}

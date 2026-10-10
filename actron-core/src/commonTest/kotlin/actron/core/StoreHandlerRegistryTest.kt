package actron.core

import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Checks that the handler registry records which types each handler was declared for,
 * in the same order the Store uses for first-match selection.
 *
 * ```
 * Loading --enter--> Main
 * Main   --Increment--> Main(count + 1)
 * Main   --Fail--> throws --recover<IllegalStateException>--> Error
 * Error  --exit-->
 * ```
 */
class StoreHandlerRegistryTest {

    sealed interface AppState : State {
        data object Loading : AppState
        data class Main(val count: Int) : AppState
        data object Error : AppState
    }

    sealed interface AppAction : Action {
        data object Increment : AppAction
        data object Fail : AppAction
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
                action<AppAction.Fail> {
                    throw IllegalStateException("fail")
                }
                recover<IllegalStateException> {
                    nextState { AppState.Error }
                }
            }
            state<AppState.Error> {
                exit { }
            }
            state<AppState> {
                action<AppAction> { }
            }
        }
    }

    private fun Store<AppState, AppAction, AppEvent>.registry(): HandlerRegistry<AppState, AppAction> {
        return (this as StoreImpl<AppState, AppAction, AppEvent>).handlerRegistry
    }

    @Test
    fun enterAndExitMatchersRecordStateType() {
        val registry = createStore().registry()

        assertEquals(listOf(HandlerMatcher(stateType = AppState.Loading::class)), registry.enter)
        assertEquals(listOf(HandlerMatcher(stateType = AppState.Error::class)), registry.exit)
    }

    @Test
    fun actionMatchersRecordStateAndActionTypesInRegistrationOrder() {
        val registry = createStore().registry()

        assertEquals(
            listOf(
                HandlerMatcher(stateType = AppState.Main::class, inputType = AppAction.Increment::class),
                HandlerMatcher(stateType = AppState.Main::class, inputType = AppAction.Fail::class),
                HandlerMatcher(stateType = AppState::class, inputType = AppAction::class),
            ),
            registry.action,
        )
    }

    @Test
    fun recoverMatchersRecordStateAndExceptionTypes() {
        val registry = createStore().registry()

        assertEquals(
            listOf(HandlerMatcher(stateType = AppState.Main::class, inputType = IllegalStateException::class)),
            registry.recover,
        )
    }

    @Test
    fun storeWithoutHandlersHasEmptyRegistry() {
        val store: Store<AppState, AppAction, AppEvent> = Store(AppState.Loading) {}
        val registry = store.registry()

        assertTrue(registry.enter.isEmpty())
        assertTrue(registry.action.isEmpty())
        assertTrue(registry.exit.isEmpty())
        assertTrue(registry.recover.isEmpty())
    }

    @Test
    fun handlersFromLegacyConstructorsHaveNoMatcher() {
        // Inline code compiled against Actron 4.0.0 still calls the constructors without metadata.
        val stateHandler = StoreBuilder.StateHandler<(AppState) -> Boolean, EnterScope<AppState, AppEvent, AppState>>(
            predicate = { true },
            handler = { },
        )
        val threadedHandler = StoreBuilder.StateHandlerConfig.ThreadedHandler<(AppAction) -> Boolean, ActionScope<AppState, AppAction, AppEvent, AppState>>(
            dispatcher = null,
            predicate = { true },
            handler = { },
        )

        assertNull(stateHandler.matcher)
        assertNull(threadedHandler.inputType)
    }
}

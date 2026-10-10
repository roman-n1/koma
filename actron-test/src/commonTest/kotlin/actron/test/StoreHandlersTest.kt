package actron.test

import actron.core.Action
import actron.core.Event
import actron.core.State
import actron.core.Store
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ```
 * Loading --enter--> Main(0)
 * Main    --Increment--> Main(count + 1)
 * Main    --Fail--> throws --recover<IllegalStateException>--> Error
 * Error   --exit-->
 * any     --Reset--> Loading
 * ```
 */
class StoreHandlersTest {

    sealed interface AppState : State {
        data object Loading : AppState
        data class Main(val count: Int) : AppState
        data object Error : AppState
    }

    sealed interface AppAction : Action {
        data object Increment : AppAction
        data object Fail : AppAction
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
                action<AppAction.Reset> {
                    nextState { AppState.Loading }
                }
            }
        }
    }

    @Test
    fun describesEveryHandlerKindInFirstMatchOrder() {
        val handlers = createStore().describeHandlers()

        assertEquals(listOf(StateHandlerDescription(0, AppState.Loading::class)), handlers.enter)
        assertEquals(
            listOf(
                ActionHandlerDescription(0, AppState.Main::class, AppAction.Increment::class),
                ActionHandlerDescription(1, AppState.Main::class, AppAction.Fail::class),
                ActionHandlerDescription(2, AppState::class, AppAction.Reset::class),
            ),
            handlers.action,
        )
        assertEquals(listOf(StateHandlerDescription(0, AppState.Error::class)), handlers.exit)
        assertEquals(
            listOf(RecoverHandlerDescription(0, AppState.Main::class, IllegalStateException::class)),
            handlers.recover,
        )
    }

    @Test
    fun rendersAsReadableText() {
        assertEquals(
            """
            enter:
              #0 state<Loading>
            action:
              #0 state<Main> / action<Increment>
              #1 state<Main> / action<Fail>
              #2 state<AppState> / action<Reset>
            exit:
              #0 state<Error>
            recover:
              #0 state<Main> / recover<IllegalStateException>
            """.trimIndent(),
            createStore().describeHandlers().toString(),
        )
    }

    @Test
    fun emptyKindsRenderAsNone() {
        val store: Store<AppState, AppAction, AppEvent> = Store(AppState.Loading) {}

        assertEquals(
            "enter: none\naction: none\nexit: none\nrecover: none",
            store.describeHandlers().toString(),
        )
    }

    @Test
    fun describingDoesNotStartTheStore() {
        val store = createStore()

        store.describeHandlers()

        assertEquals(AppState.Loading, store.currentState)
    }
}

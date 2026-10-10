package actron.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Covers the enter/exit lifecycle, recovery chains, persistence and closing.
 *
 * ```
 * Idle --Load--> Loading --(enter)--> Loaded
 *   ^                                   |
 *   +------------ Reset ----------------+
 * ```
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StoreLifecycleTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    sealed interface AppState : State {
        data class Idle(val value: Int = 0) : AppState
        data object Loading : AppState
        data class Loaded(val items: List<String>) : AppState
        data class Failed(val message: String) : AppState
    }

    sealed interface AppAction : Action {
        data object Load : AppAction
        data object Reset : AppAction
        data object Increment : AppAction
        data object Throw : AppAction
        data object LaunchWork : AppAction
    }

    sealed interface AppEvent : Event {
        data class Exited(val state: String) : AppEvent
    }

    @Test
    fun exit_runsOnlyWhenTheStateVariantChanges() = runTest(testDispatcher) {
        val log = mutableListOf<String>()
        val store: Store<AppState, AppAction, AppEvent> = Store(AppState.Idle()) {
            coroutineContext(Dispatchers.Unconfined)
            state<AppState.Idle> {
                enter { log += "enter Idle(${state.value})" }
                exit { log += "exit Idle(${state.value})" }
                action<AppAction.Increment> { nextState { state.copy(value = state.value + 1) } }
                action<AppAction.Load> { nextState { AppState.Loading } }
            }
            state<AppState.Loading> {
                enter {
                    log += "enter Loading"
                    nextState { AppState.Loaded(listOf("a")) }
                }
                exit { log += "exit Loading" }
            }
            state<AppState.Loaded> {
                enter { log += "enter Loaded" }
            }
        }

        store.dispatchAndAwaitForTest(AppAction.Increment)
        store.dispatchAndAwaitForTest(AppAction.Increment)
        store.dispatchAndAwaitForTest(AppAction.Load)

        assertEquals(
            listOf(
                "enter Idle(0)",
                "exit Idle(2)",
                "enter Loading",
                "exit Loading",
                "enter Loaded",
            ),
            log,
        )
        assertEquals(AppState.Loaded(listOf("a")), store.currentState)
    }

    @Test
    fun exit_eventIsEmittedBeforeTheNextStateIsCommitted() = runTest(testDispatcher) {
        val log = mutableListOf<String>()
        val store: Store<AppState, AppAction, AppEvent> = Store(AppState.Idle()) {
            coroutineContext(Dispatchers.Unconfined)
            plugin(
                Plugin(
                    onState = { _, state -> log += "state $state" },
                    onEvent = { _, event -> log += "event $event" },
                ),
            )
            state<AppState.Idle> {
                action<AppAction.Load> { nextState { AppState.Loaded(emptyList()) } }
                exit { event(AppEvent.Exited("Idle")) }
            }
        }

        store.dispatchAndAwaitForTest(AppAction.Load)

        assertEquals(
            listOf(
                "event ${AppEvent.Exited("Idle")}",
                "state ${AppState.Loaded(emptyList())}",
            ),
            log,
        )
    }

    @Test
    fun sameVariantUpdate_keepsStateScopedLaunchesRunning() = runTest(testDispatcher) {
        var cancelled = false
        val store: Store<AppState, AppAction, Nothing> = Store(AppState.Idle()) {
            coroutineContext(Dispatchers.Unconfined)
            state<AppState.Idle> {
                enter {
                    launch {
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled = true
                        }
                    }
                }
                action<AppAction.Increment> { nextState { state.copy(value = state.value + 1) } }
                action<AppAction.Load> { nextState { AppState.Loading } }
            }
        }

        store.startAndAwaitForTest()
        store.dispatchAndAwaitForTest(AppAction.Increment)
        assertEquals(false, cancelled)

        store.dispatchAndAwaitForTest(AppAction.Load)
        assertEquals(true, cancelled)
    }

    @Test
    fun recover_withoutMatchingHandler_forwardsOriginalExceptionAndKeepsProcessing() = runTest(testDispatcher) {
        val handled = mutableListOf<Throwable>()
        val store: Store<AppState, AppAction, Nothing> = Store(AppState.Idle()) {
            coroutineContext(Dispatchers.Unconfined)
            exceptionHandler(ExceptionHandler { handled += it })
            state<AppState.Idle> {
                action<AppAction.Throw> { throw IllegalStateException("boom") }
                action<AppAction.Increment> { nextState { state.copy(value = state.value + 1) } }
                recover<IllegalArgumentException> { nextState { AppState.Failed("unexpected") } }
            }
        }

        store.dispatchAndAwaitForTest(AppAction.Throw)
        store.dispatchAndAwaitForTest(AppAction.Increment)

        assertEquals(1, handled.size)
        val error = handled.single()
        assertIs<IllegalStateException>(error)
        assertEquals("boom", error.message)
        assertEquals(AppState.Idle(value = 1), store.currentState)
    }

    @Test
    fun recover_movingToAStateWhoseEnterThrows_forwardsToExceptionHandlerInsteadOfLooping() = runTest(testDispatcher) {
        val handled = mutableListOf<Throwable>()
        var recoverCount = 0
        val store: Store<AppState, AppAction, Nothing> = Store(AppState.Idle()) {
            coroutineContext(Dispatchers.Unconfined)
            exceptionHandler(ExceptionHandler { handled += it })
            state<AppState.Idle> {
                action<AppAction.Throw> { throw IllegalStateException("first") }
            }
            state<AppState.Failed> {
                enter { throw IllegalStateException("second") }
            }
            state<AppState> {
                recover<IllegalStateException> {
                    recoverCount++
                    nextState { AppState.Failed(error.message.orEmpty()) }
                }
            }
        }

        store.dispatchAndAwaitForTest(AppAction.Throw)

        assertEquals(1, recoverCount)
        assertEquals(listOf("second"), handled.map { it.message })
        assertEquals(AppState.Failed("first"), store.currentState)
    }

    @Test
    fun recover_fromEnterException_canMoveToAnotherState() = runTest(testDispatcher) {
        val store: Store<AppState, AppAction, Nothing> = Store(AppState.Idle()) {
            coroutineContext(Dispatchers.Unconfined)
            state<AppState.Idle> {
                action<AppAction.Load> { nextState { AppState.Loading } }
            }
            state<AppState.Loading> {
                enter { throw IllegalStateException("network") }
                recover<IllegalStateException> { nextState { AppState.Failed(error.message.orEmpty()) } }
            }
        }

        store.dispatchAndAwaitForTest(AppAction.Load)

        assertEquals(AppState.Failed("network"), store.currentState)
    }

    @Test
    fun stateSaver_restoreException_fallsBackToInitialStateAndReportsTheError() = runTest(testDispatcher) {
        val handled = mutableListOf<Throwable>()
        val store: Store<AppState, AppAction, Nothing> = Store(AppState.Idle(value = 7)) {
            coroutineContext(Dispatchers.Unconfined)
            exceptionHandler(ExceptionHandler { handled += it })
            stateSaver(
                StateSaver(
                    save = {},
                    restore = { throw IllegalStateException("corrupted snapshot") },
                ),
            )
        }

        assertEquals(AppState.Idle(value = 7), store.currentState)
        assertEquals(listOf("corrupted snapshot"), handled.map { it.message })
    }

    @Test
    fun stateSaver_savesEveryCommittedChangeButNotUnchangedStates() = runTest(testDispatcher) {
        val saved = mutableListOf<AppState>()
        val store: Store<AppState, AppAction, Nothing> = Store(AppState.Idle()) {
            coroutineContext(Dispatchers.Unconfined)
            stateSaver(StateSaver(save = { saved += it }, restore = { it }))
            state<AppState.Idle> {
                action<AppAction.Increment> { nextState { state.copy(value = state.value + 1) } }
                action<AppAction.Reset> { nextState { state } }
                action<AppAction.Load> { nextState { AppState.Loading } }
            }
            state<AppState.Loading> {
                enter { nextState { AppState.Loaded(listOf("x")) } }
            }
        }

        store.dispatchAndAwaitForTest(AppAction.Increment)
        store.dispatchAndAwaitForTest(AppAction.Reset)
        store.dispatchAndAwaitForTest(AppAction.Load)

        assertEquals(
            listOf(AppState.Idle(value = 1), AppState.Loading, AppState.Loaded(listOf("x"))),
            saved,
        )
    }

    @Test
    fun close_cancelsStateScopedLaunchesAndIgnoresLaterDispatches() = runTest(testDispatcher) {
        var cancelled = false
        val store: Store<AppState, AppAction, Nothing> = Store(AppState.Idle()) {
            coroutineContext(Dispatchers.Unconfined)
            state<AppState.Idle> {
                action<AppAction.LaunchWork> {
                    launch {
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled = true
                        }
                    }
                }
                action<AppAction.Increment> { nextState { state.copy(value = state.value + 1) } }
            }
        }

        store.dispatchAndAwaitForTest(AppAction.LaunchWork)
        store.close()
        store.dispatchAndAwaitForTest(AppAction.Increment)

        assertTrue(cancelled)
        assertEquals(AppState.Idle(value = 0), store.currentState)
    }

    @Test
    fun reenteringAState_startsAFreshStateRuntime() = runTest(testDispatcher) {
        var enterCount = 0
        var active = 0
        val store: Store<AppState, AppAction, Nothing> = Store(AppState.Idle()) {
            coroutineContext(Dispatchers.Unconfined)
            state<AppState.Idle> {
                enter {
                    enterCount++
                    launch {
                        active++
                        try {
                            awaitCancellation()
                        } finally {
                            active--
                        }
                    }
                }
                action<AppAction.Load> { nextState { AppState.Loaded(emptyList()) } }
            }
            state<AppState.Loaded> {
                action<AppAction.Reset> { nextState { AppState.Idle() } }
            }
        }

        store.startAndAwaitForTest()
        store.dispatchAndAwaitForTest(AppAction.Load)
        assertEquals(0, active)
        store.dispatchAndAwaitForTest(AppAction.Reset)

        assertEquals(2, enterCount)
        assertEquals(1, active)
    }
}

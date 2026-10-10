package actron.logging

import actron.core.Action
import actron.core.Event
import actron.core.State
import actron.core.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LoggingOutputTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private data class CounterState(val count: Int) : State

    private sealed interface CounterAction : Action {
        data object Increment : CounterAction
    }

    private sealed interface CounterEvent : Event {
        data object Incremented : CounterEvent
    }

    private data class Entry(val severity: Logger.Severity, val tag: String, val message: String)

    @Test
    fun simpleLogging_logsActionEventAndStateWithTheConfiguredTagAndSeverity() = runTest(testDispatcher) {
        val entries = mutableListOf<Entry>()
        val logger = Logger { severity, tag, message -> entries += Entry(severity, tag, message()) }
        val store: Store<CounterState, CounterAction, CounterEvent> = Store(CounterState(0)) {
            coroutineContext(Dispatchers.Unconfined)
            plugin(simpleLogging(tag = "Chat", severity = Logger.Severity.Info, logger = logger))
            state<CounterState> {
                action<CounterAction.Increment> {
                    event(CounterEvent.Incremented)
                    nextState { state.copy(count = state.count + 1) }
                }
            }
        }

        store.dispatch(CounterAction.Increment)

        assertEquals(
            listOf(
                Entry(Logger.Severity.Info, "Chat", "Action: Increment"),
                Entry(Logger.Severity.Info, "Chat", "Event: Incremented"),
                Entry(Logger.Severity.Info, "Chat", "State: CounterState(count=1) <- CounterState(count=0)"),
            ),
            entries,
        )
        store.close()
    }

    @Test
    fun simpleLogging_doesNotLogUnchangedState() = runTest(testDispatcher) {
        val entries = mutableListOf<String>()
        val store: Store<CounterState, CounterAction, CounterEvent> = Store(CounterState(0)) {
            coroutineContext(Dispatchers.Unconfined)
            plugin(simpleLogging(logger = { _, _, message -> entries += message() }))
            state<CounterState> {
                action<CounterAction.Increment> { nextState { state } }
            }
        }

        store.dispatch(CounterAction.Increment)

        assertEquals(listOf("Action: Increment"), entries)
        store.close()
    }

    @Test
    fun simpleLogging_keepsProcessingOrderOnAMultiThreadedDispatcher() = runTest {
        val entries = mutableListOf<String>()
        val logger = Logger { _, _, message -> entries += message() }
        val store: Store<CounterState, CounterAction, CounterEvent> = Store(CounterState(0)) {
            coroutineContext(Dispatchers.Default)
            plugin(simpleLogging(logger = logger))
            state<CounterState> {
                action<CounterAction.Increment> { nextState { state.copy(count = state.count + 1) } }
            }
        }

        withContext(Dispatchers.Default) {
            repeat(200) { store.dispatch(CounterAction.Increment) }
            withTimeout(10_000) { store.state.first { it.count == 200 } }
        }

        val expected = (0 until 200).flatMap {
            listOf("Action: Increment", "State: CounterState(count=${it + 1}) <- CounterState(count=$it)")
        }
        // Hooks run under the Store's lock, so the plain list is written by one hook at a time.
        assertEquals(expected, entries)
        store.close()
    }

    private sealed interface FlowState : State {
        data object Loading : FlowState
        data class Ready(val work: Int = 0) : FlowState
    }

    private sealed interface FlowAction : Action {
        data object Go : FlowAction
        data object Work : FlowAction
    }

    /**
     * A logger that throws (or a state whose `toString()` throws) is reported to the exception
     * handler; the action and the state change being logged are still processed, and the new
     * state is entered normally.
     */
    @Test
    fun simpleLogging_throwingLogger_doesNotAbortActionsOrTransitions() = runTest(testDispatcher) {
        val handled = mutableListOf<Throwable>()
        var readyEntered = 0
        val logger = Logger { _, _, message -> throw IllegalStateException("logger failed on: ${message()}") }
        val store: Store<FlowState, FlowAction, Nothing> = Store(FlowState.Loading) {
            coroutineContext(Dispatchers.Unconfined)
            exceptionHandler(actron.core.ExceptionHandler { handled += it })
            plugin(simpleLogging(logger = logger))
            state<FlowState.Loading> {
                action<FlowAction.Go> { nextState { FlowState.Ready() } }
            }
            state<FlowState.Ready> {
                enter { readyEntered++ }
                action<FlowAction.Work> { launch { transaction { nextState { state.copy(work = state.work + 1) } } } }
            }
        }

        store.dispatch(FlowAction.Go)
        store.dispatch(FlowAction.Work)

        assertEquals(FlowState.Ready(work = 1), store.currentState)
        assertEquals(1, readyEntered)
        assertEquals(
            listOf("Action: Go", "State: Ready(work=0) <- Loading", "Action: Work", "State: Ready(work=1) <- Ready(work=0)"),
            handled.map { it.message?.removePrefix("logger failed on: ") },
        )
        store.close()
    }
}

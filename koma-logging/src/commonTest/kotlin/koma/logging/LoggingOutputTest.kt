package koma.logging

import koma.core.Action
import koma.core.Event
import koma.core.ExceptionHandler
import koma.core.State
import koma.core.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What `simpleLogging()` promises about its entries: without a dispatcher they keep the order
 * the Store processed them in, on a multi-threaded dispatcher too, and a logger that throws
 * does not abort the action or transition being logged.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LoggingOutputTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private data class CounterState(val count: Int) : State

    private sealed interface CounterAction : Action {
        data object Increment : CounterAction
    }

    private sealed interface CounterEvent : Event

    @Test
    fun simpleLogging_keepsProcessingOrderOnAMultiThreadedDispatcher() = runTest {
        val entries = mutableListOf<String>()
        val logger = Logger { _, _, _, message -> entries += message() }
        val store: Store<CounterState, CounterAction, CounterEvent> = Store(CounterState(0)) {
            coroutineContext(Dispatchers.Default)
            plugin(simpleLogging(logger = logger))
            state<CounterState> {
                action<CounterAction.Increment> { nextState { state.copy(count = state.count + 1) } }
            }
        }

        withContext(Dispatchers.Default) {
            repeat(200) { store.dispatch(CounterAction.Increment) }
            withTimeout(10_000) { while (store.currentState.count < 200) delay(5) }
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
        val logger = Logger { _, _, _, message -> throw IllegalStateException("logger failed on: ${message()}") }
        val store: Store<FlowState, FlowAction, Nothing> = Store(FlowState.Loading) {
            coroutineContext(Dispatchers.Unconfined)
            exceptionHandler(ExceptionHandler { handled += it })
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

package koma.logging

import koma.core.Action
import koma.core.Event
import koma.core.State
import koma.core.Store
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
        val logger = Logger { severity, tag, _, message -> entries += Entry(severity, tag, message()) }
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
            plugin(simpleLogging(logger = { _, _, _, message -> entries += message() }))
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
            withTimeout(10_000) { store.state.first { it.count == 200 } }
        }

        val expected = (0 until 200).flatMap {
            listOf("Action: Increment", "State: CounterState(count=${it + 1}) <- CounterState(count=$it)")
        }
        // Hooks run under the Store's lock, so the plain list is written by one hook at a time.
        assertEquals(expected, entries)
        store.close()
    }
}

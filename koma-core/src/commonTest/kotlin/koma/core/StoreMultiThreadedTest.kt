package koma.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Runs the Store on [Dispatchers.Default], its default context, where handlers, launches and
 * transactions may run on different threads.
 */
class StoreMultiThreadedTest {

    data class Counter(val count: Int = 0) : State

    sealed interface CounterAction : Action {
        data object Increment : CounterAction
        data object LaunchIncrement : CounterAction
    }

    @Test
    fun concurrentDispatchesAndTransactions_neverLoseAnUpdate() = runTest {
        val senders = 8
        val perSender = 250
        val total = senders * perSender
        var committed = 0
        val store: Store<Counter, CounterAction, Nothing> = Store(Counter()) {
            coroutineContext(Dispatchers.Default)
            plugin(Plugin(onState = { _, _ -> committed++ }))
            state<Counter> {
                action<CounterAction.Increment> { nextState { state.copy(count = state.count + 1) } }
                action<CounterAction.LaunchIncrement> {
                    launch {
                        transaction { nextState { state.copy(count = state.count + 1) } }
                    }
                }
            }
        }

        withContext(Dispatchers.Default) {
            coroutineScope {
                repeat(senders) { sender ->
                    launch {
                        repeat(perSender) { index ->
                            store.dispatch(if ((sender + index) % 2 == 0) CounterAction.Increment else CounterAction.LaunchIncrement)
                        }
                    }
                }
            }
            withTimeout(10_000) {
                store.state.first { it.count == total }
            }
        }

        assertEquals(Counter(total), store.currentState)
        // onState runs under the Store's lock, so the plain counter is consistent.
        assertEquals(total, committed)
        store.close()
    }
}

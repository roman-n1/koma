package actron.core

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

    data class Seen(val values: List<Int> = emptyList()) : State

    data class Add(val value: Int) : Action

    /**
     * Actions dispatched one after another from a single thread are processed in dispatch order.
     * Each dispatch is a coroutine on the Store's dispatcher; without ordering, two coroutines
     * scheduled on different worker threads reach the Store's lock in arbitrary order.
     */
    @Test
    fun dispatches_areProcessedInDispatchOrder() = runTest {
        val rounds = 20
        val perRound = 200
        repeat(rounds) { round ->
            val store: Store<Seen, Add, Nothing> = Store(Seen()) {
                coroutineContext(Dispatchers.Default)
                state<Seen> {
                    action<Add> { nextState { state.copy(values = state.values + action.value) } }
                }
            }
            withContext(Dispatchers.Default) {
                repeat(perRound) { store.dispatch(Add(it)) }
                withTimeout(10_000) {
                    store.state.first { it.values.size == perRound }
                }
            }
            val seen = store.currentState.values
            store.close()
            assertEquals((0 until perRound).toList(), seen, "round $round")
        }
    }

    data class Quiet(val n: Int = 0) : State

    data object Emit : Action

    data class Note(val n: Int) : Event

    /**
     * Events emitted from launched coroutines are processed outside the Store lock, so the plugin
     * hook rounds for them ran concurrently and a plugin keeping plain state (a recorder) raced
     * with itself and lost entries. Hook rounds are now serialized.
     */
    @Test
    fun pluginHookRounds_neverOverlap_evenForEventsEmittedFromLaunches() = runTest {
        val launches = 4
        val perLaunch = 2000
        val seen = mutableListOf<Int>() // deliberately not thread-safe
        var inRound = false
        var overlaps = 0
        val store: Store<Quiet, Emit, Note> = Store(Quiet()) {
            coroutineContext(Dispatchers.Default)
            plugin(
                Plugin(
                    onEvent = { _, event ->
                        if (inRound) overlaps++
                        inRound = true
                        seen += event.n
                        kotlinx.coroutines.yield()
                        inRound = false
                    },
                ),
            )
            state<Quiet> {
                action<Emit> {
                    repeat(launches) { l ->
                        launch { repeat(perLaunch) { i -> event(Note(l * perLaunch + i)) } }
                    }
                }
            }
        }

        withContext(Dispatchers.Default) {
            store.dispatch(Emit)
            withTimeout(30_000) {
                while (seen.size < launches * perLaunch) kotlinx.coroutines.delay(20)
            }
        }
        kotlinx.coroutines.delay(100)

        assertEquals(0, overlaps)
        assertEquals((0 until launches * perLaunch).toSet(), seen.toSet())
        store.close()
    }
}

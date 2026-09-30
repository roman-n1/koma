@file:OptIn(ExperimentalKomaApi::class, InternalKomaApi::class)

package koma.statechart.machine

import koma.core.Action
import koma.core.Event
import koma.core.ExceptionHandler
import koma.core.ExperimentalKomaApi
import koma.core.InternalKomaApi
import koma.core.StoreProbe
import koma.core.StoreTrace
import koma.statechart.ActionMatcher
import koma.statechart.AtomicState
import koma.statechart.CompoundState
import koma.statechart.StateChartDefinition
import koma.statechart.StateId
import koma.statechart.Transition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * `close()` racing a storm of dispatches, results and command starts, many times over, on
 * [Dispatchers.Default]. Whatever the interleaving: nothing commits after the store closed,
 * every command that started ended (completed or cancelled), every processing that started
 * finished, and nothing is processed after `StoreClosed`.
 */
class CloseRaceTest {

    data class Ctx(val n: Int = 0)

    sealed interface Act : Action {
        data class Load(val n: Int) : Act
        data class Loaded(val n: Int) : Act
    }

    data object Nothing : Event

    data class Fetch(val n: Int)

    private val root = StateId("Root")
    private val idle = StateId("Idle")
    private val loading = StateId("Loading")

    private val chart = StateChartDefinition(
        root,
        listOf(CompoundState(root, initial = idle), AtomicState(idle, parent = root), AtomicState(loading, parent = root)),
        listOf(
            Transition(idle, loading, ActionMatcher.of<Act.Load>("Load")),
            Transition(loading, loading, ActionMatcher.of<Act.Load>("Load")),
            Transition(loading, idle, ActionMatcher.of<Act.Loaded>("Loaded"), effect = "store"),
        ),
    )

    private val machine = Machine<Ctx, Act, Fetch, Nothing>(DefinitionId("close-race"), DefinitionVersion("1"), chart) {
        effect("store") { c, a -> c.copy(n = (a as Act.Loaded).n) }
        onEnter(loading) { command(Fetch((action as Act.Load).n)) }
    }

    @Test
    fun closingDuringAStorm_neverCommitsOrStartsAnythingAfterwards() = runTest {
        repeat(25) { iteration ->
            val traces = Channel<StoreTrace<MachineSnapshot<Ctx>, MachineInput<Act>, Nothing>>(Channel.UNLIMITED)
            val handled = Channel<Throwable>(Channel.UNLIMITED)
            val started = MutableStateFlow(0)
            val ended = MutableStateFlow(0)
            val executionScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            // "No handler starts after close" has no exact detector from inside a handler: a body
            // that began a nanosecond before the cancellation reads a cancelled context, and a
            // flag set after close() is read by bodies that entered before it. What is checked is
            // the consequence: nothing commits after close, and every body that ran also ended.
            val handler = CommandHandler<Fetch, Act> { command, results ->
                started.update { it + 1 }
                try {
                    delay(Random.nextLong(0, 4).milliseconds)
                    results.result(Act.Loaded(command.command.n))
                } finally {
                    ended.update { it + 1 }
                }
            }
            val store = MachineStore(machine, Ctx(), handler, executionScope, coroutineContext = Dispatchers.Default) {
                exceptionHandler(ExceptionHandler { handled.trySend(it) })
                probe(StoreProbe { traces.trySend(it) })
            }

            val storm: Job = launch(Dispatchers.Default) {
                var n = 0
                while (true) {
                    store.dispatch(Act.Load(n++))
                    if (n % 7 == 0) delay(1.milliseconds)
                }
            }
            withContext(Dispatchers.Default) { delay(Random.nextLong(1, 15).milliseconds) }
            store.close()
            val snapshotAtClose = store.currentState
            storm.cancelAndJoin()
            executionScope.coroutineContext[Job]!!.let { it.cancel(); it.join() }
            // StoreClosed comes once every coroutine of the inner store has ended.
            val all = mutableListOf<StoreTrace<MachineSnapshot<Ctx>, MachineInput<Act>, Nothing>>()
            withContext(Dispatchers.Default) {
                withTimeout(10_000) {
                    while (true) {
                        val trace = traces.receive()
                        all += trace
                        if (trace === StoreTrace.StoreClosed) break
                    }
                }
            }
            generateSequence { traces.tryReceive().getOrNull() }.forEach { all += it }

            assertEquals(snapshotAtClose, store.currentState, "iteration $iteration: the snapshot changed after close")
            assertEquals(started.value, ended.value, "iteration $iteration: a command that started never ended")
            val startedProcessing = all.filterIsInstance<StoreTrace.ProcessingStarted>().map { it.input to it.ordinal }.toSet()
            val finished = all.filterIsInstance<StoreTrace.ProcessingFinished>().map { it.input to it.ordinal }.toSet()
            assertEquals(startedProcessing, finished, "iteration $iteration: a processing that started never finished")
            val closedAt = all.indexOfFirst { it === StoreTrace.StoreClosed }
            assertTrue(closedAt >= 0, "iteration $iteration: no StoreClosed trace")
            assertTrue(all.drop(closedAt + 1).none { it is StoreTrace.StateCommitted<*> || it is StoreTrace.ProcessingStarted }, "iteration $iteration: work after StoreClosed")
            assertEquals(null, handled.tryReceive().getOrNull(), "iteration $iteration: something was reported")
        }
    }
}

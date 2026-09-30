@file:OptIn(ExperimentalKomaApi::class)

package koma.statechart.machine

import koma.core.Action
import koma.core.Event
import koma.core.ExceptionHandler
import koma.core.ExperimentalKomaApi
import koma.statechart.ActionMatcher
import koma.statechart.AtomicState
import koma.statechart.CompoundState
import koma.statechart.StateChartDefinition
import koma.statechart.StateId
import koma.statechart.Transition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Checkpoints taken while a [MachineStore] on [Dispatchers.Default] is stormed from several
 * threads: activations re-entered and exited while commands of every lane policy start,
 * finish, queue and are dropped. No hand can time this; every checkpoint must still be a
 * consistent cut: each command of its snapshot in exactly one place (the checkpoint's own
 * check), lanes within their policy, nothing queued behind a free lane, revisions never going
 * back, and no failure reported.
 *
 * ```
 * [*] --> Idle
 * Idle --Go--> Busy        onEnter: Fetch (net, Latest); Log x2 (log, Sequential); Ping x2 (ping, DropIfRunning); Work x3 (work, Parallel(2))
 * Busy --Go--> Busy
 * Busy --Done--> Idle
 * ```
 */
class ExecutorCheckpointStormTest {

    sealed interface Act : Action {
        data object Go : Act
        data object Done : Act
    }

    data object Nothing : Event

    data class Cmd(val kind: String, val n: Int)

    private val root = StateId("Root")
    private val idle = StateId("Idle")
    private val busy = StateId("Busy")
    private val net = LaneId("net")
    private val log = LaneId("log")
    private val ping = LaneId("ping")
    private val work = LaneId("work")

    private val chart = StateChartDefinition(
        root,
        listOf(CompoundState(root, initial = idle), AtomicState(idle, parent = root), AtomicState(busy, parent = root)),
        listOf(
            Transition(idle, busy, ActionMatcher.of<Act.Go>("Go")),
            Transition(busy, busy, ActionMatcher.of<Act.Go>("Go")),
            Transition(busy, idle, ActionMatcher.of<Act.Done>("Done")),
        ),
    )

    private val machine = Machine<Unit, Act, Cmd, Nothing>(DefinitionId("storm"), DefinitionVersion("1"), chart) {
        onEnter(busy) {
            command(Cmd("fetch", 0), net, ConcurrencyPolicy.Latest)
            command(Cmd("log", 1), log, ConcurrencyPolicy.Sequential)
            command(Cmd("log", 2), log, ConcurrencyPolicy.Sequential)
            command(Cmd("ping", 1), ping, ConcurrencyPolicy.DropIfRunning)
            command(Cmd("ping", 2), ping, ConcurrencyPolicy.DropIfRunning)
            command(Cmd("work", 1), work, ConcurrencyPolicy.Parallel(2))
            command(Cmd("work", 2), work, ConcurrencyPolicy.Parallel(2))
            command(Cmd("work", 3), work, ConcurrencyPolicy.Parallel(2))
        }
    }

    @Test
    fun checkpointsUnderAStorm_areConsistentCuts() = runTest {
        val handled = Channel<Throwable>(Channel.UNLIMITED)
        val executionScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = CommandHandler<Cmd, Act> { _, _ -> delay(Random.nextLong(0, 4).milliseconds) }
        val store = MachineStore(machine, Unit, handler, executionScope, coroutineContext = Dispatchers.Default) {
            exceptionHandler(ExceptionHandler { handled.trySend(it) })
        }
        val checkpoints = mutableListOf<ExecutorCheckpoint<Unit, Cmd>>()

        withContext(Dispatchers.Default) {
            coroutineScope {
                repeat(4) { sender ->
                    launch {
                        repeat(120) { index ->
                            store.dispatch(if (index % 7 == 6 && sender == 0) Act.Done else Act.Go)
                            if (index % 10 == 0) delay(1.milliseconds)
                        }
                    }
                }
                launch {
                    repeat(300) {
                        checkpoints += store.checkpoint()
                        yield()
                    }
                }
            }
            withTimeout(20_000) {
                store.dispatch(Act.Done)
                store.state.first { it.commands.isEmpty() && it.isActive(idle) }
            }
            checkpoints += store.checkpoint()
        }
        store.close()
        executionScope.cancel()

        assertEquals(301, checkpoints.size)
        for ((index, checkpoint) in checkpoints.withIndex()) {
            val lanes = checkpoint.lanes
            assertTrue(lanes.runningIn(net).size <= 1, "checkpoint $index: Latest lane runs one: ${lanes.runningIn(net)}")
            assertTrue(lanes.runningIn(log).size <= 1, "checkpoint $index: Sequential lane runs one: ${lanes.runningIn(log)}")
            assertTrue(lanes.runningIn(ping).size <= 1, "checkpoint $index: DropIfRunning lane runs one: ${lanes.runningIn(ping)}")
            assertTrue(lanes.runningIn(work).size <= 2, "checkpoint $index: Parallel(2) lane runs two: ${lanes.runningIn(work)}")
            assertTrue(net !in lanes.queued && ping !in lanes.queued, "checkpoint $index: Latest and DropIfRunning never queue")
            if (log in lanes.queued) assertEquals(1, lanes.runningIn(log).size, "checkpoint $index: queued behind a free Sequential lane")
            if (work in lanes.queued) assertEquals(2, lanes.runningIn(work).size, "checkpoint $index: queued behind a free Parallel lane")
            val active = checkpoint.snapshot.activations[busy]
            assertTrue(checkpoint.registrations.values.all { it.scope == active }, "checkpoint $index: a command of an exited activation")
            if (index > 0) assertTrue(checkpoint.snapshot.revision >= checkpoints[index - 1].snapshot.revision, "checkpoint $index: the revision went back")
        }
        val last = checkpoints.last()
        assertTrue(last.lanes.running.isEmpty() && last.lanes.queued.isEmpty() && last.ending.isEmpty(), "quiescent: $last")
        // How much the storm exercised depends on the platform's threads; informational.
        println("checkpoints with a full lane: ${checkpoints.count { it.lanes.queued.isNotEmpty() }}, with a command ending: ${checkpoints.count { it.ending.isNotEmpty() }} of ${checkpoints.size}")
        assertEquals(null, handled.tryReceive().getOrNull(), "nothing was reported")
    }
}

@file:OptIn(ExperimentalActronApi::class, ExperimentalCoroutinesApi::class)

package actron.timetravel

import actron.core.Action
import actron.core.Event
import actron.core.ExceptionHandler
import actron.core.ExperimentalActronApi
import actron.statechart.ActionMatcher
import actron.statechart.AtomicState
import actron.statechart.CompoundState
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.Transition
import actron.statechart.machine.CommandHandler
import actron.statechart.machine.ConcurrencyPolicy
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.ExecutorCheckpoint
import actron.statechart.machine.LaneId
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineClock
import actron.statechart.machine.MachineStore
import actron.statechart.machine.MachineTime
import actron.statechart.test.VirtualMachineClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The live executor's checkpoint against the one a recording carries forward with the same
 * lane bookkeeping: equal once the executor has settled, consistent at any moment of a storm.
 * A recording that begins at a live checkpoint replays and branches from there.
 *
 * ```
 * [*] --> Idle
 * Idle --Go--> Busy        onEnter: Fetch (net, Latest); Log(a), Log(b) (log, Sequential); Ping(1), Ping(2) (ping, DropIfRunning); Work(1..3) (work, Parallel(2))
 * Busy --Go--> Busy
 * Busy --Done--> Idle
 * ```
 */
class CheckpointTest {

    sealed interface Act : Action {
        data object Go : Act
        data object Done : Act
    }

    data object Nothing : Event

    data class Cmd(val kind: String, val n: Int)

    private val root = StateId("Root")
    private val idle = StateId("Idle")
    private val busy = StateId("Busy")

    private val chart = StateChartDefinition(
        root,
        listOf(CompoundState(root, initial = idle), AtomicState(idle, parent = root), AtomicState(busy, parent = root)),
        listOf(
            Transition(idle, busy, ActionMatcher.of<Act.Go>("Go")),
            Transition(busy, busy, ActionMatcher.of<Act.Go>("Go")),
            Transition(busy, idle, ActionMatcher.of<Act.Done>("Done")),
        ),
    )

    private val machine = Machine<Unit, Act, Cmd, Nothing>(DefinitionId("checkpoints"), DefinitionVersion("1"), chart) {
        onEnter(busy) {
            command(Cmd("fetch", 0), LaneId("net"), ConcurrencyPolicy.Latest)
            command(Cmd("log", 1), LaneId("log"), ConcurrencyPolicy.Sequential)
            command(Cmd("log", 2), LaneId("log"), ConcurrencyPolicy.Sequential)
            command(Cmd("ping", 1), LaneId("ping"), ConcurrencyPolicy.DropIfRunning)
            command(Cmd("ping", 2), LaneId("ping"), ConcurrencyPolicy.DropIfRunning)
            command(Cmd("work", 1), LaneId("work"), ConcurrencyPolicy.Parallel(2))
            command(Cmd("work", 2), LaneId("work"), ConcurrencyPolicy.Parallel(2))
            command(Cmd("work", 3), LaneId("work"), ConcurrencyPolicy.Parallel(2))
        }
    }

    /** A recorded store whose commands complete when their gate opens. */
    private inner class Gated(scope: TestScope) {
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val gates = mutableMapOf<Cmd, CompletableDeferred<Unit>>()
        val recorder = MachineRecorder(machine, Unit)
        val executionScope = CoroutineScope(dispatcher + SupervisorJob())
        val store = MachineStore(
            machine, Unit, CommandHandler<Cmd, Act> { command, _ -> gates.getOrPut(command.command) { CompletableDeferred() }.await() },
            executionScope, VirtualMachineClock(scope.testScheduler), dispatcher, observers = listOf(recorder),
        ) { exceptionHandler(ExceptionHandler.Ignore) }

        fun open(command: Cmd) {
            gates.getOrPut(command) { CompletableDeferred() }.complete(Unit)
        }
    }

    private fun <C, CMD> ExecutorCheckpoint<C, CMD>.state() = Triple(snapshot, lanes, ending)

    @Test
    fun onceTheExecutorHasSettled_itsCheckpointIsTheOneTheRecordingCarriesForward() = runTest {
        val gated = Gated(this)
        val store = gated.store
        store.start()
        store.dispatch(Act.Go)
        runCurrent()
        assertEquals(store.checkpoint().state(), gated.recorder.recording().let { it.checkpointAt(it.length) }.state(), "after the entry: five running, two queued, the dropped ping gone")

        gated.open(Cmd("log", 1))
        gated.open(Cmd("work", 2))
        runCurrent()
        assertEquals(store.checkpoint().state(), gated.recorder.recording().let { it.checkpointAt(it.length) }.state(), "after two completions: the lanes moved on")

        store.dispatch(Act.Go)
        runCurrent()
        assertEquals(store.checkpoint().state(), gated.recorder.recording().let { it.checkpointAt(it.length) }.state(), "after a re-entry")

        store.dispatch(Act.Done)
        runCurrent()
        val settled = store.checkpoint()
        assertEquals(settled.state(), gated.recorder.recording().let { it.checkpointAt(it.length) }.state(), "after the exit")
        assertTrue(settled.lanes.running.isEmpty() && settled.lanes.queued.isEmpty() && settled.ending.isEmpty())
        store.close()
    }

    @Test
    fun theRecordingCarriesTheEndingCommands_untilTheMachineDecidesTheirAbandonment() = runTest {
        val gated = Gated(this)
        val store = gated.store
        store.start()
        store.dispatch(Act.Go)
        runCurrent()
        val recording = gated.recorder.recording()

        // Step 1 is the entry: the dropped ping is ending there; step 2 decides its abandonment.
        val entered = recording.steps.indexOfFirst { it is RecordedStep.Committed && it.decision.commands.isNotEmpty() } + 1
        assertEquals(listOf(Cmd("ping", 2)), recording.checkpointAt(entered).ending.values.map { it.command })
        assertTrue(recording.checkpointAt(entered + 1).ending.isEmpty())
        assertEquals(setOf(Cmd("fetch", 0), Cmd("log", 1), Cmd("ping", 1), Cmd("work", 1), Cmd("work", 2)), recording.checkpointAt(entered).lanes.running.values.map { it.command }.toSet())
        assertEquals(mapOf(LaneId("log") to listOf(Cmd("log", 2)), LaneId("work") to listOf(Cmd("work", 3))), recording.checkpointAt(entered).lanes.queued.mapValues { (_, w) -> w.map { it.command } })
        assertEquals(recording.checkpointAt(0), ExecutorCheckpoint.initial(recording.initial))
        assertFailsWith<IllegalArgumentException> { recording.checkpointAt(recording.length + 1) }
        store.close()
    }

    @Test
    fun aRecordingSinceALiveCheckpoint_replaysFromIt_andBranchesWhereTheRunWas() = runTest {
        val gated = Gated(this)
        val store = gated.store
        store.start()
        store.dispatch(Act.Go)
        runCurrent()
        gated.open(Cmd("log", 1))
        runCurrent()
        val checkpoint = store.checkpoint()
        // The run goes on after the checkpoint.
        gated.open(Cmd("work", 1))
        store.dispatch(Act.Go)
        runCurrent()
        val whole = gated.recorder.recording()

        val since = whole.since(checkpoint)

        assertEquals(checkpoint, since.start)
        assertTrue(since.length in 1 until whole.length)
        assertEquals(whole.steps.takeLast(since.length), since.steps)
        val session = ReplaySession(machine, since)
        assertNull(session.verify(), "the run after the checkpoint replays from it")
        assertEquals(checkpoint, session.checkpoint)
        val branch = session.branch()
        assertEquals(checkpoint.lanes.running.values.map { it.command }.toSet(), branch.awaiting.map { it.command }.toSet())
        assertEquals(checkpoint.lanes.queued, branch.queued)
        session.seek(since.length)
        assertEquals(store.checkpoint().state(), session.checkpoint.state(), "carried forward to the end, it is the live executor's state again")

        // A checkpoint of another run is refused: its snapshot is at no position of this one.
        // (A run identical in every input gives equal checkpoints; they are values, not tokens.)
        val other = Gated(this)
        other.store.start()
        other.store.dispatch(Act.Go)
        other.store.dispatch(Act.Go)
        runCurrent()
        val foreign = other.store.checkpoint()
        assertTrue(foreign.snapshot.revision <= whole.snapshotAt(whole.length).revision)
        assertFailsWith<IllegalArgumentException> { whole.since(foreign) }
        val forged = ExecutorCheckpoint(checkpoint.snapshot, checkpoint.now, checkpoint.lanes.copy(running = checkpoint.lanes.running.mapValues { (_, r) -> r.copy(command = Cmd("forged", 9)) }), checkpoint.ending)
        assertFailsWith<IllegalArgumentException> { whole.since(forged) }
        store.close()
        other.store.close()
    }

    /**
     * Live checkpoints taken during a storm on [Dispatchers.Default]: whatever the executor's
     * lag behind the store, each must be a checkpoint of the recorded run (the recording from
     * it exists and replays), which is what a journal needs to begin its replay range at one.
     */
    @Test
    fun everyLiveCheckpointOfAStorm_isACheckpointOfTheRecording() = runTest {
        val recorder = MachineRecorder(machine, Unit)
        val handled = Channel<Throwable>(Channel.UNLIMITED)
        val executionScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = CommandHandler<Cmd, Act> { _, _ -> delay(Random.nextLong(0, 4).milliseconds) }
        val store = MachineStore(machine, Unit, handler, executionScope, coroutineContext = Dispatchers.Default, observers = listOf(recorder)) {
            exceptionHandler(ExceptionHandler { handled.trySend(it) })
        }
        val checkpoints = mutableListOf<ExecutorCheckpoint<Unit, Cmd>>()

        withContext(Dispatchers.Default) {
            coroutineScope {
                repeat(4) { sender ->
                    launch {
                        repeat(100) { index ->
                            store.dispatch(if (index % 9 == 8 && sender == 1) Act.Done else Act.Go)
                            if (index % 10 == 0) delay(1.milliseconds)
                        }
                    }
                }
                launch {
                    repeat(200) {
                        checkpoints += store.checkpoint()
                        yield()
                    }
                }
            }
            withTimeout(20_000) {
                store.dispatch(Act.Done)
                store.state.first { it.commands.isEmpty() && it.isActive(idle) }
            }
        }
        val recording = recorder.recording()
        store.close()
        executionScope.cancel()

        assertNull(recorder.problem)
        assertEquals(200, checkpoints.size)
        for ((index, checkpoint) in checkpoints.withIndex()) {
            val since = recording.since(checkpoint)
            assertNull(ReplaySession(machine, since).verify(), "checkpoint $index at revision ${checkpoint.snapshot.revision}")
        }
        // How much the storm exercised depends on the platform's threads; informational.
        println("checkpoints with a full lane: ${checkpoints.count { it.lanes.queued.isNotEmpty() }}, with a command ending: ${checkpoints.count { it.ending.isNotEmpty() }} of ${checkpoints.size}")
        assertEquals(null, handled.tryReceive().getOrNull(), "nothing was reported")
    }
}

@file:OptIn(ExperimentalActronApi::class, ExperimentalCoroutinesApi::class)

package actron.statechart.machine

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
import actron.statechart.Trigger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A checkpoint of the live executor: the snapshot it carried out, its clock, and every command
 * of that snapshot in exactly one place (running, queued in its lane, or ending with its last
 * input on the way), on a machine that fills every kind of lane on entry.
 *
 * ```
 * [*] --> Idle
 * Idle --Go--> Busy        onEnter: Fetch (net, Latest); Log(a), Log(b) (log, Sequential);
 *                                   Ping(1), Ping(2) (ping, DropIfRunning); Work(1..3) (work, Parallel(2))
 * Busy --Go--> Busy
 * Busy --Done--> Idle
 * Busy --after 10s--> Idle
 * ```
 */
class ExecutorCheckpointTest {

    sealed interface Act : Action {
        data object Go : Act
        data object Done : Act
    }

    data object Nothing : Event

    sealed interface Cmd {
        data object Fetch : Cmd
        data class Log(val name: String) : Cmd
        data class Ping(val n: Int) : Cmd
        data class Work(val n: Int) : Cmd
    }

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
            Transition(busy, idle, Trigger.After(10.seconds)),
        ),
    )

    private val machine = Machine<Unit, Act, Cmd, Nothing>(DefinitionId("checkpoint"), DefinitionVersion("1"), chart) {
        onEnter(busy) {
            command(Cmd.Fetch, net, ConcurrencyPolicy.Latest)
            command(Cmd.Log("a"), log, ConcurrencyPolicy.Sequential)
            command(Cmd.Log("b"), log, ConcurrencyPolicy.Sequential)
            command(Cmd.Ping(1), ping, ConcurrencyPolicy.DropIfRunning)
            command(Cmd.Ping(2), ping, ConcurrencyPolicy.DropIfRunning)
            command(Cmd.Work(1), work, ConcurrencyPolicy.Parallel(2))
            command(Cmd.Work(2), work, ConcurrencyPolicy.Parallel(2))
            command(Cmd.Work(3), work, ConcurrencyPolicy.Parallel(2))
        }
    }

    private class TestClock(private val scheduler: TestCoroutineScheduler) : MachineClock {
        override fun now(): MachineTime = MachineTime(scheduler.currentTime.milliseconds)

        override suspend fun delayUntil(deadline: MachineTime) {
            val remaining = deadline - now()
            if (remaining.isPositive()) delay(remaining)
        }
    }

    /** A store whose commands complete when their gate is opened, and never otherwise. */
    private inner class Fixture(scope: TestScope) {
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val gates = mutableMapOf<Cmd, CompletableDeferred<Unit>>()
        val handled = mutableListOf<Throwable>()
        val executionScope = CoroutineScope(dispatcher + SupervisorJob())
        val handler = CommandHandler<Cmd, Act> { command, _ -> gates.getOrPut(command.command) { CompletableDeferred() }.await() }
        val store = MachineStore(machine, Unit, handler, executionScope, TestClock(scope.testScheduler), dispatcher) {
            exceptionHandler(ExceptionHandler { handled += it })
        }

        fun open(command: Cmd) {
            gates.getOrPut(command) { CompletableDeferred() }.complete(Unit)
        }
    }

    private fun ExecutorCheckpoint<Unit, Cmd>.running() = lanes.running.values.map { it.command }.toSet()
    private fun ExecutorCheckpoint<Unit, Cmd>.queued() = lanes.queued.mapValues { (_, waiting) -> waiting.map { it.command } }
    private fun ExecutorCheckpoint<Unit, Cmd>.ending() = ending.values.map { it.command }.toSet()

    @Test
    fun beforeTheStart_theCheckpointIsTheInitialSnapshot_withNothingRunning() = runTest {
        val fixture = Fixture(this)

        val checkpoint = fixture.store.checkpoint()

        assertEquals(ExecutorCheckpoint.initial(machine.initialSnapshot(Unit)), checkpoint)
        fixture.store.close()
    }

    @Test
    fun everyCommandOfTheSnapshot_isRunningOrQueuedOrEnding_exactlyOnce() = runTest {
        val fixture = Fixture(this)
        val store = fixture.store
        store.start()
        runCurrent()
        store.dispatch(Act.Go)
        // Requested behind the decision's turn at the actor: taken before the store has decided
        // the abandonment the actor fed, so the dropped Ping is still ending.
        val early = async { store.checkpoint() }
        runCurrent()

        val checkpoint = early.await()

        assertEquals(setOf(Cmd.Fetch, Cmd.Log("a"), Cmd.Ping(1), Cmd.Work(1), Cmd.Work(2)), checkpoint.running())
        assertEquals(mapOf(log to listOf(Cmd.Log("b")), work to listOf(Cmd.Work(3))), checkpoint.queued())
        assertEquals(setOf(Cmd.Ping(2)), checkpoint.ending(), "dropped: its abandonment is on its way to the machine")
        assertEquals(8, checkpoint.snapshot.commands.size)
        assertTrue(checkpoint.snapshot.revision < store.currentState.revision, "the abandonment was decided after the checkpoint: a step after it")

        val settled = store.checkpoint()
        assertEquals(checkpoint.lanes, settled.lanes)
        assertTrue(settled.ending.isEmpty(), "the machine deregistered the dropped command")
        assertEquals(7, settled.snapshot.commands.size)
        assertEquals(store.currentState, settled.snapshot)
        assertTrue(fixture.handled.isEmpty())
        store.close()
    }

    @Test
    fun aFinishedCommand_isEndingUntilTheMachineDecidesItsCompletion_thenItsLaneMovesOn() = runTest {
        val fixture = Fixture(this)
        val store = fixture.store
        store.start()
        store.dispatch(Act.Go)
        runCurrent()

        fixture.open(Cmd.Log("a"))
        val early = async { store.checkpoint() }
        runCurrent()
        val checkpoint = early.await()

        assertEquals(setOf(Cmd.Log("a")), checkpoint.ending(), "completed at the executor, the completion not yet decided")
        assertTrue(Cmd.Log("b") in checkpoint.running(), "the lane moved on as soon as the job ended")
        assertTrue(log !in checkpoint.queued())

        val settled = store.checkpoint()
        assertTrue(settled.ending.isEmpty())
        assertTrue(settled.registrations.values.none { it.command == Cmd.Log("a") }, "deregistered by the machine")
        assertEquals(setOf(Cmd.Fetch, Cmd.Log("b"), Cmd.Ping(1), Cmd.Work(1), Cmd.Work(2)), settled.running())
        assertEquals(mapOf(work to listOf(Cmd.Work(3))), settled.queued())

        fixture.open(Cmd.Work(2))
        runCurrent()
        val parallel = store.checkpoint()
        assertEquals(setOf(Cmd.Fetch, Cmd.Log("b"), Cmd.Ping(1), Cmd.Work(1), Cmd.Work(3)), parallel.running())
        assertTrue(parallel.lanes.queued.isEmpty())
        store.close()
    }

    @Test
    fun theTimersOfTheSnapshot_haveTheirRemainingTime_atTheCheckpointsClock() = runTest {
        val fixture = Fixture(this)
        val store = fixture.store
        store.start()
        store.dispatch(Act.Go)
        runCurrent()
        val timer = store.currentState.timers.keys.single()

        assertEquals(10.seconds, store.checkpoint().remaining(timer))
        advanceTimeBy(4.seconds)
        val later = store.checkpoint()

        assertEquals(6.seconds, later.remaining(timer))
        assertEquals(MachineTime(4.seconds), later.now)
        assertEquals(null, later.remaining(TimerId(99)))
        store.close()
    }

    @Test
    fun anExitedActivation_leavesNothingInTheLanes() = runTest {
        val fixture = Fixture(this)
        val store = fixture.store
        store.start()
        store.dispatch(Act.Go)
        runCurrent()
        store.dispatch(Act.Done)
        runCurrent()

        val checkpoint = store.checkpoint()

        assertEquals(ExecutorCheckpoint(store.currentState, checkpoint.now), checkpoint)
        assertTrue(checkpoint.snapshot.commands.isEmpty() && checkpoint.snapshot.timers.isEmpty())
        store.close()
    }

    @Test
    fun aReEntry_replacesTheLanesContents_withTheNewActivations() = runTest {
        val fixture = Fixture(this)
        val store = fixture.store
        store.start()
        store.dispatch(Act.Go)
        runCurrent()
        val first = store.checkpoint()
        store.dispatch(Act.Go)
        runCurrent()

        val second = store.checkpoint()

        // The exited activation's Ping still held its lane when the new ones were admitted (a
        // lane frees its place when the job has ended, not when it was cancelled), so both new
        // pings were dropped and the machine has deregistered them by now.
        assertEquals(first.running() - Cmd.Ping(1), second.running())
        assertEquals(first.queued(), second.queued())
        assertTrue(second.registrations.keys.intersect(first.registrations.keys).isEmpty(), "every command is a new one")
        assertTrue(second.registrations.values.all { it.scope == second.snapshot.activations.getValue(busy) })
        assertTrue(fixture.handled.isEmpty(), "the superseded Fetch of the exited activation is not a failure")
        store.close()
    }

    @Test
    fun aClosedStore_hasNoCheckpoint_andAPendingRequestFails() = runTest {
        val fixture = Fixture(this)
        val store = fixture.store
        store.start()
        runCurrent()
        val pending = async { runCatching { store.checkpoint() } }
        store.close()
        runCurrent()

        assertIs<IllegalStateException>(pending.await().exceptionOrNull())
        assertFailsWith<IllegalStateException> { store.checkpoint() }
    }
}

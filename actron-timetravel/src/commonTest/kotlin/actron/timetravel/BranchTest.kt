@file:OptIn(ExperimentalActronApi::class, ExperimentalCoroutinesApi::class, InternalActronApi::class)

package actron.timetravel

import actron.core.Action
import actron.core.Event
import actron.core.ExceptionHandler
import actron.core.ExperimentalActronApi
import actron.core.InternalActronApi
import actron.observability.FailureDescriptor
import actron.statechart.ActionMatcher
import actron.statechart.AtomicState
import actron.statechart.CompoundState
import actron.statechart.ParallelState
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.Transition
import actron.statechart.Trigger
import actron.statechart.machine.AbandonReason
import actron.statechart.machine.CommandHandler
import actron.statechart.machine.CommandId
import actron.statechart.machine.CommandRecord
import actron.statechart.machine.CommandRegistration
import actron.statechart.machine.ConcurrencyPolicy
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.ExecutorCheckpoint
import actron.statechart.machine.LaneId
import actron.statechart.machine.Lanes
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineClock
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MachineStore
import actron.statechart.machine.MachineTime
import actron.statechart.test.VirtualMachineClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A branch from a recorded position: new inputs decided by the pure machine on a virtual
 * clock, commands awaiting answers instead of running, recorded answers reused only for an
 * equal command, timers fired by advancing the clock, the live store untouched.
 *
 * ```
 * [*] --> Idle
 * Idle --Load(query)--> Loading            onEnter: command Fetch(query); timer 10s to Idle
 * Loading --Load(query) / remember--> Loading
 * Loading --Loaded / store--> Content      onEnter: event Shown
 * Content --Refresh--> Loading
 * ```
 */
class BranchTest {

    data class Ctx(val query: String = "", val items: List<String> = emptyList(), val timeouts: Int = 0)

    sealed interface Act : Action {
        data class Load(val query: String) : Act
        data class Loaded(val items: List<String>) : Act
        data object Refresh : Act
    }

    sealed interface Ev : Event {
        data object Shown : Ev
    }

    data class Fetch(val query: String)

    private val root = StateId("Root")
    private val idle = StateId("Idle")
    private val loading = StateId("Loading")
    private val content = StateId("Content")

    private val chart = StateChartDefinition(
        root,
        listOf(CompoundState(root, initial = idle), AtomicState(idle, parent = root), AtomicState(loading, parent = root), AtomicState(content, parent = root)),
        listOf(
            Transition(idle, loading, ActionMatcher.of<Act.Load>("Load"), effect = "remember"),
            Transition(loading, loading, ActionMatcher.of<Act.Load>("Load"), effect = "remember"),
            Transition(loading, content, ActionMatcher.of<Act.Loaded>("Loaded"), effect = "store"),
            Transition(loading, idle, Trigger.After(10.seconds), effect = "timeout"),
            Transition(content, loading, ActionMatcher.of<Act.Refresh>("Refresh")),
        ),
    )

    private val machine = Machine<Ctx, Act, Fetch, Ev>(DefinitionId("branchable"), DefinitionVersion("1"), chart) {
        effect("remember") { c, a -> c.copy(query = (a as Act.Load).query) }
        effect("store") { c, a -> c.copy(items = (a as Act.Loaded).items) }
        effect("timeout") { c, _ -> c.copy(timeouts = c.timeouts + 1) }
        onEnter(loading) { command(Fetch(context.query)) }
        onEnter(content) { event(Ev.Shown) }
    }

    /** A live run: Load("cats") answered, then Refresh with the answer still pending when recorded. */
    private class Live(val recording: Recording<Ctx, Act, Fetch, Ev>, val store: MachineStore<Ctx, Act, Fetch, Ev>, val handlerCalls: () -> Int)

    private suspend fun kotlinx.coroutines.test.TestScope.recordedLive(): Live {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        var calls = 0
        val handler = CommandHandler<Fetch, Act> { command, results ->
            calls++
            if (command.command.query == "cats") {
                delay(100.milliseconds)
                results.result(Act.Loaded(listOf("tom", "felix")))
            } else {
                awaitCancellation()
            }
        }
        val recorder = MachineRecorder(machine, Ctx())
        val store = MachineStore(machine, Ctx(), handler, scope, VirtualMachineClock(testScheduler), dispatcher, observers = listOf(recorder)) {
            exceptionHandler(ExceptionHandler.Ignore)
        }
        store.start()
        runCurrent()
        store.dispatch(Act.Load("cats"))
        advanceTimeBy(150.milliseconds); runCurrent()
        assertTrue(store.currentState.isActive(content))
        store.dispatch(Act.Refresh) // Fetch("cats") again, never answered by the live handler
        runCurrent()
        return Live(recorder.recording(), store, { calls })
    }

    @Test
    fun aBranchFromAnEarlierPosition_takesNewInputs_whileTheLiveStoreIsUntouched() = runTest {
        val live = recordedLive()
        val liveBefore = live.store.currentState
        val callsBefore = live.handlerCalls()
        val session = ReplaySession(machine, live.recording)
        val afterStart = 1
        session.seek(afterStart)

        val branch = session.branch()
        assertTrue(branch.snapshot.isActive(idle))
        val decision = branch.dispatch(Act.Load("dogs"))

        assertTrue(decision.isHandled && branch.snapshot.isActive(loading))
        assertEquals(listOf(Fetch("dogs")), branch.awaiting.map { it.command })
        assertEquals(liveBefore, live.store.currentState, "the live store did not move")
        assertEquals(callsBefore, live.handlerCalls(), "no handler ran for the branch")
        // The branch's ids continue the snapshot's counters: no collision with the recorded ones.
        assertTrue(branch.awaiting.single().id.value > live.recording.snapshotAt(afterStart).counters.commands)
        live.store.close()
    }

    @Test
    fun anAwaitingCommand_isAnsweredByTheCaller_orByAnEqualRecordedCommand() = runTest {
        val live = recordedLive()
        val session = ReplaySession(machine, live.recording)
        session.seek(live.recording.length) // Loading again with Fetch("cats") awaiting, as the live store is
        val branch = session.branch()
        val awaiting = branch.awaiting.single()
        assertEquals(Fetch("cats"), awaiting.command)

        // The recording answered an equal Fetch("cats") from the same node: reusable.
        val reused = branch.reuseRecordedAnswers(awaiting.id)
        assertTrue(reused != null && reused.isNotEmpty())
        assertTrue(branch.snapshot.isActive(content))
        assertEquals(listOf("tom", "felix"), branch.snapshot.context.items)
        assertEquals(listOf(Ev.Shown), branch.effects.map { it.event })
        assertTrue(branch.awaiting.isEmpty(), "the recorded completion was reused as well")

        // A different query has no recorded answer: awaiting, until the caller answers.
        branch.dispatch(Act.Refresh)
        branch.dispatch(Act.Load("dogs"))
        val dogs = branch.awaiting.single()
        assertEquals(Fetch("dogs"), dogs.command)
        assertNull(branch.reuseRecordedAnswers(dogs.id), "Fetch(dogs) was never recorded; no fallback to anything")
        branch.answer(dogs.id, Act.Loaded(listOf("rex")))
        assertEquals(listOf("rex"), branch.snapshot.context.items)
        // The answer moved the machine out of Loading, which ends the command's activation: it is
        // no longer awaiting, as the live executor would have cancelled it, and cannot be completed.
        assertTrue(branch.awaiting.isEmpty())
        assertFailsWith<IllegalArgumentException> { branch.complete(dogs.id) }
        live.store.close()
    }

    @Test
    fun timers_fireWhenTheVirtualClockAdvances_pastTheirDeadlines() = runTest {
        val live = recordedLive()
        val session = ReplaySession(machine, live.recording)
        session.seek(live.recording.length)
        val branch = session.branch()
        val nowAtBranch = branch.now
        assertEquals(1, branch.snapshot.timers.size)

        assertTrue(branch.advance(9.seconds).isEmpty(), "not due yet")
        assertEquals(nowAtBranch + 9.seconds, branch.now)
        val fired = branch.advance(2.seconds)

        assertEquals(1, fired.size)
        assertTrue(branch.snapshot.isActive(idle))
        assertEquals(1, branch.snapshot.context.timeouts)
        assertTrue(branch.awaiting.isEmpty(), "the awaiting Fetch left with Loading")
        assertEquals(nowAtBranch + 11.seconds, branch.now)
        val firedInput = fired.single().let { d -> branch.history.indexOf(d) }
        assertTrue(firedInput >= 0)
        assertFailsWith<IllegalArgumentException> { branch.advance((-1).seconds) }
        live.store.close()
    }

    @Test
    fun aFailedCommand_stepsWithCommandFailure_andLeavesAwaiting() = runTest {
        val live = recordedLive()
        val session = ReplaySession(machine, live.recording)
        session.seek(live.recording.length)
        val branch = session.branch()
        val awaiting = branch.awaiting.single()

        val decision = branch.fail(awaiting.id, FailureDescriptor(type = "IOException"))

        assertTrue(decision.isHandled)
        assertTrue(branch.awaiting.isEmpty())
        assertTrue(branch.snapshot.isActive(loading), "no transition declared for CommandFailure")
        live.store.close()
    }

    @Test
    fun latestLane_supersedesAwaitingCommandsInTheBranch() = runTest {
        val a = StateId("A")
        val b = StateId("B")
        val b0 = StateId("B0")
        val b1 = StateId("B1")
        val regions = StateChartDefinition(
            root,
            listOf(ParallelState(root), AtomicState(a, parent = root), CompoundState(b, initial = b0, parent = root), AtomicState(b0, parent = b), AtomicState(b1, parent = b)),
            listOf(Transition(b0, b1, ActionMatcher.of<Act.Refresh>("Refresh"))),
        )
        val lanes = Machine<Ctx, Act, Fetch, Ev>(DefinitionId("lanes"), DefinitionVersion("1"), regions) {
            onEnter(a) { command(Fetch("a"), LaneId("net"), ConcurrencyPolicy.Latest) }
            onEnter(b1) { command(Fetch("b"), LaneId("net"), ConcurrencyPolicy.Latest) }
        }
        val initial = lanes.initialSnapshot(Ctx())
        val started = lanes.decide(initial, MachineInput.Start(MachineTime.Zero))
        val recording = Recording<Ctx, Act, Fetch, Ev>(lanes.id, lanes.version, initial, listOf(RecordedStep.Committed<Ctx, Act, Fetch, Ev>(MachineInput.Start(MachineTime.Zero), started)))
        val session = ReplaySession(lanes, recording)
        session.seek(1)
        val branch = session.branch()
        assertEquals(listOf(Fetch("a")), branch.awaiting.map { it.command })

        branch.dispatch(Act.Refresh)

        assertEquals(listOf(Fetch("b")), branch.awaiting.map { it.command }, "a's command was superseded in the lane")
        assertTrue(branch.history.any { d -> d.snapshot.commands.keys.none { it == CommandId(1) } })
        assertTrue(branch.history.size >= 2, "the abandonment was decided as an input, as the live executor would feed it")
        val abandoned = branch.history.drop(1).firstOrNull()
        assertTrue(abandoned != null)
        assertEquals(setOf(CommandId(2)), branch.snapshot.commands.keys)
        assertEquals(AbandonReason.Superseded, AbandonReason.Superseded)
    }

    /**
     * ```
     * [*] --> Busy       onEnter: Fetch("one"), Fetch("two"), Fetch("three") in lane "audit", Sequential; Fetch("ping-1"), Fetch("ping-2") in lane "ping", DropIfRunning
     * Busy --Refresh--> Busy
     * ```
     */
    private val sequential = Machine<Ctx, Act, Fetch, Ev>(
        DefinitionId("sequential"), DefinitionVersion("1"),
        StateChartDefinition(root, listOf(CompoundState(root, initial = idle), AtomicState(idle, parent = root)), listOf(Transition(idle, idle, ActionMatcher.of<Act.Refresh>("Refresh")))),
    ) {
        onEnter(idle) {
            command(Fetch("one"), LaneId("audit"), ConcurrencyPolicy.Sequential)
            command(Fetch("two"), LaneId("audit"), ConcurrencyPolicy.Sequential)
            command(Fetch("three"), LaneId("audit"), ConcurrencyPolicy.Sequential)
            command(Fetch("ping-1"), LaneId("ping"), ConcurrencyPolicy.DropIfRunning)
            command(Fetch("ping-2"), LaneId("ping"), ConcurrencyPolicy.DropIfRunning)
        }
    }

    @Test
    fun queuedCommands_cannotBeAnswered_untilTheirLaneLetsThemRun() {
        val start = sequential.decide(sequential.initialSnapshot(Ctx()), MachineInput.Start(MachineTime.Zero))
        // A branch from the start decision's checkpoint: the lanes as the executor would hold them.
        val recording = Recording<Ctx, Act, Fetch, Ev>(sequential.id, sequential.version, sequential.initialSnapshot(Ctx()), listOf(RecordedStep.Committed<Ctx, Act, Fetch, Ev>(MachineInput.Start(MachineTime.Zero), start)))
        val session = ReplaySession(sequential, recording)
        session.seek(1)
        val lanes = session.branch()

        assertEquals(listOf(Fetch("one"), Fetch("ping-1")), lanes.checkpoint.lanes.running.values.map { it.command }, "one per sequential lane, the second ping was dropped")
        assertEquals(listOf(Fetch("ping-2")), lanes.checkpoint.ending.values.map { it.command }, "dropped: its abandonment is on its way, so the machine still holds it")
        assertEquals(listOf(Fetch("one"), Fetch("ping-1"), Fetch("ping-2")), lanes.awaiting.map { it.command }, "the caller may still answer what is ending, as a live result could arrive first")
        assertEquals(mapOf(LaneId("audit") to listOf(Fetch("two"), Fetch("three"))), lanes.queued.mapValues { (_, waiting) -> waiting.map { it.command } })
        val two = lanes.queued.getValue(LaneId("audit")).first()
        val refused = assertFailsWith<IllegalArgumentException> { lanes.answer(two.id, Act.Loaded(emptyList())) }
        assertTrue("queued in lane audit" in refused.message.orEmpty(), refused.message)

        lanes.complete(lanes.awaiting.first { it.command == Fetch("one") }.id)

        assertEquals(listOf(Fetch("ping-1"), Fetch("two"), Fetch("ping-2")), lanes.awaiting.map { it.command }, "the lane moved on; running commands keep their start order")
        assertEquals(listOf(Fetch("three")), lanes.queued.getValue(LaneId("audit")).map { it.command })
        lanes.complete(two.id)
        lanes.complete(lanes.awaiting.first { it.command == Fetch("three") }.id)
        assertTrue(lanes.queued.isEmpty())
        assertEquals(listOf(Fetch("ping-1"), Fetch("ping-2")), lanes.awaiting.map { it.command })
        assertEquals(lanes.snapshot.commands.keys, lanes.checkpoint.registrations.keys, "the branch's checkpoint is consistent")
    }

    @Test
    fun aBranchFromTheLiveExecutorsCheckpoint_startsWhereTheRunIs_withoutARecording() = runTest {
        val live = recordedLive()
        val checkpoint = live.store.checkpoint()
        assertEquals(listOf(Fetch("cats")), checkpoint.lanes.running.values.map { it.command })

        val branch = Branch(machine, checkpoint)

        assertEquals(checkpoint, branch.checkpoint)
        assertEquals(listOf(Fetch("cats")), branch.awaiting.map { it.command })
        assertNull(branch.reuseRecordedAnswers(branch.awaiting.single().id), "no recording: the caller answers")
        branch.answer(branch.awaiting.single().id, Act.Loaded(listOf("garfield")))
        assertTrue(branch.snapshot.isActive(content))
        assertEquals(listOf("garfield"), branch.snapshot.context.items)
        assertEquals(live.store.currentState.context.items, listOf("tom", "felix"), "the live store did not move")
        assertFailsWith<IllegalArgumentException> { Branch(Machine<Ctx, Act, Fetch, Ev>(DefinitionId("other"), DefinitionVersion("1"), chart) {}, checkpoint) }
        live.store.close()
    }
}

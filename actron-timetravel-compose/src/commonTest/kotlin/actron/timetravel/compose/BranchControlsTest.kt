@file:OptIn(ExperimentalActronApi::class)

package actron.timetravel.compose

import actron.core.Action
import actron.core.Event
import actron.core.ExperimentalActronApi
import actron.observability.StoreInstanceId
import actron.statechart.ActionMatcher
import actron.statechart.AtomicState
import actron.statechart.CompoundState
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.Transition
import actron.statechart.Trigger
import actron.statechart.machine.CommandId
import actron.statechart.machine.ConcurrencyPolicy
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.LaneId
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MachineTime
import actron.timetravel.GroupBranch
import actron.timetravel.GroupRecording
import actron.timetravel.GroupReplaySession
import actron.timetravel.GroupStep
import actron.timetravel.RecordedStep
import actron.timetravel.Recording
import actron.timetravel.ReplaySession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The branch controls without the screen: scripted inputs decide, awaiting commands are
 * answered or completed, the clock fires timers, a refused request is a problem and not a
 * decision, and the definition highlights what is active.
 *
 * ```
 * [*] --> Root
 * state Root {
 *     [*] --> Idle
 *     Idle --Load--> Loading            onEnter: command Fetch in lane "load", Latest; after 10s --> Idle
 *     Loading --Loaded--> Content
 *     Content --Load--> Loading
 * }
 * ```
 */
class BranchControlsTest {

    @Test
    fun anExperimentRestoresAwaitingCommandsAndTimers_withoutChangingTheReplayCheckpoint() {
        val initial = machine.initialSnapshot(Ctx())
        val start = MachineInput.Start(MachineTime.Zero)
        val started = machine.decide(initial, start)
        val load = MachineInput.Dispatch(Act.Load, MachineTime.Zero)
        val loading = machine.decide(started.snapshot, load)
        val recording = Recording(machine.id, machine.version, initial, listOf(
            RecordedStep.Committed<Ctx, Act, Fetch, Nothing>(start, started),
            RecordedStep.Committed<Ctx, Act, Fetch, Nothing>(load, loading),
        ))
        val replay = GroupReplayControls(GroupReplaySession(mapOf(store to machine), GroupRecording(mapOf(store to recording), listOf(GroupStep(store, 0), GroupStep(store, 1)))))
        replay.seek(2)
        val checkpoint = replay.checkpoint(store)
        val answer = BranchInput.Answer(store, "Loaded", Act.Loaded)
        val controls = GroupTimeTravelControls(replay, inputs = listOf(answer))
        controls.branchHere()
        val experiment = checkNotNull(controls.branch)
        val awaiting = experiment.awaiting(store).single()
        assertEquals(Fetch, awaiting.command)
        assertEquals(checkpoint.snapshot.timers, experiment.snapshot(store).timers)
        experiment.answer(answer, awaiting.id)
        assertTrue(experiment.snapshot(store).isActive(content))
        assertEquals(checkpoint, replay.checkpoint(store))

        controls.returnToReplay()
        controls.branchHere()
        val fresh = checkNotNull(controls.branch)
        assertEquals(awaiting, fresh.awaiting(store).single())
        fresh.advance(11.seconds)
        assertTrue(fresh.snapshot(store).isActive(idle), "the recorded timer fires on the experiment's virtual clock")
        assertEquals(checkpoint, replay.checkpoint(store))
    }

    data class Ctx(val loaded: Int = 0)

    sealed interface Act : Action {
        data object Load : Act
        data object Loaded : Act
    }

    data object Fetch

    data object Nothing : Event

    private val root = StateId("Root")
    private val idle = StateId("Idle")
    private val loading = StateId("Loading")
    private val content = StateId("Content")
    private val chart = StateChartDefinition(
        root,
        listOf(CompoundState(root, initial = idle), AtomicState(idle, parent = root), AtomicState(loading, parent = root), AtomicState(content, parent = root)),
        listOf(
            Transition(idle, loading, ActionMatcher.of<Act.Load>("Load")),
            Transition(loading, content, ActionMatcher.of<Act.Loaded>("Loaded"), effect = "count"),
            Transition(loading, idle, Trigger.After(10.seconds)),
            Transition(content, loading, ActionMatcher.of<Act.Load>("Load")),
        ),
    )
    private val machine = Machine<Ctx, Act, Fetch, Nothing>(DefinitionId("branchy"), DefinitionVersion("1"), chart) {
        effect("count") { c, _ -> c.copy(loaded = c.loaded + 1) }
        onEnter(loading) { command(Fetch, LaneId("load"), ConcurrencyPolicy.Latest) }
    }
    private val store = StoreInstanceId("s")

    private fun controls(): BranchControls {
        val start = MachineInput.Start(MachineTime.Zero)
        val started = machine.decide(machine.initialSnapshot(Ctx()), start)
        val recording = Recording(machine.id, machine.version, machine.initialSnapshot(Ctx()), listOf(RecordedStep.Committed<Ctx, Act, Fetch, Nothing>(start, started)))
        val session = ReplaySession(machine, recording).also { it.seek(1) }
        val branch = GroupBranch(mapOf(store to session.branch()), emptyList())
        return BranchControls(
            branch, mapOf(store to machine),
            listOf(BranchInput.Dispatch(store, "Load", Act.Load), BranchInput.Answer(store, "Loaded", Act.Loaded)),
        )
    }

    @Test
    fun scriptedInputs_decide_commandsAreAnswered_andTimersFire_whenTheClockAdvances() {
        val c = controls()
        assertEquals(listOf(store), c.members)
        assertTrue(c.snapshot(store).isActive(idle))
        assertTrue("class Root,Idle actron_active" in c.mermaid(store), c.mermaid(store))

        c.dispatch(c.inputsOf(store).filterIsInstance<BranchInput.Dispatch>().single())

        assertTrue(c.snapshot(store).isActive(loading))
        val awaiting = c.awaiting(store).single()
        assertEquals(Fetch, awaiting.command)
        val first = c.decisions.single()
        assertEquals(BranchDecision(0, store, "Load", first.decision, first.diff), first)
        assertEquals("#0 s Load: handled revision=2; entered Loading; exited Idle; commands +c1; timers +t1", BranchText.line(first))
        assertTrue("actron_active" in c.mermaid(store) && "Loading" in c.mermaid(store).lines().last())

        c.answer(c.inputsOf(store).filterIsInstance<BranchInput.Answer>().single(), awaiting.id)

        assertTrue(c.snapshot(store).isActive(content))
        assertEquals(1, (c.snapshot(store).context as Ctx).loaded)
        assertTrue(c.awaiting(store).isEmpty(), "the transition exited the command's activation, which cancelled it")
        assertEquals(listOf("Load", "Loaded -> c1"), c.decisions.map { it.cause })
        assertNull(c.problem)

        // A command that is not awaiting any more is refused: a problem, not a decision.
        c.complete(store, awaiting.id)
        assertTrue(c.problem != null, "refused")
        c.complete(store, CommandId(9))
        assertTrue(c.problem != null, "refused")
        assertEquals(2, c.decisions.size)

        // Back to Loading from Content; the clock fires the timer.
        c.dispatch(BranchInput.Dispatch(store, "Load", Act.Load))
        assertNull(c.problem, "the next request clears the problem")
        assertTrue(c.snapshot(store).isActive(loading))
        c.advance(11.seconds)
        assertTrue(c.snapshot(store).isActive(idle))
        assertEquals("timer", c.decisions.last().cause)
        assertEquals(MachineTime(11.seconds), c.now(store))
    }
}

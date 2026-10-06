@file:OptIn(koma.core.ExperimentalKomaApi::class)

package koma.timetravel

import koma.core.Action
import koma.core.Event
import koma.statechart.*
import koma.statechart.machine.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class RecordInputsTest {
    private data object Tick : Action
    private data object Noise : Action
    private val idle = StateId("idle")

    @Test fun generatedInputsBecomeReplayableRecordingWithIgnoredAndFailedStepsWithoutExtraDecisions() {
        var guards = 0
        val machine = Machine<Int, Action, String, Event>(DefinitionId("record-inputs"), DefinitionVersion("1"),
            StateChartDefinition(idle, listOf(AtomicState(idle)), listOf(Transition(idle, idle, Trigger.Eventless, guard = "never", kind = TransitionKind.Internal)))) {
            guard("never") { _, _ -> guards++; false }
            onAction(idle, ActionMatcher.of<Tick>("tick")) { context++; command("never-execute-io") }
            invariant("less-than-two") { it.context < 2 }
            enforceInvariants()
        }
        val initial = machine.initialSnapshot(0)
        val inputs = listOf<MachineInput<Action>>(MachineInput.Start(MachineTime.Zero), MachineInput.Dispatch(Tick, MachineTime.Zero),
            MachineInput.Dispatch(Noise, MachineTime.Zero), MachineInput.Dispatch(Tick, MachineTime.Zero))
        val recording = machine.recordInputs(initial, inputs)
        assertEquals(3, guards, "Start and two handled macrosteps each evaluate their automatic guard once")
        assertEquals(inputs, recording.steps.map { it.input })
        assertTrue(recording.steps[2] is RecordedStep.Ignored)
        assertEquals("InvariantViolationException", (recording.steps.last() as RecordedStep.Failed).failure.type)
        assertEquals(1, recording.snapshotAt(recording.length).context)
        assertEquals("never-execute-io", recording.checkpointAt(recording.length).registrations.values.single().command)
        assertNull(ReplaySession(machine, recording).verify())
        assertEquals(6, guards, "Verification is a separate explicit replay, not a second decision during recording")
    }

    @Test fun checkpointPayloadsAndClockAreValidatedBeforeRecordingAStartedWorkflow() {
        val machine = Machine<Unit, Action, String, Event>(DefinitionId("checkpoint-recording"), DefinitionVersion("1"),
            StateChartDefinition(idle, listOf(AtomicState(idle)), emptyList())) { onEnter(idle) { command("pending") } }
        val first = machine.recordInputs(machine.initialSnapshot(Unit), listOf(MachineInput.Start(MachineTime.Zero)))
        val checkpoint = first.checkpointAt(first.length)
        assertFailsWith<IllegalArgumentException> { machine.recordInputs(checkpoint.snapshot, emptyList()) }
        assertFailsWith<IllegalArgumentException> { machine.recordInputs(ExecutorCheckpoint.initial(checkpoint.snapshot), emptyList()) }
        assertFailsWith<IllegalArgumentException> { machine.recordInputs(checkpoint, listOf(MachineInput.Dispatch(Noise, MachineTime.Zero + (-1).seconds))) }
        val resumed = machine.recordInputs(checkpoint, listOf(MachineInput.Dispatch(Noise, MachineTime.Zero)))
        assertEquals(checkpoint.registrations, resumed.checkpointAt(resumed.length).registrations)
        assertNull(ReplaySession(machine, resumed).verify())
    }
}

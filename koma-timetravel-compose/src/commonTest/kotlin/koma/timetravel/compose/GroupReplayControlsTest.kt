@file:OptIn(ExperimentalKomaApi::class)

package koma.timetravel.compose

import koma.core.Action
import koma.core.Event
import koma.core.ExperimentalKomaApi
import koma.core.InputId
import koma.observability.Capability
import koma.observability.ExecutionMode
import koma.observability.GroupSeq
import koma.observability.InputDescriptor
import koma.observability.JOURNAL_FORMAT_VERSION
import koma.observability.JournalEntry
import koma.observability.JournalRecord
import koma.observability.MachineGroupId
import koma.observability.OutcomeDescriptor
import koma.observability.OutcomeKind
import koma.observability.Payload
import koma.observability.RuntimeSessionId
import koma.observability.StoreInstanceId
import koma.observability.StoreSeq
import koma.statechart.ActionMatcher
import koma.statechart.AtomicState
import koma.statechart.CompoundState
import koma.statechart.StateChartDefinition
import koma.statechart.StateId
import koma.statechart.Transition
import koma.statechart.machine.DefinitionId
import koma.statechart.machine.DefinitionVersion
import koma.statechart.machine.Machine
import koma.statechart.machine.MachineInput
import koma.statechart.machine.MachineTime
import koma.timetravel.GroupMismatch
import koma.timetravel.GroupRecording
import koma.timetravel.GroupReplaySession
import koma.timetravel.GroupStep
import koma.timetravel.RecordedStep
import koma.timetravel.Recording
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class GroupReplayControlsTest {
    @Test
    fun oneCursor_restoresEveryMember_andVerificationDoesNotMoveIt() {
        val controls = GroupReplayFixture.controls()
        assertNull(controls.selected)
        assertFalse(controls.canStepBackward)
        repeat(3) { controls.stepForward() }
        assertEquals(3, controls.position)
        assertEquals(GroupReplayFixture.Ctx(1), controls.snapshot(GroupReplayFixture.a).context)
        assertEquals(GroupReplayFixture.Ctx(10), controls.snapshot(GroupReplayFixture.b).context)
        val selected = checkNotNull(controls.selected)
        assertEquals(2, selected.index)
        assertEquals(GroupReplayFixture.Ctx(0), selected.before.context)
        assertEquals(GroupReplayFixture.Ctx(1), selected.after.context)
        assertTrue(selected.diff.contextChanged)

        controls.seek(5)
        assertEquals(GroupReplayFixture.Ctx(2), controls.snapshot(GroupReplayFixture.a).context)
        assertEquals(GroupReplayFixture.Ctx(11), controls.snapshot(GroupReplayFixture.b).context)
        val checkpoints = controls.members.map { controls.checkpoint(it) }
        controls.verify()
        assertEquals(emptyList(), controls.verification)
        assertEquals(5, controls.position)
        assertEquals(checkpoints, controls.members.map { controls.checkpoint(it) })
        controls.stepBackward()
        assertEquals(4, controls.position)
        assertEquals(GroupReplayFixture.Ctx(1), controls.snapshot(GroupReplayFixture.a).context)
        assertEquals(GroupReplayFixture.Ctx(11), controls.snapshot(GroupReplayFixture.b).context)
        controls.seek(Int.MAX_VALUE)
        assertEquals(6, controls.position)
        assertFalse(controls.canStepForward)
        assertTrue(checkNotNull(controls.forwardUnavailable).contains("end of the group"))
        controls.seek(-1)
        assertEquals(0, controls.position)
        assertNull(controls.selected)
    }

    @Test
    fun aChangedReducer_stopsAtTheGlobalPosition_andNamesTheMemberAndDifference() {
        val controls = GroupReplayFixture.controls(changed = true)
        repeat(3) { controls.stepForward() }
        val before = controls.members.map { controls.snapshot(it) }
        controls.stepForward()
        val mismatch = assertIs<GroupMismatch.Replay>(controls.divergence)
        assertEquals(3, mismatch.position)
        assertEquals(GroupReplayFixture.b, mismatch.store)
        assertEquals(before, controls.members.map { controls.snapshot(it) })
        assertEquals(3, controls.position)
        assertEquals(3, controls.selected?.index, "show the failing input, not the last successful one")
        assertFalse(controls.canStepForward)
        assertTrue(checkNotNull(controls.forwardUnavailable).contains("root-2"))
        assertTrue(mismatch.mismatch.differences.any { it.contains("context") })
        controls.verify()
        assertTrue(checkNotNull(controls.verification).isNotEmpty())
        assertEquals(3, controls.position)
        controls.seek(2)
        assertNull(controls.divergence)
        assertTrue(controls.canStepForward)
    }

    @Test
    fun anEmptyGroup_hasNoSelectedInput_andNoForwardStep() {
        val controls = GroupReplayControls(GroupReplaySession(emptyMap(), GroupRecording(emptyMap(), emptyList())))
        controls.seek(10)
        controls.stepForward()
        controls.stepBackward()
        controls.verify()
        assertEquals(0, controls.position)
        assertNull(controls.selected)
        assertFalse(controls.canStepForward)
        assertEquals(emptyList(), controls.verification)
    }
}

internal object GroupReplayFixture {
    val a = StoreInstanceId("chat-7")
    val b = StoreInstanceId("root-2")
    data class Ctx(val n: Int)
    data object Inc : Action
    data object Nothing : Event
    private val root = StateId("Root")
    private val idle = StateId("Idle")
    private val chart = StateChartDefinition(root, listOf(CompoundState(root, initial = idle), AtomicState(idle, parent = root)), listOf(Transition(idle, idle, ActionMatcher.of<Inc>("Inc"), effect = "inc")))
    fun machine(increment: Int = 1) = Machine<Ctx, Inc, Nothing, Nothing>(DefinitionId("group-counter"), DefinitionVersion("1"), chart) {
        effect("inc") { c, _ -> c.copy(n = c.n + increment) }
    }
    private fun recording(initial: Int): Recording<Ctx, Inc, Nothing, Nothing> {
        val machine = machine()
        val inputs = listOf(MachineInput.Start(MachineTime.Zero), MachineInput.Dispatch(Inc, MachineTime(10.milliseconds)), MachineInput.Dispatch(Inc, MachineTime(20.milliseconds)))
        var snapshot = machine.initialSnapshot(Ctx(initial))
        val steps = inputs.map { input ->
            val decision = machine.decide(snapshot, input)
            snapshot = decision.snapshot
            RecordedStep.Committed<Ctx, Inc, Nothing, Nothing>(input, decision)
        }
        return Recording(machine.id, machine.version, machine.initialSnapshot(Ctx(initial)), steps)
    }
    fun recording() = GroupRecording(mapOf(a to recording(0), b to recording(10)), (0..2).flatMap { listOf(GroupStep(a, it), GroupStep(b, it)) })
    fun controls(changed: Boolean = false) = GroupReplayControls(GroupReplaySession(mapOf(a to machine(), b to machine(if (changed) 7 else 1)), recording()))

    fun journal(recording: GroupRecording): List<JournalRecord<*, *, *>> = buildList {
        var seq = 0L
        fun append(store: StoreInstanceId, entry: JournalEntry<*, *, *>) {
            seq++
            add(JournalRecord(JOURNAL_FORMAT_VERSION, RuntimeSessionId("group-run"), MachineGroupId("counter"), store, ExecutionMode.Live, GroupSeq(seq), StoreSeq(seq), seq.milliseconds, entry))
        }
        for (store in recording.members.keys) append(store, JournalEntry.StoreRegistered(Capability.DeterministicReplay))
        for (step in recording.order) {
            val recorded = recording.members.getValue(step.store).steps[step.step] as RecordedStep.Committed<*, *, *, *>
            val id = InputId(step.step.toLong() + 1)
            val kind = if (recorded.input is MachineInput.Start) InputDescriptor.Startup else InputDescriptor.Dispatch(Payload.Projected("Inc"))
            append(step.store, JournalEntry.InputAccepted(id, kind))
            append(step.store, JournalEntry.ProcessingStarted(id, step.step.toLong() + 1))
            append(step.store, JournalEntry.StateCommitted(id, recorded.decision.snapshot.revision, Payload.Omitted, Payload.Omitted))
            append(step.store, JournalEntry.ProcessingFinished(id, step.step.toLong() + 1, OutcomeDescriptor(OutcomeKind.Handled, 1), 1.milliseconds))
        }
    }
}

@file:OptIn(ExperimentalKomaApi::class, ExperimentalCoroutinesApi::class)

package koma.timetravel

import koma.core.ExperimentalKomaApi
import koma.observability.StoreInstanceId
import koma.statechart.machine.EffectId
import koma.statechart.machine.MachineInput
import koma.statechart.machine.MachineTime
import koma.statechart.machine.MessageId
import koma.statechart.machine.SourceId
import koma.timetravel.GroupFixture.PickerAct
import koma.timetravel.GroupFixture.PickerCtx
import koma.timetravel.GroupFixture.RootAct
import koma.timetravel.GroupFixture.RootCtx
import koma.timetravel.GroupFixture.RootEv
import koma.timetravel.GroupFixture.machines
import koma.timetravel.GroupFixture.pickerId
import koma.timetravel.GroupFixture.rootId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * A recorded group run replays in the group's order without a mismatch; a delivery the
 * recording cannot have produced (twice, before its send, from nobody, where no route leads) is
 * a mismatch; a group recording since a cut replays from it, its in-flight messages expected.
 */
class GroupReplaySessionTest {

    private fun TestScope.script(live: GroupFixture.Live) {
        runCurrent()
        live.pickerStore.dispatch(PickerAct.Pick("tom"))
        runCurrent()
        live.pickerStore.dispatch(PickerAct.Pick("ann"))
        advanceTimeBy(60.milliseconds)
        runCurrent()
    }

    @Test
    fun aRecordedGroupRun_replaysInItsOrder_withoutAMismatch() = runTest {
        val live = GroupFixture.Live(this)
        script(live)
        val recording = live.recorder.recording()
        assertTrue(live.recorder.problems.isEmpty())
        assertEquals(listOf("tom", "ann"), live.rootStore.currentState.context.names)
        assertEquals(listOf(1, 2), live.pickerStore.currentState.context.acks)

        val session = GroupReplaySession(machines, recording)

        assertEquals(emptyList(), session.verify())
        assertTrue(recording.length > 6, "$recording")
        assertEquals(2, recording.routes.size)
        var matched = 0
        while (true) {
            val step = session.stepForward() ?: break
            assertIs<GroupReplayStep.Matched>(step)
            matched++
        }
        assertEquals(recording.length, matched)
        assertEquals(live.rootStore.currentState, session.snapshotOf(rootId))
        assertEquals(live.pickerStore.currentState, session.snapshotOf(pickerId))
        // The root's first apply came from the picker's first pick: in the order, the send precedes the receive.
        val receive = recording.order.indexOfFirst { step -> step.store == rootId && recording.members.getValue(rootId).steps[step.step].input is MachineInput.BridgeReceived }
        val send = recording.order.indexOfFirst { step -> step.store == pickerId && (recording.members.getValue(pickerId).steps[step.step] as? RecordedStep.Committed)?.decision?.effects?.isNotEmpty() == true }
        assertTrue(send in 0 until receive, "send at $send, receive at $receive")
        session.seek(receive)
        assertTrue((session.snapshotOf(rootId).context as RootCtx).names.isEmpty(), "before the receive the root has nothing")
        assertIs<GroupReplayStep.Matched>(session.stepForward())
        assertEquals(listOf("tom"), (session.snapshotOf(rootId).context as RootCtx).names)
        assertTrue(session.stepBackward())
        assertEquals(receive, session.position)
        live.close()
    }

    @Test
    fun aDeliveryTheRecordingCannotHaveProduced_isAMismatch() = runTest {
        val live = GroupFixture.Live(this)
        script(live)
        val recording = live.recorder.recording()
        val rootRecording = recording.members.getValue(rootId) as Recording<RootCtx, RootAct, Nothing, RootEv>
        val received = rootRecording.steps.first { it.input is MachineInput.BridgeReceived }.input as MachineInput.BridgeReceived<RootAct>

        // Delivered twice: the same message decided again at the end of the run.
        val twice = rootRecording.appending(received.copy(now = MachineTime(1000.milliseconds)))
        val doubled = GroupRecording(recording.members + (rootId to twice), recording.order + GroupStep(rootId, twice.length - 1), recording.routes)
        assertEquals(listOf<GroupMismatch>(GroupMismatch.DeliveredTwice(doubled.length - 1, rootId, received.message, recording.order.indexOfFirst { it.store == rootId && rootRecording.steps[it.step].input == received })), GroupReplaySession(machines, doubled).verify())

        // Received before sent: the root's steps moved ahead of the picker's.
        val early = GroupRecording(recording.members, recording.order.filter { it.store == rootId } + recording.order.filter { it.store == pickerId }, recording.routes)
        val mismatch = assertIs<GroupMismatch.ReceivedBeforeSent>(GroupReplaySession(machines, early).verify().first())
        assertEquals(received.message, mismatch.message)

        // No route: the bridge never led from the picker to the root.
        val unrouted = GroupRecording(recording.members, recording.order, recording.routes.filter { it.from != pickerId })
        assertTrue(GroupReplaySession(machines, unrouted).verify().all { it is GroupMismatch.NoRoute && it.store == rootId })

        // Sent by nobody: a message from a store that is not a member.
        val ghost = rootRecording.appending(MachineInput.BridgeReceived(MessageId(StoreInstanceId("ghost"), EffectId(1)), RootAct.Apply("x"), MachineTime(1000.milliseconds)))
        val haunted = GroupRecording(recording.members + (rootId to ghost), recording.order + GroupStep(rootId, ghost.length - 1), recording.routes)
        assertIs<GroupMismatch.SentByNobody>(GroupReplaySession(machines, haunted).verify().single())

        // An input from a source the group had not attached: the run missed a source.
        val pickerRecording = recording.members.getValue(pickerId) as Recording<PickerCtx, PickerAct, GroupFixture.Fetch, GroupFixture.PickerEv>
        val fed = pickerRecording.appending(MachineInput.External(SourceId("socket:ghost"), PickerAct.Ack(99), MachineTime(1000.milliseconds)))
        val unattached = GroupRecording(recording.members + (pickerId to fed), recording.order + GroupStep(pickerId, fed.length - 1), recording.routes)
        val unknown = assertIs<GroupMismatch.UnknownSource>(GroupReplaySession(machines, unattached).verify().single())
        assertEquals(SourceId("socket:ghost"), unknown.source)
        val attached = GroupRecording(unattached.members, unattached.order, unattached.routes, sourceIds = setOf(SourceId("socket:ghost")))
        assertEquals(emptyList(), GroupReplaySession(machines, attached).verify(), "attached, the source's input is the run's own")

        // A member decided differently: its own mismatch, at the group's position.
        val altered = GroupReplaySession(mapOf(pickerId to GroupFixture.picker, rootId to koma.statechart.machine.Machine<RootCtx, RootAct, Nothing, RootEv>(GroupFixture.rootMachine.id, GroupFixture.rootMachine.version, GroupFixture.rootMachine.chart) {
            effect("remember") { c, _ -> c.copy(names = c.names + "other") }
        }), recording).verify()
        val replay = assertIs<GroupMismatch.Replay>(altered.first())
        assertEquals(rootId, replay.store)
        live.close()
    }

    @Test
    fun aGroupRecordingSinceACut_replaysFromIt_withItsInFlightMessagesExpected() = runTest {
        val live = GroupFixture.Live(this)
        runCurrent()
        live.pickerStore.dispatch(PickerAct.Pick("tom"))
        runCurrent()
        // The cut is requested while a pick is being processed: the barrier freezes the members,
        // the picker finishes and its effect is delivered into the root's frozen queue, so the
        // message is in flight at the cut.
        live.pickerStore.dispatch(PickerAct.Pick("ann"))
        val pending = async { live.group.checkpoint() }
        runCurrent()
        val cut = checkNotNull(pending.await())
        assertTrue(cut.inFlight.isNotEmpty(), "a message was on the way at the cut: ${cut.held}")
        runCurrent()
        live.pickerStore.dispatch(PickerAct.Pick("bob"))
        advanceTimeBy(60.milliseconds)
        runCurrent()
        val whole = live.recorder.recording()

        val since = whole.since(cut)

        assertTrue(since.length in 1 until whole.length)
        assertEquals(cut.inFlight.map { it.id }, since.inFlight)
        val session = GroupReplaySession(machines, since)
        assertEquals(emptyList(), session.verify(), "the deliveries after the cut are of messages sent before it or after it")
        for ((id, member) in cut.members) assertEquals(member.snapshot, session.snapshotOf(id), "$id starts at its cut")
        session.seek(since.length)
        assertEquals(live.rootStore.currentState, session.snapshotOf(rootId))
        // Without the in-flight messages, the same deliveries would look unsent.
        val forgetful = GroupRecording(since.members, since.order, since.routes)
        assertTrue(GroupReplaySession(machines, forgetful).verify().any { it is GroupMismatch.ReceivedBeforeSent } || cut.inFlight.isEmpty())
        live.close()
    }

    private fun <C, A : koma.core.Action, CMD, E : koma.core.Event> Recording<C, A, CMD, E>.appending(input: MachineInput<A>): Recording<C, A, CMD, E> {
        val machine = machines.getValue(if (definition == GroupFixture.rootMachine.id) rootId else pickerId) as koma.statechart.machine.Machine<C, A, CMD, E>
        val decision = machine.decide(snapshotAt(length), input)
        return Recording(definition, version, start, steps + RecordedStep.Committed(input, decision))
    }
}

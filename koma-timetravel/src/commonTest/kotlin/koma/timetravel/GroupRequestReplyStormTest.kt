@file:OptIn(ExperimentalKomaApi::class)

package koma.timetravel

import koma.core.ExceptionHandler
import koma.core.ExperimentalKomaApi
import koma.observability.JournalEntry
import koma.observability.MachineGroupId
import koma.observability.RecordingSession
import koma.observability.RuntimeSessionId
import koma.statechart.machine.CommandHandler
import koma.statechart.machine.GroupCheckpoint
import koma.statechart.machine.MachineGroup
import koma.statechart.machine.MachineStore
import koma.timetravel.GroupFixture.Fetch
import koma.timetravel.GroupFixture.PickerAct
import koma.timetravel.GroupFixture.PickerCtx
import koma.timetravel.GroupFixture.PickerEv
import koma.timetravel.GroupFixture.RootAct
import koma.timetravel.GroupFixture.RootCtx
import koma.timetravel.GroupFixture.RootEv
import koma.timetravel.GroupFixture.machines
import koma.timetravel.GroupFixture.pickerId
import koma.timetravel.GroupFixture.rootId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The cut storm over a request/reply pair: four threads pick, the root replies to each pick
 * in the step that received it, thirty cuts are taken. The whole run and the run since every
 * cut replay without a mismatch, so no reply is to nothing, and in the journal every reply
 * names the pick it replies to and no request names anything.
 */
class GroupRequestReplyStormTest {

    @Test
    fun everyReplyOfAStorm_followsItsRequest_inTheJournalAndInEveryCutsReplay() = runTest {
        val executionScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val session = RecordingSession(backgroundScope, id = RuntimeSessionId("rr"), group = MachineGroupId("picker"))
        val group = MachineGroup(session)
        val recorder = GroupRecorder(group)
        val pickerMember = group.member<PickerCtx, PickerAct, Fetch, PickerEv>(pickerId)
        val rootMember = group.member<RootCtx, RootAct, Nothing, RootEv>(rootId)
        val pickerStore = MachineStore(
            GroupFixture.picker, PickerCtx(), CommandHandler<Fetch, PickerAct> { command, results ->
                delay(Random.nextLong(0, 3).milliseconds)
                results.result(PickerAct.Fetched(command.command.name))
            },
            executionScope, coroutineContext = Dispatchers.Default, observers = listOf(pickerMember, recorder.member(pickerId, GroupFixture.picker, PickerCtx())),
        ) { exceptionHandler(ExceptionHandler.Ignore) }
        val rootStore = MachineStore(
            GroupFixture.rootMachine, RootCtx(), CommandHandler<Nothing, RootAct> { _, _ -> }, executionScope, coroutineContext = Dispatchers.Default,
            observers = listOf(rootMember, recorder.member(rootId, GroupFixture.rootMachine, RootCtx())),
        ) { exceptionHandler(ExceptionHandler.Ignore) }
        with(GroupFixture) { group.routeAsPair(pickerMember, rootMember) }
        pickerMember.attach(pickerStore)
        rootMember.attach(rootStore)
        pickerStore.start()
        rootStore.start()
        val senders = 4
        val perSender = 60
        val cuts = mutableListOf<GroupCheckpoint>()
        var failedCuts = 0

        withContext(Dispatchers.Default) {
            coroutineScope {
                repeat(senders) { sender ->
                    launch {
                        repeat(perSender) { index ->
                            pickerStore.dispatch(PickerAct.Pick("$sender-$index"))
                            if (index % 5 == 0) delay(1.milliseconds)
                        }
                    }
                }
                launch {
                    repeat(30) {
                        val cut = group.checkpoint(5.seconds)
                        if (cut == null) failedCuts++ else cuts += cut
                        delay(Random.nextLong(0, 3).milliseconds)
                    }
                }
            }
            withTimeout(30_000) {
                pickerStore.state.first { it.context.acks.size == senders * perSender }
            }
        }
        val recording = recorder.recording()
        pickerStore.close()
        rootStore.close()
        executionScope.cancel()

        assertEquals(0, failedCuts, "no cut timed out")
        assertEquals(30, cuts.size)
        assertEquals(emptyList(), GroupReplaySession(machines, recording).verify(), "the whole run replays, every reply after a request of the pair")
        for ((index, cut) in cuts.withIndex()) {
            assertEquals(emptyList(), GroupReplaySession(machines, recording.since(cut)).verify(), "cut $index with ${cut.inFlight.size} in flight")
        }
        val sent = session.records().map { it.entry }.filterIsInstance<JournalEntry.BridgeSent>()
        val requests = sent.filter { it.to == rootId }
        val replies = sent.filter { it.to == pickerId }
        assertEquals(senders * perSender, requests.size)
        assertEquals(senders * perSender, replies.size)
        assertTrue(requests.all { it.cause == null }, "a request replies to nothing")
        assertTrue(replies.all { it.cause?.from == pickerId }, "every reply names the pick it was decided from")
        assertEquals(requests.map { it.message }.toSet(), replies.mapNotNull { it.cause }.toSet(), "every pick was replied to, once")
    }
}

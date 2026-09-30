@file:OptIn(ExperimentalKomaApi::class)

package koma.timetravel

import koma.core.ExceptionHandler
import koma.core.ExperimentalKomaApi
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
 * Group cuts taken while two members exchange messages under a storm from several threads on
 * [Dispatchers.Default]: each cut must be consistent, which the recording proves: the group's
 * run since the cut replays without a mismatch, so no delivery after the cut is of a message
 * that was neither in flight at the cut nor sent after it. Live goes on: every pick reaches
 * the root and every acknowledgement the picker.
 */
class GroupCheckpointStormTest {

    @Test
    fun everyCutOfAStorm_isConsistent_andTheGroupKeepsRunning() = runTest {
        val executionScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val group = MachineGroup()
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
        with(GroupFixture) { group.routeBoth() }
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
                // Every pick is acknowledged; fetches are cancelled by the next pick's re-entry, so their count is not a criterion.
                pickerStore.state.first { it.context.acks.size == senders * perSender }
            }
        }
        val recording = recorder.recording()
        pickerStore.close()
        rootStore.close()
        executionScope.cancel()

        assertEquals(senders * perSender, rootStore.currentState.context.names.size, "every pick reached the root")
        assertEquals(0, failedCuts, "no cut timed out")
        assertTrue(cuts.size == 30)
        assertTrue(recorder.problems.isEmpty(), recorder.problems.toString())
        assertEquals(emptyList(), GroupReplaySession(machines, recording).verify(), "the whole run replays")
        for ((index, cut) in cuts.withIndex()) {
            val since = recording.since(cut)
            assertEquals(emptyList(), GroupReplaySession(machines, since).verify(), "cut $index at ${cut.members.mapValues { it.value.snapshot.revision }} with ${cut.inFlight.size} in flight")
        }
        println("cuts with messages in flight: ${cuts.count { it.inFlight.isNotEmpty() }}, with held inputs: ${cuts.count { c -> c.held.values.any { it > 0 } }} of ${cuts.size}")
    }
}

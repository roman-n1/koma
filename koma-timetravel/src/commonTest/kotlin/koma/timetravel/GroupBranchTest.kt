@file:OptIn(ExperimentalKomaApi::class, ExperimentalCoroutinesApi::class)

package koma.timetravel

import koma.core.ExperimentalKomaApi
import koma.statechart.machine.MessageId
import koma.timetravel.GroupFixture.PickerAct
import koma.timetravel.GroupFixture.PickerCtx
import koma.timetravel.GroupFixture.RootCtx
import koma.timetravel.GroupFixture.machines
import koma.timetravel.GroupFixture.pickerId
import koma.timetravel.GroupFixture.rootId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A branch of the whole group: a new input on one member reaches the other through the local
 * bridge, and its answer comes back, while the live group is untouched and no live bridge runs.
 */
class GroupBranchTest {

    @Test
    fun aBranchOfTheGroup_deliversRoutedEffectsThroughALocalBridge_andLeavesTheLiveGroupAlone() = runTest {
        val live = GroupFixture.Live(this)
        runCurrent()
        live.pickerStore.dispatch(PickerAct.Pick("tom"))
        runCurrent()
        val recording = live.recorder.recording()
        val liveRoot = live.rootStore.currentState
        val livePicker = live.pickerStore.currentState
        val session = GroupReplaySession(machines, recording)
        session.seek(recording.length)

        val branch = session.branch(GroupFixture.branchRoutes)
        val caused = branch.dispatch(pickerId, PickerAct.Pick("zed"))

        assertEquals(listOf("tom", "zed"), (branch.snapshot(rootId).context as RootCtx).names, "the pick reached the root through the local bridge")
        assertEquals(listOf(1, 2), (branch.snapshot(pickerId).context as PickerCtx).acks, "and the root's acknowledgement came back")
        assertEquals(3, caused.size, "the pick, the apply, the acknowledgement")
        assertEquals(listOf(rootId, pickerId), branch.delivered.map { it.to })
        assertTrue(branch.delivered.all { it.id == MessageId(it.id.from, it.id.effect) && it.id.from != it.to })
        assertEquals(liveRoot, live.rootStore.currentState, "the live root did not move")
        assertEquals(livePicker, live.pickerStore.currentState, "the live picker did not move")
        assertTrue(live.group.inFlight.isEmpty(), "no live bridge was used")
        assertTrue(branch.advance(1.seconds).isEmpty(), "no timers in these machines")
        assertEquals(listOf(pickerId, rootId), branch.members.keys.toList())
        live.close()
    }
}

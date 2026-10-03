@file:OptIn(ExperimentalKomaApi::class)

package koma.timetravel.compose

import koma.core.ExperimentalKomaApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

class GroupTimeTravelControlsTest {
    @Test
    fun anExperimentStartsAtTheSelectedPrefix_andDoesNotChangeRecordedHistory() {
        val replay = GroupReplayFixture.controls()
        replay.seek(4)
        replay.verify()
        val checkpoints = replay.members.associateWith { replay.checkpoint(it) }
        val recording = replay.session.recording
        val input = BranchInput.Dispatch(GroupReplayFixture.b, "Inc", GroupReplayFixture.Inc)
        val controls = GroupTimeTravelControls(replay, inputs = listOf(input))
        assertEquals(InspectorMode.Replay, controls.mode)

        controls.branchHere()
        val branch = checkNotNull(controls.branch)
        assertEquals(InspectorMode.Branch, controls.mode)
        assertEquals(4, controls.branchPosition)
        for (store in replay.members) {
            assertEquals(checkpoints.getValue(store).snapshot, branch.snapshot(store))
            assertEquals(checkpoints.getValue(store).now, branch.now(store))
        }
        branch.dispatch(input)
        assertEquals(GroupReplayFixture.Ctx(12), branch.snapshot(GroupReplayFixture.b).context)
        assertEquals(GroupReplayFixture.Ctx(1), branch.snapshot(GroupReplayFixture.a).context)
        controls.branchHere()
        assertSame(branch, controls.branch, "starting again must not silently erase an active experiment")
        assertEquals(checkpoints, replay.members.associateWith { replay.checkpoint(it) })
        assertEquals(4, replay.position)
        assertEquals(emptyList(), replay.verification)
        assertSame(recording, replay.session.recording)

        controls.returnToReplay()
        assertNull(controls.branch)
        assertNull(controls.branchPosition)
        assertEquals(InspectorMode.Replay, controls.mode)
        replay.stepForward()
        assertEquals(5, replay.position)
        controls.branchHere()
        val next = checkNotNull(controls.branch)
        assertNotSame(branch, next)
        assertEquals(5, controls.branchPosition)
        assertEquals(GroupReplayFixture.Ctx(2), next.snapshot(GroupReplayFixture.a).context)
        assertEquals(GroupReplayFixture.Ctx(11), next.snapshot(GroupReplayFixture.b).context)
        assertEquals(emptyList(), next.decisions)
    }

    @Test
    fun branchingAtADivergence_preservesTheFailureWhenReturning() {
        val replay = GroupReplayFixture.controls(changed = true)
        repeat(4) { replay.stepForward() }
        val failure = checkNotNull(replay.divergence)
        val controls = GroupTimeTravelControls(replay)
        controls.branchHere()
        assertEquals(3, controls.branchPosition)
        controls.returnToReplay()
        assertEquals(3, replay.position)
        assertSame(failure, replay.divergence)
    }
}

package koma.timetravel.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import koma.core.ExperimentalKomaApi
import koma.timetravel.GroupBranch

/**
 * Moves between group replay and an isolated experiment from its current checkpoint.
 * [routes] and [inputs] are application-supplied scripts; no live handlers are invoked.
 * Without routes, branch effects are not delivered to other members.
 * While experimenting, the inspector pauses replay navigation. Returning discards the
 * experiment and leaves the replay cursor, recording and verification results unchanged.
 * Drive [replay] through this inspector rather than moving its session externally.
 */
@ExperimentalKomaApi
@Stable
class GroupTimeTravelControls(
    val replay: GroupReplayControls,
    private val routes: List<GroupBranch.Route> = emptyList(),
    private val inputs: List<BranchInput> = emptyList(),
) {
    var branch: BranchControls? by mutableStateOf(null)
        private set

    /** Global prefix from which the current experiment began, or null in replay mode. */
    var branchPosition: Int? by mutableStateOf(null)
        private set

    val mode: InspectorMode get() = if (branch == null) InspectorMode.Replay else InspectorMode.Branch

    /** Starts once from every member's current checkpoint, including its executor state. */
    fun branchHere() {
        if (branch != null) return
        val experiment = replay.session.branch(routes)
        val controls = BranchControls(experiment, replay.session.members.mapValues { it.value.machine }, inputs)
        branchPosition = replay.position
        branch = controls
    }

    /** Discards the experiment; a new [branchHere] starts fresh from the replay cursor. */
    fun returnToReplay() {
        branch = null
        branchPosition = null
    }
}

/** Makes the experiment's origin explicit and gives it a route back to recorded history. */
@ExperimentalKomaApi
@Composable
fun GroupTimeTravelBar(controls: GroupTimeTravelControls, modifier: Modifier = Modifier) {
    Row(modifier = modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (controls.branch == null) {
            OutlinedButton(onClick = controls::branchHere, modifier = Modifier.testTag("time-travel-branch")) { Text("branch here") }
        } else {
            OutlinedButton(onClick = controls::returnToReplay, modifier = Modifier.testTag("time-travel-return")) { Text("return to replay") }
            Text("experiment from group position ${controls.branchPosition}; replay paused", modifier = Modifier.testTag("time-travel-origin"))
        }
    }
}

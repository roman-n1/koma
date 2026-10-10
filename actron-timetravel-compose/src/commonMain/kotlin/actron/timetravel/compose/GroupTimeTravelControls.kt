package actron.timetravel.compose

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
import actron.core.ExperimentalActronApi
import actron.timetravel.GroupBranch

/**
 * Moves between group replay and an isolated experiment from its current checkpoint.
 * [routes] and [inputs] are application-supplied scripts; no live handlers are invoked.
 * Without routes, branch effects are not delivered to other members.
 * While experimenting, the inspector pauses replay navigation. Returning discards the
 * experiment and leaves the replay cursor, recording and verification results unchanged.
 * Drive [replay] through this inspector rather than moving its session externally.
 */
@ExperimentalActronApi
@Stable
class GroupTimeTravelControls(
    val replay: GroupReplayControls,
    private val routes: List<GroupBranch.Route> = emptyList(),
    private val inputs: List<BranchInput> = emptyList(),
) {
    var travel: GroupTravel by mutableStateOf(GroupTravel.Replaying)
        private set
    val mode: InspectorMode get() = if (travel is GroupTravel.Replaying) InspectorMode.Replay else InspectorMode.Branch

    /** Starts once from every member's current checkpoint, including its executor state. */
    fun branchHere() {
        if (travel is GroupTravel.Experiment) return
        val experiment = replay.session.branch(routes)
        val controls = BranchControls(experiment, replay.session.members.mapValues { it.value.machine }, inputs)
        travel = GroupTravel.Experiment(replay.position, controls)
    }

    /** Discards the experiment; a new [branchHere] starts fresh from the replay cursor. */
    fun returnToReplay() {
        travel = GroupTravel.Replaying
    }
}

@ExperimentalActronApi
sealed interface GroupTravel {
    data object Replaying : GroupTravel
    data class Experiment(val position: Int, val branch: BranchControls) : GroupTravel
}

/** Makes the experiment's origin explicit and gives it a route back to recorded history. */
@ExperimentalActronApi
@Composable
fun GroupTimeTravelBar(controls: GroupTimeTravelControls, modifier: Modifier = Modifier) {
    Row(modifier = modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val travel = controls.travel
        if (travel is GroupTravel.Replaying) {
            OutlinedButton(onClick = controls::branchHere, modifier = Modifier.testTag("time-travel-branch")) { Text("branch here") }
        } else if (travel is GroupTravel.Experiment) {
            OutlinedButton(onClick = controls::returnToReplay, modifier = Modifier.testTag("time-travel-return")) { Text("return to replay") }
            Text("experiment from group position ${travel.position}; replay paused", modifier = Modifier.testTag("time-travel-origin"))
        }
    }
}

package actron.timetravel.compose

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import actron.core.ExperimentalActronApi
import actron.observability.StoreInstanceId
import actron.statechart.machine.ExecutorCheckpoint
import actron.statechart.machine.MachineSnapshot
import actron.statechart.toMermaid
import actron.timetravel.GroupMismatch
import actron.timetravel.GroupReplaySession
import actron.timetravel.GroupReplayStep
import actron.timetravel.RecordedStep
import actron.timetravel.inspect.SnapshotDiff

/** One recorded input in the group's order, with the state change it produced. */
@ExperimentalActronApi
data class GroupReplayPosition(
    val index: Int,
    val store: StoreInstanceId,
    val memberStep: Int,
    val recorded: RecordedStep<*, *, *, *>,
    val before: MachineSnapshot<*>,
    val after: MachineSnapshot<*>,
    val diff: SnapshotDiff,
)

/**
 * A single Compose cursor for every member of a recorded group. Seeking/backward movement
 * shows recorded checkpoints; forward movement re-decides an input and checks its bridge
 * causality. No Store, command handler or external source is run.
 *
 * The recording is the timeline: its positions remain meaningful when a diagnostic journal
 * is partial or filtered. A filter never changes the order in which the group is replayed.
 */
@ExperimentalActronApi
@Stable
class GroupReplayControls(val session: GroupReplaySession) {
    var position: Int by mutableStateOf(session.position)
        private set

    var movement: GroupReplayMovement by mutableStateOf(GroupReplayMovement.Open)
        private set

    /** Whether verification ran, with every problem it found. Does not move. */
    var verification: GroupVerification by mutableStateOf(GroupVerification.Unchecked)
        private set

    val length: Int get() = session.length
    val members: List<StoreInstanceId> = session.members.keys.sortedBy { it.value }
    val canStepBackward: Boolean get() = position > 0
    val canStepForward: Boolean get() = position < length && movement is GroupReplayMovement.Open

    val forwardAvailability: actron.timetravel.inspect.Availability
        get() {
            val current = movement
            if (current is GroupReplayMovement.Diverged) return actron.timetravel.inspect.Availability.Unavailable(listOf(current.mismatch.explain()))
            if (position == length) return actron.timetravel.inspect.Availability.Unavailable(listOf("at the end of the group recording"))
            return actron.timetravel.inspect.Availability.Available
        }

    val hasSelection: Boolean get() = movement is GroupReplayMovement.Diverged || position > 0
    fun selectedPosition(): GroupReplayPosition {
        val current = movement
        val index = if (current is GroupReplayMovement.Diverged) current.mismatch.position else position - 1
        check(index in 0 until length) { "[Actron] Before the first recorded group input" }
        return at(index)
    }

    fun at(index: Int): GroupReplayPosition {
        val step = session.recording.order[index]
        val member = session.recording.members.getValue(step.store)
        val before = member.snapshotAt(step.step)
        val after = member.snapshotAt(step.step + 1)
        return GroupReplayPosition(index, step.store, step.step, member.steps[step.step], before, after, SnapshotDiff.between(before, after))
    }

    fun snapshot(store: StoreInstanceId): MachineSnapshot<*> {
        position // Subscribe Compose readers to movement of the whole group.
        return session.snapshotOf(store)
    }

    fun checkpoint(store: StoreInstanceId): ExecutorCheckpoint<*, *> {
        position
        return session.checkpointOf(store)
    }

    fun mermaid(store: StoreInstanceId): String = session.members.getValue(store).machine.chart.toMermaid(snapshot(store).configuration.active)

    fun stepForward() {
        if (!canStepForward) return
        when (val step = session.stepForward()) {
            is GroupReplayStep.Finished -> Unit
            is GroupReplayStep.Matched -> movement = GroupReplayMovement.Open
            is GroupReplayStep.Diverged -> movement = GroupReplayMovement.Diverged(step.mismatch)
        }
        position = session.position
    }

    fun stepBackward() {
        session.stepBackward()
        movement = GroupReplayMovement.Open
        position = session.position
    }

    fun seek(target: Int) {
        session.seek(target.coerceIn(0, length))
        movement = GroupReplayMovement.Open
        position = session.position
    }

    fun verify() {
        verification = GroupVerification.Checked(session.verify())
    }
}

@ExperimentalActronApi
sealed interface GroupReplayMovement {
    data object Open : GroupReplayMovement
    data class Diverged(val mismatch: GroupMismatch) : GroupReplayMovement
}
@ExperimentalActronApi
sealed interface GroupVerification {
    data object Unchecked : GroupVerification
    data class Checked(val problems: List<GroupMismatch>) : GroupVerification
}

@OptIn(ExperimentalActronApi::class)
internal fun GroupMismatch.explain(): String = "group step $position: " + when (this) {
    is GroupMismatch.Replay -> "${store.value}: ${mismatch.describe()}"
    is GroupMismatch.ReceivedBeforeSent -> "${store.value} received $message before it was sent"
    is GroupMismatch.SentByNobody -> "${store.value} received $message from an unknown member"
    is GroupMismatch.DeliveredTwice -> "${store.value} received $message twice (first at group step $first)"
    is GroupMismatch.NoRoute -> "no route from ${message.from.value} to ${store.value} for $message"
    is GroupMismatch.UnknownSource -> "${store.value} received input from unrecorded source ${source.value}"
    is GroupMismatch.ReplyWithoutRequest -> "${store.value} received a reply without a request in pair $pair: $message"
}

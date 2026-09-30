package koma.timetravel

import koma.core.Action
import koma.core.Event
import koma.core.ExperimentalKomaApi
import koma.observability.StoreInstanceId
import koma.statechart.machine.Decision
import koma.statechart.machine.ExecutorCheckpoint
import koma.statechart.machine.Machine
import koma.statechart.machine.MachineInput
import koma.statechart.machine.MachineSnapshot
import koma.statechart.machine.MessageId

/**
 * Where a group replay differs from its recording: a member's own divergence, or a bridge
 * delivery the recording cannot have produced (handoff §10: delivered once, to the right
 * instance, after it was sent).
 */
@ExperimentalKomaApi
sealed interface GroupMismatch {
    /** The position of the group's order the mismatch is at. */
    val position: Int

    /** A member decided differently from its recording. */
    data class Replay(override val position: Int, val store: StoreInstanceId, val mismatch: ReplayMismatch<*, *, *, *>) : GroupMismatch

    /** [store] received [message] before the sender's step that emitted it, or the sender never emitted it. */
    data class ReceivedBeforeSent(override val position: Int, val store: StoreInstanceId, val message: MessageId) : GroupMismatch

    /** [message] names a sender that is not a member of the group. */
    data class SentByNobody(override val position: Int, val store: StoreInstanceId, val message: MessageId) : GroupMismatch

    /** [store] received [message] a second time; the first delivery was at [first]. */
    data class DeliveredTwice(override val position: Int, val store: StoreInstanceId, val message: MessageId, val first: Int) : GroupMismatch

    /** [store] received [message] although the bridge has no route from its sender to [store]: instances mixed. */
    data class NoRoute(override val position: Int, val store: StoreInstanceId, val message: MessageId) : GroupMismatch
}

/** The result of one forward step of a [GroupReplaySession]. */
@ExperimentalKomaApi
sealed interface GroupReplayStep {
    data class Matched(val position: Int, val store: StoreInstanceId, val decision: Decision<*, *, *>) : GroupReplayStep

    data class Diverged(val mismatch: GroupMismatch) : GroupReplayStep
}

/**
 * Replays a [GroupRecording]: a cursor over the group's order that steps each member's
 * [ReplaySession] in turn, so at every position each member is at the snapshot it had when the
 * group was there. [verify] decides every member's steps again and checks the bridge
 * deliveries against the sends. Nothing runs, no bridge delivers: the recorded inputs already
 * hold every delivery, and a replay only checks that they could have happened.
 *
 * @param machines The machine of each member; every member of the recording needs one
 * @throws IllegalArgumentException if a member has no machine or its recording is not replayable by it
 */
@ExperimentalKomaApi
class GroupReplaySession(
    machines: Map<StoreInstanceId, Machine<*, *, *, *>>,
    val recording: GroupRecording,
) {
    private val sessions: Map<StoreInstanceId, ReplaySession<*, *, *, *>> = recording.members.mapValues { (id, memberRecording) ->
        val machine = requireNotNull(machines[id]) { "[Koma] No machine for member $id" }
        open(machine, memberRecording)
    }

    /** The number of steps of the group. */
    val length: Int get() = recording.length

    /** The current position, 0 to [length]. */
    var position: Int = 0
        private set

    /** The step at [position], or `null` at the end. */
    val next: GroupStep? get() = recording.order.getOrNull(position)

    /** The members' sessions, each at its own position for the group's. */
    val members: Map<StoreInstanceId, ReplaySession<*, *, *, *>> get() = sessions

    /** The snapshot of [store] at the group's position. */
    fun snapshotOf(store: StoreInstanceId): MachineSnapshot<*> = session(store).snapshot

    /** The executor's state of [store] at the group's position. */
    fun checkpointOf(store: StoreInstanceId): ExecutorCheckpoint<*, *> = session(store).checkpoint

    /**
     * Decides the next step of the group on its member and compares with the recording; advances
     * on a match, stays on a divergence. `null` at the end.
     */
    fun stepForward(): GroupReplayStep? {
        val step = next ?: return null
        return when (val result = session(step.store).stepForward()) {
            null -> null
            is ReplayStep.Matched<*, *, *, *> -> {
                position++
                GroupReplayStep.Matched(position - 1, step.store, result.decision)
            }
            is ReplayStep.Diverged<*, *, *, *> -> GroupReplayStep.Diverged(GroupMismatch.Replay(position, step.store, result.mismatch))
        }
    }

    /** Moves one step back; `false` at the start. */
    fun stepBackward(): Boolean {
        if (position == 0) return false
        seek(position - 1)
        return true
    }

    /**
     * Moves the group to [position]: each member to the number of its steps before it.
     *
     * @throws IllegalArgumentException if [position] is outside 0..[length]
     */
    fun seek(position: Int) {
        require(position in 0..length) { "[Koma] Position $position is outside 0..$length" }
        for ((id, session) in sessions) session.seek(recording.stepsBefore(position, id))
        this.position = position
    }

    /**
     * Every mismatch of the group, in order of position: each member's first divergence, and
     * every bridge delivery that was received before it was sent, sent by nobody, delivered
     * twice, or delivered where no route leads. Does not move.
     */
    fun verify(): List<GroupMismatch> {
        val mismatches = mutableListOf<GroupMismatch>()
        for ((id, session) in sessions) {
            session.verify()?.let { mismatch -> mismatches += GroupMismatch.Replay(positionOf(id, mismatch.position), id, mismatch) }
        }
        val sent = recording.inFlight.toMutableSet()
        val received = mutableMapOf<Pair<StoreInstanceId, MessageId>, Int>()
        for ((index, step) in recording.order.withIndex()) {
            val recorded = recording.members.getValue(step.store).steps[step.step]
            val input = recorded.input
            if (input is MachineInput.BridgeReceived) {
                val message = input.message
                val key = step.store to message
                when {
                    key in received -> mismatches += GroupMismatch.DeliveredTwice(index, step.store, message, received.getValue(key))
                    message.from !in recording.members -> mismatches += GroupMismatch.SentByNobody(index, step.store, message)
                    message !in sent -> mismatches += GroupMismatch.ReceivedBeforeSent(index, step.store, message)
                    recording.routes.none { it.from == message.from && it.to == step.store } -> mismatches += GroupMismatch.NoRoute(index, step.store, message)
                }
                received[key] = index
            }
            if (recorded is RecordedStep.Committed<*, *, *, *>) for (effect in recorded.decision.effects) sent += MessageId(step.store, effect.id)
        }
        return mismatches.sortedBy { it.position }
    }

    /**
     * A branch of the whole group from the current position: every member's [Branch] from its
     * checkpoint here, joined by a local bridge over [routes].
     */
    fun branch(routes: List<GroupBranch.Route>): GroupBranch =
        GroupBranch(sessions.mapValues { (_, session) -> session.branch() }, routes)

    private fun session(store: StoreInstanceId): ReplaySession<*, *, *, *> = requireNotNull(sessions[store]) { "[Koma] $store is not a member" }

    private fun positionOf(store: StoreInstanceId, step: Int): Int = recording.order.indexOfFirst { it.store == store && it.step == step }.let { if (it < 0) length else it }

    @Suppress("UNCHECKED_CAST")
    private fun open(machine: Machine<*, *, *, *>, recording: Recording<*, *, *, *>): ReplaySession<*, *, *, *> =
        ReplaySession(machine as Machine<Any?, Action, Any?, Event>, recording as Recording<Any?, Action, Any?, Event>)
}

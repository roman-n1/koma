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
import koma.statechart.machine.PairRole
import koma.statechart.machine.SourceId

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

    /** [store] decided an input of the source [source], which the group had not attached: a source the run missed. */
    data class UnknownSource(override val position: Int, val store: StoreInstanceId, val source: SourceId) : GroupMismatch

    /**
     * [store] received [message] over the reply route of the pair [pair], but its sender had
     * received no request of the pair from [store] when it emitted it: a reply to nothing. A
     * run since a cut cannot see what came before it: a member that starts at a checkpoint
     * counts as having heard from everyone, and a message in flight at the start is not checked.
     */
    data class ReplyWithoutRequest(override val position: Int, val store: StoreInstanceId, val message: MessageId, val pair: String) : GroupMismatch
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

    // The recording's bridge/source relationships do not depend on the replay cursor.
    private val bridgeMismatches by lazy { verifyBridge() }

    /** The snapshot of [store] at the group's position. */
    fun snapshotOf(store: StoreInstanceId): MachineSnapshot<*> = session(store).snapshot

    /** The executor's state of [store] at the group's position. */
    fun checkpointOf(store: StoreInstanceId): ExecutorCheckpoint<*, *> = session(store).checkpoint

    /**
     * Checks the next input's bridge/source causality, decides it on its member and compares
     * with the recording. Advances on a match; a divergence leaves every member and the group
     * cursor unchanged. `null` at the end.
     */
    fun stepForward(): GroupReplayStep? {
        val step = next ?: return null
        bridgeMismatches.firstOrNull { it.position == position }?.let { return GroupReplayStep.Diverged(it) }
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
     * twice, delivered where no route leads, or a reply whose sender had received no request of
     * the pair from the receiver. Does not move.
     */
    fun verify(): List<GroupMismatch> {
        val mismatches = mutableListOf<GroupMismatch>()
        for ((id, session) in sessions) {
            session.verify()?.let { mismatch -> mismatches += GroupMismatch.Replay(positionOf(id, mismatch.position), id, mismatch) }
        }
        mismatches += bridgeMismatches
        return mismatches.sortedBy { it.position }
    }

    private fun verifyBridge(): List<GroupMismatch> {
        val mismatches = mutableListOf<GroupMismatch>()
        val sent = recording.inFlight.toMutableSet()
        val received = mutableMapOf<Pair<StoreInstanceId, MessageId>, Int>()
        // The requesters each member has received a request from so far, and, per message, the
        // requesters its sender had heard from when it emitted the message. A member whose
        // recording begins at a checkpoint (a run since a cut) may have heard from anyone before
        // it: it counts as having heard from every member, and a message in flight at the start
        // was emitted before it and is not checked.
        val requestersHeard = mutableMapOf<StoreInstanceId, MutableSet<StoreInstanceId>>()
        for ((id, member) in recording.members) if (member.start.snapshot.revision > 0) requestersHeard[id] = (recording.members.keys - id).toMutableSet()
        val heardAtEmit = mutableMapOf<MessageId, Set<StoreInstanceId>>()
        for ((index, step) in recording.order.withIndex()) {
            val recorded = recording.members.getValue(step.store).steps[step.step]
            val input = recorded.input
            if (input is MachineInput.BridgeReceived) {
                val message = input.message
                val key = step.store to message
                val routes = recording.routes.filter { it.from == message.from && it.to == step.store }
                when {
                    key in received -> mismatches += GroupMismatch.DeliveredTwice(index, step.store, message, received.getValue(key))
                    message.from !in recording.members -> mismatches += GroupMismatch.SentByNobody(index, step.store, message)
                    message !in sent -> mismatches += GroupMismatch.ReceivedBeforeSent(index, step.store, message)
                    routes.isEmpty() -> mismatches += GroupMismatch.NoRoute(index, step.store, message)
                    else -> {
                        val reply = routes.firstNotNullOfOrNull { route -> route.pair?.takeIf { it.role == PairRole.Reply } }
                        if (reply != null && message !in recording.inFlight && step.store !in heardAtEmit[message].orEmpty()) mismatches += GroupMismatch.ReplyWithoutRequest(index, step.store, message, reply.name)
                        if (routes.any { it.pair?.role == PairRole.Request }) requestersHeard.getOrPut(step.store) { mutableSetOf() } += message.from
                    }
                }
                received[key] = index
            }
            if (input is MachineInput.External && input.source !in recording.sourceIds) mismatches += GroupMismatch.UnknownSource(index, step.store, input.source)
            if (recorded is RecordedStep.Committed<*, *, *, *>) {
                val heard = requestersHeard[step.store].orEmpty().toSet()
                for (effect in recorded.decision.effects) {
                    val message = MessageId(step.store, effect.id)
                    sent += message
                    heardAtEmit[message] = heard
                }
            }
        }
        return mismatches.sortedBy { it.position }
    }

    /**
     * A branch of the whole group from the current position: every member's [Branch] from its
     * checkpoint here, joined by a local bridge over [routes], with the sources' snapshots as at
     * the recording's start for the caller to script their data from.
     */
    fun branch(routes: List<GroupBranch.Route>): GroupBranch =
        GroupBranch(sessions.mapValues { (_, session) -> session.branch() }, routes, recording.sourceSnapshots)

    private fun session(store: StoreInstanceId): ReplaySession<*, *, *, *> = requireNotNull(sessions[store]) { "[Koma] $store is not a member" }

    private fun positionOf(store: StoreInstanceId, step: Int): Int = recording.order.indexOfFirst { it.store == store && it.step == step }.let { if (it < 0) length else it }

    @Suppress("UNCHECKED_CAST")
    private fun open(machine: Machine<*, *, *, *>, recording: Recording<*, *, *, *>): ReplaySession<*, *, *, *> =
        ReplaySession(machine as Machine<Any?, Action, Any?, Event>, recording as Recording<Any?, Action, Any?, Event>)
}

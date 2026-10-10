package actron.timetravel

import actron.core.InputAttribution

import actron.core.Action
import actron.core.Event
import actron.core.InputId
import actron.observability.FailureDescriptor
import actron.observability.StoreInstanceId
import actron.statechart.machine.Decision
import actron.statechart.machine.DecisionObserver
import actron.statechart.machine.ExecutorCheckpoint
import actron.statechart.machine.GroupCheckpoint
import actron.statechart.machine.IgnoreReason
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineGroup
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MessageId
import actron.statechart.machine.RoutePair
import actron.statechart.machine.RouteProtocol
import actron.statechart.machine.SourceId
import actron.statechart.machine.SourceSnapshot
import kotlinx.coroutines.sync.Mutex

/** One step of a group's run: the [step] of [store]'s recording, at its position in the group's order. */
data class GroupStep(val store: StoreInstanceId, val step: Int)

/**
 * A route of the group's bridge, as a recording remembers it: effects of [from] reached [to] at
 * some time of the run; [pair] is its place in a request/reply pair, when it has one.
 */
data class GroupRoute(val from: StoreInstanceId, val to: StoreInstanceId, val pair: RouteProtocol = RouteProtocol.OneWay)

/**
 * A run of a [MachineGroup]: each member's [Recording] and the order the members' decisions were
 * made in, across the group. A group replay decides the members' inputs again in this order; a
 * group branch continues all of them from one position with a local bridge.
 *
 * @property members The recording of each member
 * @property order Every decision of the group, in the order it was made: the member and the
 * index of its step
 * @property routes Every route the bridge had during the run, the removed ones included, so a
 * replay can tell a delivery that never had a route
 * @property inFlight The messages already sent when the run begins, for a run recorded since a
 * cut: their deliveries are expected, their sends are before the run
 * @property sourceIds The external sources the group had attached: an input from any other
 * source is a source the run missed (handoff §11)
 * @property sourceSnapshots The sources' state where the run begins, for a run recorded since a
 * cut: what a branch scripts the sources' next data from
 */
class GroupRecording(
    val members: Map<StoreInstanceId, Recording<*, *, *, *>>,
    val order: List<GroupStep>,
    val routes: List<GroupRoute> = emptyList(),
    val inFlight: List<MessageId> = emptyList(),
    val sourceIds: Set<SourceId> = emptySet(),
    val sourceSnapshots: Map<SourceId, SourceSnapshot> = emptyMap(),
) {
    init {
        val counts = order.groupingBy { it.store }.eachCount()
        for ((id, recording) in members) {
            require((counts[id] ?: 0) == recording.length) { "[Actron] The order has ${counts[id] ?: 0} steps of $id, its recording ${recording.length}" }
        }
        for ((index, step) in order.withIndex()) {
            require(step.store in members) { "[Actron] Step $index belongs to ${step.store}, which is not a member" }
        }
        for ((id, _) in members) {
            val steps = order.filter { it.store == id }.map { it.step }
            require(steps == steps.indices.toList()) { "[Actron] The steps of $id are not in order: $steps" }
        }
    }

    /** The number of decisions of the group. */
    val length: Int get() = order.size

    /** How many of [store]'s steps come before [position] of the group's order. */
    fun stepsBefore(position: Int, store: StoreInstanceId): Int {
        require(position in 0..length) { "[Actron] Position $position is outside 0..$length" }
        return order.subList(0, position).count { it.store == store }
    }

    /**
     * This run from [checkpoint] on: each member's recording since its checkpoint, and the order
     * without the steps before the cut. A member of the checkpoint that has no recording, or a
     * recording the checkpoint is not of, is refused.
     */
    fun since(checkpoint: GroupCheckpoint): GroupRecording {
        val trimmed = mutableMapOf<StoreInstanceId, Recording<*, *, *, *>>()
        val dropped = mutableMapOf<StoreInstanceId, Int>()
        for ((id, cut) in checkpoint.members) {
            val recording = requireNotNull(members[id]) { "[Actron] The checkpoint has a member $id this recording has not" }
            val since = recording.sinceUnchecked(cut)
            trimmed[id] = since
            dropped[id] = recording.length - since.length
        }
        for ((id, recording) in members) if (id !in trimmed) {
            trimmed[id] = recording
            dropped[id] = 0
        }
        val seen = mutableMapOf<StoreInstanceId, Int>()
        val newOrder = buildList {
            for (step in order) {
                val index = seen[step.store] ?: 0
                seen[step.store] = index + 1
                val skip = dropped.getValue(step.store)
                if (index >= skip) add(GroupStep(step.store, step.step - skip))
            }
        }
        return GroupRecording(trimmed, newOrder, routes, checkpoint.inFlight.map { it.id }, sourceIds + checkpoint.sources.keys, checkpoint.sources)
    }

    override fun toString(): String = "GroupRecording(${members.keys}, ${order.size} steps)"

    @Suppress("UNCHECKED_CAST")
    private fun Recording<*, *, *, *>.sinceUnchecked(cut: ExecutorCheckpoint<*, *>): Recording<*, *, *, *> =
        (this as Recording<Any, Action, Any, Event>).since(cut as ExecutorCheckpoint<Any, Any>)
}

/**
 * Records the run of a [MachineGroup]: one [MachineRecorder] per member, and the order of the
 * decisions across the group, assigned under one lock as they are reported. Give each member's
 * store the observer [member] returns.
 *
 * @param routes The bridge's routes, every one it had, read when [recording] is taken (a
 * group's routes may be registered, or removed, after the recorder is created)
 */
class GroupRecorder(
    private val routes: () -> List<GroupRoute> = { emptyList() },
    private val sources: () -> Set<SourceId> = { emptySet() },
) {
    /** Records the group [group] is; its route history and sources are read when the recording is taken. */
    constructor(group: MachineGroup) : this({ group.routeHistory.map { GroupRoute(it.from, it.to, it.pair) } }, { group.sourceIds.toSet() })

    private val lock = Mutex()
    private val recorders = linkedMapOf<StoreInstanceId, MachineRecorder<*, *, *, *>>()
    private val counts = mutableMapOf<StoreInstanceId, Int>()
    private val order = ArrayList<GroupStep>()

    /**
     * The observer to give the store of [id], built from [machine] and [context].
     *
     * @throws IllegalArgumentException if [id] is already recorded
     */
    fun <C : Any, A : Action, CMD : Any, E : Event> member(id: StoreInstanceId, machine: Machine<C, A, CMD, E>, context: C): DecisionObserver<C, A, CMD, E> {
        val recorder = MachineRecorder(machine, context)
        locked {
            require(id !in recorders) { "[Actron] $id is already recorded by this group recorder" }
            recorders[id] = recorder
        }
        return object : DecisionObserver<C, A, CMD, E> {
            override fun onCommitted(input: InputAttribution, machineInput: MachineInput<A>, decision: Decision<C, CMD, E>) = locked {
                recorder.onCommitted(input, machineInput, decision)
                step(id)
            }

            override fun onIgnored(input: InputAttribution, machineInput: MachineInput<A>, reason: IgnoreReason) = locked {
                recorder.onIgnored(input, machineInput, reason)
                step(id)
            }

            override fun onFailed(input: InputAttribution, machineInput: MachineInput<A>, failure: FailureDescriptor) = locked {
                recorder.onFailed(input, machineInput, failure)
                step(id)
            }
        }
    }

    /** The problems of the members' recorders, by member; empty when every run began at its initial snapshot. */
    val problems: Map<StoreInstanceId, String>
        get() = locked { buildMap { for ((id, recorder) in recorders) recorder.origin.withProblem { put(id, it) } } }

    /** The group's recording so far. */
    fun recording(): GroupRecording = locked {
        GroupRecording(recorders.mapValues { (_, recorder) -> recorder.recording() }, order.toList(), routes(), sourceIds = sources())
    }

    private fun step(id: StoreInstanceId) {
        val index = counts[id] ?: 0
        counts[id] = index + 1
        order += GroupStep(id, index)
    }

    private inline fun <T : Any> locked(block: () -> T): T {
        while (!lock.tryLock()) {
            // Spin: the holder appends one step.
        }
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}

package actron.timetravel

import actron.core.Action
import actron.core.Event
import actron.core.ExperimentalActronApi
import actron.observability.FailureDescriptor
import actron.statechart.StateId
import actron.statechart.machine.ActivationId
import actron.statechart.machine.CommandId
import actron.statechart.machine.CommandRegistration
import actron.statechart.machine.Decision
import actron.statechart.machine.EffectEnvelope
import actron.statechart.machine.ExecutorCheckpoint
import actron.statechart.machine.LaneChange
import actron.statechart.machine.LaneId
import actron.statechart.machine.Lanes
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MachineSnapshot
import actron.statechart.machine.MachineTime
import actron.statechart.machine.MessageId
import actron.statechart.machine.SourceId
import kotlin.time.Duration

/**
 * A run that continues from a checkpoint with inputs of the caller's choosing, decided by the
 * pure machine on a virtual clock. Nothing runs: the commands a live executor would be running
 * are [awaiting] until the caller answers them ([answer], [complete], [fail]) or reuses what the
 * recording has for an equal command ([reuseRecordedAnswers]); the commands a live executor
 * would have waiting in their lanes are [queued] and start, under the same lane bookkeeping
 * ([Lanes]), when the running ones end; a timer fires when the caller [advance]s the clock past
 * its deadline; events accumulate in [effects] and are never delivered anywhere. The live store
 * the checkpoint or recording came from is untouched.
 *
 * A branch starts from a recorded position (`ReplaySession.branch()`) or from the live
 * executor's own checkpoint (`MachineStore.checkpoint()`), and [checkpoint] is where it is now.
 *
 * @param machine The machine to decide with; the checkpoint must be of its definition and version
 * @param start Where the branch begins
 * @param recordedAnswers Supplies actual ordered command answers; by default all answers come from the caller
 */
@ExperimentalActronApi
class Branch<C : Any, A : Action, CMD : Any, E : Event>(
    private val machine: Machine<C, A, CMD, E>,
    start: ExecutorCheckpoint<C, CMD>,
    private val recordedAnswers: (CommandRegistration<CMD>, StateId, MachineTime) -> List<MachineInput<A>> = { _, _, _ -> emptyList() },
) {
    constructor(machine: Machine<C, A, CMD, E>, start: ExecutorCheckpoint<C, CMD>, recording: Recording<C, A, CMD, E>) :
        this(machine, start, { registration, node, now -> recording.answersFor(registration, node, now) })

    init {
        require(start.snapshot.definition == machine.id && start.snapshot.version == machine.version) {
            "[Actron] The checkpoint is of ${start.snapshot.definition} ${start.snapshot.version}; the machine is ${machine.id} ${machine.version}"
        }
    }

    /** The snapshot of the branch now. */
    var snapshot: MachineSnapshot<C> = start.snapshot
        private set

    /** The virtual clock of the branch; [advance] moves it. */
    var now: MachineTime = start.now
        private set

    private var lanes: Lanes<CMD> = start.lanes
    private val ending: MutableMap<CommandId, CommandRegistration<CMD>> = start.ending.toMutableMap()
    private val decisions = mutableListOf<Decision<C, CMD, E>>()

    /** The executor's state the branch is at: a checkpoint of its own. */
    val checkpoint: ExecutorCheckpoint<C, CMD> get() = ExecutorCheckpoint(snapshot, now, lanes, ending.toMap())

    /**
     * The commands a live run would be waiting on: running, or ended with their last input on
     * its way. The caller answers them, in any order.
     */
    val awaiting: List<CommandRegistration<CMD>> get() = lanes.running.values + ending.values

    /** The commands waiting in their lanes, not started; they cannot be answered until their lane lets them run. */
    val queued: Map<LaneId, List<CommandRegistration<CMD>>> get() = lanes.queued

    /** Every decision of the branch, in order. */
    val history: List<Decision<C, CMD, E>> get() = decisions.toList()

    /** The events the branch's decisions emitted, in order; shown, never delivered. */
    val effects: List<EffectEnvelope<E>> get() = decisions.flatMap { it.effects }

    /** Decides [action] as a dispatch at [now]. */
    fun dispatch(action: A): Decision<C, CMD, E> = apply(MachineInput.Dispatch(action, now))

    /** Decides [action] as the bridge message [message] at [now], as a group's local bridge delivers it. */
    fun deliver(message: MessageId, action: A): Decision<C, CMD, E> = apply(MachineInput.BridgeReceived(message, action, now))

    /** Decides [action] as data the external source [source] fed at [now]: the scripted data a branch gets instead of the source itself. */
    fun feed(source: SourceId, action: A): Decision<C, CMD, E> = apply(MachineInput.External(source, action, now))

    /**
     * Answers the awaiting [command] with [result]; the command keeps awaiting, as a live one
     * keeps running until it completes.
     *
     * @throws IllegalArgumentException if [command] is not awaiting
     */
    fun answer(command: CommandId, result: A): Decision<C, CMD, E> {
        requireAwaiting(command)
        return apply(MachineInput.CommandResult(command, result, now))
    }

    /**
     * Completes the awaiting [command].
     *
     * @throws IllegalArgumentException if [command] is not awaiting
     */
    fun complete(command: CommandId): Decision<C, CMD, E> {
        requireAwaiting(command)
        return apply(MachineInput.CommandCompleted(command, now))
    }

    /**
     * Fails the awaiting [command] with [failure].
     *
     * @throws IllegalArgumentException if [command] is not awaiting
     */
    fun fail(command: CommandId, failure: FailureDescriptor): Decision<C, CMD, E> {
        requireAwaiting(command)
        return apply(MachineInput.CommandFailed(command, failure, now))
    }

    /**
     * Moves the clock forward by [duration] and fires every timer that comes due, in deadline
     * order, each at its own deadline. Returns the decisions the firings produced.
     */
    fun advance(duration: Duration): List<Decision<C, CMD, E>> {
        require(!duration.isNegative()) { "[Actron] A branch's clock does not go back" }
        val target = now + duration
        val fired = mutableListOf<Decision<C, CMD, E>>()
        while (true) {
            val due = snapshot.timers.entries.filter { it.value.deadline <= target }.minWithOrNull(compareBy({ it.value.deadline }, { it.key.value })) ?: break
            now = maxOf(now, due.value.deadline)
            fired += apply(MachineInput.TimerFired(due.key, due.value.deadline))
        }
        now = target
        return fired
    }

    /**
     * Answers the awaiting [command] the way the recording answered an equal command: same
     * command value, registered by the same node in the same lane. The recorded results,
     * completion or failure are applied in their recorded order. An empty batch means no
     * recorded answer was applied: the command keeps
     * awaiting, and the caller supplies the answer.
     */
    fun reuseRecordedAnswers(command: CommandId): List<Decision<C, CMD, E>> {
        val registration: CommandRegistration<CMD> = when {
            command in lanes.running -> lanes.running.getValue(command)
            command in ending -> ending.getValue(command)
            else -> return emptyList()
        }
        for ((node, scope) in snapshot.activations) if (scope == registration.scope) {
            return recordedAnswers(registration, node, now).map { apply(it) }
        }
        return emptyList()
    }

    private fun requireAwaiting(command: CommandId) {
        if (command in lanes.running || command in ending) return
        for ((lane, waiting) in lanes.queued) if (waiting.any { it.id == command }) {
            throw IllegalArgumentException("[Actron] Command $command is queued in lane $lane and has not started; it cannot be answered before the lane lets it run")
        }
        throw IllegalArgumentException("[Actron] Command $command is not awaiting in this branch")
    }

    private fun apply(input: MachineInput<A>): Decision<C, CMD, E> {
        val decision = machine.decide(snapshot, input)
        decisions += decision
        if (!decision.isHandled) return decision
        snapshot = decision.snapshot
        // The bookkeeping of the live executor with jobs that end the moment they are told to.
        when (input) {
            is MachineInput.CommandCompleted -> finished(input.command)
            is MachineInput.CommandFailed -> finished(input.command)
            is MachineInput.CommandAbandoned -> finished(input.command)
            else -> Unit
        }
        val exited = lanes.exited(decision.cancelledScopes)
        lanes = exited.lanes
        for (id in exited.cancelled) finished(id)
        val abandoned = mutableListOf<LaneChange.Abandoned<CMD>>()
        for (registration in decision.commands) {
            val change = lanes.admit(registration)
            lanes = change.lanes
            abandoned += change.abandoned
        }
        ending.keys.retainAll(snapshot.commands.keys)
        // The live executor reports an abandoned command at once; here the report is decided at once.
        for (entry in abandoned) {
            ending[entry.registration.id] = entry.registration
            apply(MachineInput.CommandAbandoned(entry.registration.id, entry.reason, now))
        }
        return decision
    }

    private fun finished(id: CommandId) {
        lanes = lanes.finished(id).lanes
    }

}

/** Recorded command answers are genuine ordered input batches; a caller-driven branch reuses none. */
private fun <C : Any, A : Action, CMD : Any, E : Event> Recording<C, A, CMD, E>.answersFor(
    registration: CommandRegistration<CMD>, node: StateId, now: MachineTime,
): List<MachineInput<A>> {
    fun registrations(snapshot: MachineSnapshot<C>, commands: Collection<CommandRegistration<CMD>>): List<CommandRegistration<CMD>> =
        commands.filter { candidate -> snapshot.activations[node] == candidate.scope }
    val commands = registrations(start.snapshot, start.registrations.values) + steps.flatMap { step ->
        if (step is RecordedStep.Committed) registrations(step.decision.snapshot, step.decision.commands) else emptyList()
    }
    for (recorded in commands) if (recorded.command == registration.command && recorded.lane == registration.lane) {
        return buildList {
            for (step in steps) when (val input = step.input) {
                is MachineInput.CommandResult -> if (input.command == recorded.id) add(MachineInput.CommandResult(registration.id, input.action, now))
                is MachineInput.CommandCompleted -> if (input.command == recorded.id) add(MachineInput.CommandCompleted(registration.id, now))
                is MachineInput.CommandFailed -> if (input.command == recorded.id) add(MachineInput.CommandFailed(registration.id, input.failure, now))
                else -> Unit
            }
        }
    }
    return emptyList()
}

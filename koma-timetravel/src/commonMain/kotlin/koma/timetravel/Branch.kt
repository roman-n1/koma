package koma.timetravel

import koma.core.Action
import koma.core.Event
import koma.core.ExperimentalKomaApi
import koma.observability.FailureDescriptor
import koma.statechart.StateId
import koma.statechart.machine.AbandonReason
import koma.statechart.machine.CommandId
import koma.statechart.machine.CommandRegistration
import koma.statechart.machine.ConcurrencyPolicy
import koma.statechart.machine.Decision
import koma.statechart.machine.EffectEnvelope
import koma.statechart.machine.Machine
import koma.statechart.machine.MachineInput
import koma.statechart.machine.MachineSnapshot
import koma.statechart.machine.MachineTime
import kotlin.time.Duration

/**
 * A run that continues from a recorded position with inputs of the caller's choosing, decided
 * by the pure machine on a virtual clock. Nothing runs: a registered command waits in
 * [awaiting] until the caller answers it ([answer], [complete], [fail]) or reuses what the
 * recording has for an equal command ([reuseRecordedAnswers]); a timer fires when the caller
 * [advance]s the clock past its deadline; events accumulate in [effects] and are never
 * delivered anywhere. The live store the recording came from is untouched.
 *
 * Of the lane policies only `Latest` is emulated (a new command in a lane abandons the ones
 * awaiting there); the others queue in the live executor, and here every awaiting command may
 * be answered in any order.
 */
@ExperimentalKomaApi
class Branch<C, A : Action, CMD, E : Event> internal constructor(
    private val machine: Machine<C, A, CMD, E>,
    start: MachineSnapshot<C>,
    now: MachineTime,
    private val recording: Recording<C, A, CMD, E>,
) {
    /** The snapshot of the branch now. */
    var snapshot: MachineSnapshot<C> = start
        private set

    /** The virtual clock of the branch; [advance] moves it. */
    var now: MachineTime = now
        private set

    private val pending = linkedMapOf<CommandId, CommandRegistration<CMD>>()
    private val decisions = mutableListOf<Decision<C, CMD, E>>()

    init {
        // Commands the recorded snapshot still held are awaiting here too; the recording's own
        // answers to them may be reused.
        for (step in recording.steps.take(indexOfStart(start))) {
            if (step is RecordedStep.Committed) for (registration in step.decision.commands) pending[registration.id] = registration
        }
        pending.keys.retainAll(start.commands.keys)
    }

    /** The commands registered and not yet answered, completed, failed or cancelled: what a live run would be waiting on. */
    val awaiting: List<CommandRegistration<CMD>> get() = pending.values.toList()

    /** Every decision of the branch, in order. */
    val history: List<Decision<C, CMD, E>> get() = decisions.toList()

    /** The events the branch's decisions emitted, in order; shown, never delivered. */
    val effects: List<EffectEnvelope<E>> get() = decisions.flatMap { it.effects }

    /** Decides [action] as a dispatch at [now]. */
    fun dispatch(action: A): Decision<C, CMD, E> = apply(MachineInput.Dispatch(action, now))

    /**
     * Answers the awaiting [command] with [result]; the command keeps awaiting, as a live one
     * keeps running until it completes.
     *
     * @throws IllegalArgumentException if [command] is not awaiting
     */
    fun answer(command: CommandId, result: A): Decision<C, CMD, E> {
        require(command in pending) { "[Koma] Command $command is not awaiting in this branch" }
        return apply(MachineInput.CommandResult(command, result, now))
    }

    /**
     * Completes the awaiting [command].
     *
     * @throws IllegalArgumentException if [command] is not awaiting
     */
    fun complete(command: CommandId): Decision<C, CMD, E> {
        require(command in pending) { "[Koma] Command $command is not awaiting in this branch" }
        return apply(MachineInput.CommandCompleted(command, now))
    }

    /**
     * Fails the awaiting [command] with [failure].
     *
     * @throws IllegalArgumentException if [command] is not awaiting
     */
    fun fail(command: CommandId, failure: FailureDescriptor): Decision<C, CMD, E> {
        require(command in pending) { "[Koma] Command $command is not awaiting in this branch" }
        return apply(MachineInput.CommandFailed(command, failure, now))
    }

    /**
     * Moves the clock forward by [duration] and fires every timer that comes due, in deadline
     * order, each at its own deadline. Returns the decisions the firings produced.
     */
    fun advance(duration: Duration): List<Decision<C, CMD, E>> {
        require(!duration.isNegative()) { "[Koma] A branch's clock does not go back" }
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
     * command value, registered by the same node. The recorded results, completion or failure
     * are applied in their recorded order. `null` when the recording has no such command or no
     * answer for it: the command keeps awaiting, and the caller supplies the answer.
     */
    fun reuseRecordedAnswers(command: CommandId): List<Decision<C, CMD, E>>? {
        val registration = pending[command] ?: return null
        val node = snapshot.nodeOf(registration.scope) ?: return null
        val recorded = recordedRegistrations().firstOrNull { (recordedRegistration, recordedNode) ->
            recordedRegistration.command == registration.command && recordedNode == node && recordedRegistration.lane == registration.lane
        }?.first ?: return null
        val answers = recording.steps.mapNotNull { step ->
            when (val input = step.input) {
                is MachineInput.CommandResult<A> -> if (input.command == recorded.id) MachineInput.CommandResult(command, input.action, now) else null
                is MachineInput.CommandCompleted -> if (input.command == recorded.id) MachineInput.CommandCompleted(command, now) else null
                is MachineInput.CommandFailed -> if (input.command == recorded.id) MachineInput.CommandFailed(command, input.failure, now) else null
                else -> null
            }
        }
        if (answers.isEmpty()) return null
        return answers.map { apply(it) }
    }

    private fun recordedRegistrations(): List<Pair<CommandRegistration<CMD>, StateId?>> = recording.steps.flatMap { step ->
        if (step is RecordedStep.Committed) step.decision.commands.map { it to step.decision.snapshot.nodeOf(it.scope) } else emptyList()
    }

    private fun apply(input: MachineInput<A>): Decision<C, CMD, E> {
        val decision = machine.decide(snapshot, input)
        decisions += decision
        if (!decision.isHandled) return decision
        snapshot = decision.snapshot
        when (input) {
            is MachineInput.CommandCompleted -> pending.remove(input.command)
            is MachineInput.CommandFailed -> pending.remove(input.command)
            is MachineInput.CommandAbandoned -> pending.remove(input.command)
            else -> Unit
        }
        val exited = decision.cancelledScopes.toSet()
        pending.values.removeAll { it.scope in exited }
        for (registration in decision.commands) {
            if (registration.policy == ConcurrencyPolicy.Latest) {
                val superseded = pending.values.filter { it.lane == registration.lane }.map { it.id }
                pending[registration.id] = registration
                for (id in superseded) apply(MachineInput.CommandAbandoned(id, AbandonReason.Superseded, now))
            } else {
                pending[registration.id] = registration
            }
        }
        return decision
    }

    private fun indexOfStart(start: MachineSnapshot<C>): Int {
        var index = 0
        while (index < recording.length && recording.snapshotAt(index) !== start && recording.snapshotAt(index) != start) index++
        return index
    }

    private fun MachineSnapshot<C>.nodeOf(scope: koma.statechart.machine.ActivationId): StateId? =
        activations.entries.firstOrNull { it.value == scope }?.key
}

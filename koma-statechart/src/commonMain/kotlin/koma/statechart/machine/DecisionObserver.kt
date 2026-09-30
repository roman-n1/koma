@file:OptIn(InternalKomaApi::class)

package koma.statechart.machine

import koma.core.Action
import koma.core.Event
import koma.core.ExperimentalKomaApi
import koma.core.InputId
import koma.core.InternalKomaApi
import koma.observability.ActivationRef
import koma.observability.CommandRef
import koma.observability.FailureDescriptor
import koma.observability.JournalEntry
import koma.observability.Payload
import koma.observability.RecordingSession
import koma.observability.StoreInstanceId
import koma.observability.TimerRef

/**
 * Sees what a [MachineStore] decided, as it happens. The callbacks run on the store's thread,
 * under its lock ([onCommitted], [onIgnored]) or on the dispatching thread ([onRejected]): keep
 * them short and never call back into the store. An exception thrown by a callback is reported
 * to the store's exception handler; the store continues.
 */
@ExperimentalKomaApi
interface DecisionObserver<C, A : Action, CMD, E : Event> {
    /**
     * [decision]'s snapshot was committed while processing [input] (`null` when unknown), for
     * [machineInput]. The store's `StateCommitted` trace of the same input and revision precedes
     * this call. A recording of the machine inputs in this order, with the snapshots they
     * produced, is what a replay decides again.
     */
    fun onCommitted(input: InputId?, machineInput: MachineInput<A>, decision: Decision<C, CMD, E>) {}

    /**
     * The machine ignored [machineInput] for [reason] while processing [input]; nothing was committed.
     */
    fun onIgnored(input: InputId?, machineInput: MachineInput<A>, reason: IgnoreReason) {}

    /**
     * [action] was refused at admission and never became an input.
     */
    fun onRejected(action: A, rejection: Admission.Rejected) {}

    /**
     * The machine failed to decide [machineInput] while processing [input]: a guard, reducer or
     * rule threw [failure]. Nothing was committed; the cause reaches the store's exception handler.
     */
    fun onFailed(input: InputId?, machineInput: MachineInput<A>, failure: FailureDescriptor) {}
}

/**
 * Records the decisions of a [MachineStore] into this session as [JournalEntry.DecisionCommitted],
 * [JournalEntry.DecisionIgnored] and [JournalEntry.InputRejected] entries of [store], next to
 * the inputs and commits `recordTo(session, store)` journals for the same store. Ids, node and
 * lane names are the machine's; of a command or a rejected action the journal keeps what
 * [command] and [action] return, nothing by default.
 *
 * Pass the result to [MachineStore] as one of its observers.
 */
@ExperimentalKomaApi
fun <C, A : Action, CMD, E : Event> RecordingSession.decisionsOf(
    store: StoreInstanceId,
    command: (CMD) -> Payload<CMD> = { Payload.Omitted },
    action: (A) -> Payload<A> = { Payload.Omitted },
): DecisionObserver<C, A, CMD, E> = DecisionJournal(this, store, command, action)

@OptIn(ExperimentalKomaApi::class)
private class DecisionJournal<C, A : Action, CMD, E : Event>(
    private val session: RecordingSession,
    private val store: StoreInstanceId,
    private val describeCommand: (CMD) -> Payload<CMD>,
    private val describeAction: (A) -> Payload<A>,
) : DecisionObserver<C, A, CMD, E> {
    override fun onCommitted(input: InputId?, machineInput: MachineInput<A>, decision: Decision<C, CMD, E>) {
        session.publish(
            store,
            JournalEntry.DecisionCommitted(
                input = input,
                revision = decision.snapshot.revision,
                active = decision.snapshot.configuration.active.map { it.value }.sorted(),
                transitions = decision.transitions.map { it.index },
                exited = decision.exited.map { ActivationRef(it.node.value, it.id.value) },
                entered = decision.entered.map { ActivationRef(it.node.value, it.id.value) },
                commands = decision.commands.map { CommandRef(it.id.value, it.scope.value, it.lane?.value, it.policy?.let(::policyName), guarded { describeCommand(it.command) }) },
                cancelledScopes = decision.cancelledScopes.map { it.value },
                timersScheduled = decision.timersScheduled.map { TimerRef(it.id.value, it.transition.index, it.activation.value, it.deadline.sinceStart) },
                timersCancelled = decision.timersCancelled.map { it.value },
                effects = decision.effects.size,
            ),
        )
    }

    override fun onIgnored(input: InputId?, machineInput: MachineInput<A>, reason: IgnoreReason) {
        session.publish(store, JournalEntry.DecisionIgnored(input, reason.name))
    }

    override fun onRejected(action: A, rejection: Admission.Rejected) {
        session.publish(store, JournalEntry.InputRejected(guarded { describeAction(action) }, "QueueFull(pending=${rejection.pending}, limit=${rejection.limit})"))
    }

    private inline fun <T> guarded(describe: () -> Payload<T>): Payload<T> = try {
        describe()
    } catch (e: Exception) {
        Payload.Unavailable
    }

    private fun policyName(policy: ConcurrencyPolicy): String = when (policy) {
        ConcurrencyPolicy.Latest -> "Latest"
        ConcurrencyPolicy.Sequential -> "Sequential"
        ConcurrencyPolicy.DropIfRunning -> "DropIfRunning"
        is ConcurrencyPolicy.Parallel -> "Parallel(${policy.limit})"
    }
}

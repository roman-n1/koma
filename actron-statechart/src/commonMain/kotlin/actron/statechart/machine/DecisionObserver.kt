@file:OptIn(InternalActronApi::class)

package actron.statechart.machine

import actron.core.InputAttribution

import actron.core.Action
import actron.core.Event
import actron.core.ExperimentalActronApi
import actron.core.InputId
import actron.core.InternalActronApi
import actron.observability.ActivationRef
import actron.observability.CommandExecution
import actron.observability.CommandRef
import actron.observability.FailureDescriptor
import actron.observability.JournalEntry
import actron.observability.Payload
import actron.observability.RecordingSession
import actron.observability.StoreInstanceId
import actron.observability.TimerRef

/**
 * Sees what a [MachineStore] decided, as it happens. The callbacks run on the store's thread,
 * under its lock ([onCommitted], [onIgnored]) or on the dispatching thread ([onRejected]): keep
 * them short and never call back into the store. An exception thrown by a callback is reported
 * to the store's exception handler; the store continues.
 */
interface DecisionObserver<C : Any, A : Action, CMD : Any, E : Event> {
    /**
     * [decision]'s snapshot was committed while processing [input] ([InputAttribution.Unattributed] when unknown), for
     * [machineInput]. The store's `StateCommitted` trace of the same input and revision precedes
     * this call. A recording of the machine inputs in this order, with the snapshots they
     * produced, is what a replay decides again.
     */
    fun onCommitted(input: InputAttribution, machineInput: MachineInput<A>, decision: Decision<C, CMD, E>) {}

    /**
     * The machine ignored [machineInput] for [reason] while processing [input]; nothing was committed.
     */
    fun onIgnored(input: InputAttribution, machineInput: MachineInput<A>, reason: IgnoreReason) {}

    /**
     * [action] was refused at admission and never became an input.
     */
    fun onRejected(action: A, rejection: Admission.Rejected) {}

    /**
     * The machine failed to decide [machineInput] while processing [input]: a guard, reducer or
     * rule threw [failure]. Nothing was committed; the cause reaches the store's exception handler.
     */
    fun onFailed(input: InputAttribution, machineInput: MachineInput<A>, failure: FailureDescriptor) {}

    /**
     * The store closed with commands its executor had not finished: [queued] had been registered
     * and never started, [running] were cancelled (handoff §4: `Abandoned(StoreClosed)`). Called
     * once, on the executor's thread as it stops, only when there is something to tell.
     */
    fun onClosed(queued: List<CommandId>, running: List<CommandId>) {}
}

/**
 * Records the decisions of a [MachineStore] into this session as [JournalEntry.DecisionCommitted],
 * [JournalEntry.DecisionIgnored], [JournalEntry.InputRejected] and, at close,
 * [JournalEntry.CommandsAbandoned] entries of [store], next to the inputs and commits
 * `recordTo(session, store)` journals for the same store. Ids, node and lane names are the
 * machine's; of a command or a rejected action the journal keeps what [command] and [action]
 * return, nothing by default.
 *
 * Pass the result to [MachineStore] as one of its observers.
 */
fun <C : Any, A : Action, CMD : Any, E : Event> RecordingSession.decisionsOf(
    store: StoreInstanceId,
    command: (CMD) -> Payload<CMD> = { Payload.Omitted },
    action: (A) -> Payload<A> = { Payload.Omitted },
): DecisionObserver<C, A, CMD, E> = DecisionJournal(this, store, command, action)

/**
 * Journals what the mailbox of [store] does with its effects: [JournalEntry.EffectQueued] with
 * what [describe] keeps of the effect (nothing by default), [JournalEntry.EffectHandlingStarted],
 * [JournalEntry.EffectAcknowledged] and [JournalEntry.EffectDiscarded]. Pass the result to
 * [MailboxConfig.listeners].
 */
fun <E : Event> RecordingSession.effectsOf(store: StoreInstanceId, describe: (E) -> Payload<E> = { Payload.Omitted }): EffectListener<E> = object : EffectListener<E> {
    override fun onQueued(input: InputAttribution, effect: PendingEffect<E>) {
        val payload = try {
            describe(effect.event)
        } catch (e: Exception) {
            Payload.Unavailable
        }
        publish(store, JournalEntry.EffectQueued(input, effect.id.value, effect.policy.name, payload))
    }

    override fun onHandlingStarted(effect: PendingEffect<E>) {
        publish(store, JournalEntry.EffectHandlingStarted(effect.id.value, effect.attempts))
    }

    override fun onAcknowledged(id: EffectId) {
        publish(store, JournalEntry.EffectAcknowledged(id.value))
    }

    override fun onDiscarded(id: EffectId, reason: EffectDiscardReason) {
        publish(store, JournalEntry.EffectDiscarded(id.value, reason.name))
    }
}

private class DecisionJournal<C : Any, A : Action, CMD : Any, E : Event>(
    private val session: RecordingSession,
    private val store: StoreInstanceId,
    private val describeCommand: (CMD) -> Payload<CMD>,
    private val describeAction: (A) -> Payload<A>,
) : DecisionObserver<C, A, CMD, E> {
    override fun onCommitted(input: InputAttribution, machineInput: MachineInput<A>, decision: Decision<C, CMD, E>) {
        external(input, machineInput)
        session.publish(
            store,
            JournalEntry.DecisionCommitted(
                input = input,
                revision = decision.snapshot.revision,
                active = decision.snapshot.configuration.active.map { it.value }.sorted(),
                transitions = decision.transitions.map { it.index },
                exited = decision.exited.map { ActivationRef(it.node.value, it.id.value) },
                entered = decision.entered.map { ActivationRef(it.node.value, it.id.value) },
                commands = decision.commands.map { CommandRef(it.id.value, it.scope.value, when (val lane = it.lane) {
                    CommandLane.Independent -> CommandExecution.Independent
                    is LaneId -> CommandExecution.InLane(lane.value, policyName(it.policy))
                }, guarded { describeCommand(it.command) }) },
                cancelledScopes = decision.cancelledScopes.map { it.value },
                timersScheduled = decision.timersScheduled.map { TimerRef(it.id.value, it.transition.index, it.activation.value, it.deadline.sinceStart) },
                timersCancelled = decision.timersCancelled.map { it.value },
                effects = decision.effects.size,
            ),
        )
    }

    override fun onIgnored(input: InputAttribution, machineInput: MachineInput<A>, reason: IgnoreReason) {
        external(input, machineInput)
        session.publish(store, JournalEntry.DecisionIgnored(input, reason.name))
    }

    override fun onFailed(input: InputAttribution, machineInput: MachineInput<A>, failure: FailureDescriptor) {
        external(input, machineInput)
    }

    // The source an input came from is the journal's business as much as the decision.
    private fun external(input: InputAttribution, machineInput: MachineInput<A>) {
        if (machineInput is MachineInput.External) session.publish(store, JournalEntry.ExternalReceived(input, machineInput.source.value))
    }

    override fun onRejected(action: A, rejection: Admission.Rejected) {
        session.publish(store, JournalEntry.InputRejected(guarded { describeAction(action) }, "QueueFull(pending=${rejection.pending}, limit=${rejection.limit})"))
    }

    override fun onClosed(queued: List<CommandId>, running: List<CommandId>) {
        session.publish(store, JournalEntry.CommandsAbandoned("StoreClosed", queued.map { it.value }, running.map { it.value }))
    }

    private inline fun <T : Any> guarded(describe: () -> Payload<T>): Payload<T> = try {
        describe()
    } catch (e: Exception) {
        Payload.Unavailable
    }

    private fun policyName(policy: ConcurrencyPolicy): String = when (policy) {
        ConcurrencyPolicy.Independent -> "Independent"
        ConcurrencyPolicy.Latest -> "Latest"
        ConcurrencyPolicy.Sequential -> "Sequential"
        ConcurrencyPolicy.DropIfRunning -> "DropIfRunning"
        is ConcurrencyPolicy.Parallel -> "Parallel(${policy.limit})"
    }
}

/** Opt-in live decision diagnostics. Metadata only; never copies action/context payloads. */
interface DecisionExplanationObserver {
    /** Called under the decision lock before commit/ignored/failed callbacks; never call the Store back. */
    fun onExplained(input: InputAttribution, explanation: DecisionExplanation)
}

/** Opt-in typed trace of the one actual decision, including failed and ignored decisions. */
interface DecisionTraceObserver<C : Any, A : Action, CMD : Any, E : Event> : DecisionObserver<C, A, CMD, E> {
    /** Called before commit/outcome callbacks; never call the Store back or mutate snapshot data. */
    fun onDecided(input: InputAttribution, machineInput: MachineInput<A>, explained: ExplainedDecision<C, CMD, E>)
}

/** Observes actual live guard evaluations without a second decision or extra guard invocations. */
fun <C : Any, A : Action, CMD : Any, E : Event> decisionDiagnostics(
    consume: (InputAttribution, DecisionExplanation) -> Unit,
): DecisionObserver<C, A, CMD, E> = object : DecisionObserver<C, A, CMD, E>, DecisionExplanationObserver {
    override fun onExplained(input: InputAttribution, explanation: DecisionExplanation) = consume(input, explanation)
}

package koma.statechart.machine

import koma.core.Action
import koma.core.Event
import koma.observability.FailureDescriptor
import koma.statechart.StateId

/** Complete data of one parent and its state-scoped child. */
data class InvocationSnapshot<PC, CC>(val parent: MachineSnapshot<PC>, val child: MachineSnapshot<CC>? = null, val owner: ActivationId? = null)

/** Parent input, or an input addressed to an exact child instance. */
sealed interface InvocationInput<out PA : Action, out CA : Action> {
    data class Parent<PA : Action>(val input: MachineInput<PA>) : InvocationInput<PA, Nothing>
    data class Child<CA : Action>(val owner: ActivationId, val input: MachineInput<CA>) : InvocationInput<Nothing, CA>
}

/** The child instance that produced a decision. Child-local ids are qualified by [owner]. */
data class InvokedChildDecision<C, CMD, E : Event>(val owner: ActivationId, val decision: Decision<C, CMD, E>)
data class InvokedCommand<CMD>(val owner: ActivationId, val command: CommandRegistration<CMD>)
data class InvokedTimer(val owner: ActivationId, val timer: TimerSchedule)
data class InvocationCancellation(val owner: ActivationId, val scopes: List<ActivationId>)

/** An atomic composite decision; execute intents only when handled, after committing [snapshot]. */
data class InvocationDecision<PC, CC, PCMD, PE : Event, CCMD, CE : Event>(
    val outcome: DecisionOutcome,
    val snapshot: InvocationSnapshot<PC, CC>,
    val parentDecisions: List<Decision<PC, PCMD, PE>> = emptyList(),
    val childDecisions: List<InvokedChildDecision<CC, CCMD, CE>> = emptyList(),
    val childCancellations: List<InvocationCancellation> = emptyList(),
    val childCommands: List<InvokedCommand<CCMD>> = emptyList(),
    val childTimers: List<InvokedTimer> = emptyList(),
    val parentCommands: List<CommandRegistration<PCMD>> = emptyList(),
    val parentTimers: List<TimerSchedule> = emptyList(),
)

/** Feedback between parent/child failed to settle within the delivery bound. */
class InvocationLimitException(val limit: Int) : IllegalStateException("[Koma] Parent/child routing exceeded $limit deliveries")

/**
 * Pure, typed child invocation owned by [node]'s parent activation. Routing drains in FIFO order;
 * one failed child/parent decision rolls back the entire composite input. No IO executes here.
 * Executors address child commands and timers by both parent owner and child-local identity.
 */
class InvokedMachine<PC, PA : Action, PCMD, PE : Event, CC, CA : Action, CCMD, CE : Event>(
    val parent: Machine<PC, PA, PCMD, PE>,
    val node: StateId,
    val child: Machine<CC, CA, CCMD, CE>,
    private val context: (MachineSnapshot<PC>) -> CC,
    private val toChild: (PE) -> CA? = { null },
    private val toParent: (CE) -> PA? = { null },
    private val maxDeliveries: Int = 100,
) {
    init {
        require(parent.chart.node(node) != null) { "[Koma] Invocation owner is undeclared" }
        require(maxDeliveries > 0) { "[Koma] Invocation delivery limit must be positive" }
    }

    fun initialSnapshot(context: PC): InvocationSnapshot<PC, CC> = InvocationSnapshot(parent.initialSnapshot(context))

    fun decide(base: InvocationSnapshot<PC, CC>, input: InvocationInput<PA, CA>): InvocationDecision<PC, CC, PCMD, PE, CCMD, CE> {
        require((base.child == null) == (base.owner == null)) { "[Koma] Child snapshot and owner must coexist" }
        require(base.owner == base.parent.activations[node]) { "[Koma] Invocation owner does not match parent activation" }
        require(parent.validateSnapshot(base.parent).isEmpty()) { "[Koma] Invalid parent invocation snapshot" }
        base.child?.let { require(child.validateSnapshot(it).isEmpty()) { "[Koma] Invalid child invocation snapshot" } }
        var parentSnapshot = base.parent
        var childSnapshot = base.child
        var owner = base.owner
        val parents = mutableListOf<Decision<PC, PCMD, PE>>()
        val children = mutableListOf<InvokedChildDecision<CC, CCMD, CE>>()
        val cancellations = mutableListOf<InvocationCancellation>()
        data class Routed<PA : Action, CA : Action>(val input: InvocationInput<PA, CA>, val originatingOwner: ActivationId? = null)
        val queue = ArrayDeque<Routed<PA, CA>>()
        queue.addLast(Routed(input))
        var deliveries = 0
        var handled = false
        var ignored: DecisionOutcome = DecisionOutcome.Ignored(IgnoreReason.NoTransition)
        try {
            fun acceptChild(childOwner: ActivationId, decision: Decision<CC, CCMD, CE>, now: MachineTime) {
                if (decision.outcome is DecisionOutcome.Failed) throw decision.outcome.cause
                handled = handled || decision.isHandled
                children += InvokedChildDecision(childOwner, decision)
                childSnapshot = decision.snapshot
                for (effect in decision.effects) toParent(effect.event)?.let {
                    queue.addLast(Routed(InvocationInput.Parent(MachineInput.External(SourceId("invocation:${node.value}:${childOwner.value}"), it, now)), childOwner))
                }
            }
            while (queue.isNotEmpty()) {
                if (++deliveries > maxDeliveries) throw InvocationLimitException(maxDeliveries)
                val routed = queue.removeFirst()
                if (routed.originatingOwner != null && routed.originatingOwner != owner) continue
                when (val next = routed.input) {
                    is InvocationInput.Parent -> {
                        val decision = parent.decide(parentSnapshot, next.input)
                        if (decision.outcome is DecisionOutcome.Failed) throw decision.outcome.cause
                        handled = handled || decision.isHandled
                        ignored = decision.outcome
                        parents += decision
                        parentSnapshot = decision.snapshot
                        val nextOwner = parentSnapshot.activations[node]
                        if (nextOwner != owner) {
                            owner?.let { old -> cancellations += InvocationCancellation(old, childSnapshot?.activations?.values?.toList().orEmpty()) }
                            owner = nextOwner
                            childSnapshot = null
                            if (nextOwner != null) {
                                val start = child.decide(child.initialSnapshot(context(parentSnapshot)), MachineInput.Start(next.input.now))
                                acceptChild(nextOwner, start, next.input.now)
                            }
                        }
                        val currentOwner = owner
                        if (currentOwner != null) for (effect in decision.effects) toChild(effect.event)?.let {
                            queue.addLast(Routed(InvocationInput.Child(currentOwner, MachineInput.Dispatch(it, next.input.now))))
                        }
                    }
                    is InvocationInput.Child -> {
                        val snapshot = childSnapshot
                        if (snapshot == null || owner != next.owner) { ignored = DecisionOutcome.Ignored(IgnoreReason.NotStarted); continue }
                        val decision = child.decide(snapshot, next.input)
                        ignored = decision.outcome
                        acceptChild(next.owner, decision, next.input.now)
                    }
                }
            }
        } catch (error: Exception) {
            return InvocationDecision(DecisionOutcome.Failed(FailureDescriptor.of(error), error), base)
        }
        val currentChild = childSnapshot
        return InvocationDecision(
            if (handled) DecisionOutcome.Handled else ignored,
            InvocationSnapshot(parentSnapshot, childSnapshot, owner), parents.toList(), children.toList(), cancellations.toList(),
            children.filter { it.owner == owner }.flatMap { entry -> entry.decision.commands.filter { it.id in currentChild?.commands.orEmpty() }.map { InvokedCommand(entry.owner, it) } },
            children.filter { it.owner == owner }.flatMap { entry -> entry.decision.timersScheduled.filter { it.id in currentChild?.timers.orEmpty() }.map { InvokedTimer(entry.owner, it) } },
            parents.flatMap { it.commands }.filter { it.id in parentSnapshot.commands },
            parents.flatMap { it.timersScheduled }.filter { it.id in parentSnapshot.timers },
        )
    }
}

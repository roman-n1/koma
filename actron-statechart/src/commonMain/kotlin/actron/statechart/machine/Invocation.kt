package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import actron.observability.FailureDescriptor
import actron.statechart.StateId

/** Complete data of one parent and its state-scoped child. */
data class InvocationSnapshot<PC : Any, CC : Any>(val parent: MachineSnapshot<PC>, val child: ChildInvocation<CC> = ChildInvocation.Dormant)

/** Child lifecycle; only an active invocation owns a snapshot, commands and timers. */
sealed interface ChildInvocation<out C : Any> {
    fun ownedBy(owner: ActivationId): Boolean
    fun matchesParent(activations: Map<StateId, ActivationId>, node: StateId): Boolean
    fun cancel(consume: (InvocationCancellation) -> Unit)
    val commandIds: Set<CommandId>
    val timerIds: Set<TimerId>

    data object Dormant : ChildInvocation<Nothing> {
        override fun ownedBy(owner: ActivationId): Boolean = false
        override fun matchesParent(activations: Map<StateId, ActivationId>, node: StateId): Boolean = node !in activations
        override fun cancel(consume: (InvocationCancellation) -> Unit) {}
        override val commandIds: Set<CommandId> = emptySet()
        override val timerIds: Set<TimerId> = emptySet()
    }
    data class Active<C : Any>(val owner: ActivationId, val snapshot: MachineSnapshot<C>) : ChildInvocation<C> {
        override fun ownedBy(owner: ActivationId): Boolean = this.owner == owner
        override fun matchesParent(activations: Map<StateId, ActivationId>, node: StateId): Boolean = activations[node] == owner
        override fun cancel(consume: (InvocationCancellation) -> Unit) { consume(InvocationCancellation(owner, snapshot.activations.values.toList())) }
        override val commandIds: Set<CommandId> get() = snapshot.commands.keys
        override val timerIds: Set<TimerId> get() = snapshot.timers.keys
    }
}

/** Parent input, or an input addressed to an exact child instance. */
sealed interface InvocationInput<out PA : Action, out CA : Action> {
    data class Parent<PA : Action>(val input: MachineInput<PA>) : InvocationInput<PA, Nothing>
    data class Child<CA : Action>(val owner: ActivationId, val input: MachineInput<CA>) : InvocationInput<Nothing, CA>
}

/** The child instance that produced a decision. Child-local ids are qualified by [owner]. */
data class InvokedChildDecision<C : Any, CMD : Any, E : Event>(val owner: ActivationId, val decision: Decision<C, CMD, E>)
data class InvokedCommand<CMD : Any>(val owner: ActivationId, val command: CommandRegistration<CMD>)
data class InvokedTimer(val owner: ActivationId, val timer: TimerSchedule)
data class InvocationCancellation(val owner: ActivationId, val scopes: List<ActivationId>)

/** An atomic composite decision; execute intents only when handled, after committing [snapshot]. */
data class InvocationDecision<PC : Any, CC : Any, PCMD : Any, PE : Event, CCMD : Any, CE : Event>(
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
class InvocationLimitException(val limit: Int) : IllegalStateException("[Actron] Parent/child routing exceeded $limit deliveries")

/**
 * Pure, typed child invocation owned by [node]'s parent activation. Routing drains in FIFO order;
 * one failed child/parent decision rolls back the entire composite input. No IO executes here.
 * Executors address child commands and timers by both parent owner and child-local identity.
 */
class InvokedMachine<PC : Any, PA : Action, PCMD : Any, PE : Event, CC : Any, CA : Action, CCMD : Any, CE : Event>(
    val parent: Machine<PC, PA, PCMD, PE>,
    val node: StateId,
    val child: Machine<CC, CA, CCMD, CE>,
    private val context: (MachineSnapshot<PC>) -> CC,
    toChild: (PE, (CA) -> Unit) -> Unit = { _, _ -> },
    toParent: (CE, (PA) -> Unit) -> Unit = { _, _ -> },
    private val maxDeliveries: Int = 100,
) {
    private val toChild = EventRoute(toChild)
    private val toParent = EventRoute(toParent)
    init {
        require(parent.chart.hasNode(node)) { "[Actron] Invocation owner is undeclared" }
        require(maxDeliveries > 0) { "[Actron] Invocation delivery limit must be positive" }
    }

    fun initialSnapshot(context: PC): InvocationSnapshot<PC, CC> = InvocationSnapshot(parent.initialSnapshot(context))

    fun decide(base: InvocationSnapshot<PC, CC>, input: InvocationInput<PA, CA>): InvocationDecision<PC, CC, PCMD, PE, CCMD, CE> {
        require(base.child.matchesParent(base.parent.activations, node)) { "[Actron] Invocation owner does not match parent activation" }
        require(parent.validateSnapshot(base.parent).isEmpty()) { "[Actron] Invalid parent invocation snapshot" }
        val restoredChild = base.child
        if (restoredChild is ChildInvocation.Active) require(restoredChild.snapshot.isStarted && child.validateSnapshot(restoredChild.snapshot).isEmpty()) { "[Actron] Invalid child invocation snapshot" }
        var parentSnapshot = base.parent
        var childState = base.child
        val parents = mutableListOf<Decision<PC, PCMD, PE>>()
        val children = mutableListOf<InvokedChildDecision<CC, CCMD, CE>>()
        val cancellations = mutableListOf<InvocationCancellation>()
        data class Routed<PA : Action, CA : Action>(val input: InvocationInput<PA, CA>, val isCurrent: () -> Boolean = { true })
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
                childState = ChildInvocation.Active(childOwner, decision.snapshot)
                for (effect in decision.effects) toParent.mapEvent(effect.event) {
                    queue.addLast(Routed(InvocationInput.Parent(MachineInput.External(SourceId("invocation:${node.value}:${childOwner.value}"), it, now)), isCurrent = { childState.ownedBy(childOwner) }))
                }
            }
            while (queue.isNotEmpty()) {
                if (++deliveries > maxDeliveries) throw InvocationLimitException(maxDeliveries)
                val routed = queue.removeFirst()
                if (!routed.isCurrent()) continue
                when (val next = routed.input) {
                    is InvocationInput.Parent -> {
                        val decision = parent.decide(parentSnapshot, next.input)
                        if (decision.outcome is DecisionOutcome.Failed) throw decision.outcome.cause
                        handled = handled || decision.isHandled
                        ignored = decision.outcome
                        parents += decision
                        parentSnapshot = decision.snapshot
                        if (!childState.matchesParent(parentSnapshot.activations, node)) {
                            childState.cancel { cancellations += it }
                            childState = ChildInvocation.Dormant
                            if (node in parentSnapshot.activations) {
                                val nextOwner = parentSnapshot.activations.getValue(node)
                                val start = child.decide(child.initialSnapshot(context(parentSnapshot)), MachineInput.Start(next.input.now))
                                acceptChild(nextOwner, start, next.input.now)
                            }
                        }
                        val currentChild = childState
                        if (currentChild is ChildInvocation.Active) for (effect in decision.effects) toChild.mapEvent(effect.event) {
                            queue.addLast(Routed(InvocationInput.Child(currentChild.owner, MachineInput.Dispatch(it, next.input.now))))
                        }
                    }
                    is InvocationInput.Child -> {
                        val currentChild = childState
                        if (currentChild !is ChildInvocation.Active || !currentChild.ownedBy(next.owner)) { ignored = DecisionOutcome.Ignored(IgnoreReason.NotStarted); continue }
                        val decision = child.decide(currentChild.snapshot, next.input)
                        ignored = decision.outcome
                        acceptChild(next.owner, decision, next.input.now)
                    }
                }
            }
        } catch (error: Exception) {
            return InvocationDecision(DecisionOutcome.Failed(FailureDescriptor.of(error), error), base)
        }
        val currentChild = childState
        return InvocationDecision(
            if (handled) DecisionOutcome.Handled else ignored,
            InvocationSnapshot(parentSnapshot, childState), parents.toList(), children.toList(), cancellations.toList(),
            children.filter { currentChild.ownedBy(it.owner) }.flatMap { entry -> entry.decision.commands.filter { it.id in currentChild.commandIds }.map { InvokedCommand(entry.owner, it) } },
            children.filter { currentChild.ownedBy(it.owner) }.flatMap { entry -> entry.decision.timersScheduled.filter { it.id in currentChild.timerIds }.map { InvokedTimer(entry.owner, it) } },
            parents.flatMap { it.commands }.filter { it.id in parentSnapshot.commands },
            parents.flatMap { it.timersScheduled }.filter { it.id in parentSnapshot.timers },
        )
    }
}

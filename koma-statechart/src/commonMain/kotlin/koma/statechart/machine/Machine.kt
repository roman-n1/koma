package koma.statechart.machine

import koma.core.Action
import koma.core.Event
import koma.core.ExperimentalKomaApi
import koma.core.KomaStoreDsl
import koma.observability.FailureDescriptor
import koma.statechart.ActionMatcher
import koma.statechart.HistoryState
import koma.statechart.StateChartDefinition
import koma.statechart.StateChartRuntime
import koma.statechart.StateConfiguration
import koma.statechart.StateId
import koma.statechart.StepResult
import koma.statechart.TimerFired
import koma.statechart.activeLeaves
import koma.statechart.endpointIssues
import koma.statechart.inEntryOrder
import koma.statechart.initialConfiguration
import koma.statechart.instantTimerCycles

/**
 * What an enter hook of a [Machine] sees and may do. It runs inside [Machine.decide], so it is a
 * pure function of its inputs: read [context], [input], [action] and [now], assign [context],
 * register [command]s and emit [event]s. No clock, no random, no repository: whatever the hook
 * needs that is not here belongs in the input or the context.
 */
@KomaStoreDsl
interface MachineEnterScope<C, out A : Action, in CMD, in E : Event> {
    /** The node being entered. */
    val node: StateId

    /** The activation this entry creates; commands registered here belong to it. */
    val activation: ActivationId

    /** The input that caused the step. */
    val input: MachineInput<A>

    /**
     * The action of the step: the dispatched action or command result, [TimerFired] for a timer,
     * [CommandFailure] for a failed command, `null` for [MachineInput.Start].
     */
    val action: Action?

    /** The machine's clock, from the input. */
    val now: MachineTime

    /** The context as updated so far in this step; assign it to update it. */
    var context: C

    /**
     * Registers a command that runs on its own, cancelled when this activation exits.
     */
    fun command(command: CMD): CommandId

    /**
     * Registers a command in [lane] under [policy], cancelled when this activation exits.
     */
    fun command(command: CMD, lane: LaneId, policy: ConcurrencyPolicy): CommandId

    /**
     * Asks the executor to deliver [event] after the snapshot is committed.
     */
    fun event(event: E): EffectId
}

/**
 * What an exit hook of a [Machine] sees and may do; pure like [MachineEnterScope].
 */
@KomaStoreDsl
interface MachineExitScope<C, out A : Action, in E : Event> {
    /** The node being exited. */
    val node: StateId

    /** The activation that ends. */
    val activation: ActivationId

    /** The input that caused the step. */
    val input: MachineInput<A>

    /** The action of the step; see [MachineEnterScope.action]. */
    val action: Action?

    /** The machine's clock, from the input. */
    val now: MachineTime

    /** The context as updated so far in this step; assign it to update it. */
    var context: C

    /**
     * Asks the executor to deliver [event] after the snapshot is committed.
     */
    fun event(event: E): EffectId
}

/**
 * What an action handler of a [Machine] sees and may do; pure like [MachineEnterScope]. It runs
 * for an action that no transition takes while [node] is active: the configuration stays, the
 * activation stays, and commands registered here belong to that activation.
 */
@KomaStoreDsl
interface MachineActionScope<C, out A : Action, in CMD, in E : Event> {
    /** The node whose handler runs. */
    val node: StateId

    /** The current activation of [node]; commands registered here belong to it. */
    val activation: ActivationId

    /** The input that caused the step. */
    val input: MachineInput<A>

    /** The action being handled: the dispatched action, a command result or [CommandFailure]. */
    val action: Action

    /** The machine's clock, from the input. */
    val now: MachineTime

    /** The context as updated so far in this step; assign it to update it. */
    var context: C

    /** Registers a command that runs on its own, cancelled when [activation] exits. */
    fun command(command: CMD): CommandId

    /** Registers a command in [lane] under [policy], cancelled when [activation] exits. */
    fun command(command: CMD, lane: LaneId, policy: ConcurrencyPolicy): CommandId

    /** Asks the executor to deliver [event] after the snapshot is committed. */
    fun event(event: E): EffectId
}

/**
 * Builder of a [Machine]: the implementations of the chart's guard and effect labels, the enter
 * and exit rules of its nodes and the action handlers of its nodes, all pure.
 */
@KomaStoreDsl
class MachineBuilder<C, A : Action, CMD, E : Event> internal constructor() {
    internal val guards = mutableMapOf<String, (MachineSnapshot<C>, Action) -> Boolean>()
    internal val effects = mutableMapOf<String, (C, Action) -> C>()
    internal val entries = mutableMapOf<StateId, MutableList<MachineEnterScope<C, A, CMD, E>.() -> Unit>>()
    internal val exits = mutableMapOf<StateId, MutableList<MachineExitScope<C, A, E>.() -> Unit>>()
    internal val handlers = mutableMapOf<StateId, MutableList<ActionHandler<C, A, CMD, E>>>()

    internal class ActionHandler<C, A : Action, CMD, E : Event>(val matcher: ActionMatcher, val rule: MachineActionScope<C, A, CMD, E>.() -> Unit)

    /**
     * Implements the guard [label]: a pure predicate over the snapshot before the step and the
     * action ([TimerFired] for a timer, [CommandFailure] for a failed command).
     *
     * @throws IllegalArgumentException if [label] already has an implementation
     */
    fun guard(label: String, guard: (snapshot: MachineSnapshot<C>, action: Action) -> Boolean) {
        require(guards.put(label, guard) == null) { "[Koma] Guard '$label' is implemented twice" }
    }

    /**
     * Implements the effect [label] of transitions: a pure update of the context, run after the
     * exit hooks and before the enter hooks of the step.
     *
     * @throws IllegalArgumentException if [label] already has an implementation
     */
    fun effect(label: String, effect: (context: C, action: Action) -> C) {
        require(effects.put(label, effect) == null) { "[Koma] Effect '$label' is implemented twice" }
    }

    /**
     * Adds a rule run whenever [id] is entered, outermost nodes first; rules of one node run in the
     * order added.
     */
    fun onEnter(id: StateId, rule: MachineEnterScope<C, A, CMD, E>.() -> Unit) {
        entries.getOrPut(id) { mutableListOf() } += rule
    }

    /**
     * Adds a rule run whenever [id] is exited, innermost nodes first; rules of one node run in the
     * order added.
     */
    fun onExit(id: StateId, rule: MachineExitScope<C, A, E>.() -> Unit) {
        exits.getOrPut(id) { mutableListOf() } += rule
    }

    /**
     * Adds a handler for actions matching [matcher] while [id] is active and no transition of the
     * active configuration takes the action: the way to update the context, register a command
     * or emit an event without leaving and re-entering a node (a self-loop would end the node's
     * activation and cancel its commands). Transitions always win. Among handlers, the innermost
     * active node with a matching handler wins, and within a node the first added; one handler
     * runs per input.
     */
    fun onAction(id: StateId, matcher: ActionMatcher, rule: MachineActionScope<C, A, CMD, E>.() -> Unit) {
        handlers.getOrPut(id) { mutableListOf() } += ActionHandler(matcher, rule)
    }
}

/**
 * Creates a [Machine] for [chart] under [id] and [version].
 *
 * @throws IllegalArgumentException if a guard or effect label used by [chart] has no
 * implementation, if a rule is added for an undeclared node or a [HistoryState], if a label is
 * implemented twice, if the hierarchy of [chart] is malformed or refers to undeclared states, or
 * if timers without a positive delay restart each other in a loop
 */
fun <C, A : Action, CMD, E : Event> Machine(
    id: DefinitionId,
    version: DefinitionVersion,
    chart: StateChartDefinition,
    builder: MachineBuilder<C, A, CMD, E>.() -> Unit = {},
): Machine<C, A, CMD, E> = Machine(id, version, chart, MachineBuilder<C, A, CMD, E>().apply(builder))

/**
 * A replay-ready statechart: a pure decision function over [MachineSnapshot]s.
 *
 * [decide] takes a snapshot and a [MachineInput] and returns a [Decision]: the next snapshot and,
 * as data, the commands to register, the scopes to cancel, the timers to schedule and cancel and
 * the events to deliver. It runs nothing, reads no clock and keeps no state, so the same snapshot
 * and input always give the same decision, and a recorded run can be decided again. Executing the
 * decision is the executor's job, after it committed the snapshot.
 *
 * Transition selection, exit and entry order, history and parallel regions are those of
 * [StateChartRuntime]. Around it the machine updates the context (exit rules innermost first,
 * transition effects in selection order, enter rules outermost first), gives every entered node a
 * new [ActivationId], scopes the commands of an enter rule to that activation, cancels the
 * commands and timers of exited activations, and schedules the timers of entered nodes at
 * `now + delay`.
 *
 * A step that exits and re-enters a node (a self-loop, a transition into an ancestor) ends its
 * activation and creates a new one: commands of the old one are cancelled, its timers restart.
 * An action that no transition takes may still be handled by an action handler of an active node
 * (see [MachineBuilder.onAction]): the context, commands and events change, the configuration
 * and the activations do not.
 * A [MachineInput.CommandResult] whose command is not registered any more is ignored as stale;
 * that is how a late result of a cancelled load cannot complete a newer one.
 */
class Machine<C, A : Action, CMD, E : Event> internal constructor(
    val id: DefinitionId,
    val version: DefinitionVersion,
    val chart: StateChartDefinition,
    config: MachineBuilder<C, A, CMD, E>,
) {
    private val guards: Map<String, (MachineSnapshot<C>, Action) -> Boolean> = config.guards.toMap()
    private val effects: Map<String, (C, Action) -> C> = config.effects.toMap()
    private val entries: Map<StateId, List<MachineEnterScope<C, A, CMD, E>.() -> Unit>> = config.entries.mapValues { it.value.toList() }
    private val exits: Map<StateId, List<MachineExitScope<C, A, E>.() -> Unit>> = config.exits.mapValues { it.value.toList() }
    private val handlers: Map<StateId, List<MachineBuilder.ActionHandler<C, A, CMD, E>>> = config.handlers.mapValues { it.value.toList() }

    private val runtime = StateChartRuntime<MachineSnapshot<C>>(chart, { chart.activeLeaves(it.configuration).first() }, guards)

    init {
        val missing = chart.transitions.mapNotNull { it.effect }.distinct().filter { it !in effects }
        require(missing.isEmpty()) { "[Koma] Missing effect implementations: ${missing.joinToString()}" }
        val declared = chart.states.filter { it !is HistoryState }.map { it.id }.toSet()
        val undeclared = (entries.keys + exits.keys + handlers.keys).filter { it !in declared }
        require(undeclared.isEmpty()) { "[Koma] Rules for undeclared states: ${undeclared.joinToString()}" }
        val undeclaredStates = chart.endpointIssues()
        require(undeclaredStates.isEmpty()) { "[Koma] Chart refers to undeclared or duplicate states: ${undeclaredStates.joinToString()}" }
        val loops = chart.instantTimerCycles()
        require(loops.isEmpty()) {
            "[Koma] Timers without a positive delay restart each other forever: " +
                loops.joinToString { cycle -> cycle.joinToString(" -> ") { "${it.source.value} --after ${it.after}--> ${it.target.value}" } }
        }
    }

    /** Timers by source, as positions in the chart's transition list, in declaration order. */
    private val timersBySource: Map<StateId, List<TransitionId>> = chart.transitions.withIndex()
        .filter { it.value.isTimer }
        .groupBy({ it.value.source }, { TransitionId(it.index) })

    /**
     * The snapshot before the first input: revision 0, nothing active, [context] as given.
     */
    fun initialSnapshot(context: C): MachineSnapshot<C> = MachineSnapshot(
        definition = id,
        version = version,
        revision = 0,
        configuration = StateConfiguration(active = emptySet()),
        context = context,
    )

    /**
     * Decides [input] on [snapshot]. Never throws for a failing guard, reducer or rule: that is a
     * [DecisionOutcome.Failed] decision with the snapshot unchanged.
     *
     * @throws IllegalArgumentException if [snapshot] belongs to another machine or version
     */
    fun decide(snapshot: MachineSnapshot<C>, input: MachineInput<A>): Decision<C, CMD, E> {
        require(snapshot.definition == id && snapshot.version == version) {
            "[Koma] Snapshot of ${snapshot.definition} ${snapshot.version} given to machine $id $version"
        }
        return try {
            when (input) {
                is MachineInput.Start -> if (snapshot.isStarted) ignored(snapshot, IgnoreReason.AlreadyStarted) else start(snapshot, input)
                is MachineInput.Dispatch -> when {
                    !snapshot.isStarted -> ignored(snapshot, IgnoreReason.NotStarted)
                    else -> step(snapshot, input, input.action, changed = false)
                }
                is MachineInput.BridgeReceived -> when {
                    !snapshot.isStarted -> ignored(snapshot, IgnoreReason.NotStarted)
                    else -> step(snapshot, input, input.action, changed = false)
                }
                is MachineInput.External -> when {
                    !snapshot.isStarted -> ignored(snapshot, IgnoreReason.NotStarted)
                    else -> step(snapshot, input, input.action, changed = false)
                }
                is MachineInput.TimerFired -> when {
                    !snapshot.isStarted -> ignored(snapshot, IgnoreReason.NotStarted)
                    else -> fire(snapshot, input)
                }
                is MachineInput.CommandResult -> when {
                    !snapshot.isStarted -> ignored(snapshot, IgnoreReason.NotStarted)
                    input.command !in snapshot.commands -> ignored(snapshot, IgnoreReason.StaleCommand)
                    else -> step(snapshot, input, input.action, changed = false)
                }
                is MachineInput.CommandCompleted -> when {
                    !snapshot.isStarted -> ignored(snapshot, IgnoreReason.NotStarted)
                    input.command !in snapshot.commands -> ignored(snapshot, IgnoreReason.StaleCommand)
                    else -> Decision(DecisionOutcome.Handled, snapshot.copy(revision = snapshot.revision + 1, commands = snapshot.commands - input.command))
                }
                is MachineInput.CommandFailed -> when {
                    !snapshot.isStarted -> ignored(snapshot, IgnoreReason.NotStarted)
                    input.command !in snapshot.commands -> ignored(snapshot, IgnoreReason.StaleCommand)
                    else -> step(snapshot.copy(commands = snapshot.commands - input.command), input, CommandFailure(input.command, input.failure), changed = true)
                }
                is MachineInput.CommandAbandoned -> when {
                    !snapshot.isStarted -> ignored(snapshot, IgnoreReason.NotStarted)
                    input.command !in snapshot.commands -> ignored(snapshot, IgnoreReason.StaleCommand)
                    else -> Decision(DecisionOutcome.Handled, snapshot.copy(revision = snapshot.revision + 1, commands = snapshot.commands - input.command))
                }
            }
        } catch (e: Exception) {
            Decision(DecisionOutcome.Failed(FailureDescriptor.of(e), e), snapshot)
        }
    }

    private fun ignored(snapshot: MachineSnapshot<C>, reason: IgnoreReason): Decision<C, CMD, E> =
        Decision(DecisionOutcome.Ignored(reason), snapshot)

    private fun start(snapshot: MachineSnapshot<C>, input: MachineInput.Start): Decision<C, CMD, E> {
        val configuration = chart.initialConfiguration()
        val entered = chart.inEntryOrder(configuration.active)
        return Step(snapshot, input, action = null).apply {
            enter(entered)
        }.decision(configuration, transitions = emptyList())
    }

    /**
     * Steps [base] with [action]. [changed] says that [base] already differs from the snapshot
     * the input was decided on (a deregistered command), so an action no transition takes is
     * still a handled decision.
     */
    private fun step(base: MachineSnapshot<C>, input: MachineInput<A>, action: Action, changed: Boolean): Decision<C, CMD, E> {
        return when (val result = runtime.step(base.configuration, base, action)) {
            StepResult.Ignored -> handle(base, input, action)
                ?: if (changed) Decision(DecisionOutcome.Handled, base.copy(revision = base.revision + 1)) else ignored(base, IgnoreReason.NoTransition)
            is StepResult.Transitioned -> take(base, input, action, result)
        }
    }

    /**
     * Runs the first action handler of the innermost active node that matches [action], if any:
     * the configuration and the activations stay as they are.
     */
    private fun handle(base: MachineSnapshot<C>, input: MachineInput<A>, action: Action): Decision<C, CMD, E>? {
        for (node in chart.inEntryOrder(base.configuration.active).asReversed()) {
            val handler = handlers[node]?.firstOrNull { it.matcher.matches(action) } ?: continue
            val step = Step(base, input, action)
            step.handle(node, handler.rule)
            return step.decision(configuration = base.configuration, transitions = emptyList())
        }
        return null
    }

    private fun fire(snapshot: MachineSnapshot<C>, input: MachineInput.TimerFired): Decision<C, CMD, E> {
        val record = snapshot.timers[input.timer] ?: return ignored(snapshot, IgnoreReason.UnknownTimer)
        val timer = chart.transitions[record.transition.index]
        // The timer is spent whether or not its guard holds: it fires once per scheduling.
        val spent = snapshot.copy(timers = snapshot.timers - input.timer)
        return when (val result = runtime.fire(snapshot.configuration, snapshot, timer)) {
            StepResult.Ignored -> Decision(DecisionOutcome.Handled, spent.copy(revision = spent.revision + 1), timersCancelled = listOf(input.timer))
            is StepResult.Transitioned -> take(spent, input, TimerFired(timer), result, alreadyCancelled = listOf(input.timer))
        }
    }

    private fun take(
        base: MachineSnapshot<C>,
        input: MachineInput<A>,
        action: Action,
        result: StepResult.Transitioned,
        alreadyCancelled: List<TimerId> = emptyList(),
    ): Decision<C, CMD, E> {
        val step = Step(base, input, action)
        step.exit(result.exited)
        for (transition in result.transitions) {
            transition.effect?.let { step.context = effects.getValue(it)(step.context, action) }
        }
        step.enter(result.entered)
        return step.decision(
            configuration = result.configuration,
            transitions = result.transitions.map { TransitionId(chart.transitions.indexOf(it)) },
            alreadyCancelled = alreadyCancelled,
        )
    }

    /**
     * The mutable working set of one decision. Nothing escapes it but the [Decision] it builds.
     */
    private inner class Step(val base: MachineSnapshot<C>, val input: MachineInput<A>, val action: Action?) {
        var context: C = base.context
        var counters: MachineCounters = base.counters
        val exited = mutableListOf<Activation>()
        val entered = mutableListOf<Activation>()
        val commands = mutableListOf<CommandRegistration<CMD>>()
        val timersScheduled = mutableListOf<TimerSchedule>()
        val effects = mutableListOf<EffectEnvelope<E>>()

        fun exit(nodes: List<StateId>) {
            for (node in nodes) {
                val activation = Activation(node, base.activations.getValue(node))
                exited += activation
                for (rule in exits[node].orEmpty()) {
                    val scope = ExitScope(activation)
                    scope.rule()
                    context = scope.context
                }
            }
        }

        fun enter(nodes: List<StateId>) {
            for (node in nodes) {
                val activation = Activation(node, ActivationId(counters.activations + 1))
                counters = counters.copy(activations = activation.id.value)
                entered += activation
                for (rule in entries[node].orEmpty()) {
                    val scope = EnterScope(activation)
                    scope.rule()
                    context = scope.context
                }
                for (transition in timersBySource[node].orEmpty()) {
                    val delay = chart.transitions[transition.index].after!!
                    val timerId = TimerId(counters.timers + 1)
                    counters = counters.copy(timers = timerId.value)
                    timersScheduled += TimerSchedule(timerId, transition, activation.id, input.now + delay)
                }
            }
        }

        fun decision(configuration: StateConfiguration, transitions: List<TransitionId>, alreadyCancelled: List<TimerId> = emptyList()): Decision<C, CMD, E> {
            val exitedIds = exited.map { it.id }.toSet()
            val exitedNodes = exited.map { it.node }.toSet()
            val timersCancelled = alreadyCancelled + base.timers.filter { (_, record) -> record.activation in exitedIds }.keys
            val commandsKept = base.commands.filterValues { it.scope !in exitedIds }
            val snapshot = base.copy(
                revision = base.revision + 1,
                configuration = configuration,
                context = context,
                activations = (base.activations - exitedNodes) + entered.associate { it.node to it.id },
                commands = commandsKept + commands.associate { it.id to CommandRecord(it.scope, it.lane) },
                timers = (base.timers - timersCancelled.toSet()) + timersScheduled.associate { it.id to TimerRecord(it.transition, it.activation, it.deadline) },
                counters = counters,
            )
            return Decision(
                outcome = DecisionOutcome.Handled,
                snapshot = snapshot,
                transitions = transitions,
                exited = exited.toList(),
                entered = entered.toList(),
                commands = commands.toList(),
                cancelledScopes = exited.map { it.id },
                timersScheduled = timersScheduled.toList(),
                timersCancelled = timersCancelled,
                effects = effects.toList(),
            )
        }

        fun handle(node: StateId, rule: MachineActionScope<C, A, CMD, E>.() -> Unit) {
            val scope = ActionScope(Activation(node, base.activations.getValue(node)))
            scope.rule()
            context = scope.context
        }

        /** What every scope shares: the activation it belongs to, the input, the clock, the context. */
        abstract inner class Scope(private val owner: Activation) {
            val node: StateId get() = owner.node
            val activation: ActivationId get() = owner.id
            val input: MachineInput<A> get() = this@Step.input
            val now: MachineTime get() = this@Step.input.now
            var context: C = this@Step.context

            fun event(event: E): EffectId = emit(event)

            protected fun register(command: CMD, lane: LaneId?, policy: ConcurrencyPolicy?): CommandId {
                val id = CommandId(counters.commands + 1)
                counters = counters.copy(commands = id.value)
                commands += CommandRegistration(id, command, owner.id, lane, policy)
                return id
            }
        }

        inner class EnterScope(owner: Activation) : Scope(owner), MachineEnterScope<C, A, CMD, E> {
            override val action: Action? get() = this@Step.action

            override fun command(command: CMD): CommandId = register(command, lane = null, policy = null)

            override fun command(command: CMD, lane: LaneId, policy: ConcurrencyPolicy): CommandId = register(command, lane, policy)
        }

        inner class ActionScope(owner: Activation) : Scope(owner), MachineActionScope<C, A, CMD, E> {
            override val action: Action get() = checkNotNull(this@Step.action)

            override fun command(command: CMD): CommandId = register(command, lane = null, policy = null)

            override fun command(command: CMD, lane: LaneId, policy: ConcurrencyPolicy): CommandId = register(command, lane, policy)
        }

        inner class ExitScope(owner: Activation) : Scope(owner), MachineExitScope<C, A, E> {
            override val action: Action? get() = this@Step.action
        }

        private fun emit(event: E): EffectId {
            val id = EffectId(counters.effects + 1)
            counters = counters.copy(effects = id.value)
            effects += EffectEnvelope(id, event)
            return id
        }
    }
}

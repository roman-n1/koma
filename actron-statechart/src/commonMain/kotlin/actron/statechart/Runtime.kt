package actron.statechart

import actron.core.Action
import actron.core.ExperimentalActronApi
import actron.core.State
import actron.observability.FailureDescriptor

/** Observed guard decision; unconditional transitions do not invoke an application guard. */
sealed interface GuardCheck {
    val allows: Boolean

    data object Unconditional : GuardCheck {
        override val allows: Boolean = true
        override fun toString(): String = "always"
    }
    data object Allowed : GuardCheck {
        override val allows: Boolean = true
        override fun toString(): String = "true"
    }
    data object Rejected : GuardCheck {
        override val allows: Boolean = false
        override fun toString(): String = "false"
    }
    data class Failed(val failure: FailureDescriptor) : GuardCheck {
        override val allows: Boolean = false
        override fun toString(): String = "failed"
    }
}

/**
 * Outcome of [StateChartRuntime.step] and [StateChartRuntime.fire].
 */
sealed interface StepResult {
    /**
     * [transitions] were taken: [exited] were left, then [entered] were entered, and the chart is
     * now in [configuration].
     *
     * A node in both [exited] and [entered] was left and entered again, for example the source of
     * a self-loop, or a compound state whose transition targets itself or one of its descendants.
     *
     * @property transitions The transitions taken, in selection order; never empty. Without
     * parallel states it is exactly one transition; with them, at most one per active region.
     * @property exited Nodes left, innermost first
     * @property entered Nodes entered, outermost first. Only real states: a transition into a
     * [HistoryState] lists the states it restored, never the history state itself.
     * @property configuration The configuration after the step
     * @property timersToStart The timers ([Trigger.After] transitions) of the [entered] states, in
     * the order of [entered], each state's in declaration order: the caller starts each of them now
     * and calls [StateChartRuntime.fire] when its delay has passed
     * @property timersToCancel The timers of the [exited] states, in the order of [exited], each
     * state's in declaration order: the caller cancels each of them, before starting
     * [timersToStart]. A timer in both lists belongs to a state that was left and entered again,
     * so it restarts. When the step was a timer firing, that timer is here too, since its source
     * is always exited.
     */
    data class Transitioned(
        val transitions: List<Transition>,
        val exited: List<StateId>,
        val entered: List<StateId>,
        val configuration: StateConfiguration,
        val timersToStart: List<Transition> = emptyList(),
        val timersToCancel: List<Transition> = emptyList(),
    ) : StepResult {
        init {
            require(transitions.isNotEmpty()) { "[Actron] Transitioned needs at least one transition" }
        }

        /**
         * The result of taking [transition] in a flat chart (one without compound states):
         * [Transition.source] is exited, [Transition.target] is entered and becomes the only
         * active node. It equals what [StateChartRuntime.step] returns for such a chart when the
         * chart has no timers; no timers are started or cancelled.
         */
        constructor(transition: Transition) : this(
            transitions = listOf(transition),
            exited = listOf(transition.source),
            entered = listOf(transition.target),
            configuration = StateConfiguration(active = setOf(transition.target)),
        )

        /**
         * The first transition taken; the only one without parallel states.
         */
        val transition: Transition get() = transitions.first()

        /**
         * The target of [transition]. In a flat chart this is the state the chart is in after the
         * step. When it is a compound or history state, the new active leaf is the last node of
         * [entered].
         */
        val target: StateId get() = transition.target
    }

    /**
     * No transition was taken, because none leaves the active nodes for this action or every
     * matching transition's guard was false, or, for [StateChartRuntime.fire], because the timer's
     * source is no longer active or its guard was false. The chart stays in the same
     * configuration, and no timer is started or cancelled.
     */
    data object Ignored : StepResult
}

/**
 * The action that guards of a timer receive when [timer] fires (see [StateChartRuntime.fire]).
 *
 * A guard has the signature `(state, action)`. A timer has no action, so the runtime passes this
 * one: a guard used by timers can read the state as usual, and one shared with action transitions
 * can tell the cases apart with `action is TimerFired`.
 *
 * It is not dispatched anywhere. Passed to [StateChartRuntime.step], it is an ordinary action that
 * fires only transitions whose matcher matches it; timers fire only through
 * [StateChartRuntime.fire].
 *
 * @property timer The timer that fired
 */
data class TimerFired(val timer: Transition) : Action

/**
 * Runs a [StateChartDefinition] one action at a time over Actron states of type [S].
 *
 * [step] is a pure function of the current configuration, the state and the action: it does not
 * keep state, launch work or call Actron. Holding the configuration is the caller's job, for example
 * a Actron Store. For a chart whose Actron state determines its configuration (one active leaf, so no
 * [ParallelState] and no [HistoryState]), the shorter `step(state, action)` derives it from
 * `stateIdOf(state)`.
 *
 * The chart node of a state is `stateIdOf(state)`, the active leaf. There is no default mapping:
 * class names are not stable under code shrinking, so the caller states it explicitly, for example
 * with a `when` over a sealed state hierarchy.
 *
 * Transition choice: from the active leaf, the first transition in declaration order that matches
 * the action and whose guard is absent or true is taken; when the leaf has none, its parent is
 * searched the same way, then the grandparent, outwards. An inner transition thus takes priority
 * over an outer one, as in Harel statecharts and SCXML, and in a flat chart this is Actron's own
 * first-match rule for handlers. [validate] reports cases where the order decides between
 * unguarded transitions of one state.
 *
 * Parallel states: every active leaf (one per active region) chooses a transition that way, leaves
 * in declaration order, and all the choices are taken together in one step unless they conflict,
 * that is unless their exit sets meet. On a conflict the transition whose source is a proper
 * descendant of the other's wins; otherwise the one chosen first wins. So transitions in different
 * regions fire together, and a transition of the parallel state or of an ancestor, chosen by
 * several leaves, fires once and exits every region.
 *
 * Exit and entry: a transition exits every active descendant of its domain, the innermost compound
 * state that is a proper ancestor of both source and target (the implicit root when there is
 * none), innermost first, ties in reverse declaration order. Then it enters the target's
 * ancestors below the domain, the target and, while the entered node is compound, its initial
 * child, outermost first, ties in declaration order. Entering a parallel state, as a target or as
 * an ancestor of one, enters each of its other regions through its initial states too. A
 * self-loop therefore exits and re-enters its source, and a transition from one region of a
 * parallel state into another exits and re-enters the whole parallel state.
 *
 * History: right before a compound or parallel state with [HistoryState] children is exited, each
 * of them records its active children (shallow) or active atomic descendants (deep) in
 * [StateConfiguration.history]. A transition into a history state enters what it recorded, or its
 * default, or its parent's initial child, and never enters the history state itself. The history
 * lives in the configuration, so charts with history states need the `step` overload that takes
 * the configuration; `step(state, action)` starts from a configuration with nothing recorded.
 *
 * Timers: a [Trigger.After] transition never fires for an action. It is a timer that starts when
 * its source is entered and is cancelled when its source is exited; the runtime keeps no clock and
 * only reports which timers to start and cancel: [initialTimers] for the initial configuration,
 * then [StepResult.Transitioned.timersToStart] and [StepResult.Transitioned.timersToCancel] for
 * each step. When a timer's delay has passed, the caller calls [fire], which takes the timer alone,
 * with the same exit and entry rules as an action step, if its source is still active and its guard
 * holds for [TimerFired]. Every transition is external, so a timer's source is exited when it
 * fires, and a self-loop timer is cancelled and started again: it fires periodically.
 *
 * @param definition The chart to run
 * @param stateIdOf Maps a state to its active leaf in [definition]
 * @param guards Guard implementations by label. A guard receives the current state and the action,
 * so it can read state data such as a retry counter; a guard of a timer receives [TimerFired]
 * as the action (see [fire]). Every guard label used in [definition] must be present.
 * @throws IllegalArgumentException if a guard label used by a transition has no implementation, or
 * if the hierarchy is malformed: an unknown, atomic or history parent, a parent cycle, a compound
 * state without children or whose initial state is not its child, a history state as an initial
 * state, or an invalid history default (see [validate])
 */
class StateChartRuntime<S : State>(
    val definition: StateChartDefinition,
    private val stateIdOf: (S) -> StateId,
    private val guards: Map<String, (S, Action) -> Boolean> = emptyMap(),
) {
    init {
        require(definition.transitions.none { definition.nodeSatisfies(it.source) { node -> node is FinalState } }) { "[Actron] Final states cannot have outgoing transitions" }
        require(definition.transitions.filter { it.trigger == Trigger.Completion }.all {
            definition.nodeSatisfies(it.source) { node -> node is CompoundState || node is ParallelState }
        }) { "[Actron] Completion transitions need a compound or parallel source" }
        val missing = buildList { definition.transitions.forEach { it.guard.withLabel { add(it) } } }.distinct().filter { it !in guards }
        require(missing.isEmpty()) { "[Actron] Missing guard implementations: ${missing.joinToString()}" }
        val malformed = definition.hierarchyIssues() + definition.historyIssues()
        require(malformed.isEmpty()) { "[Actron] Malformed state hierarchy: ${malformed.joinToString()}" }
    }

    /**
     * Returns the configuration the chart starts in: [StateChartDefinition.initial], its
     * ancestors and, when it is compound, its initial descendants.
     */
    fun initialConfiguration(): StateConfiguration = definition.initialConfiguration()

    /**
     * Returns the timers to start with [initialConfiguration]: the [Trigger.After] transitions of
     * its states, outermost first, each state's in declaration order.
     */
    fun initialTimers(): List<Transition> = activeTimers(initialConfiguration())

    /**
     * Returns the timers that run while the chart is in [configuration]: the [Trigger.After]
     * transitions whose source is active, outermost first (ties in declaration order), each
     * state's in declaration order. Use it to start timers for a configuration restored from
     * storage; the delays then start over.
     */
    fun activeTimers(configuration: StateConfiguration): List<Transition> = definition.timersOf(definition.inEntryOrder(configuration.active))

    /**
     * Returns the step taken from [configuration] for [action], or [StepResult.Ignored].
     *
     * [state] is only passed to guards. [configuration] must come from [initialConfiguration], an
     * earlier step or [StateChartDefinition.configurationOf].
     */
    fun step(configuration: StateConfiguration, state: S, action: Action): StepResult =
        stepObserved(configuration, state, action) { _, _ -> }

    internal fun stepObserved(
        configuration: StateConfiguration, state: S, action: Action,
        observe: (Transition, GuardCheck) -> Unit,
    ): StepResult {
        val taken = definition.selectTransitions(configuration) { transition ->
            transition.matchesAction(action) && enabled(transition, state, action, observe)
        }
        if (taken.isEmpty()) return StepResult.Ignored
        return transitioned(configuration, taken)
    }

    /**
     * Returns the step taken from [configuration] when [timer] fires, or [StepResult.Ignored].
     *
     * Call it when [timer] has run for its delay since it was started (see [initialTimers] and
     * [StepResult.Transitioned.timersToStart]) and has not been cancelled. When the timer's source
     * is not active in [configuration] (a stale timer the caller failed to cancel) or its guard is
     * false for `(state, TimerFired(timer))`, nothing happens. Otherwise [timer] is taken alone,
     * with the same exit, history and entry rules as [step]; no other transition fires with it,
     * in any region.
     *
     * @throws IllegalArgumentException if [timer] is not a [Trigger.After] transition of [definition]
     */
    fun fire(configuration: StateConfiguration, state: S, timer: Transition): StepResult =
        fireObserved(configuration, state, timer) { _, _ -> }

    internal fun fireObserved(
        configuration: StateConfiguration, state: S, timer: Transition,
        observe: (Transition, GuardCheck) -> Unit,
    ): StepResult {
        require(timer.isTimer) { "[Actron] Not a timer: $timer" }
        require(timer in definition.transitions) { "[Actron] Timer is not declared in the chart: $timer" }
        if (timer.source !in configuration.active) return StepResult.Ignored
        if (!enabled(timer, state, TimerFired(timer), observe)) return StepResult.Ignored
        return transitioned(configuration, listOf(timer))
    }

    /** One automatic microstep: eventless transitions first, then completion transitions. */
    fun automaticStep(configuration: StateConfiguration, state: S): StepResult = automaticObserved(configuration, state) { _, _ -> }

    internal fun automaticObserved(configuration: StateConfiguration, state: S, observe: (Transition, GuardCheck) -> Unit): StepResult {
        for (completion in listOf(false, true)) {
            val trigger = if (completion) Trigger.Completion else Trigger.Eventless
            val action = AutomaticTransition(completion)
            val taken = definition.selectTransitions(configuration) { transition ->
                transition.trigger == trigger && (!completion || definition.isComplete(configuration, transition.source)) &&
                    enabled(transition, state, action, observe)
            }
            if (taken.isNotEmpty()) return transitioned(configuration, taken)
        }
        return StepResult.Ignored
    }

    private fun enabled(
        transition: Transition, state: S, action: Action,
        observe: (Transition, GuardCheck) -> Unit,
    ): Boolean {
        val result = try {
            var checked: GuardCheck = GuardCheck.Unconditional
            transition.guard.withLabel { checked = if (guards.getValue(it)(state, action)) GuardCheck.Allowed else GuardCheck.Rejected }
            checked
        } catch (error: Exception) {
            observe(transition, GuardCheck.Failed(FailureDescriptor.of(error)))
            throw error
        }
        observe(transition, result)
        return result.allows
    }

    private fun transitioned(configuration: StateConfiguration, taken: List<Transition>): StepResult.Transitioned {
        val microstep = definition.microstep(configuration, taken)
        return StepResult.Transitioned(
            transitions = taken,
            exited = microstep.exited,
            entered = microstep.entered,
            configuration = microstep.configuration,
            timersToStart = definition.timersOf(microstep.entered),
            timersToCancel = definition.timersOf(microstep.exited),
        )
    }

    /**
     * Returns the step taken from the configuration whose active leaf is `stateIdOf(state)` (see
     * [StateChartDefinition.configurationOf]) for [action], or [StepResult.Ignored].
     *
     * That configuration remembers no history, so a transition into a [HistoryState] enters its
     * default; keep the configuration and use the other overload to restore history. One leaf
     * cannot describe the regions of a [ParallelState] either, so charts with parallel states need
     * the other overload too.
     */
    fun step(state: S, action: Action): StepResult = step(definition.configurationOf(stateIdOf(state)), state, action)
}

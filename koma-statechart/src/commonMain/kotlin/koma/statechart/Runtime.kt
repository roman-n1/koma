package koma.statechart

import koma.core.Action
import koma.core.ExperimentalKomaApi
import koma.core.State

/**
 * Outcome of [StateChartRuntime.step].
 */
@ExperimentalKomaApi
sealed interface StepResult {
    /**
     * [transitions] were taken: [exited] were left, then [entered] were entered, and the chart is
     * now in [configuration].
     *
     * A node in both [exited] and [entered] was left and entered again, for example the source of
     * a self-loop, or a compound state whose transition targets itself or one of its descendants.
     *
     * @property transitions The transitions taken, in selection order; never empty. Without
     * parallel states it is exactly one transition.
     * @property exited Nodes left, innermost first
     * @property entered Nodes entered, outermost first
     * @property configuration The configuration after the step
     */
    data class Transitioned(
        val transitions: List<Transition>,
        val exited: List<StateId>,
        val entered: List<StateId>,
        val configuration: StateConfiguration,
    ) : StepResult {
        init {
            require(transitions.isNotEmpty()) { "[Koma] Transitioned needs at least one transition" }
        }

        /**
         * The result of taking [transition] in a flat chart (one without compound states):
         * [Transition.source] is exited, [Transition.target] is entered and becomes the only
         * active node. It equals what [StateChartRuntime.step] returns for such a chart.
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
         * step. When it is a compound state, the new active leaf is the last node of [entered].
         */
        val target: StateId get() = transition.target
    }

    /**
     * No transition was taken, because none leaves the active nodes for this action or every
     * matching transition's guard was false. The chart stays in the same configuration.
     */
    data object Ignored : StepResult
}

/**
 * Runs a [StateChartDefinition] one action at a time over Koma states of type [S].
 *
 * [step] is a pure function of the current configuration, the state and the action: it does not
 * keep state, launch work or call Koma. Holding the configuration is the caller's job, for example
 * a Koma Store. For a chart whose Koma state determines its configuration (one active leaf), the
 * shorter `step(state, action)` derives it from `stateIdOf(state)`.
 *
 * The chart node of a state is `stateIdOf(state)`, the active leaf. There is no default mapping:
 * class names are not stable under code shrinking, so the caller states it explicitly, for example
 * with a `when` over a sealed state hierarchy.
 *
 * Transition choice: from the active leaf, the first transition in declaration order that matches
 * the action and whose guard is absent or true is taken; when the leaf has none, its parent is
 * searched the same way, then the grandparent, outwards. An inner transition thus takes priority
 * over an outer one, as in Harel statecharts and SCXML, and in a flat chart this is Koma's own
 * first-match rule for handlers. [validate] reports cases where the order decides between
 * unguarded transitions of one state.
 *
 * Exit and entry: a transition exits every active descendant of its domain, the innermost compound
 * state that is a proper ancestor of both source and target (the implicit root when there is
 * none), innermost first. Then it enters the target's ancestors below the domain, the target and,
 * while the entered node is compound, its initial child, outermost first. A self-loop therefore
 * exits and re-enters its source.
 *
 * @param definition The chart to run
 * @param stateIdOf Maps a state to its active leaf in [definition]
 * @param guards Guard implementations by label. A guard receives the current state and the action,
 * so it can read state data such as a retry counter. Every guard label used in [definition] must
 * be present.
 * @throws IllegalArgumentException if a guard label used by a transition has no implementation, or
 * if the hierarchy is malformed: an unknown or atomic parent, a parent cycle, or a compound state
 * without children or whose initial state is not its child (see [validate])
 */
@ExperimentalKomaApi
class StateChartRuntime<S : State>(
    val definition: StateChartDefinition,
    private val stateIdOf: (S) -> StateId,
    private val guards: Map<String, (S, Action) -> Boolean> = emptyMap(),
) {
    init {
        val missing = definition.transitions.mapNotNull { it.guard }.distinct().filter { it !in guards }
        require(missing.isEmpty()) { "[Koma] Missing guard implementations: ${missing.joinToString()}" }
        val malformed = definition.hierarchyIssues()
        require(malformed.isEmpty()) { "[Koma] Malformed state hierarchy: ${malformed.joinToString()}" }
    }

    /**
     * Returns the configuration the chart starts in: [StateChartDefinition.initial], its
     * ancestors and, when it is compound, its initial descendants.
     */
    fun initialConfiguration(): StateConfiguration = definition.initialConfiguration()

    /**
     * Returns the step taken from [configuration] for [action], or [StepResult.Ignored].
     *
     * [state] is only passed to guards. [configuration] must come from [initialConfiguration], an
     * earlier step or [StateChartDefinition.configurationOf].
     */
    fun step(configuration: StateConfiguration, state: S, action: Action): StepResult {
        val taken = definition.selectTransitions(configuration) { transition ->
            transition.on.matches(action) && transition.guard.let { it == null || guards.getValue(it)(state, action) }
        }
        if (taken.isEmpty()) return StepResult.Ignored
        val microstep = definition.microstep(configuration, taken)
        return StepResult.Transitioned(taken, microstep.exited, microstep.entered, microstep.configuration)
    }

    /**
     * Returns the step taken from the configuration whose active leaf is `stateIdOf(state)` (see
     * [StateChartDefinition.configurationOf]) for [action], or [StepResult.Ignored].
     */
    fun step(state: S, action: Action): StepResult = step(definition.configurationOf(stateIdOf(state)), state, action)
}

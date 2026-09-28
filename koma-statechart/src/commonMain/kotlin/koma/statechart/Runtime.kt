package koma.statechart

import koma.core.Action
import koma.core.ExperimentalKomaApi

/**
 * Outcome of [StateChartRuntime.step].
 */
@ExperimentalKomaApi
sealed interface StepResult {
    /**
     * [transition] was taken; the chart is now in [transition]'s target.
     */
    data class Transitioned(val transition: Transition) : StepResult {
        /**
         * The state the chart is in after the step.
         */
        val target: StateId get() = transition.target
    }

    /**
     * No transition was taken, because none leaves the state for this action or every matching
     * transition's guard was false. The chart stays in the same state.
     */
    data object Ignored : StepResult
}

/**
 * Runs a flat [StateChartDefinition] one action at a time.
 *
 * [step] is a pure function of the current state and the action: it does not keep state, launch
 * work or call Koma. Holding the current state is the caller's job, for example a Koma Store.
 *
 * Transition choice: among transitions that leave the current state and match the action, the
 * first one in declaration order whose guard is absent or true is taken. This mirrors Koma's own
 * first-match rule for handlers. [validate] reports cases where the order decides between
 * unguarded transitions.
 *
 * @param definition The chart to run
 * @param guards Guard implementations by label; every guard label used in [definition] must be
 * present
 * @throws IllegalArgumentException if a guard label used by a transition has no implementation
 */
@ExperimentalKomaApi
class StateChartRuntime(
    val definition: StateChartDefinition,
    private val guards: Map<String, (Action) -> Boolean> = emptyMap(),
) {
    init {
        val missing = definition.transitions.mapNotNull { it.guard }.distinct().filter { it !in guards }
        require(missing.isEmpty()) { "[Koma] Missing guard implementations: ${missing.joinToString()}" }
    }

    /**
     * Returns the transition taken from [current] for [action], or [StepResult.Ignored].
     */
    fun step(current: StateId, action: Action): StepResult {
        val taken = definition.transitionsFrom(current).firstOrNull { transition ->
            transition.on.matches(action) && transition.guard.let { it == null || guards.getValue(it)(action) }
        }
        return if (taken == null) StepResult.Ignored else StepResult.Transitioned(taken)
    }
}

/**
 * Whether [action] matches this matcher: by type when [ActionMatcher.type] is set, otherwise by
 * the action's simple class name.
 */
@ExperimentalKomaApi
fun ActionMatcher.matches(action: Action): Boolean {
    val type = type
    return if (type != null) type.isInstance(action) else action::class.simpleName == name
}

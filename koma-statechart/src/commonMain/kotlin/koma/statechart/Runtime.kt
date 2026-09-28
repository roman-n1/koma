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
 * Runs a flat [StateChartDefinition] one action at a time over Koma states of type [S].
 *
 * [step] is a pure function of the current state and the action: it does not keep state, launch
 * work or call Koma. Holding the current state is the caller's job, for example a Koma Store.
 *
 * The chart node of a state is `stateIdOf(state)`. There is no default mapping: class names are not
 * stable under code shrinking, so the caller states it explicitly, for example with a `when` over a
 * sealed state hierarchy.
 *
 * Transition choice: among transitions that leave the current node and match the action, the
 * first one in declaration order whose guard is absent or true is taken. This mirrors Koma's own
 * first-match rule for handlers. [validate] reports cases where the order decides between
 * unguarded transitions.
 *
 * @param definition The chart to run
 * @param stateIdOf Maps a state to its node in [definition]
 * @param guards Guard implementations by label. A guard receives the current state and the action,
 * so it can read state data such as a retry counter. Every guard label used in [definition] must
 * be present.
 * @throws IllegalArgumentException if a guard label used by a transition has no implementation
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
    }

    /**
     * Returns the transition taken from `stateIdOf(state)` for [action], or [StepResult.Ignored].
     */
    fun step(state: S, action: Action): StepResult {
        val current = stateIdOf(state)
        val taken = definition.transitionsFrom(current).firstOrNull { transition ->
            transition.on.matches(action) && transition.guard.let { it == null || guards.getValue(it)(state, action) }
        }
        return if (taken == null) StepResult.Ignored else StepResult.Transitioned(taken)
    }
}

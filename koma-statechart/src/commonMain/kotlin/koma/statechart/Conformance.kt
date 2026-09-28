package koma.statechart

import koma.core.Action
import koma.core.Event
import koma.core.ExperimentalKomaApi
import koma.core.Plugin
import koma.core.PluginScope
import koma.core.State

/**
 * A place where a running Store did something its [StateChartDefinition] does not declare.
 */
@ExperimentalKomaApi
sealed interface ConformanceViolation {
    /**
     * The Store started in [actual], but the chart starts in [expected].
     */
    data class UnexpectedInitialState(val expected: StateId, val actual: StateId) : ConformanceViolation

    /**
     * The Store entered [id], which the chart does not declare.
     */
    data class UndeclaredState(val id: StateId) : ConformanceViolation

    /**
     * The Store moved from [from] to [to], but the chart has no transition between them.
     *
     * @property lastAction The most recent action seen before the change, if any. It is a hint,
     * not proof: a change made from `launch {}` or a chained `enter {}` may come after an
     * unrelated action.
     */
    data class UndeclaredTransition(val from: StateId, val to: StateId, val lastAction: Action?) : ConformanceViolation
}

/**
 * A [Plugin] that checks a running Store against a [StateChartDefinition] and records which
 * declared transitions the Store actually took.
 *
 * It only observes, through the public plugin hooks, so it works with an unmodified Koma Store
 * and does not change how the Store behaves. Register it in tests, or in debug builds, with
 * `plugin(conformance)` or `koma-test`'s `patch { plugin(conformance) }`.
 *
 * Each committed state is mapped to a [StateId] with [stateIdOf]; by default the state's simple
 * class name, which matches Koma's rule that a state variant is a class. A change that keeps the
 * same [StateId] (for example a data update inside one variant) is not a transition.
 *
 * Plugin hooks do not say which action caused a state change. So a change counts as covering
 * a declared transition from the old to the new state, preferring one triggered by the most
 * recent action when there is such a transition.
 *
 * @param definition The chart the Store should follow
 * @param stateIdOf Maps a Koma state to its chart state
 */
@ExperimentalKomaApi
class StateChartConformance<S : State, A : Action, E : Event>(
    val definition: StateChartDefinition,
    private val stateIdOf: (S) -> StateId = { defaultStateId(it) },
) : Plugin<S, A, E> {
    private val declaredStates = definition.states.map { it.id }.toSet()
    private val recordedViolations = mutableListOf<ConformanceViolation>()
    private val recordedCovered = linkedSetOf<Transition>()
    private var lastAction: A? = null

    /**
     * Violations seen so far, in the order they happened.
     */
    val violations: List<ConformanceViolation> get() = recordedViolations.toList()

    /**
     * Declared transitions the Store took at least once, in the order first taken.
     */
    val coveredTransitions: Set<Transition> get() = recordedCovered.toSet()

    /**
     * Declared transitions the Store has not taken yet, in declaration order.
     */
    val uncoveredTransitions: List<Transition> get() = definition.transitions.filter { it !in recordedCovered }

    override suspend fun onStart(scope: PluginScope<S, A>, state: S) {
        val id = stateIdOf(state)
        checkDeclared(id)
        if (id != definition.initial) {
            recordedViolations += ConformanceViolation.UnexpectedInitialState(expected = definition.initial, actual = id)
        }
    }

    override suspend fun onAction(scope: PluginScope<S, A>, state: S, action: A) {
        lastAction = action
    }

    override suspend fun onState(scope: PluginScope<S, A>, prevState: S, state: S) {
        val from = stateIdOf(prevState)
        val to = stateIdOf(state)
        if (from == to) return
        checkDeclared(to)

        val candidates = definition.transitionsFrom(from).filter { it.target == to }
        val action = lastAction
        val taken = candidates.firstOrNull { action != null && it.on.matches(action) } ?: candidates.firstOrNull()
        if (taken == null) {
            recordedViolations += ConformanceViolation.UndeclaredTransition(from = from, to = to, lastAction = action)
        } else {
            recordedCovered += taken
        }
    }

    private fun checkDeclared(id: StateId) {
        if (id !in declaredStates) recordedViolations += ConformanceViolation.UndeclaredState(id)
    }
}

/**
 * The default chart state of a Koma state: its simple class name.
 *
 * @throws IllegalArgumentException if the state's class has no simple name, such as an
 * anonymous object
 */
@ExperimentalKomaApi
fun defaultStateId(state: State): StateId {
    val name = requireNotNull(state::class.simpleName) { "[Koma] State type must have a simple name: $state" }
    return StateId(name)
}

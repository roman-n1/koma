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

    /**
     * The Store moved from [from] to [to], and the chart declares transitions between them, but
     * none of them is triggered by [action], the action the change is attributed to.
     *
     * This also catches a change made from `launch {}` that completes after an unrelated action:
     * the plugin cannot tell which action launched the work, so the chart has to declare the
     * transition for the action that is most recent when the change is committed.
     *
     * @property action The most recent action, not yet used for another state change
     */
    data class UnexpectedTrigger(val from: StateId, val to: StateId, val action: Action) : ConformanceViolation
}

/**
 * A [Plugin] that checks a running Store against a [StateChartDefinition] and records which
 * declared transitions the Store actually took.
 *
 * It only observes, through the public plugin hooks, so it works with an unmodified Koma Store
 * and does not change how the Store behaves. Register it in tests, or in debug builds, with
 * `plugin(conformance)` or `koma-test`'s `patch { plugin(conformance) }`.
 *
 * Each committed state is mapped to a [StateId] with [stateIdOf]. The mapping is explicit because
 * class names are not stable under code shrinking; a `when` over a sealed state hierarchy is the
 * usual choice. A change that keeps the same [StateId] (for example a data update inside one
 * variant) is not a transition.
 *
 * Start: the Store may start in any declared state, not only [StateChartDefinition.initial],
 * because a [koma.core.StateSaver] or `patch { initialState(...) }` may provide the first state.
 * Only an undeclared start state is reported, as [ConformanceViolation.UndeclaredState].
 *
 * Attribution: plugin hooks do not say which action caused a state change. A change is attributed
 * to the most recent action that has not been used for an earlier change (its *trigger*):
 * - no transition from the old to the new state: [ConformanceViolation.UndeclaredTransition];
 * - a declared transition between them matches the trigger: the first such one is covered;
 * - transitions between them exist but none matches the trigger:
 *   [ConformanceViolation.UnexpectedTrigger], and nothing is covered;
 * - there is no trigger, because no action has arrived yet (`enter {}` at startup) or the last
 *   action already caused a change (a chained `enter {}` after it): the change is automatic and
 *   the first declared transition between the states is covered, whatever its matcher.
 *
 * Self-transitions: Koma does not notify plugins when a handler keeps the state, so a self-loop
 * is credited by action. When an action arrives, if the first transition (in declaration order,
 * guards ignored) that leaves the current state and matches it is a self-loop, that self-loop is
 * held as pending. If a change of [StateId] is committed before the next action, the pending
 * self-loop is dropped and the change is attributed as above. Otherwise it is covered: it is
 * committed when the next action arrives, and [coveredTransitions] and [uncoveredTransitions]
 * already count it.
 *
 * @param definition The chart the Store should follow
 * @param stateIdOf Maps a Koma state to its chart state
 */
@ExperimentalKomaApi
class StateChartConformance<S : State, A : Action, E : Event>(
    val definition: StateChartDefinition,
    private val stateIdOf: (S) -> StateId,
) : Plugin<S, A, E> {
    private val declaredStates = definition.states.map { it.id }.toSet()
    private val recordedViolations = mutableListOf<ConformanceViolation>()
    private val recordedCovered = linkedSetOf<Transition>()
    private var lastAction: A? = null
    private var trigger: A? = null
    private var pendingSelfLoop: Transition? = null

    /**
     * Violations seen so far, in the order they happened.
     */
    val violations: List<ConformanceViolation> get() = recordedViolations.toList()

    /**
     * Declared transitions the Store took at least once, in the order first taken, including a
     * pending self-loop (see the class documentation).
     */
    val coveredTransitions: Set<Transition> get() = (recordedCovered + listOfNotNull(pendingSelfLoop)).toSet()

    /**
     * Declared transitions the Store has not taken yet, in declaration order.
     */
    val uncoveredTransitions: List<Transition> get() = coveredTransitions.let { covered -> definition.transitions.filter { it !in covered } }

    override suspend fun onStart(scope: PluginScope<S, A>, state: S) {
        checkDeclared(stateIdOf(state))
    }

    override suspend fun onAction(scope: PluginScope<S, A>, state: S, action: A) {
        pendingSelfLoop?.let { recordedCovered += it }
        lastAction = action
        trigger = action
        val current = stateIdOf(state)
        pendingSelfLoop = definition.transitionsFrom(current).firstOrNull { it.on.matches(action) }?.takeIf { it.target == current }
    }

    override suspend fun onState(scope: PluginScope<S, A>, prevState: S, state: S) {
        val from = stateIdOf(prevState)
        val to = stateIdOf(state)
        if (from == to) return
        pendingSelfLoop = null
        checkDeclared(to)

        val candidates = definition.transitionsFrom(from).filter { it.target == to }
        val action = trigger
        trigger = null
        val taken = if (action == null) candidates.firstOrNull() else candidates.firstOrNull { it.on.matches(action) }
        when {
            taken != null -> recordedCovered += taken
            candidates.isEmpty() -> recordedViolations += ConformanceViolation.UndeclaredTransition(from = from, to = to, lastAction = lastAction)
            action != null -> recordedViolations += ConformanceViolation.UnexpectedTrigger(from = from, to = to, action = action)
        }
    }

    private fun checkDeclared(id: StateId) {
        if (id !in declaredStates) recordedViolations += ConformanceViolation.UndeclaredState(id)
    }
}

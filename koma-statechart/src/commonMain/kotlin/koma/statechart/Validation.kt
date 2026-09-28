package koma.statechart

import koma.core.Action
import koma.core.ExperimentalKomaApi

/**
 * A structural problem found by [validate].
 */
@ExperimentalKomaApi
sealed interface ValidationIssue {
    /**
     * Two or more states use the same [id].
     */
    data class DuplicateStateId(val id: StateId) : ValidationIssue

    /**
     * The initial state [id] is not declared.
     */
    data class UnknownInitialState(val id: StateId) : ValidationIssue

    /**
     * [transition] leaves a state that is not declared.
     */
    data class UnknownTransitionSource(val transition: Transition) : ValidationIssue

    /**
     * [transition] enters a state that is not declared.
     */
    data class UnknownTransitionTarget(val transition: Transition) : ValidationIssue

    /**
     * State [id] cannot be reached from the initial state.
     */
    data class UnreachableState(val id: StateId) : ValidationIssue

    /**
     * More than one transition without a guard leaves [source] on the same action, so the choice
     * between them depends on declaration order.
     */
    data class AmbiguousTransitions(
        val source: StateId,
        val on: ActionMatcher,
        val transitions: List<Transition>,
    ) : ValidationIssue

    /**
     * More than one transition without a guard leaves [source] and matches the [sample] action,
     * through different matchers, for example a supertype matcher declared before a subtype one.
     * The runtime always takes the first of [transitions], so the others never fire for actions
     * like [sample].
     *
     * Only reported for samples passed to [validate], because common code cannot ask whether one
     * action type is a subtype of another.
     *
     * @property transitions The matching unguarded transitions, in declaration order
     */
    data class ShadowedTransitions(
        val source: StateId,
        val sample: Action,
        val transitions: List<Transition>,
    ) : ValidationIssue
}

/**
 * Checks the structure of this definition and returns every issue found, or an empty list.
 *
 * Issues are reported in a stable order: duplicates, initial state, transition endpoints,
 * reachability, ambiguity, shadowing.
 *
 * Transitions with equal matchers are always checked ([ValidationIssue.AmbiguousTransitions]).
 * Overlap between different matchers, such as a matcher for a sealed parent type and one for its
 * subtype, can only be found by trying real actions: for every source state and every action in
 * [sampleActions], if two or more unguarded transitions from that source match the action (see
 * [ActionMatcher.matches]) and they do not all share one matcher, a
 * [ValidationIssue.ShadowedTransitions] is reported. Groups with a single matcher are already
 * covered by [ValidationIssue.AmbiguousTransitions] and are not reported again.
 *
 * @param sampleActions Actions to probe transitions with, typically one instance of every action
 * type the chart uses; duplicates are ignored
 */
@ExperimentalKomaApi
fun StateChartDefinition.validate(sampleActions: List<Action> = emptyList()): List<ValidationIssue> {
    val issues = mutableListOf<ValidationIssue>()
    val ids = states.map { it.id }
    val declared = ids.toSet()

    ids.groupingBy { it }.eachCount()
        .filterValues { it > 1 }
        .keys
        .forEach { issues += ValidationIssue.DuplicateStateId(it) }

    if (initial !in declared) {
        issues += ValidationIssue.UnknownInitialState(initial)
    }

    for (transition in transitions) {
        if (transition.source !in declared) issues += ValidationIssue.UnknownTransitionSource(transition)
        if (transition.target !in declared) issues += ValidationIssue.UnknownTransitionTarget(transition)
    }

    if (initial in declared) {
        val reachable = reachableStates()
        ids.distinct()
            .filter { it !in reachable }
            .forEach { issues += ValidationIssue.UnreachableState(it) }
    }

    transitions
        .filter { it.guard == null }
        .groupBy { it.source to it.on }
        .filterValues { it.size > 1 }
        .forEach { (key, group) -> issues += ValidationIssue.AmbiguousTransitions(key.first, key.second, group) }

    val samples = sampleActions.distinct()
    if (samples.isNotEmpty()) {
        transitions
            .filter { it.guard == null }
            .groupBy { it.source }
            .forEach { (source, unguarded) ->
                for (sample in samples) {
                    val matching = unguarded.filter { it.on.matches(sample) }
                    if (matching.map { it.on }.distinct().size > 1) {
                        issues += ValidationIssue.ShadowedTransitions(source, sample, matching)
                    }
                }
            }
    }

    return issues
}

/**
 * Returns the states reachable from [StateChartDefinition.initial] by following transitions,
 * including the initial state itself. Guards are ignored.
 */
@ExperimentalKomaApi
fun StateChartDefinition.reachableStates(): Set<StateId> {
    val reachable = linkedSetOf(initial)
    val queue = ArrayDeque(listOf(initial))
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        for (transition in transitionsFrom(current)) {
            if (reachable.add(transition.target)) queue.addLast(transition.target)
        }
    }
    return reachable
}

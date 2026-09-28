package koma.statechart

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
}

/**
 * Checks the structure of this definition and returns every issue found, or an empty list.
 *
 * Issues are reported in a stable order: duplicates, initial state, transition endpoints,
 * reachability, ambiguity.
 */
@ExperimentalKomaApi
fun StateChartDefinition.validate(): List<ValidationIssue> {
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

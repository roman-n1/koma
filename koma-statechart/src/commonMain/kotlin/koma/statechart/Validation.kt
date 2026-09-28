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
     * State [id] declares [parent] as its parent, but no state [parent] is declared.
     */
    data class UnknownParent(val id: StateId, val parent: StateId) : ValidationIssue

    /**
     * State [id] declares [parent] as its parent, but [parent] is an [AtomicState], which cannot
     * have children.
     */
    data class AtomicParent(val id: StateId, val parent: StateId) : ValidationIssue

    /**
     * Following parents from [states] leads back to where it started, so these states are not
     * inside any top-level state.
     *
     * Each cycle is reported once; cycles come in the declaration order of their first state.
     *
     * @property states The states of the cycle, starting with the first declared one, each
     * followed by its parent
     */
    data class ParentCycle(val states: List<StateId>) : ValidationIssue

    /**
     * The initial state [initial] of compound state [id] is not one of its children (it may also
     * be undeclared).
     */
    data class InitialNotChild(val id: StateId, val initial: StateId) : ValidationIssue

    /**
     * Compound state [id] has no children.
     */
    data class EmptyCompoundState(val id: StateId) : ValidationIssue

    /**
     * State [id] never becomes active, starting from the initial configuration.
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
 * Issues are reported in a stable order: duplicates, initial state, hierarchy (unknown or atomic
 * parents by state, parent cycles, compound initial states, empty compound states), transition
 * endpoints, reachability, ambiguity, shadowing.
 *
 * Hierarchy issues use the first declaration of a duplicated id. A state is reachable when it can
 * become active: the initial configuration and every configuration reached by taking any
 * transition whose source is active, guards and transition priority ignored (see
 * [reachableStates]).
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

    issues += hierarchyIssues()

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
 * Problems that leave the tree of states undefined, in the order [validate] reports them.
 */
@OptIn(ExperimentalKomaApi::class)
internal fun StateChartDefinition.hierarchyIssues(): List<ValidationIssue> {
    val issues = mutableListOf<ValidationIssue>()
    for (node in hierarchy.nodes.values) {
        val parent = node.parent ?: continue
        when (node(parent)) {
            null -> issues += ValidationIssue.UnknownParent(node.id, parent)
            is AtomicState -> issues += ValidationIssue.AtomicParent(node.id, parent)
            is CompoundState -> Unit
        }
    }
    val cycles = mutableListOf<List<StateId>>()
    for (start in hierarchy.nodes.keys) {
        val walk = mutableListOf<StateId>()
        var current: StateId? = start
        while (current != null && current !in walk && node(current) != null) {
            walk += current
            current = hierarchy.nodes.getValue(current).parent
        }
        if (current == null || current !in walk) continue
        val cycle = walk.drop(walk.indexOf(current))
        val first = cycle.minBy { declarationOrder(it) }
        val rotated = cycle.drop(cycle.indexOf(first)) + cycle.take(cycle.indexOf(first))
        if (rotated !in cycles) cycles += rotated
    }
    cycles.sortedBy { declarationOrder(it.first()) }.forEach { issues += ValidationIssue.ParentCycle(it) }
    for (node in hierarchy.nodes.values) {
        if (node !is CompoundState) continue
        if (node(node.initial)?.parent != node.id) issues += ValidationIssue.InitialNotChild(node.id, node.initial)
    }
    for (node in hierarchy.nodes.values) {
        if (node is CompoundState && childrenOf(node.id).isEmpty()) issues += ValidationIssue.EmptyCompoundState(node.id)
    }
    return issues
}

/**
 * Returns the states that can become active, starting from the initial configuration (see
 * [StateChartRuntime.initialConfiguration]) and taking, in every configuration reached, any
 * transition whose source is active. Guards and transition priority are ignored, and entering a
 * state makes its ancestors active too. For a flat chart these are the states reachable from
 * [StateChartDefinition.initial] by following transitions, including the initial state itself.
 *
 * States come in breadth-first order of the configurations that first make them active.
 */
@ExperimentalKomaApi
fun StateChartDefinition.reachableStates(): Set<StateId> = configurationGraph.firstReaching.keys

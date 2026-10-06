package koma.statechart

import koma.core.Action
import koma.core.ExperimentalKomaApi
import kotlin.time.Duration

/**
 * A structural problem found by [validate].
 */
sealed interface ValidationIssue {
    /** Final leaves cannot transition out. */
    data class TransitionFromFinal(val transition: Transition) : ValidationIssue
    /** Completion only belongs to compound or parallel states. */
    data class InvalidCompletionSource(val transition: Transition) : ValidationIssue
    /** Automatic choices without guards depend on declaration order. */
    data class AmbiguousAutomaticTransitions(val source: StateId, val trigger: Trigger, val transitions: List<Transition>) : ValidationIssue

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
     * State [id] declares [parent] as its parent, but [parent] is a [HistoryState], which cannot
     * have children.
     */
    data class HistoryParent(val id: StateId, val parent: StateId) : ValidationIssue

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
     * The initial state [initial] of compound state [id], or of the chart when [id] is `null`, is a
     * [HistoryState]. A history state only says what to restore, so it cannot be where a state or
     * the chart starts. A history state that is not a child of [id] is reported as
     * [InitialNotChild] instead.
     */
    data class HistoryAsInitial(val id: StateId?, val initial: StateId) : ValidationIssue

    /**
     * The [HistoryState.default] of history state [id] cannot be entered from it: for a shallow
     * history it must be a child of the history's parent, for a deep one a proper descendant of it,
     * and in both cases a declared state that is not a history state. Only checked when the
     * history's parent is a declared compound or parallel state; for a parallel parent the
     * children are its regions.
     */
    data class InvalidHistoryDefault(val id: StateId, val default: StateId) : ValidationIssue

    /**
     * Parallel state [id] has fewer than two regions, so it is not really parallel: with one
     * region it behaves like a compound state with that region as its initial child, with none
     * like an atomic state. The runtime accepts it; this is a warning.
     *
     * @property regions The regions it has (its children that are not history states), in
     * declaration order
     */
    data class TooFewRegions(val id: StateId, val regions: List<StateId>) : ValidationIssue

    /**
     * [transition] leaves a [HistoryState]. A history state is never active, so the transition
     * never fires.
     */
    data class TransitionFromHistory(val transition: Transition) : ValidationIssue

    /**
     * Timer [transition] has a delay that is zero or negative. A timer should wait for a positive
     * time; what a caller does with such a delay (fire at once, or never) is up to the caller.
     */
    data class NonPositiveDelay(val transition: Transition) : ValidationIssue

    /**
     * State [id] never becomes active, starting from the initial configuration. History states are
     * never active and are never reported.
     */
    data class UnreachableState(val id: StateId) : ValidationIssue

    /**
     * More than one transition without a guard leaves [source] on the same action, so the choice
     * between them depends on declaration order. Timers are reported as [AmbiguousTimers].
     */
    data class AmbiguousTransitions(
        val source: StateId,
        val on: ActionMatcher,
        val transitions: List<Transition>,
    ) : ValidationIssue

    /**
     * More than one timer without a guard leaves [source] with the same [delay], so they would fire
     * at the same time and which one wins depends on the order they were started in, that is
     * declaration order.
     *
     * @property transitions The timers, in declaration order
     */
    data class AmbiguousTimers(
        val source: StateId,
        val delay: Duration,
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
 * Issues are reported in a stable order: duplicates, initial state, hierarchy (unknown, atomic or
 * history parents by state, parent cycles, compound initial states, empty compound states),
 * history (a history state as the chart's initial state, then as a compound's initial state, then
 * invalid history defaults), parallel states with fewer than two regions (in declaration order),
 * transition endpoints (per transition: unknown source, unknown target, source is a history
 * state, non-positive timer delay), reachability, ambiguity (action transitions, then timers),
 * shadowing.
 *
 * Hierarchy issues use the first declaration of a duplicated id. A state is reachable when it can
 * become active: the initial configuration and every configuration reached by taking any
 * transition whose source is active, guards and transition priority ignored (see
 * [reachableStates]). History states are never active and never reported as unreachable. Timers
 * count as transitions that may be taken whenever their source is active.
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
    issues += historyIssues()
    for ((id, regions) in hierarchy.regions) {
        if (regions.size < 2) issues += ValidationIssue.TooFewRegions(id, regions)
    }

    for (transition in transitions) {
        if (transition.source !in declared) issues += ValidationIssue.UnknownTransitionSource(transition)
        if (transition.target !in declared) issues += ValidationIssue.UnknownTransitionTarget(transition)
        if (node(transition.source) is HistoryState) issues += ValidationIssue.TransitionFromHistory(transition)
        if (node(transition.source) is FinalState) issues += ValidationIssue.TransitionFromFinal(transition)
        if (transition.trigger == Trigger.Completion && node(transition.source) !is CompoundState && node(transition.source) !is ParallelState) {
            issues += ValidationIssue.InvalidCompletionSource(transition)
        }
        val delay = transition.after
        if (delay != null && !delay.isPositive()) issues += ValidationIssue.NonPositiveDelay(transition)
    }

    if (initial in declared) {
        val reachable = reachableStates()
        ids.distinct()
            .filter { it !in reachable && node(it) !is HistoryState }
            .forEach { issues += ValidationIssue.UnreachableState(it) }
    }

    val withoutGuard = transitions.filter { it.guard == null }
    withoutGuard
        .mapNotNull { t -> t.on?.let { on -> (t.source to on) to t } }
        .groupBy({ it.first }, { it.second })
        .filterValues { it.size > 1 }
        .forEach { (key, group) -> issues += ValidationIssue.AmbiguousTransitions(key.first, key.second, group) }
    withoutGuard
        .mapNotNull { t -> t.after?.let { delay -> (t.source to delay) to t } }
        .groupBy({ it.first }, { it.second })
        .filterValues { it.size > 1 }
        .forEach { (key, group) -> issues += ValidationIssue.AmbiguousTimers(key.first, key.second, group) }

    withoutGuard.filter { it.trigger == Trigger.Eventless || it.trigger == Trigger.Completion }
        .groupBy { it.source to it.trigger }.filterValues { it.size > 1 }
        .forEach { (key, group) -> issues += ValidationIssue.AmbiguousAutomaticTransitions(key.first, key.second, group) }

    val samples = sampleActions.distinct()
    if (samples.isNotEmpty()) {
        transitions
            .filter { it.guard == null }
            .groupBy { it.source }
            .forEach { (source, unguarded) ->
                for (sample in samples) {
                    val matching = unguarded.filter { it.on?.matches(sample) == true }
                    if (matching.map { it.on }.distinct().size > 1) {
                        issues += ValidationIssue.ShadowedTransitions(source, sample, matching)
                    }
                }
            }
    }

    return issues
}

/**
 * Problems that make a step leave the declared chart, in the order [validate] reports them:
 * duplicate ids, an undeclared initial state and transitions from or to undeclared states.
 */
internal fun StateChartDefinition.endpointIssues(): List<ValidationIssue> {
    val issues = mutableListOf<ValidationIssue>()
    val ids = states.map { it.id }
    val declared = ids.toSet()
    ids.groupingBy { it }.eachCount()
        .filterValues { it > 1 }
        .keys
        .forEach { issues += ValidationIssue.DuplicateStateId(it) }
    if (initial !in declared) issues += ValidationIssue.UnknownInitialState(initial)
    for (transition in transitions) {
        if (transition.source !in declared) issues += ValidationIssue.UnknownTransitionSource(transition)
        if (transition.target !in declared) issues += ValidationIssue.UnknownTransitionTarget(transition)
    }
    return issues
}

/**
 * Timers whose delay is not positive and that, directly or through other such timers, start
 * themselves again, so they would fire forever without time passing. Each cycle is reported once,
 * by its timers in firing order starting with the first declared one.
 *
 * A timer starts when its source is entered, so the check follows what firing really enters,
 * from every configuration the chart can reach (see [reachableStates]): the target's initial
 * descendants, the other regions of a parallel state that is entered again, and what a history
 * state restores. Guards are ignored, so a cycle is reported even if a guard would end it.
 */
internal fun StateChartDefinition.instantTimerCycles(): List<List<Transition>> {
    val instant = transitions.withIndex().filter { (_, t) -> t.after?.isPositive() == false }
    if (instant.isEmpty()) return emptyList()
    // A node is an instant timer that has just been started in a configuration; an edge leads to
    // the timers its firing starts, in the configuration the firing produces.
    data class Node(val configuration: StateConfiguration, val timer: Int)
    val next = mutableMapOf<Node, List<Node>>()
    fun successors(node: Node): List<Node> = next.getOrPut(node) {
        val step = microstep(node.configuration, listOf(transitions[node.timer]))
        instant.filter { (_, u) -> u.source in step.entered }.map { Node(step.configuration, it.index) }
    }
    val nodes = configurationGraph.reachable.flatMap { configuration ->
        instant.filter { (_, t) -> t.source in configuration.active }.map { Node(configuration, it.index) }
    }
    val cycles = mutableListOf<List<Int>>()
    for (start in nodes) {
        // Shortest cycle through start, found breadth-first.
        val previous = mutableMapOf<Node, Node>()
        val queue = ArrayDeque(listOf(start))
        var closing: Node? = null
        while (queue.isNotEmpty() && closing == null) {
            val current = queue.removeFirst()
            for (candidate in successors(current)) {
                if (candidate == start) {
                    closing = current
                    break
                }
                if (candidate !in previous) {
                    previous[candidate] = current
                    queue += candidate
                }
            }
        }
        var current = closing ?: continue
        val cycle = mutableListOf(current.timer)
        while (current != start) {
            current = previous.getValue(current)
            cycle += current.timer
        }
        cycle.reverse()
        val first = cycle.indexOf(cycle.min())
        val rotated = cycle.drop(first) + cycle.take(first)
        if (cycles.none { it.toSet() == rotated.toSet() }) cycles += rotated
    }
    return cycles.map { cycle -> cycle.map { transitions[it] } }
}

/**
 * Whether [configuration] could have been produced by this chart: every active node is declared,
 * is not a history state and comes with its parent, exactly one top-level node is active, an
 * active compound state has exactly one active child and an active parallel state has all its
 * regions active; history records must describe a valid shallow or deep configuration of their
 * current parent, including after the chart's hierarchy changes between versions.
 */
internal fun StateChartDefinition.isConsistent(configuration: StateConfiguration): Boolean =
    hasConsistentActiveNodes(configuration.active) &&
        configuration.history.all { (id, remembered) -> isConsistentHistoryRecord(id, remembered) }

/**
 * [configuration] with the history records this chart cannot restore removed, or `null` when its
 * active nodes are not a configuration of this chart. A record left behind by an earlier version
 * of the chart (a leaf that became compound, a region added to a parallel state, a removed leaf)
 * only affects the next transition into its history state, which then takes the default target,
 * so it does not justify discarding a snapshot whose active nodes are valid.
 */
internal fun StateChartDefinition.consistentPart(configuration: StateConfiguration): StateConfiguration? {
    if (!hasConsistentActiveNodes(configuration.active)) return null
    val history = configuration.history.filter { (id, remembered) -> isConsistentHistoryRecord(id, remembered) }
    return if (history.size == configuration.history.size) configuration else StateConfiguration(configuration.active, history)
}

private fun StateChartDefinition.isConsistentHistoryRecord(id: StateId, remembered: Set<StateId>): Boolean {
    val history = node(id) as? HistoryState ?: return false
    if (remembered.isEmpty() || remembered.any { node(it) is HistoryState || !isDescendant(it, history.parent) }) return false
    return if (!history.deep) {
        when (val parent = node(history.parent)) {
            is CompoundState -> remembered.size == 1 && node(remembered.single())?.parent == parent.id
            is ParallelState -> remembered == hierarchy.regions.getValue(parent.id).toSet()
            else -> false
        }
    } else {
        val restored = configurationOf(remembered).active.filterTo(linkedSetOf()) { it == history.parent || isDescendant(it, history.parent) }
        hasConsistentActiveNodes(restored, history.parent) && activeLeaves(StateConfiguration(restored)).toSet() == remembered
    }
}

/** Checks a whole configuration, or just the subtree belonging to one history parent. */
private fun StateChartDefinition.hasConsistentActiveNodes(active: Set<StateId>, root: StateId? = null): Boolean {
    if (root != null && root !in active) return false
    for (id in active) {
        val node = node(id) ?: return false
        val parent = node.parent
        if (id != root && parent != null && parent !in active) return false
        val consistent = when (node) {
            is AtomicState, is FinalState -> true
            is CompoundState -> childrenOf(id).count { it.id in active } == 1
            is ParallelState -> hierarchy.regions[id].orEmpty().all { it in active }
            is HistoryState -> false
        }
        if (!consistent) return false
    }
    return root != null || childrenOf(null).count { it.id in active } == 1
}

/**
 * Problems that leave the tree of states undefined, in the order [validate] reports them.
 */
internal fun StateChartDefinition.hierarchyIssues(): List<ValidationIssue> {
    val issues = mutableListOf<ValidationIssue>()
    for (node in hierarchy.nodes.values) {
        val parent = node.parent ?: continue
        when (node(parent)) {
            null -> issues += ValidationIssue.UnknownParent(node.id, parent)
            is AtomicState, is FinalState -> issues += ValidationIssue.AtomicParent(node.id, parent)
            is HistoryState -> issues += ValidationIssue.HistoryParent(node.id, parent)
            is CompoundState, is ParallelState -> Unit
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
 * Problems with history states that leave their entry undefined, in the order [validate] reports
 * them.
 */
internal fun StateChartDefinition.historyIssues(): List<ValidationIssue> {
    val issues = mutableListOf<ValidationIssue>()
    if (node(initial) is HistoryState) issues += ValidationIssue.HistoryAsInitial(null, initial)
    for (node in hierarchy.nodes.values) {
        if (node is CompoundState && node(node.initial).let { it is HistoryState && it.parent == node.id }) {
            issues += ValidationIssue.HistoryAsInitial(node.id, node.initial)
        }
    }
    for (node in hierarchy.nodes.values) {
        if (node !is HistoryState || node(node.parent).let { it !is CompoundState && it !is ParallelState }) continue
        val default = node.default ?: continue
        val target = node(default)
        val valid = target != null && target !is HistoryState &&
            if (node.deep) isDescendant(default, node.parent) else target.parent == node.parent
        if (!valid) issues += ValidationIssue.InvalidHistoryDefault(node.id, default)
    }
    return issues
}

/**
 * Returns the states that can become active, starting from the initial configuration (see
 * [StateChartRuntime.initialConfiguration]) and taking, in every configuration reached, any
 * transition whose source is active. Guards and transition priority are ignored, and entering a
 * state makes its ancestors active too. With parallel states, taking a transition also takes the
 * transitions other regions would take for the same action, as the runtime does (see
 * [shortestPathTo]). For a flat chart these are the states reachable from
 * [StateChartDefinition.initial] by following transitions, including the initial state itself.
 *
 * Configurations include what history states remember (see [StateConfiguration.history]), so a
 * transition into a history state is followed exactly as the runtime would take it: it restores
 * what was remembered on the path that led there, or its default. History states themselves are
 * never active and never returned.
 *
 * States come in breadth-first order of the configurations that first make them active.
 */
fun StateChartDefinition.reachableStates(): Set<StateId> = configurationGraph.firstReaching.keys

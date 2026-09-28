package koma.statechart

import koma.core.ExperimentalKomaApi

/**
 * A walk through a statechart: [transitions] taken one after another, starting with [start] as the
 * active leaf.
 *
 * Paths are plain data for model-based tests. A test dispatches an action for each transition in
 * order and checks that the Store ends in [end], or in `leaves[i]` after the i-th action. When a
 * transition has a guard, the test must pick an action for which that guard holds.
 *
 * In a flat chart each transition leaves the previous one's target, and the two-argument
 * constructor checks that. In a hierarchical chart a transition may leave an ancestor of the
 * previous leaf and may enter a compound state, so the new leaf can differ from its target; use
 * the paths built by [shortestPathTo] and [transitionCoveragePaths], which carry the leaves.
 *
 * @property start The active leaf the path starts in
 * @property transitions The transitions to take, in order
 * @property leaves The active leaf after each transition; as many as [transitions]
 */
@ExperimentalKomaApi
data class StateChartPath(
    val start: StateId,
    val transitions: List<Transition>,
    val leaves: List<StateId>,
) {
    /**
     * A path through a flat chart: each transition must leave the previous one's target (the first
     * one [start]), and the leaves are the targets.
     *
     * @throws IllegalArgumentException if the transitions are not connected
     */
    constructor(start: StateId, transitions: List<Transition>) : this(start, requireConnected(start, transitions), transitions.map { it.target })

    init {
        require(leaves.size == transitions.size) {
            "[Koma] Path has ${transitions.size} transitions but ${leaves.size} leaves"
        }
    }

    /**
     * The active leaf the path ends in.
     */
    val end: StateId get() = leaves.lastOrNull() ?: start

    /**
     * The actions to dispatch, in order.
     */
    val actions: List<ActionMatcher> get() = transitions.map { it.on }
}

@OptIn(ExperimentalKomaApi::class)
private fun requireConnected(start: StateId, transitions: List<Transition>): List<Transition> {
    transitions.fold(start) { current, transition ->
        require(transition.source == current) {
            "[Koma] Path is not connected: $transition does not leave $current"
        }
        transition.target
    }
    return transitions
}

/**
 * Returns a shortest path from the initial configuration to a configuration in which [target] is
 * active, or `null` when [target] never becomes active (see [reachableStates]). Guards and
 * transition priority are ignored: in every configuration, any transition whose source is active
 * may be taken. For a flat chart this is a shortest path from [StateChartDefinition.initial] to
 * [target].
 *
 * Among paths of equal length, the one found first by following transitions in declaration order
 * is returned, so the result is stable for a given definition.
 */
@ExperimentalKomaApi
fun StateChartDefinition.shortestPathTo(target: StateId): StateChartPath? {
    val graph = configurationGraph
    return graph.firstReaching[target]?.let { StateChartPath(graph.start, it.transitions, it.leaves) }
}

/**
 * Returns paths from the initial configuration that together take every transition whose source
 * can become active at least once. Guards and transition priority are ignored as in
 * [shortestPathTo], and transitions whose source never becomes active are left out (see
 * [validate]).
 *
 * Each transition gets the shortest path to its source (see [shortestPathTo]) followed by the
 * transition itself. A path that is the start of another returned path is dropped, since the
 * longer path covers it. Paths come in the declaration order of the transition they were built
 * for.
 */
@ExperimentalKomaApi
fun StateChartDefinition.transitionCoveragePaths(): List<StateChartPath> {
    val graph = configurationGraph
    val candidates = transitions.mapNotNull { transition ->
        graph.firstReaching[transition.source]?.let { prefix ->
            val step = microstep(prefix.configuration, listOf(transition))
            ReachedConfiguration(step.configuration, prefix.transitions + transition, prefix.leaves + step.leaf(this))
        }
    }.distinctBy { it.transitions }
    return candidates
        .filter { path -> candidates.none { other -> other.transitions.size > path.transitions.size && other.transitions.subList(0, path.transitions.size) == path.transitions } }
        .map { StateChartPath(graph.start, it.transitions, it.leaves) }
}

/**
 * A configuration found by [configurationGraph] with the first-found shortest path to it.
 */
@OptIn(ExperimentalKomaApi::class)
internal class ReachedConfiguration(
    val configuration: StateConfiguration,
    val transitions: List<Transition>,
    val leaves: List<StateId>,
)

/**
 * Configurations reachable from the initial one; see [configurationGraph].
 *
 * @property start The active leaf of the initial configuration
 * @property firstReaching For every node that can become active, the first configuration found
 * with it active, in the order found
 */
@OptIn(ExperimentalKomaApi::class)
internal class ConfigurationGraph(
    val start: StateId,
    val firstReaching: Map<StateId, ReachedConfiguration>,
)

/**
 * Breadth-first search over configurations from the initial one. In each configuration every
 * transition whose source is active is followed, in declaration order.
 */
@OptIn(ExperimentalKomaApi::class)
internal fun StateChartDefinition.buildConfigurationGraph(): ConfigurationGraph {
    val initialConfiguration = initialConfiguration()
    val start = activeLeaves(initialConfiguration).firstOrNull() ?: initial
    val first = ReachedConfiguration(initialConfiguration, emptyList(), emptyList())
    val seen = mutableSetOf(initialConfiguration)
    val firstReaching = linkedMapOf<StateId, ReachedConfiguration>()
    initialConfiguration.active.forEach { firstReaching.getOrPut(it) { first } }
    val queue = ArrayDeque(listOf(first))
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        for (transition in transitions) {
            if (transition.source !in current.configuration.active) continue
            val step = microstep(current.configuration, listOf(transition))
            if (!seen.add(step.configuration)) continue
            val next = ReachedConfiguration(step.configuration, current.transitions + transition, current.leaves + step.leaf(this))
            step.configuration.active.forEach { firstReaching.getOrPut(it) { next } }
            queue.addLast(next)
        }
    }
    return ConfigurationGraph(start, firstReaching)
}

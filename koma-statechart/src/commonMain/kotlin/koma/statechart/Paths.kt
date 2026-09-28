package koma.statechart

import koma.core.ExperimentalKomaApi

/**
 * A walk through a statechart: [transitions] taken one after another, starting in [start].
 *
 * Paths are plain data for model-based tests. A test dispatches an action for each transition in
 * order and checks that the Store ends in [end]. When a transition has a guard, the test must pick
 * an action for which that guard holds.
 *
 * @property start The state the path starts in
 * @property transitions The transitions to take, in order; each one leaves the previous one's target
 */
@ExperimentalKomaApi
data class StateChartPath(
    val start: StateId,
    val transitions: List<Transition>,
) {
    init {
        transitions.fold(start) { current, transition ->
            require(transition.source == current) {
                "[Koma] Path is not connected: $transition does not leave $current"
            }
            transition.target
        }
    }

    /**
     * The state the path ends in.
     */
    val end: StateId get() = transitions.lastOrNull()?.target ?: start

    /**
     * The actions to dispatch, in order.
     */
    val actions: List<ActionMatcher> get() = transitions.map { it.on }
}

/**
 * Returns a shortest path from [StateChartDefinition.initial] to [target], or `null` when
 * [target] is not reachable. Guards are ignored.
 *
 * Among paths of equal length, the one found first by following transitions in declaration order
 * is returned, so the result is stable for a given definition.
 */
@ExperimentalKomaApi
fun StateChartDefinition.shortestPathTo(target: StateId): StateChartPath? =
    shortestPathTree()[target]?.let { StateChartPath(initial, it) }

/**
 * Returns paths from [StateChartDefinition.initial] that together take every reachable transition
 * at least once. Guards are ignored, and transitions whose source is unreachable are left out
 * (see [validate]).
 *
 * Each transition gets the shortest path to its source followed by the transition itself. A path
 * that is the start of another returned path is dropped, since the longer path covers it. Paths
 * come in the declaration order of the transition they were built for.
 */
@ExperimentalKomaApi
fun StateChartDefinition.transitionCoveragePaths(): List<StateChartPath> {
    val tree = shortestPathTree()
    val candidates = transitions.mapNotNull { transition ->
        tree[transition.source]?.let { prefix -> prefix + transition }
    }.distinct()
    return candidates
        .filter { path -> candidates.none { other -> other.size > path.size && other.subList(0, path.size) == path } }
        .map { StateChartPath(initial, it) }
}

/**
 * Breadth-first search from the initial state. Maps every reachable state to the transitions of
 * its first-found shortest path.
 */
@OptIn(ExperimentalKomaApi::class)
private fun StateChartDefinition.shortestPathTree(): Map<StateId, List<Transition>> {
    val paths = linkedMapOf(initial to emptyList<Transition>())
    val queue = ArrayDeque(listOf(initial))
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        val prefix = paths.getValue(current)
        for (transition in transitionsFrom(current)) {
            if (transition.target !in paths) {
                paths[transition.target] = prefix + transition
                queue.addLast(transition.target)
            }
        }
    }
    return paths
}

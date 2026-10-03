package koma.statechart

import koma.core.ExperimentalKomaApi

/**
 * A walk through a statechart: [transitions] taken one after another, starting with [start] as the
 * active leaf, one action per transition.
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
 * A step may be a timer ([Trigger.After]): the test then lets the timer's delay pass (advancing
 * virtual time) instead of dispatching an action, or calls [StateChartRuntime.fire]. [triggers]
 * lists what each step needs, and [actions] only the actions.
 *
 * With parallel states several leaves are active at once. [start] and [leaves] then name the first
 * active leaf in declaration order, and [startLeaves] and [activeLeaves] name all of them. The
 * action for a transition may also fire transitions in other regions, as the runtime does; the
 * leaves after it already include what those did (see [shortestPathTo]).
 *
 * @property start The active leaf the path starts in (the first one, with parallel states)
 * @property transitions The transitions to take, in order; each stands for the action that takes
 * it, or for its timer firing
 * @property leaves The active leaf after each transition (the first one, with parallel states); as
 * many as [transitions]
 * @property startLeaves All active leaves the path starts in, in declaration order
 * @property activeLeaves All active leaves after each transition, in declaration order; as many
 * as [transitions]
 */
data class StateChartPath(
    val start: StateId,
    val transitions: List<Transition>,
    val leaves: List<StateId>,
    val startLeaves: List<StateId> = listOf(start),
    val activeLeaves: List<List<StateId>> = leaves.map { listOf(it) },
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
        require(activeLeaves.size == transitions.size) {
            "[Koma] Path has ${transitions.size} transitions but ${activeLeaves.size} sets of active leaves"
        }
    }

    /**
     * The active leaf the path ends in.
     */
    val end: StateId get() = leaves.lastOrNull() ?: start

    /**
     * The actions to dispatch, in order: one per action step; timer steps are left out, so for a
     * path with timers this is shorter than [transitions] (see [triggers]).
     */
    val actions: List<ActionMatcher> get() = transitions.mapNotNull { it.on }

    /**
     * What fires each step, in order: an action or a timer; as many as [transitions].
     */
    val triggers: List<Trigger> get() = transitions.map { it.trigger }
}

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
 * With parallel states a step follows the runtime, so that dispatching one action per transition
 * replays the path: taking transition `t` also takes, in every other region, what the runtime
 * would choose for an action that matches exactly the transitions whose matcher equals `t.on`
 * (an equal [ActionMatcher], not merely an overlapping one), with guards true, and leaves out any
 * transition that conflicts with `t` (see [StateChartRuntime]). Without parallel states a step is
 * `t` alone. A timer is a step like any transition whose source is active, and it fires alone, as
 * [StateChartRuntime.fire] does; its delay does not count, only the number of steps.
 *
 * Among paths of equal length, the one found first by following transitions in declaration order
 * is returned, so the result is stable for a given definition.
 */
fun StateChartDefinition.shortestPathTo(target: StateId): StateChartPath? {
    val graph = configurationGraph
    return graph.firstReaching[target]?.let { graph.pathTo(it) }
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
fun StateChartDefinition.transitionCoveragePaths(): List<StateChartPath> {
    val graph = configurationGraph
    val candidates = transitions.mapNotNull { transition ->
        graph.firstReaching[transition.source]?.let { prefix ->
            val step = graphStep(prefix.configuration, transition)
            ReachedConfiguration(step.configuration, prefix.transitions + transition, prefix.leafSets + listOf(step.leaves(this)))
        }
    }.distinctBy { it.transitions }
    return candidates
        .filter { path -> candidates.none { other -> other.transitions.size > path.transitions.size && other.transitions.subList(0, path.transitions.size) == path.transitions } }
        .map { graph.pathTo(it) }
}

/**
 * A configuration found by [configurationGraph] with the first-found shortest path to it.
 */
internal class ReachedConfiguration(
    val configuration: StateConfiguration,
    val transitions: List<Transition>,
    val leafSets: List<List<StateId>>,
)

/**
 * Configurations reachable from the initial one; see [configurationGraph].
 *
 * @property startLeaves The active leaves of the initial configuration, never empty
 * @property firstReaching For every node that can become active, the first configuration found
 * with it active, in the order found
 * @property reachable Every configuration found, the initial one first, then in the order found
 */
internal class ConfigurationGraph(
    val startLeaves: List<StateId>,
    val firstReaching: Map<StateId, ReachedConfiguration>,
    val reachable: List<StateConfiguration>,
) {
    fun pathTo(reached: ReachedConfiguration): StateChartPath =
        StateChartPath(startLeaves.first(), reached.transitions, reached.leafSets.map { it.first() }, startLeaves, reached.leafSets)
}

/**
 * Breadth-first search over configurations from the initial one. In each configuration every
 * transition whose source is active is followed, in declaration order, as a [graphStep].
 */
internal fun StateChartDefinition.buildConfigurationGraph(): ConfigurationGraph {
    val initialConfiguration = initialConfiguration()
    val startLeaves = activeLeaves(initialConfiguration).ifEmpty { listOf(initial) }
    val first = ReachedConfiguration(initialConfiguration, emptyList(), emptyList())
    val seen = linkedSetOf(initialConfiguration)
    val firstReaching = linkedMapOf<StateId, ReachedConfiguration>()
    initialConfiguration.active.forEach { firstReaching.getOrPut(it) { first } }
    val queue = ArrayDeque(listOf(first))
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        for (transition in transitions) {
            if (transition.source !in current.configuration.active) continue
            val step = graphStep(current.configuration, transition)
            if (!seen.add(step.configuration)) continue
            val next = ReachedConfiguration(step.configuration, current.transitions + transition, current.leafSets + listOf(step.leaves(this)))
            step.configuration.active.forEach { firstReaching.getOrPut(it) { next } }
            queue.addLast(next)
        }
    }
    return ConfigurationGraph(startLeaves, firstReaching, seen.toList())
}

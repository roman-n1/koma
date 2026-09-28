package koma.statechart

import koma.core.Action
import koma.core.ExperimentalKomaApi

/**
 * One transition fired by [HierarchyReference]: nodes exited and entered, and the configuration
 * after it.
 */
@OptIn(ExperimentalKomaApi::class)
internal class Fired(val transition: Transition, val exited: List<StateId>, val entered: List<StateId>, val after: Set<StateId>)

/**
 * A naive re-statement of the design note's semantics for compound states, independent of the
 * library's own implementation: plain parent maps, ancestor chains, a scan over all transitions
 * per node, the LCCA from chains, deepest-first exit and recursive default entry. Only meant for
 * well-formed charts built from [RandomAction]s.
 */
@OptIn(ExperimentalKomaApi::class)
internal class HierarchyReference(val chart: StateChartDefinition) {
    private val first = LinkedHashMap<StateId, StateNode>().also { map -> chart.states.forEach { if (it.id !in map) map[it.id] = it } }

    fun parent(id: StateId): StateId? = first[id]?.parent?.takeIf { it in first }

    /** [id], its parent, ..., up to the top; stops before repeating. */
    fun chain(id: StateId): List<StateId> {
        val chain = mutableListOf(id)
        var p = parent(id)
        while (p != null && p !in chain) {
            chain += p
            p = parent(p)
        }
        return chain
    }

    fun isProperAncestor(ancestor: StateId, of: StateId) = ancestor in chain(of).drop(1)

    fun depth(id: StateId) = chain(id).size - 1

    fun defaultEntry(id: StateId): List<StateId> = when (val node = first[id]) {
        is CompoundState -> listOf(node.initial) + defaultEntry(node.initial)
        else -> emptyList()
    }

    fun initialConfiguration(): Set<StateId> = (chain(chart.initial).reversed() + defaultEntry(chart.initial)).toSet()

    fun leaf(configuration: Set<StateId>): StateId = configuration.single { c -> configuration.none { it != c && parent(it) == c } }

    fun fire(configuration: Set<StateId>, t: Transition): Fired {
        val lcca = chain(t.source).drop(1).firstOrNull { first[it] is CompoundState && isProperAncestor(it, t.target) }
        val exited = configuration.filter { lcca == null || isProperAncestor(lcca, it) }.sortedByDescending { depth(it) }
        val entered = chain(t.target).takeWhile { it != lcca }.reversed() + defaultEntry(t.target)
        return Fired(t, exited, entered, configuration - exited.toSet() + entered)
    }

    fun step(configuration: Set<StateId>, action: RandomAction, guard: (String, RandomAction) -> Boolean): Fired? {
        for (node in chain(leaf(configuration))) {
            for (t in chart.transitions) {
                if (t.source != node || !matches(t.on, action)) continue
                if (t.guard != null && !guard(t.guard, action)) continue
                return fire(configuration, t)
            }
        }
        return null
    }

    /** Guard calls the runtime should make, in order: lazily, in priority order. */
    fun expectedGuardCalls(configuration: Set<StateId>, action: RandomAction, guard: (String, RandomAction) -> Boolean): List<Pair<String, Action>> {
        val calls = mutableListOf<Pair<String, Action>>()
        for (node in chain(leaf(configuration))) {
            for (t in chart.transitions) {
                if (t.source != node || !matches(t.on, action)) continue
                val guard = t.guard ?: return calls
                calls += guard to action
                if (guard(guard, action)) return calls
            }
        }
        return calls
    }

    /** Shortest distances to every configuration, by relaxation until nothing changes. */
    fun distances(): Map<Set<StateId>, Int> {
        val distance = mutableMapOf(initialConfiguration() to 0)
        var changed = true
        while (changed) {
            changed = false
            for ((configuration, d) in distance.entries.toList()) {
                for (t in chart.transitions) {
                    if (t.source !in configuration) continue
                    val next = fire(configuration, t).after
                    if (d + 1 < (distance[next] ?: Int.MAX_VALUE)) {
                        distance[next] = d + 1
                        changed = true
                    }
                }
            }
        }
        return distance
    }

    fun nodeDistances(): Map<StateId, Int> {
        val result = mutableMapOf<StateId, Int>()
        for ((configuration, d) in distances()) for (id in configuration) if (d < (result[id] ?: Int.MAX_VALUE)) result[id] = d
        return result
    }

    /** The configuration whose active leaf is [leaf]. */
    fun configurationOf(leaf: StateId): Set<StateId> = chain(leaf).reversed().toSet()

    /** Transitions that may fire from [leaf], in priority order: the leaf's, then outwards. */
    fun priority(leaf: StateId): List<Transition> = chain(leaf).flatMap { node -> chart.transitions.filter { it.source == node } }

    companion object {
        private val simpleNames = mapOf(RandomAction.Ping to "Ping", RandomAction.Pong to "Pong", RandomAction.Reset to "Reset")

        /** Hard-coded matcher semantics for [RandomCharts.matchers], independent of the library's `matches`. */
        fun matches(matcher: ActionMatcher, action: RandomAction): Boolean = when (matcher.type) {
            null -> matcher.name == (simpleNames[action] ?: "Go")
            RandomAction::class -> true
            RandomAction.Go::class -> action is RandomAction.Go
            RandomAction.Ping::class -> action == RandomAction.Ping
            RandomAction.Pong::class -> action == RandomAction.Pong
            else -> error("unexpected matcher $matcher")
        }
    }
}

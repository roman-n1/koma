package actron.statechart

import actron.core.ExperimentalActronApi

/** One step of [ParallelReference]: the transitions taken, nodes exited and entered, and the configuration after it. */
@OptIn(ExperimentalActronApi::class)
internal class ParallelFired(val transitions: List<Transition>, val exited: List<StateId>, val entered: List<StateId>, val after: HistoryConfiguration)

/**
 * A naive re-statement of the design note's semantics with parallel states, independent of the
 * library's implementation. Where the library computes the entry set target by target, as SCXML
 * does, this reference fills in a configuration until it is *legal*: after the nodes a step must
 * enter (the chain from the domain down to each target, or to each node a history restores), every
 * active compound state without an active child gets its initial child, and every active parallel
 * state gets each region that has nothing active below it, until nothing changes.
 *
 * Selection restates SCXML's `removeConflictingTransitions`: leaves in declaration order, each
 * scanning its chain outwards for the first enabled transition; a pick whose exit set meets an
 * earlier one's replaces it only when its source lies strictly inside that earlier source, else it
 * is dropped. Only meant for well-formed charts.
 */
@OptIn(ExperimentalActronApi::class)
internal class ParallelReference(val chart: StateChartDefinition) {
    private val nodes = LinkedHashMap<StateId, StateNode>().also { map -> chart.states.forEach { if (it.id !in map) map[it.id] = it } }
    private val index = nodes.keys.withIndex().associate { (i, id) -> id to i }
    private val histories = nodes.values.filterIsInstance<HistoryState>()

    fun parent(id: StateId): StateId? = (nodes[id]?.parent as? StateId)?.takeIf { it in nodes }

    /** [id], its parent, ..., up to the top. */
    fun chain(id: StateId): List<StateId> = generateSequence(id) { parent(it) }.toList()

    fun inside(id: StateId, ancestor: StateId) = ancestor in chain(id).drop(1)

    fun depth(id: StateId) = chain(id).size - 1

    fun order(id: StateId) = index[id] ?: Int.MAX_VALUE

    fun regions(parallel: StateId) = nodes.values.filter { it.parent == parallel && it !is HistoryState }.map { it.id }

    fun leaves(active: Set<StateId>): List<StateId> = active.filter { a -> active.none { parent(it) == a } }.sortedBy { order(it) }

    /** Fills [active] in until every active compound has an active child and every active parallel all its regions. */
    fun complete(active: Set<StateId>): Set<StateId> {
        val result = active.toMutableSet()
        var changed = true
        while (changed) {
            changed = false
            for (id in result.toList()) {
                val missing = when (val node = nodes[id]) {
                    is CompoundState -> if (result.none { parent(it) == id }) listOf(node.initial) else emptyList()
                    is ParallelState -> regions(id).filter { r -> r !in result }
                    else -> emptyList()
                }
                if (missing.isNotEmpty() && result.addAll(missing)) changed = true
            }
        }
        return result
    }

    fun initialConfiguration() = HistoryConfiguration(complete(chain(chart.initial).toSet()))

    fun domain(t: Transition): StateId? = chain(t.source).drop(1).firstOrNull { nodes[it] is CompoundState && inside(t.target, it) }

    fun exitSet(active: Set<StateId>, t: Transition): Set<StateId> = domain(t).let { d -> active.filter { d == null || inside(it, d) }.toSet() }

    fun record(history: HistoryState, active: Set<StateId>): Set<StateId> = if (history.deep) {
        active.filter { inside(it, history.parent) && active.none { c -> parent(c) == it } }.toSet()
    } else {
        active.filter { parent(it) == history.parent }.toSet()
    }

    /** Takes [transitions] (non-conflicting) together. */
    fun fire(configuration: HistoryConfiguration, transitions: List<Transition>): ParallelFired {
        val exit = transitions.flatMap { exitSet(configuration.active, it) }.toSet()
        val history = configuration.history.toMutableMap()
        for (x in exit) for (h in histories) if (h.parent == x) {
            val recorded = record(h, configuration.active)
            if (recorded.isNotEmpty()) history[h.id] = recorded
        }
        val must = mutableSetOf<StateId>()
        for (t in transitions) {
            val d = domain(t)
            val target = nodes[t.target]
            if (target is HistoryState) {
                must += chain(t.target).drop(1).takeWhile { it != d }
                val restore = history[target.id] ?: (target.default as? StateId)?.let(::setOf) ?: when (val p = nodes.getValue(target.parent)) {
                    is CompoundState -> setOf(p.initial)
                    else -> regions(p.id).toSet()
                }
                for (r in restore) must += chain(r).takeWhile { it != target.parent }
            } else {
                must += chain(t.target).takeWhile { it != d }
            }
        }
        val kept = configuration.active - exit
        val after = complete(kept + must)
        val entered = (after - kept).sortedWith(compareBy<StateId> { depth(it) }.thenBy { order(it) })
        val exited = exit.sortedWith(compareByDescending<StateId> { depth(it) }.thenByDescending { order(it) })
        return ParallelFired(transitions, exited, entered, HistoryConfiguration(after, history))
    }

    /** SCXML selection with [enabled] deciding which transitions may fire. */
    fun select(active: Set<StateId>, enabled: (Transition) -> Boolean): List<Transition> {
        val filtered = mutableListOf<Transition>()
        for (leaf in leaves(active)) {
            var pick: Transition? = null
            loop@ for (node in chain(leaf)) {
                for (t in chart.transitions) {
                    if (t.source == node && enabled(t)) {
                        pick = t
                        break@loop
                    }
                }
            }
            val t1 = pick ?: continue
            var preempted = false
            val remove = mutableListOf<Transition>()
            for (t2 in filtered) {
                if (exitSet(active, t1).any { it in exitSet(active, t2) }) {
                    if (inside(t1.source, t2.source)) {
                        remove += t2
                    } else {
                        preempted = true
                        break
                    }
                }
            }
            if (!preempted) {
                filtered.removeAll(remove)
                filtered += t1
            }
        }
        return filtered
    }

    fun step(configuration: HistoryConfiguration, action: RandomAction, guard: (String, RandomAction) -> Boolean): ParallelFired? {
        val taken = select(configuration.active) { t -> HierarchyReference.matches((t.trigger as? actron.statechart.Trigger.OnAction)?.matcher, action) && ((t.guard as? actron.statechart.GuardKey)?.name == null || guard((t.guard as actron.statechart.GuardKey).name, action)) }
        return if (taken.isEmpty()) null else fire(configuration, taken)
    }

    /** The timers of [states], state by state in the order given, each state's in declaration order. */
    fun timersOf(states: List<StateId>): List<Transition> = states.flatMap { id -> chart.transitions.filter { it.source == id && it.trigger is Trigger.After } }

    /** A timer firing: nothing when its source is inactive or [guard] rejects its label; otherwise the timer alone. */
    fun fireTimer(configuration: HistoryConfiguration, timer: Transition, guard: (String) -> Boolean): ParallelFired? {
        if (timer.source !in configuration.active) return null
        if ((timer.guard as? actron.statechart.GuardKey)?.name != null && !guard((timer.guard as actron.statechart.GuardKey).name)) return null
        return fire(configuration, listOf(timer))
    }

    /** The configuration graph's step for [trigger]: equal matchers fire elsewhere, nothing may conflict with [trigger]; a timer fires alone. */
    fun graphStep(configuration: HistoryConfiguration, trigger: Transition): ParallelFired {
        if (trigger.trigger is Trigger.After) return fire(configuration, listOf(trigger))
        val triggerExit = exitSet(configuration.active, trigger)
        val taken = select(configuration.active) { t -> t == trigger || ((t.trigger as? actron.statechart.Trigger.OnAction)?.matcher == (trigger.trigger as? actron.statechart.Trigger.OnAction)?.matcher && exitSet(configuration.active, t).none { it in triggerExit }) }
        return fire(configuration, taken)
    }

    /** Shortest distances to every configuration, by a plain breadth-first search over [graphStep]. */
    fun distances(): Map<HistoryConfiguration, Int> {
        val distance = linkedMapOf(initialConfiguration() to 0)
        val queue = ArrayDeque(listOf(initialConfiguration()))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val d = distance.getValue(current)
            for (t in chart.transitions) {
                if (t.source !in current.active) continue
                val next = graphStep(current, t).after
                if (next !in distance) {
                    distance[next] = d + 1
                    queue.addLast(next)
                }
            }
        }
        return distance
    }

    fun nodeDistances(): Map<StateId, Int> {
        val result = mutableMapOf<StateId, Int>()
        for ((configuration, d) in distances().toList()) for (id in configuration.active) if (d < (result[id] ?: Int.MAX_VALUE)) result[id] = d
        return result
    }
}

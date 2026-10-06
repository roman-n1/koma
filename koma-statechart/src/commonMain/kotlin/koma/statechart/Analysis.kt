package koma.statechart

import koma.statechart.machine.TransitionId

/** Model query uses declaration identities, not labels presumed to identify Kotlin code. */
data class TransitionQuery(
    val sources: Set<StateId>? = null, val targets: Set<StateId>? = null,
    val trigger: Trigger? = null, val guard: String? = null, val effect: String? = null,
    val includeDescendants: Boolean = false,
)

fun StateChartDefinition.query(query: TransitionQuery): List<TransitionMatrixEntry> = transitions.mapIndexed(::TransitionMatrixEntry).filter { entry ->
    val t = entry.transition
    fun matches(id: StateId, candidates: Set<StateId>?): Boolean = candidates == null || id in candidates ||
        (query.includeDescendants && ancestorsOf(id).any { it in candidates })
    matches(t.source, query.sources) && matches(t.target, query.targets) && (query.trigger == null || t.trigger == query.trigger) &&
        (query.guard == null || t.guard == query.guard) && (query.effect == null || t.effect == query.effect)
}

/** Guards/priority/handlers/context are opaque. A complete result describes structural potential only. */
data class ReachabilityAnalysis(
    val configurations: List<StateConfiguration>, val reached: Set<StateId>, val unreached: Set<StateId>,
    val withoutIncoming: Set<StateId>, val withoutOutgoing: Set<StateId>,
    val deadEnds: List<StateConfiguration>, val terminal: List<StateConfiguration>, val truncated: Boolean,
) {
    /** Unknown when budgets stop search; never mislabels an unvisited node as proven unreachable. */
    val structurallyUnreachable: Set<StateId>? get() = if (truncated) null else unreached
}

/** Bounded configuration/history search. Automatic steps are edges, not a claim of runtime stable configurations. */
fun StateChartDefinition.analyzeReachability(maxConfigurations: Int = 10_000, maxEdges: Int = 100_000): ReachabilityAnalysis {
    require(maxConfigurations > 0 && maxEdges > 0) { "[Koma] Analysis needs positive budgets" }
    require(states.map { it.id }.distinct().size == states.size && node(initial) != null) { "[Koma] Analysis requires declared unique states" }
    require(transitions.all { node(it.source) != null && node(it.target) != null }) { "[Koma] Analysis requires declared endpoints" }
    require(node(initial) !is HistoryState) { "[Koma] Initial state cannot be history" }
    // Validate only structure here: validate() itself enumerates the unbounded legacy graph.
    for (state in states) {
        val chain = linkedSetOf(state.id)
        var parent = state.parent
        while (parent != null) {
            require(chain.add(parent)) { "[Koma] Cyclic hierarchy" }
            val container = node(parent)
            require(container is CompoundState || container is ParallelState) { "[Koma] Invalid parent" }
            parent = container.parent
        }
        if (state is CompoundState) require(node(state.initial)?.parent == state.id && node(state.initial) !is HistoryState) { "[Koma] Invalid compound initial" }
        if (state is ParallelState) require(childrenOf(state.id).any { it !is HistoryState }) { "[Koma] Empty parallel container" }
        if (state is HistoryState && state.default != null) require(node(state.default) !is HistoryState &&
            (if (state.deep) isDescendant(state.default, state.parent) else node(state.default)?.parent == state.parent)) { "[Koma] Invalid history default" }
    }
    val first = initialConfiguration()
    val seen = linkedSetOf(first)
    val queue = ArrayDeque(listOf(first))
    val dead = mutableListOf<StateConfiguration>()
    val terminal = mutableListOf<StateConfiguration>()
    var edges = 0
    var truncated = false
    while (queue.isNotEmpty() && !truncated) {
        val current = queue.removeFirst()
        val choices = transitions.filter { it.source in current.active }
        if (choices.isEmpty()) {
            if (childrenOf(null).any { isComplete(current, it.id) }) terminal += current else dead += current
        }
        for (transition in choices) {
            if (edges++ >= maxEdges) { truncated = true; break }
            val next = graphStep(current, transition).configuration
            if (next !in seen) {
                if (seen.size >= maxConfigurations) { truncated = true; break }
                seen += next; queue += next
            }
        }
    }
    val activeStates = states.filter { it !is HistoryState }.mapTo(linkedSetOf()) { it.id }
    val reached = seen.flatMapTo(linkedSetOf()) { it.active }
    return ReachabilityAnalysis(seen.toList(), reached, activeStates - reached,
        activeStates - transitions.map { it.target }.toSet(), activeStates - transitions.map { it.source }.toSet(),
        dead.toList(), terminal.toList(), truncated)
}

/** Conservative impact set, including hierarchy descendants and references in both model versions. */
data class BehaviouralImpact(val changedStates: Set<StateId>, val affectedStates: Set<StateId>,
    val beforeTransitions: Set<TransitionId>, val afterTransitions: Set<TransitionId>, val guards: Set<String>, val effects: Set<String>)

fun StateChartDefinition.impactTo(next: StateChartDefinition): BehaviouralImpact {
    val diff = diffTo(next)
    val changed = (diff.addedStates.map { it.id } + diff.removedStates.map { it.id } + diff.changedStates.map { it.before.id } +
        diff.addedTransitions.flatMap { listOf(it.source, it.target) } + diff.removedTransitions.flatMap { listOf(it.source, it.target) } +
        if (diff.initialChanged) listOf(initial, next.initial) else emptyList()).toSet()
    val affected = changed + (states + next.states).filter { n ->
        ancestorsOf(n.id).any { it in changed } || next.ancestorsOf(n.id).any { it in changed }
    }.map { it.id }
    fun indices(chart: StateChartDefinition) = chart.transitions.withIndex().filter {
        it.value.source in affected || it.value.target in affected || diff.transitionOrderChanged
    }.mapTo(linkedSetOf()) { TransitionId(it.index) }
    val referenced = transitions.filterIndexed { index, _ -> TransitionId(index) in indices(this) } +
        next.transitions.filterIndexed { index, _ -> TransitionId(index) in indices(next) }
    return BehaviouralImpact(changed, affected, indices(this), indices(next), referenced.mapNotNull { it.guard }.toSet(), referenced.mapNotNull { it.effect }.toSet())
}

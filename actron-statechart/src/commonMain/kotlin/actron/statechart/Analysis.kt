package actron.statechart

import actron.statechart.machine.TransitionId

/** Model query uses declaration identities, not labels presumed to identify Kotlin code. */
data class TransitionQuery(
    val sources: (StateId) -> Boolean = { true }, val targets: (StateId) -> Boolean = { true },
    val trigger: (Trigger) -> Boolean = { true },
    val guard: (GuardCondition) -> Boolean = { true }, val effect: (TransitionEffect) -> Boolean = { true },
    val includeDescendants: Boolean = false,
)

fun StateChartDefinition.query(query: TransitionQuery): List<TransitionMatrixEntry> = transitions.mapIndexed(::TransitionMatrixEntry).filter { entry ->
    val t = entry.transition
    fun matches(id: StateId, candidates: (StateId) -> Boolean): Boolean = candidates(id) ||
        (query.includeDescendants && ancestorsOf(id).any(candidates))
    matches(t.source, query.sources) && matches(t.target, query.targets) && query.trigger(t.trigger) &&
        query.guard(t.guard) && query.effect(t.effect)
}

/** Guards/priority/handlers/context are opaque. A complete result describes structural potential only. */
data class ReachabilityAnalysis(
    val configurations: List<StateConfiguration>, val reached: Set<StateId>, val unreached: Set<StateId>,
    val withoutIncoming: Set<StateId>, val withoutOutgoing: Set<StateId>,
    val deadEnds: List<StateConfiguration>, val terminal: List<StateConfiguration>, val truncated: Boolean,
) {
    /** Unknown when budgets stop search; never mislabels an unvisited node as proven unreachable. */
    val structurallyUnreachable: Set<StateId> get() = if (truncated) emptySet() else unreached
}

/** Bounded configuration/history search. Automatic steps are edges, not a claim of runtime stable configurations. */
fun StateChartDefinition.analyzeReachability(maxConfigurations: Int = 10_000, maxEdges: Int = 100_000): ReachabilityAnalysis {
    require(maxConfigurations > 0 && maxEdges > 0) { "[Actron] Analysis needs positive budgets" }
    require(states.map { it.id }.distinct().size == states.size && hasNode(initial)) { "[Actron] Analysis requires declared unique states" }
    require(transitions.all { hasNode(it.source) && hasNode(it.target) }) { "[Actron] Analysis requires declared endpoints" }
    require(node(initial) !is HistoryState) { "[Actron] Initial state cannot be history" }
    // Validate only structure here: validate() itself enumerates the unbounded legacy graph.
    for (state in states) {
        val chain = linkedSetOf(state.id)
        var parent = state.parent
        while (parent is StateId) {
            require(chain.add(parent)) { "[Actron] Cyclic hierarchy" }
            require(hasNode(parent)) { "[Actron] Undeclared parent" }
            val container = node(parent)
            require(container is CompoundState || container is ParallelState) { "[Actron] Invalid parent" }
            parent = container.parent
        }
        if (state is CompoundState) require(nodeSatisfies(state.initial) { it.parent == state.id && it !is HistoryState }) { "[Actron] Invalid compound initial" }
        if (state is ParallelState) require(childrenOf(state.id).any { it !is HistoryState }) { "[Actron] Empty parallel container" }
        if (state is HistoryState && state.default is StateId) require(nodeSatisfies(state.default) { it !is HistoryState &&
            (if (state.deep) isDescendant(state.default, state.parent) else it.parent == state.parent) }) { "[Actron] Invalid history default" }
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
            if (childrenOf().any { isComplete(current, it.id) }) terminal += current else dead += current
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
    return BehaviouralImpact(changed, affected, indices(this), indices(next), referenced.map { it.guard }.filterIsInstance<GuardKey>().mapTo(linkedSetOf()) { it.name }, referenced.map { it.effect }.filterIsInstance<EffectKey>().mapTo(linkedSetOf()) { it.name })
}

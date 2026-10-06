package koma.statechart

/** A changed node can invalidate persisted hierarchy/history even when its id remains. */
data class StateNodeChange(val before: StateNode, val after: StateNode)

/** Structural compatibility only; guard/effect implementation changes need explicit versioning. */
data class DefinitionDiff(
    val addedStates: List<StateNode>,
    val removedStates: List<StateNode>,
    val changedStates: List<StateNodeChange>,
    val addedTransitions: List<Transition>,
    val removedTransitions: List<Transition>,
    val initialChanged: Boolean,
    val transitionOrderChanged: Boolean,
    val snapshotMigrationRequired: Boolean,
    val replayMayChange: Boolean,
) {
    /** Stable behavioural diff suitable for PR output. */
    fun describe(): String = buildList {
        addedStates.forEach { add("+ state ${it.id}") }
        removedStates.forEach { add("- state ${it.id}") }
        changedStates.forEach { add("~ state ${it.before.id}: ${it.before} -> ${it.after}") }
        removedTransitions.forEach { add("- ${it.behaviourLabel()}") }
        addedTransitions.forEach { add("+ ${it.behaviourLabel()}") }
        if (initialChanged) add("~ initial state changed")
        if (transitionOrderChanged) add("~ transition identity/order changed")
        if (snapshotMigrationRequired) add("Persisted snapshots need migration review")
        if (replayMayChange) add("Replay compatibility needs a new definition version")
    }.joinToString("\n")
}

/** Conservative model compatibility; this cannot inspect executable guard/effect code. */
fun StateChartDefinition.diffTo(next: StateChartDefinition): DefinitionDiff {
    val old = states.associateBy { it.id }
    val new = next.states.associateBy { it.id }
    val added = next.states.filter { it.id !in old }
    val removed = states.filter { it.id !in new }
    val changed = states.mapNotNull { before -> new[before.id]?.takeIf { it != before }?.let { StateNodeChange(before, it) } }
    val parallelRegionsChanged = states.filterIsInstance<ParallelState>().any { state ->
        childrenOf(state.id).filter { it !is HistoryState }.map { it.id }.toSet() !=
            next.childrenOf(state.id).filter { it !is HistoryState }.map { it.id }.toSet()
    }
    // Timer records persist TransitionId indices, activation and deadline. Reordering, removing
    // or changing one cannot be declared snapshot-compatible merely because its nodes survived.
    val timerDefinitionsChanged = transitions.withIndex().filter { it.value.isTimer } !=
        next.transitions.withIndex().filter { it.value.isTimer }
    return DefinitionDiff(added, removed, changed,
        next.transitions - transitions.toSet(), transitions - next.transitions.toSet(),
        initial != next.initial, transitions != next.transitions,
        removed.isNotEmpty() || changed.isNotEmpty() || parallelRegionsChanged || timerDefinitionsChanged,
        this != next,
    )
}

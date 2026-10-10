package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import actron.statechart.DefinitionDiff
import actron.statechart.diffTo

/** Comparison of explicit versions of one logical machine. Executable lambda bodies are opaque. */
data class BehaviouralDiff(
    val definition: DefinitionId,
    val from: DefinitionVersion,
    val to: DefinitionVersion,
    val model: DefinitionDiff,
) {
    /** Known structural behaviour changed while the caller retained its old version. */
    val versionBumpRequired: Boolean get() = model.replayMayChange && from == to
    /** Even structurally compatible snapshots need an explicit version migration when versions differ. */
    val snapshotMigrationReviewRequired: Boolean get() = model.snapshotMigrationRequired || from != to
    val implementationsCompared: Boolean get() = false

    fun describe(): String = buildString {
        append("Definition ").append(definition.value).append(": ").append(from.value).append(" -> ").append(to.value)
        val changes = model.describe()
        if (changes.isNotEmpty()) append('\n').append(changes)
        if (versionBumpRequired) append("\nDefinition version must change for the structural behaviour change")
        if (snapshotMigrationReviewRequired) append("\nSnapshot version/configuration/work migration needs review")
        append("\nGuard, reducer, hook and invariant implementations are not compared; review their changes explicitly")
    }
}

/** Compares model data without invoking guards, reducers, invariants or hooks, including across context-type migrations. */
fun <C, A : Action, CMD, E : Event> Machine<C, A, CMD, E>.behaviouralDiffTo(next: Machine<*, *, *, *>): BehaviouralDiff {
    require(id == next.id) { "[Actron] Behavioural diff needs versions of the same logical machine" }
    return BehaviouralDiff(id, version, next.version, chart.diffTo(next.chart))
}

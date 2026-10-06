package koma.statechart

/** A direct model declaration; [index] is its priority/identity in the definition's transition list. */
data class TransitionMatrixEntry(val index: Int, val transition: Transition)

/**
 * Declared transitions, not a table of runtime eligibility. Parent rows are not copied into child
 * rows; hierarchy, parallel conflicts and guards determine actual selection. No functions run.
 */
data class TransitionMatrix(
    val states: List<StateId>,
    val triggers: List<Trigger>,
    val entries: List<TransitionMatrixEntry>,
) {
    /** All alternatives in declaration order, including guards, effects and internal transitions. */
    fun cell(source: StateId, trigger: Trigger): List<TransitionMatrixEntry> =
        entries.filter { it.transition.source == source && it.transition.trigger == trigger }

    /** Compact state/trigger table. Duplicate trigger labels are disambiguated without merging their matchers. */
    fun toMarkdown(): String = buildString {
        append("Declared transitions; guards and hierarchy determine actual selection.\n\n")
        val labels = triggers.map { it.displayLabel() }
        val headers = labels.mapIndexed { index, label ->
            if (labels.count { it == label } > 1) "$label (#${index + 1})" else label
        }
        append("| State |")
        headers.forEach { append(' ').append(markdownCell(it)).append(" |") }
        append("\n| --- |")
        triggers.forEach { append(" --- |") }
        for (source in states) {
            append("\n| ").append(markdownCell(source.value)).append(" |")
            for (trigger in triggers) {
                val values = cell(source, trigger).map { entry ->
                    val transition = entry.transition
                    buildString {
                        append(markdownCell(transition.target.value))
                        transition.guard?.let { append(" [").append(markdownCell(it)).append(']') }
                        transition.effect?.let { append(" / ").append(markdownCell(it)) }
                        if (transition.kind == TransitionKind.Internal) append(" (internal)")
                        append(" (#").append(entry.index).append(')')
                    }
                }
                append(' ').append(values.joinToString("<br>").ifEmpty { "—" }).append(" |")
            }
        }
    }

    /** Detailed one-row-per-transition table for QA documentation and review. */
    fun toTransitionTableMarkdown(): String = buildString {
        append("| # | From | Trigger | Guard | Effect | Kind | To |\n| --- | --- | --- | --- | --- | --- | --- |")
        for (entry in entries) {
            val transition = entry.transition
            append("\n| ").append(entry.index).append(" | ")
            append(listOf(transition.source.value, transition.trigger.displayLabel(), transition.guard ?: "—",
                transition.effect ?: "—", transition.kind.name, transition.target.value).joinToString(" | ", transform = ::markdownCell))
            append(" |")
        }
    }
}

/** Includes final/history/isolated nodes as empty rows and all declared trigger kinds as columns. */
fun StateChartDefinition.transitionMatrix(): TransitionMatrix = TransitionMatrix(
    states.map { it.id }, transitions.map { it.trigger }.distinct(), transitions.mapIndexed(::TransitionMatrixEntry),
)

internal fun Trigger.displayLabel(): String = when (this) {
    is Trigger.OnAction -> matcher.name
    is Trigger.After -> "after $delay"
    Trigger.Eventless -> "always"
    Trigger.Completion -> "onDone"
}

internal fun Transition.behaviourLabel(): String = buildString {
    append(source.value).append(" --").append(trigger.displayLabel())
    guard?.let { append(" [").append(it).append(']') }
    effect?.let { append(" / ").append(it) }
    if (kind == TransitionKind.Internal) append(" (internal)")
    append("--> ").append(target.value)
}

private fun markdownCell(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    .replace("\\", "\\\\").replace("|", "\\|").replace("`", "\\`").replace("[", "\\[").replace("]", "\\]")
    .replace("*", "\\*").replace("_", "\\_").replace("~", "\\~")
    .replace("\r\n", "<br>").replace("\n", "<br>").replace("\r", "<br>")

package koma.statechart

import koma.core.ExperimentalKomaApi

private val mermaidIdentifier = Regex("[A-Za-z_][A-Za-z0-9_]*")

/**
 * Words the Mermaid state-diagram grammar reads as keywords (case-insensitively), so they cannot be
 * used as bare state references.
 */
private val mermaidKeywords = setOf(
    "state", "note", "direction", "class", "classdef", "style", "scale", "hide", "end", "click",
    "acctitle", "accdescr",
)

private fun isPlainMermaidId(value: String): Boolean =
    mermaidIdentifier.matches(value) && value.lowercase() !in mermaidKeywords

/**
 * Renders this definition as a Mermaid `stateDiagram-v2`.
 *
 * States are listed in declaration order, then transitions in declaration order. A transition is
 * labelled with its action name and, when present, its guard in brackets, for example
 * `Idle --> Loading : Submit [isValid]`. State ids that are not plain identifiers, or that are
 * Mermaid keywords, are declared with an alias, so any [StateId] renders safely. Aliases never
 * collide with a plain id used by the chart, and ids that only appear as the initial state or a
 * transition endpoint are declared too.
 *
 * The output is built from the model only; nothing runs and no reflection is used.
 */
@ExperimentalKomaApi
fun StateChartDefinition.toMermaid(): String = buildString {
    val ids = (states.map { it.id } + initial + transitions.flatMap { listOf(it.source, it.target) }).distinct()
    val taken = ids.map { it.value }.filterTo(mutableSetOf(), ::isPlainMermaidId)
    var nextAlias = 0
    val refs = ids.associateWith { id ->
        if (isPlainMermaidId(id.value)) {
            id.value
        } else {
            generateSequence { "koma_state_${nextAlias++}" }.first { it !in taken }.also { taken += it }
        }
    }

    appendLine("stateDiagram-v2")
    for (id in ids) {
        val ref = refs.getValue(id)
        if (ref != id.value) {
            appendLine("    state \"${id.value.replace("\"", "'")}\" as $ref")
        }
    }
    appendLine("    [*] --> ${refs.getValue(initial)}")
    for (transition in transitions) {
        append("    ${refs.getValue(transition.source)} --> ${refs.getValue(transition.target)} : ${transition.on.name}")
        transition.guard?.let { append(" [$it]") }
        appendLine()
    }
}.trimEnd()

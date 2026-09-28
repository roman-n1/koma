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
 * A flat chart lists aliased states, then `[*] --> initial`, then transitions, all in declaration
 * order. A compound state becomes a nested block, `state Parent { ... }`, that starts with
 * `[*] --> initialChild` and declares every child in declaration order (an atomic child with a
 * plain id as a bare line), so each state is drawn inside its parent. Each transition is written in the innermost block that contains both its
 * source and its target, after the nested blocks. A transition is labelled with its action name
 * and, when present, its guard in brackets, for example `Idle --> Loading : Submit [isValid]`.
 *
 * A history state has no Mermaid syntax of its own, so it is drawn as a state labelled `[H]`
 * (shallow) or `[H*]` (deep) in its parent's block, always declared with a label, for example
 * `state "[H]" as ChatHistory`. Its default, when set, is drawn as an unlabelled edge from it
 * (`ChatHistory --> Composing`), placed like a transition. Transitions into it are drawn like any
 * other.
 *
 * State ids that are not plain identifiers, or that are Mermaid keywords, are declared with an
 * alias (`state "Signed in" as koma_state_0`, or `state "Signed in" as koma_state_0 { ... }` for a
 * compound state), so any [StateId] renders safely. Aliases never collide with a plain id used by
 * the chart, and ids that only appear as an initial state or a transition endpoint are declared
 * too, at the top level. A state whose parent is not a declared compound state, or that sits on a
 * parent cycle, is drawn at the top level (see [validate]).
 *
 * The output is built from the model only; nothing runs and no reflection is used.
 */
@ExperimentalKomaApi
fun StateChartDefinition.toMermaid(): String = buildString {
    val compoundInitials = hierarchy.nodes.values.filterIsInstance<CompoundState>().map { it.initial }
    val histories = hierarchy.nodes.values.filterIsInstance<HistoryState>()
    val historyDefaults = histories.mapNotNull { it.default }
    val ids = (states.map { it.id } + initial + compoundInitials + historyDefaults + transitions.flatMap { listOf(it.source, it.target) }).distinct()
    val taken = ids.map { it.value }.filterTo(mutableSetOf(), ::isPlainMermaidId)
    var nextAlias = 0
    val refs = ids.associateWith { id ->
        if (isPlainMermaidId(id.value)) {
            id.value
        } else {
            generateSequence { "koma_state_${nextAlias++}" }.first { it !in taken }.also { taken += it }
        }
    }
    val containers = mermaidContainers()
    fun containerOf(id: StateId): StateId? = containers[id]
    fun containerChain(id: StateId): List<StateId?> = generateSequence(containerOf(id)) { containerOf(it) }.toList() + null
    fun declaration(id: StateId): String {
        val ref = refs.getValue(id)
        val node = hierarchy.nodes[id]
        return when {
            node is HistoryState -> "state \"${if (node.deep) "[H*]" else "[H]"}\" as $ref"
            ref == id.value -> ref
            else -> "state \"${id.value.replace("\"", "'")}\" as $ref"
        }
    }
    fun blockOf(source: StateId, target: StateId): StateId? {
        val targetChain = containerChain(target)
        return containerChain(source).first { it in targetChain }
    }
    val transitionsByBlock = transitions.groupBy { blockOf(it.source, it.target) }
    val defaultsByBlock = histories.filter { it.default != null }.groupBy { blockOf(it.id, it.default!!) }

    fun appendTransitions(block: StateId?, indent: String) {
        for (history in defaultsByBlock[block].orEmpty()) {
            appendLine("$indent${refs.getValue(history.id)} --> ${refs.getValue(history.default!!)}")
        }
        for (transition in transitionsByBlock[block].orEmpty()) {
            append("$indent${refs.getValue(transition.source)} --> ${refs.getValue(transition.target)} : ${transition.on.name}")
            transition.guard?.let { append(" [$it]") }
            appendLine()
        }
    }

    fun appendCompound(node: CompoundState, indent: String) {
        val ref = refs.getValue(node.id)
        appendLine("$indent${if (ref == node.id.value) "state $ref" else declaration(node.id)} {")
        val inner = "$indent    "
        appendLine("$inner[*] --> ${refs.getValue(node.initial)}")
        for (child in hierarchy.nodes.values) {
            if (containerOf(child.id) != node.id) continue
            if (child is CompoundState) appendCompound(child, inner) else appendLine("$inner${declaration(child.id)}")
        }
        appendTransitions(node.id, inner)
        appendLine("$indent}")
    }

    appendLine("stateDiagram-v2")
    for (id in ids) {
        val node = hierarchy.nodes[id]
        if (containerOf(id) == null && node !is CompoundState && (refs.getValue(id) != id.value || node is HistoryState)) {
            appendLine("    ${declaration(id)}")
        }
    }
    appendLine("    [*] --> ${refs.getValue(initial)}")
    for (node in hierarchy.nodes.values) {
        if (node is CompoundState && containerOf(node.id) == null) appendCompound(node, "    ")
    }
    appendTransitions(null, "    ")
}.trimEnd()

/**
 * The block each declared state is drawn in: its parent when that is a declared compound state
 * drawn itself, otherwise the top level (`null`, absent from the map). States left over by a
 * parent cycle are drawn from the top, starting with the first declared one.
 */
@OptIn(ExperimentalKomaApi::class)
private fun StateChartDefinition.mermaidContainers(): Map<StateId, StateId> {
    val placed = mutableSetOf<StateId>()
    val containers = mutableMapOf<StateId, StateId>()
    fun place(node: StateNode, container: StateId?) {
        placed += node.id
        if (container != null) containers[node.id] = container
        if (node is CompoundState) {
            for (child in childrenOf(node.id)) if (child.id !in placed) place(child, node.id)
        }
    }
    for (node in hierarchy.nodes.values) {
        val parent = node.parent
        if (node.id !in placed && (parent == null || node(parent) !is CompoundState)) place(node, null)
    }
    for (node in hierarchy.nodes.values) {
        if (node.id !in placed) place(node, null)
    }
    return containers
}

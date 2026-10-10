package actron.statechart

import actron.core.ExperimentalActronApi

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

private val mermaidLineBreak = Regex("\r\n|\r|\n")

/**
 * Keeps free text on one Mermaid statement: line breaks become spaces and `;`, which Mermaid reads
 * as a statement separator, becomes `,`.
 */
private fun mermaidText(value: String): String = value.replace(mermaidLineBreak, " ").replace(';', ',')

/**
 * Renders this definition as a Mermaid `stateDiagram-v2`.
 *
 * A flat chart lists aliased states, then `[*] --> initial`, then transitions, all in declaration
 * order. A compound state becomes a nested block, `state Parent { ... }`, that starts with
 * `[*] --> initialChild` and declares every child in declaration order (an atomic child with a
 * plain id as a bare line), so each state is drawn inside its parent. Each transition is written in the innermost block that contains both its
 * source and its target, after the nested blocks. A transition is labelled with its action name,
 * or `after` and the delay for a timer ([kotlin.time.Duration.toString], so `after 5s`, `after 1m 30s`), then,
 * when present, its guard in brackets and its effect after a slash, for example
 * `Idle --> Loading : Submit [isValid] / countAttempt` or `Typing --> Idle : after 3s`.
 *
 * A parallel state becomes a block too, `state Parallel { ... }`, whose regions are separated by
 * `--` lines, one region per section in declaration order; each region is drawn in its section
 * like any child (a compound or parallel region as its own nested block). A parallel state's
 * history states are drawn in its first section. A parallel block holds no transitions: one whose
 * innermost common block is a parallel state (for example between two of its regions) is written
 * in the nearest enclosing block that is not parallel. A parallel state without children is drawn
 * like an atomic state.
 *
 * A history state has no Mermaid syntax of its own, so it is drawn as a state labelled `[H]`
 * (shallow) or `[H*]` (deep) in its parent's block, always declared with a label, for example
 * `state "[H]" as ChatHistory`. Its default, when set, is drawn as an unlabelled edge from it
 * (`ChatHistory --> Composing`), placed like a transition. Transitions into it are drawn like any
 * other.
 *
 * State ids that are not plain identifiers, or that are Mermaid keywords, are declared with an
 * alias (`state "Signed in" as actron_state_0`, or `state "Signed in" as actron_state_0 { ... }` for a
 * compound state), so any [StateId] renders safely. Aliases never collide with a plain id used by
 * the chart, and ids that only appear as an initial state or a transition endpoint are declared
 * too, at the top level. A state whose parent is not a declared compound state, or that sits on a
 * parent cycle, is drawn at the top level (see [validate]). In state labels, action names, guards
 * and effects, line breaks are written as spaces and `;` as `,`, so each statement stays on one line.
 *
 * The output is built from the model only; nothing runs and no reflection is used.
 */
fun StateChartDefinition.toMermaid(): String = toMermaid(emptySet())

/**
 * Renders this definition as [toMermaid] does, with the states in [active] highlighted: a
 * `classDef actron_active` and a `class` statement naming them (by their alias when they have
 * one) close the diagram, so a snapshot's configuration can be drawn on the chart. Ids that
 * are not in the chart are ignored; an empty [active] gives the plain diagram.
 */
fun StateChartDefinition.toMermaid(active: Set<StateId>): String = buildString {
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
            generateSequence { "actron_state_${nextAlias++}" }.first { it !in taken }.also { taken += it }
        }
    }
    val containers = mermaidContainers()
    fun containerOf(id: StateId): StateId? = containers[id]
    val drawnChildren = hierarchy.nodes.values.filter { it.id in containers }.groupBy { containers.getValue(it.id) }
    fun isBlock(node: StateNode?): Boolean = node is CompoundState || (node is ParallelState && node.id in drawnChildren)
    fun containerChain(id: StateId): List<StateId?> = generateSequence(containerOf(id)) { containerOf(it) }.toList() + null
    fun declaration(id: StateId): String {
        val ref = refs.getValue(id)
        val node = hierarchy.nodes[id]
        return when {
            node is FinalState -> "state \"${mermaidText(id.value).replace("\"", "'")} [final]\" as $ref"
            node is HistoryState -> "state \"${if (node.deep) "[H*]" else "[H]"}\" as $ref"
            ref == id.value -> ref
            else -> "state \"${mermaidText(id.value).replace("\"", "'")}\" as $ref"
        }
    }
    fun blockOf(source: StateId, target: StateId): StateId? {
        val targetChain = containerChain(target)
        var block = containerChain(source).first { it in targetChain }
        while (block != null && hierarchy.nodes[block] is ParallelState) block = containerOf(block)
        return block
    }
    val transitionsByBlock = transitions.groupBy { blockOf(it.source, it.target) }
    val defaultsByBlock = histories.flatMap { h -> listOfNotNull(h.default).map { h.id to it } }.groupBy { (h, default) -> blockOf(h, default) }

    fun appendTransitions(block: StateId?, indent: String) {
        for ((history, default) in defaultsByBlock[block].orEmpty()) {
            appendLine("$indent${refs.getValue(history)} --> ${refs.getValue(default)}")
        }
        for (transition in transitionsByBlock[block].orEmpty()) {
            val label = when (val trigger = transition.trigger) {
                is Trigger.OnAction -> mermaidText(trigger.matcher.name)
                is Trigger.After -> "after ${trigger.delay}"
                Trigger.Eventless -> "always"
                Trigger.Completion -> "done"
            }
            append("$indent${refs.getValue(transition.source)} --> ${refs.getValue(transition.target)} : $label")
            transition.guard?.let { append(" [${mermaidText(it)}]") }
            if (transition.kind == TransitionKind.Internal) append(" (internal)")
            transition.effect?.let { append(" / ${mermaidText(it)}") }
            appendLine()
        }
    }

    fun appendBlock(node: StateNode, indent: String) {
        val ref = refs.getValue(node.id)
        appendLine("$indent${if (ref == node.id.value) "state $ref" else declaration(node.id)} {")
        val inner = "$indent    "
        fun appendChild(child: StateNode) {
            if (isBlock(child)) appendBlock(child, inner) else appendLine("$inner${declaration(child.id)}")
        }
        val children = drawnChildren[node.id].orEmpty()
        if (node is CompoundState) {
            appendLine("$inner[*] --> ${refs.getValue(node.initial)}")
            children.forEach(::appendChild)
            appendTransitions(node.id, inner)
        } else {
            val (histories, regions) = children.partition { it is HistoryState }
            regions.forEachIndexed { i, region ->
                if (i > 0) appendLine("$inner--")
                appendChild(region)
                if (i == 0) histories.forEach(::appendChild)
            }
            if (regions.isEmpty()) histories.forEach(::appendChild)
        }
        appendLine("$indent}")
    }

    appendLine("stateDiagram-v2")
    for (id in ids) {
        val node = hierarchy.nodes[id]
        if (containerOf(id) == null && !isBlock(node) && (refs.getValue(id) != id.value || node is HistoryState)) {
            appendLine("    ${declaration(id)}")
        }
    }
    appendLine("    [*] --> ${refs.getValue(initial)}")
    for (node in hierarchy.nodes.values) {
        if (isBlock(node) && containerOf(node.id) == null) appendBlock(node, "    ")
    }
    appendTransitions(null, "    ")
    val highlighted = ids.filter { it in active }
    if (highlighted.isNotEmpty()) {
        appendLine("    classDef actron_active fill:#ffe0b2,stroke:#ef6c00,stroke-width:2px")
        appendLine("    class ${highlighted.joinToString(",") { refs.getValue(it) }} actron_active")
    }
}.trimEnd()

/**
 * The block each declared state is drawn in: its parent when that is a declared compound or
 * parallel state drawn itself, otherwise the top level (`null`, absent from the map). States left over by a
 * parent cycle are drawn from the top, starting with the first declared one.
 */
private fun StateChartDefinition.mermaidContainers(): Map<StateId, StateId> {
    val placed = mutableSetOf<StateId>()
    val containers = mutableMapOf<StateId, StateId>()
    fun place(node: StateNode, container: StateId?) {
        placed += node.id
        if (container != null) containers[node.id] = container
        if (node is CompoundState || node is ParallelState) {
            for (child in childrenOf(node.id)) if (child.id !in placed) place(child, node.id)
        }
    }
    for (node in hierarchy.nodes.values) {
        val parent = node.parent
        if (node.id !in placed && (parent == null || node(parent).let { it !is CompoundState && it !is ParallelState })) place(node, null)
    }
    for (node in hierarchy.nodes.values) {
        if (node.id !in placed) place(node, null)
    }
    return containers
}

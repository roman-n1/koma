package koma.statechart

import koma.core.ExperimentalKomaApi

private val mermaidIdentifier = Regex("[A-Za-z_][A-Za-z0-9_]*")

/**
 * Renders this definition as a Mermaid `stateDiagram-v2`.
 *
 * States are listed in declaration order, then transitions in declaration order. A transition is
 * labelled with its action name and, when present, its guard in brackets, for example
 * `Idle --> Loading : Submit [isValid]`. State ids that are not plain identifiers are declared
 * with an alias, so any [StateId] renders safely.
 *
 * The output is built from the model only; nothing runs and no reflection is used.
 */
@ExperimentalKomaApi
fun StateChartDefinition.toMermaid(): String = buildString {
    val aliases = mutableMapOf<StateId, String>()
    fun ref(id: StateId): String = aliases.getOrPut(id) {
        if (mermaidIdentifier.matches(id.value)) id.value else "koma_state_${aliases.size}"
    }

    appendLine("stateDiagram-v2")
    for (state in states) {
        val alias = ref(state.id)
        if (alias != state.id.value) {
            appendLine("    state \"${state.id.value.replace("\"", "'")}\" as $alias")
        }
    }
    appendLine("    [*] --> ${ref(initial)}")
    for (transition in transitions) {
        append("    ${ref(transition.source)} --> ${ref(transition.target)} : ${transition.on.name}")
        transition.guard?.let { append(" [$it]") }
        appendLine()
    }
}.trimEnd()

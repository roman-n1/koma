package actron.statechart.test

import actron.core.Action
import actron.core.Event
import actron.statechart.*
import actron.statechart.machine.*
import kotlinx.serialization.json.*

/** Repository-relative navigation only. Source mapping is authored by the application. */
data class ModelSource(val path: String, val line: Int) {
    init {
        require(line > 0 && path.isNotBlank() && path.none { it < ' ' } && !path.startsWith('/') && !path.contains('\\') &&
            path.split('/').none { it == ".." || it == "." || it.isBlank() } && !path.contains(':')) { "[Actron] Source must be a repository-relative path and positive line" }
    }
}

/** Versioned, metadata-only CI/IDE artifact. It cannot reconstruct executable Kotlin rules. */
class BehaviouralSnapshot internal constructor(private val data: JsonObject) {
    val definition: String get() = data.getValue("definition").jsonPrimitive.content
    val version: String get() = data.getValue("version").jsonPrimitive.content
    val mermaid: String get() = data.getValue("mermaid").jsonPrimitive.content
    fun toJson(): String = data.toString()
    internal val model: JsonObject get() = data.getValue("model").jsonObject
    internal val coverage: JsonObject get() = data.getValue("coverage").jsonObject

    companion object {
        /** Rejects unsupported formats and malformed transition identities before comparison/navigation. */
        fun fromJson(text: String): BehaviouralSnapshot {
            val data = Json.parseToJsonElement(text).jsonObject
            require(data["format"]?.jsonPrimitive?.int == 1) { "[Actron] Unsupported behavioural artifact format" }
            require(data.getValue("definition").jsonPrimitive.content.isNotBlank() && data.getValue("version").jsonPrimitive.content.isNotBlank())
            val model = data.getValue("model").jsonObject
            val transitions = model.getValue("transitions").jsonArray
            require(transitions.map { it.jsonObject.getValue("index").jsonPrimitive.int } == transitions.indices.toList()) { "[Actron] Invalid transition identity/order" }
            val states = model.getValue("states").jsonArray.map { it.jsonObject }
            val ids = states.map { it.getValue("id").jsonPrimitive.content }
            require(ids.all { it.isNotBlank() } && ids.distinct().size == ids.size && model.getValue("initial").jsonPrimitive.content in ids)
            require(transitions.all { it.jsonObject.getValue("source").jsonPrimitive.content in ids && it.jsonObject.getValue("target").jsonPrimitive.content in ids })
            val coverage = data.getValue("coverage").jsonObject
            require(coverage.getValue("coveredStates").jsonArray.all { it.jsonPrimitive.content in ids })
            require(coverage.getValue("guardOutcomes").jsonArray.all {
                val parts = it.jsonPrimitive.content.split(':')
                parts.size == 2 && parts[1] in setOf("true", "false") && parts[0].toIntOrNull()?.let { index ->
                    transitions.getOrNull(index)?.jsonObject?.get("guard")?.let { guard -> guard != JsonNull } == true
                } == true
            })
            require(coverage.getValue("coveredTransitions").jsonArray.all { it.jsonPrimitive.int in transitions.indices }) { "[Actron] Coverage refers to an undeclared transition" }
            data.getValue("mermaid").jsonPrimitive.content
            data.getValue("sources").jsonObject.values.forEach { value ->
                val source = value.jsonObject
                ModelSource(source.getValue("path").jsonPrimitive.content, source.getValue("line").jsonPrimitive.int)
            }
            return BehaviouralSnapshot(data)
        }
    }
}

/**
 * Stable action identities are supplied when different typed matchers share a display name.
 * No action/context/command payloads, Kotlin class names or rule bodies are exported.
 */
fun <C, A : Action, CMD, E : Event> Machine<C, A, CMD, E>.behaviouralSnapshot(
    coverage: MachineCoverage,
    sources: Map<String, ModelSource> = emptyMap(),
    active: Set<StateId> = emptySet(),
    selectedTransitions: Set<TransitionId> = emptySet(),
    actionKey: (ActionMatcher) -> String = { it.name },
): BehaviouralSnapshot {
    require(coverage.definition == id && coverage.version == version) { "[Actron] Coverage belongs to another definition/version" }
    val expected = chart.transitions.indices.mapTo(linkedSetOf(), ::TransitionId)
    require(coverage.transitions.expected == expected && coverage.transitions.covered.all { it in expected }) { "[Actron] Coverage/model declarations differ" }
    val stateIds = chart.states.filter { it !is HistoryState }.mapTo(linkedSetOf()) { it.id }
    require(coverage.states.expected == stateIds && coverage.states.covered.all { it in stateIds } && active.all { it in stateIds }) { "[Actron] State coverage/active nodes differ from declarations" }
    require(selectedTransitions.all { it in expected }) { "[Actron] Selected transition is undeclared" }
    val matchers = chart.transitions.mapNotNull { it.on }.distinct()
    val keys = matchers.associateWith(actionKey)
    require(keys.values.all { it.isNotBlank() } && keys.values.distinct().size == keys.size) { "[Actron] Typed matchers need distinct stable action keys" }
    val model = buildJsonObject {
        put("initial", chart.initial.value)
        putJsonArray("states") { chart.states.forEach { node -> add(buildJsonObject {
            put("id", node.id.value); put("parent", node.parent?.value?.let(::JsonPrimitive) ?: JsonNull)
            put("kind", when (node) { is AtomicState -> "atomic"; is FinalState -> "final"; is CompoundState -> "compound"; is ParallelState -> "parallel"; is HistoryState -> "history" })
            if (node is CompoundState) put("initial", node.initial.value)
            if (node is HistoryState) { put("deep", node.deep); put("default", node.default?.value?.let(::JsonPrimitive) ?: JsonNull) }
        }) } }
        putJsonArray("transitions") { chart.transitions.forEachIndexed { index, t -> add(buildJsonObject {
            put("index", index); put("id", "transition:$index"); put("source", t.source.value); put("target", t.target.value)
            put("trigger", when (val trigger = t.trigger) { is Trigger.OnAction -> "action:${keys.getValue(trigger.matcher)}"; is Trigger.After -> "after:${trigger.delay}"; Trigger.Eventless -> "always"; Trigger.Completion -> "onDone" })
            put("guard", t.guard?.let(::JsonPrimitive) ?: JsonNull); put("effect", t.effect?.let(::JsonPrimitive) ?: JsonNull); put("kind", t.kind.name)
        }) } }
    }
    return BehaviouralSnapshot(buildJsonObject {
        put("format", 1); put("definition", id.value); put("version", version.value); put("model", model)
        putJsonArray("active") { active.sortedBy { it.value }.forEach { add(it.value) } }
        putJsonArray("selectedTransitions") { selectedTransitions.sortedBy { it.index }.forEach { add("transition:${it.index}") } }
        put("mermaid", chart.toMermaid(active)); put("matrix", chart.transitionMatrix().toMarkdown())
        putJsonObject("coverage") {
            putJsonArray("coveredStates") { coverage.states.covered.sortedBy { it.value }.forEach { add(it.value) } }
            putJsonArray("coveredTransitions") { coverage.transitions.covered.sortedBy { it.index }.forEach { add(it.index) } }
            putJsonArray("guardOutcomes") { coverage.guards.covered.sortedWith(compareBy({ it.transition.index }, { it.result })).forEach { add("${it.transition.index}:${it.result}") } }
        }
        putJsonObject("sources") { sources.entries.sortedBy { it.key }.forEach { (key, source) -> putJsonObject(key) { put("path", source.path); put("line", source.line) } } }
    })
}

/** Data-only artifacts; callers write/upload them with ordinary build tooling. */
fun behaviouralReviewArtifacts(before: BehaviouralSnapshot?, after: BehaviouralSnapshot): Map<String, String> {
    require(before == null || before.definition == after.definition) { "[Actron] Review requires one logical definition" }
    fun declarations(snapshot: BehaviouralSnapshot?, key: String) = snapshot?.model?.getValue(key)?.jsonArray?.toList().orEmpty()
    val behaviour = buildString {
        append("Definition ${after.definition}: ${before?.version ?: "absent"} -> ${after.version}\n")
        for (key in listOf("states", "transitions")) {
            val old = declarations(before, key); val new = declarations(after, key)
            (old - new.toSet()).forEach { append("- $key $it\n") }; (new - old.toSet()).forEach { append("+ $key $it\n") }
        }
        if (before?.model?.get("initial") != after.model["initial"]) append("~ initial ${before?.model?.get("initial")} -> ${after.model["initial"]}\n")
        if (before != null && before.model != after.model && before.version == after.version) append("VERSION BUMP REQUIRED\n")
        append("Rule bodies and runtime settings are opaque; review explicitly.\n")
    }
    fun rows(snapshot: BehaviouralSnapshot?) = snapshot?.model?.getValue("transitions")?.jsonArray.orEmpty()
    fun covered(snapshot: BehaviouralSnapshot?) = snapshot?.coverage?.getValue("coveredTransitions")?.jsonArray?.mapTo(mutableSetOf()) { it.jsonPrimitive.int }.orEmpty()
    val oldRows = rows(before); val newRows = rows(after); val oldCovered = covered(before); val newCovered = covered(after)
    val coverage = buildString {
        // Integer formatting stays identical on JVM, JS, Wasm and Native.
        fun percent(n: Int, total: Int): String {
            val tenths = if (total == 0) 1000L else (1000L * n + total / 2) / total
            return "${tenths / 10}.${tenths % 10}%"
        }
        append("Transition coverage: ${if (before == null) "absent" else percent(oldCovered.size, oldRows.size)} -> ${percent(newCovered.size, newRows.size)}\n")
        newRows.forEachIndexed { index, row -> if (index !in newCovered) {
            val oldIndex = oldRows.indexOf(row)
            append(if (oldIndex < 0) "NEW UNCOVERED " else if (oldIndex in oldCovered) "REGRESSED " else "UNCOVERED ").append(row).append('\n')
        } }
        val oldGuards = before?.coverage?.getValue("guardOutcomes")?.jsonArray?.mapTo(mutableSetOf()) { it.jsonPrimitive.content }.orEmpty()
        val newGuards = after.coverage.getValue("guardOutcomes").jsonArray.mapTo(mutableSetOf()) { it.jsonPrimitive.content }
        newRows.forEachIndexed { index, row -> if (row.jsonObject["guard"] != JsonNull) {
            for (value in listOf(false, true)) if ("$index:$value" !in newGuards) {
                val oldIndex = oldRows.indexOf(row)
                append(if (oldIndex >= 0 && "$oldIndex:$value" in oldGuards) "GUARD REGRESSED " else "GUARD UNCOVERED ")
                    .append("T$index ${row.jsonObject["guard"]} $value\n")
            }
        } }
    }
    return linkedMapOf("behaviour.diff" to behaviour, "coverage.diff" to coverage,
        "before.mmd" to (before?.mermaid ?: "stateDiagram-v2\n"), "after.mmd" to after.mermaid,
        "matrix.md" to after.toJson().let { Json.parseToJsonElement(it).jsonObject.getValue("matrix").jsonPrimitive.content },
        "model.actron.json" to after.toJson())
}

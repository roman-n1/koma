package actron.statechart.test

import actron.core.Action
import actron.core.Event
import actron.statechart.*
import actron.statechart.machine.*
import kotlinx.serialization.json.*
import kotlin.test.*

class BehaviouralArtifactsTest {
    private data object Go : Action
    private val idle = StateId("idle"); private val done = StateId("done")
    private fun machine(version: String, guard: String? = null) = Machine<Unit, Action, Nothing, Event>(DefinitionId("review"), DefinitionVersion(version),
        StateChartDefinition(idle, listOf(AtomicState(idle), FinalState(done)), listOf(Transition(idle, done, ActionMatcher.of<Go>("go"), (guard)?.let { actron.statechart.GuardKey(it) } ?: actron.statechart.GuardCondition.Unconditional)))) {
        if (guard != null) guard(guard) { _, _ -> false }
    }
    private fun coverage(machine: Machine<Unit, Action, Nothing, Event>, covered: Boolean): MachineCoverage =
        machine.runScenario(machine.initialSnapshot(Unit), MachineScenario("case", listOf(MachineInput.Start(MachineTime.Zero)) +
            if (covered) listOf(MachineInput.Dispatch(Go, MachineTime.Zero)) else emptyList())).coverage

    @Test fun ciArtifactsRoundTripAndRevealCoverageRegressionWithoutPayloads() {
        val machine = machine("1")
        val before = machine.behaviouralSnapshot(coverage(machine, true))
        val after = machine.behaviouralSnapshot(coverage(machine, false), mapOf("state:idle" to ModelSource("src/Workflow.kt", 3)))
        val restored = BehaviouralSnapshot.fromJson(after.toJson())
        val files = behaviouralReviewArtifacts(before, restored)
        assertEquals(setOf("behaviour.diff", "coverage.diff", "before.mmd", "after.mmd", "matrix.md", "model.actron.json"), files.keys)
        assertTrue(files.getValue("coverage.diff").contains("100.0% -> 0.0%"))
        assertTrue(files.getValue("coverage.diff").contains("REGRESSED"))
        assertEquals(after.toJson(), restored.toJson())
        assertFalse(after.toJson().contains("context"))
    }

    @Test fun changedGuardWithSameVersionRequiresBumpAndUncoveredNewDeclarationIsReported() {
        val before = machine("1"); val after = machine("1", "blocked")
        val files = behaviouralReviewArtifacts(before.behaviouralSnapshot(coverage(before, true)), after.behaviouralSnapshot(coverage(after, false)))
        assertTrue(files.getValue("behaviour.diff").contains("VERSION BUMP REQUIRED"))
        assertTrue(files.getValue("coverage.diff").contains("NEW UNCOVERED"))
        assertFailsWith<IllegalArgumentException> { after.behaviouralSnapshot(coverage(before, true).copy(version = DefinitionVersion("other"))) }
        assertFailsWith<IllegalArgumentException> { ModelSource("../secrets", 1) }
        assertFailsWith<IllegalArgumentException> { ModelSource("/absolute", 1) }
        assertFailsWith<IllegalArgumentException> { ModelSource("src/Workflow\n.kt", 1) }
        assertFailsWith<IllegalArgumentException> { BehaviouralSnapshot.fromJson(after.behaviouralSnapshot(coverage(after, false)).toJson().replace("\"format\":1", "\"format\":2")) }
    }

    @Test fun objectPropertyOrderingDoesNotChangeBehaviourOrInventCoverageRegression() {
        val machine = machine("1")
        val before = machine.behaviouralSnapshot(coverage(machine, true))
        fun reorder(element: JsonElement): JsonElement = when (element) {
            is JsonObject -> JsonObject(element.entries.reversed().associate { it.key to reorder(it.value) })
            is JsonArray -> JsonArray(element.map(::reorder))
            else -> element
        }
        val after = BehaviouralSnapshot.fromJson(reorder(Json.parseToJsonElement(before.toJson())).toString())
        val files = behaviouralReviewArtifacts(before, after)
        assertFalse(files.getValue("behaviour.diff").contains("VERSION BUMP REQUIRED"))
        assertFalse(files.getValue("behaviour.diff").lineSequence().any { it.startsWith("+") || it.startsWith("-") })
        assertFalse(files.getValue("coverage.diff").contains("REGRESSED"))
        assertTrue(files.getValue("coverage.diff").contains("100.0% -> 100.0%"))
    }
}

package actron.review

import actron.core.*
import actron.statechart.*
import actron.statechart.machine.*
import actron.statechart.test.*
import java.io.File

private data class Send(val online: Boolean) : Action
private data object Retry : Action

private fun machine(version: String, extraRetry: Boolean): Machine<Unit, Action, Nothing, Event> {
    val idle = StateId("idle"); val sent = StateId("sent")
    return Machine(DefinitionId("messenger-send-review"), DefinitionVersion(version), StateChartDefinition(idle,
        listOf(AtomicState(idle), AtomicState(sent)), listOf(Transition(idle, sent, ActionMatcher.of<Send>("send"), guard = "online")) +
            if (extraRetry) listOf(Transition(sent, idle, ActionMatcher.of<Retry>("retry"))) else emptyList())) {
        guard("online", "An online connection is required") { _, action -> (action as Send).online }
    }
}

/** Real model execution, coverage and exported artifacts used by the PR integration. */
fun main(args: Array<String>) {
    val output = File(args[0]).apply { mkdirs() }
    val baseline = args.getOrNull(1)?.let(::File)?.takeIf { it.isFile }?.readText()?.let(BehaviouralSnapshot::fromJson)
    val current = machine("2", true)
    val generator = current.inputGenerator(listOf(actionGenerator<Unit, Send>("send") { _, _ -> listOf(Send(false), Send(true)) },
        actionGenerator<Unit, Retry>("retry") { _, _ -> listOf(Retry) }))
    val initial = current.initialSnapshot(Unit)
    val target = CoverageTarget.Custom(current.chart.requirements(CoverageTarget.AllTransitions) + current.chart.requirements(CoverageTarget.AllGuardOutcomes))
    val plan = current.generateTestPlan(initial, generator, target, maxDepth = 3)
    val result = current.runPlan(initial, plan)
    result.assertSuccess()
    val scenario = plan.plan.scenarios.first()
    val prefix = current.runScenario(initial, MachineScenario("frame-prefix", scenario.inputs.dropLast(1)))
    val frame = current.decideExplained(prefix.snapshot, scenario.inputs.last())
    val sourcePath = "verification/behaviour-review/src/jvmMain/kotlin/actron/review/Review.kt"
    val after = current.behaviouralSnapshot(result.result.coverage, mapOf(
        "state:idle" to ModelSource(sourcePath, 13), "state:sent" to ModelSource(sourcePath, 13),
        "transition:0" to ModelSource(sourcePath, 15), "transition:1" to ModelSource(sourcePath, 16)),
        active = frame.decision.snapshot.configuration.active, selectedTransitions = frame.decision.transitions.toSet())
    val artifacts = behaviouralReviewArtifacts(baseline, after)
    artifacts.forEach { (name, text) -> File(output, name).writeText(text) }
    check(!artifacts.getValue("behaviour.diff").contains("VERSION BUMP REQUIRED")) { "Version unchanged after structural behaviour change" }
    check(!artifacts.getValue("coverage.diff").contains("REGRESSED")) { "Behavioural coverage regressed" }
    File(output, "coverage.txt").writeText(result.result.coverage.describe(current.chart) + "\n" + result.result.coverage.describeGuards(current.chart))
    println("Behavioural review exported to $output; ${plan.plan.scenarios.size} executable scenarios")
}

package koma.statechart.test

import koma.core.Action
import koma.core.Event
import koma.core.ExperimentalKomaApi
import koma.statechart.machine.*

/** Minimum scenario count in the bounded discovered pool for explicit states/branches/paths. */
data class TargetedTestPlan<C, A : Action>(
    val plan: MachineTestPlan<C, A>, val requirements: CoverageRequirements, val missing: CoverageRequirements,
) {
    fun assertReady(requireOptimal: Boolean = false) {
        plan.assertReady(requireOptimal)
        if (!missing.isEmpty) throw AssertionError("[Koma] Uncovered requirements: $missing")
    }
}

data class TargetedTestPlanResult<C, A : Action>(val plan: TargetedTestPlan<C, A>, val result: TestPlanResult<C, A>) {
    fun assertSuccess() {
        plan.assertReady()
        result.assertSuccess()
        val missing = plan.requirements.missing(result.coverage)
        if (!missing.isEmpty) throw AssertionError("[Koma] Replay missed requirements: $missing")
    }
}

private fun CoverageRequirements.items(): Set<String> = states.map { "state:${it.value}" }.toSet() +
    transitions.map { "transition:${it.index}" } + guards.map { "guard:${it.transition.index}:${it.result}" }

fun <C, A : Action, CMD, E : Event> Machine<C, A, CMD, E>.generateTestPlan(
    initial: MachineSnapshot<C>, generator: MachineInputGenerator<C, A>, target: CoverageTarget,
    maxDepth: Int = 10, maxDecisions: Int = 10_000, maxFailures: Int = 1, maxSelectionAttempts: Int = 10_000,
    now: MachineTime = MachineTime.Zero,
): TargetedTestPlan<C, A> {
    require(maxSelectionAttempts > 0) { "[Koma] Selection needs a positive budget" }
    val requirements = chart.requirements(target)
    // Full bounded BFS can find prefixes combining more obligations than early-stop exploration.
    val detailed = exploreDetailed(initial, generator, maxDepth = maxDepth, maxDecisions = maxDecisions, maxFailures = maxFailures, now = now)
    val report = detailed.report
    val reached = requirements.items() - requirements.missing(report.coverage).items()
    val candidates = detailed.scenarios
    val sets = candidates.map { candidate ->
        val coverage = candidate.coverage
        reached - requirements.missing(coverage).items()
    }
    val selection = selectCoverage(sets, candidates.map { it.scenario.inputs.size }, reached, maxSelectionAttempts)
    val scenarios = if (reached.isEmpty()) listOf(candidates.minByOrNull { it.scenario.inputs.size }?.scenario ?: MachineScenario("initial", emptyList()))
        else selection.indices.map { candidates[it].scenario }
    val missing = requirements.missing(report.coverage)
    return TargetedTestPlan(MachineTestPlan(id, version, scenarios, requirements.transitions, missing.transitions,
        report.failures, report.truncated, selection.optimal, selection.attempts), requirements, missing)
}

fun <C, A : Action, CMD, E : Event> Machine<C, A, CMD, E>.runPlan(
    initial: MachineSnapshot<C>, plan: TargetedTestPlan<C, A>,
): TargetedTestPlanResult<C, A> = TargetedTestPlanResult(plan, runPlan(initial, plan.plan))

@ExperimentalKomaApi
fun <C, A : Action, CMD, E : Event> MachineTestDriver<C, A, CMD, E>.explore(
    generator: MachineInputGenerator<C, A>, strategy: ExplorationStrategy = ExplorationStrategy.BreadthFirst,
    maxDepth: Int = 10, maxDecisions: Int = 10_000,
): ExplorationReport<C, A> = machine.explore(initialSnapshot, generator, strategy, maxDepth, maxDecisions)

@ExperimentalKomaApi
fun <C, A : Action, CMD, E : Event> MachineTestDriver<C, A, CMD, E>.generateTestPlan(
    generator: MachineInputGenerator<C, A>, target: CoverageTarget, maxDepth: Int = 10, maxDecisions: Int = 10_000,
): TargetedTestPlan<C, A> = machine.generateTestPlan(initialSnapshot, generator, target, maxDepth, maxDecisions)

@ExperimentalKomaApi
fun <C, A : Action, CMD, E : Event> MachineTestDriver<C, A, CMD, E>.verifyPlan(plan: TargetedTestPlan<C, A>): TargetedTestPlanResult<C, A> =
    machine.runPlan(initialSnapshot, plan)

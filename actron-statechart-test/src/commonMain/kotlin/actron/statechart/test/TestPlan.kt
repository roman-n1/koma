package actron.statechart.test

import actron.core.Action
import actron.core.Event
import actron.statechart.machine.*

/** Executable transition-coverage plan. Optimality is within the prefixes discovered by the bounded search. */
data class MachineTestPlan<C, A : Action>(
    val definition: DefinitionId,
    val version: DefinitionVersion,
    val scenarios: List<MachineScenario<A>>,
    val requiredTransitions: Set<TransitionId>,
    val uncoveredTransitions: Set<TransitionId>,
    val discoveryFailures: List<SequenceFailure<C, A>>,
    val searchTruncated: Boolean,
    val selectionOptimal: Boolean,
    val selectionAttempts: Int,
) {
    /** Fails on discovery errors, incomplete search or missing transitions; a bounded heuristic selection may still cover all. */
    fun assertReady(requireOptimal: Boolean = false) {
        if (discoveryFailures.isNotEmpty()) throw AssertionError("[Actron] Plan discovery failed: ${discoveryFailures.first().identities}")
        if (searchTruncated) throw AssertionError("[Actron] Plan discovery budget exhausted")
        if (uncoveredTransitions.isNotEmpty()) throw AssertionError("[Actron] Plan has uncovered transitions: $uncoveredTransitions")
        if (requireOptimal && !selectionOptimal) throw AssertionError("[Actron] Plan selection budget exhausted; minimal scenario count is unknown")
    }
}

/** Result of running one typed scenario without command handlers or external IO. */
data class ScenarioResult<C, A : Action>(
    val scenario: MachineScenario<A>,
    val snapshot: MachineSnapshot<C>,
    val coverage: MachineCoverage,
    val failure: SequenceFailure<C, A>? = null,
)

/** Actual coverage of the selected scenarios, not the exploration's larger candidate pool. */
data class TestPlanResult<C, A : Action>(
    val plan: MachineTestPlan<C, A>,
    val scenarios: List<ScenarioResult<C, A>>,
    val coverage: MachineCoverage,
) {
    val failures: List<SequenceFailure<C, A>> get() = scenarios.mapNotNull { it.failure }

    fun assertSuccess() {
        plan.assertReady()
        if (failures.isNotEmpty()) throw AssertionError("[Actron] Test plan failed: ${failures.first().identities}")
        coverage.assertTransitionsCovered(plan.requiredTransitions)
    }
}

internal data class Selection(val indices: List<Int>, val attempts: Int, val optimal: Boolean)

/** Bounded exact set cover, seeded by a complete greedy cover so budget exhaustion never drops coverage. */
internal fun <T> selectCoverage(candidates: List<Set<T>>, lengths: List<Int>, target: Set<T>, maxAttempts: Int): Selection {
    if (target.isEmpty()) return Selection(emptyList(), 0, true)
    var remaining = target
    val greedy = mutableListOf<Int>()
    while (remaining.isNotEmpty()) {
        val best = candidates.indices.maxWithOrNull(compareBy<Int>(
            { (candidates[it] intersect remaining).size }, { -lengths[it] }, { -it },
        )) ?: error("[Actron] No scenario covers $remaining")
        check(candidates[best].any { it in remaining }) { "[Actron] Scenario coverage is inconsistent" }
        greedy += best
        remaining = remaining - candidates[best]
    }
    var best = greedy.toList()
    var attempts = 0
    var exhausted = false
    val visited = mutableMapOf<Set<T>, Int>()
    fun search(missing: Set<T>, chosen: List<Int>) {
        if (attempts >= maxAttempts) { exhausted = true; return }
        attempts++
        if (missing.isEmpty()) {
            if (chosen.size < best.size) best = chosen
            return
        }
        if (chosen.size >= best.size) return
        val previous = visited[missing]
        if (previous != null && previous <= chosen.size) return
        visited[missing] = chosen.size
        val next = missing.minBy { id -> candidates.count { id in it } }
        val choices = candidates.indices.filter { next in candidates[it] }
            .sortedWith(compareByDescending<Int> { (candidates[it] intersect missing).size }
                .thenBy { lengths[it] }.thenBy { it })
        for (index in choices) {
            search(missing - candidates[index], chosen + index)
            if (exhausted) return
        }
    }
    search(target, emptyList())
    return Selection(best.sorted(), attempts, !exhausted)
}

/**
 * Generates typed executable prefixes, then finds the fewest scenarios covering every reached
 * transition within that finite pool. Exact selection has its own budget; the fallback retains
 * coverage and reports unknown optimality. Unreachable/unsupplied transitions remain uncovered.
 * Breadth-first discovery can find longer prefixes that combine several branches; AllTransitions
 * stops earlier. Neither claims global optimality beyond supplied payloads and search depth.
 */
fun <C, A : Action, CMD, E : Event> Machine<C, A, CMD, E>.generateTestPlan(
    initial: MachineSnapshot<C>,
    generator: MachineInputGenerator<C, A>,
    strategy: ExplorationStrategy = ExplorationStrategy.BreadthFirst,
    maxDepth: Int = 10,
    maxDecisions: Int = 10_000,
    maxFailures: Int = 1,
    maxSelectionAttempts: Int = 10_000,
    now: MachineTime = MachineTime.Zero,
): MachineTestPlan<C, A> {
    require(maxSelectionAttempts > 0) { "[Actron] Test plan selection needs a positive budget" }
    val report = explore(initial, generator, strategy, maxDepth, maxDecisions, maxFailures, now)
    val target = report.coverage.transitions.covered intersect report.coverage.transitions.expected
    val candidates = report.coverageScenarios.filter { it.transitions.isNotEmpty() }
    val selection = selectCoverage(candidates.map { it.transitions }, candidates.map { it.scenario.inputs.size }, target, maxSelectionAttempts)
    val selected = if (target.isEmpty()) listOf(report.scenarios.minByOrNull { it.inputs.size } ?: MachineScenario("initial", emptyList()))
        else selection.indices.map { candidates[it].scenario }
    return MachineTestPlan(id, version, selected, report.coverage.transitions.expected, report.coverage.transitions.missing,
        report.failures, report.truncated, selection.optimal, selection.attempts)
}

/** Runs actual inputs from the same initial data; checks invariants after each stable decision. Never executes returned intents. */
fun <C, A : Action, CMD, E : Event> Machine<C, A, CMD, E>.runScenario(
    initial: MachineSnapshot<C>, scenario: MachineScenario<A>,
): ScenarioResult<C, A> {
    require(initial.definition == id && initial.version == version) { "[Actron] Scenario snapshot belongs to another machine or version" }
    val recorder = MachineCoverageRecorder(this)
    recorder.observeInitial(initial)
    var snapshot = initial
    val initialViolations = if (initial.isStarted) checkInvariants(initial) else emptyList()
    if (initialViolations.isNotEmpty()) return ScenarioResult(scenario, initial, recorder.snapshot(), SequenceFailure(emptyList(), initial, initialViolations))
    val prefix = mutableListOf<MachineInput<A>>()
    var time: MachineTime? = null
    for (input in scenario.inputs) {
        require(time == null || input.now >= time) { "[Actron] Scenario moved logical time backwards" }
        time = input.now
        prefix += input
        val explained = decideExplained(snapshot, input)
        recorder.accept(explained)
        snapshot = explained.decision.snapshot
        val failure = sequenceFailure(prefix, explained.decision)
        if (failure != null) return ScenarioResult(scenario, snapshot, recorder.snapshot(), failure)
    }
    return ScenarioResult(scenario, snapshot, recorder.snapshot())
}

/** Replays each selected scenario independently, preserving payloads, command ids and virtual times. */
fun <C, A : Action, CMD, E : Event> Machine<C, A, CMD, E>.runPlan(
    initial: MachineSnapshot<C>, plan: MachineTestPlan<C, A>,
): TestPlanResult<C, A> {
    require(plan.definition == id && plan.version == version) { "[Actron] Test plan belongs to another machine or version" }
    require(plan.requiredTransitions.all { it.index in chart.transitions.indices }) { "[Actron] Plan requires an undeclared transition" }
    val results = plan.scenarios.map { runScenario(initial, it) }
    val empty = MachineCoverageRecorder(this).snapshot()
    return TestPlanResult(plan, results, results.fold(empty) { coverage, result -> coverage.merge(result.coverage) })
}

/** IO-free model discovery alongside this driver's live tests. It does not mutate the live Store. */
@actron.core.ExperimentalActronApi
fun <C, A : Action, CMD, E : Event> MachineTestDriver<C, A, CMD, E>.generateTestPlan(
    generator: MachineInputGenerator<C, A>, maxDepth: Int = 10, maxDecisions: Int = 10_000,
): MachineTestPlan<C, A> = machine.generateTestPlan(initialSnapshot, generator, maxDepth = maxDepth, maxDecisions = maxDecisions)

/** Verifies a plan against independent pure snapshots; live command handlers and Store state are untouched. */
@actron.core.ExperimentalActronApi
fun <C, A : Action, CMD, E : Event> MachineTestDriver<C, A, CMD, E>.verifyPlan(plan: MachineTestPlan<C, A>): TestPlanResult<C, A> =
    machine.runPlan(initialSnapshot, plan)

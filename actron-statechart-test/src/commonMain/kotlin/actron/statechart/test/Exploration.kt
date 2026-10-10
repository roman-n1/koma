package actron.statechart.test

import actron.core.Action
import actron.core.Event
import actron.observability.FailureDescriptor
import actron.statechart.StateId
import actron.statechart.machine.*
import kotlin.random.Random

/** Supplies typed payloads and command answers; a deterministic function of snapshot and time. */
fun interface MachineInputGenerator<C, A : Action> {
    fun inputs(snapshot: MachineSnapshot<C>, now: MachineTime): List<MachineInput<A>>
}

/** Adds timers in deadline/id order. They fire at their deadline, or now when overdue. */
fun <C, A : Action> MachineInputGenerator<C, A>.withTimers(): MachineInputGenerator<C, A> =
    MachineInputGenerator { snapshot, now ->
        inputs(snapshot, now) + snapshot.timers.entries
            .sortedWith(compareBy({ it.value.deadline }, { it.key.value }))
            .map { (id, timer) -> MachineInput.TimerFired(id, maxOf(now, timer.deadline)) }
    }

/** Search strategies; both stop at the same explicit depth/decision limits. */
sealed interface ExplorationStrategy {
    /** Enumerates input sequences breadth first without merging different contexts or histories. */
    data object BreadthFirst : ExplorationStrategy

    /** Breadth-first search stopping once every declared transition was actually taken. */
    data object AllTransitions : ExplorationStrategy

    /** Stops at a finite coverage target; missing obligations remain visible in the report. */
    data class Cover(val target: CoverageTarget) : ExplorationStrategy

    /** Reproducible random walks through the application's generated input choices. */
    data class RandomWalk(val seed: Int, val runs: Int = 100) : ExplorationStrategy {
        init { require(runs > 0) { "[Actron] Random walks need at least one run" } }
    }
}

/** Coverage uses declared nodes/transitions; unreachable declarations remain visible as missing. */
data class CoverageMetric<T>(val expected: Set<T>, val covered: Set<T>) {
    val missing: Set<T> get() = expected - covered
    val percent: Double get() = if (expected.isEmpty()) 100.0 else 100.0 * (covered intersect expected).size / expected.size
}

/** A guard branch of a particular transition, rather than just a shared guard label. */
data class GuardOutcome(val transition: TransitionId, val result: Boolean)

/** Coverage from actual decisions, including timer transitions and guard rejections. */
data class MachineCoverage(
    val states: CoverageMetric<StateId>,
    val transitions: CoverageMetric<TransitionId>,
    val guards: CoverageMetric<GuardOutcome>,
    val timers: CoverageMetric<TransitionId>,
    val definition: DefinitionId? = null,
    val version: DefinitionVersion? = null,
)

/** The first failure of an executable prefix, with its stable snapshot and actual input values. */
data class SequenceFailure<C, A : Action>(
    val inputs: List<MachineInput<A>>,
    val snapshot: MachineSnapshot<C>,
    val violations: List<InvariantViolation> = emptyList(),
    val decisionFailure: FailureDescriptor? = null,
) {
    /** Stable failure identities used by the shrinker; payloads and exception messages are excluded. */
    val identities: Set<String> get() = violations.mapTo(linkedSetOf()) {
        "invariant:${it.name}:${it.failure?.type ?: "false"}"
    } + listOfNotNull(decisionFailure?.let { "decision:${it.type}" })
}

/** An executable generated scenario with actual typed payloads and virtual times. */
data class MachineScenario<A : Action>(val name: String, val inputs: List<MachineInput<A>>)

/** Coverage of an executable prefix, captured during exploration rather than inferred from its graph. */
data class ScenarioCoverage<A : Action>(val scenario: MachineScenario<A>, val transitions: Set<TransitionId>)

/** Actual prefix observations, separate from the original transition-only record's stable ABI. */
data class BehaviouralScenarioCoverage<A : Action>(val scenario: MachineScenario<A>, val coverage: MachineCoverage)
data class BehaviouralExplorationReport<C, A : Action>(val report: ExplorationReport<C, A>, val scenarios: List<BehaviouralScenarioCoverage<A>>)

/** Search results. [truncated] means a decision/failure budget stopped work, not proof of safety. */
data class ExplorationReport<C, A : Action>(
    val decisions: Int,
    val invariantsChecked: Long,
    val coverage: MachineCoverage,
    val failures: List<SequenceFailure<C, A>>,
    val truncated: Boolean,
    val maxDepth: Int,
    val scenarios: List<MachineScenario<A>> = emptyList(),
    val coverageScenarios: List<ScenarioCoverage<A>> = emptyList(),
) {
    /** An assertion with the failing prefix; shrink it with [Machine.shrink] before saving a regression. */
    fun assertSuccess() {
        if (failures.isNotEmpty()) {
            val failure = failures.first()
            throw AssertionError("[Actron] Sequence failed: ${failure.identities}\nInputs: ${failure.inputs}\nSnapshot: ${failure.snapshot}")
        }
        if (truncated) throw AssertionError("[Actron] Exploration budget exhausted; coverage is incomplete")
    }
}

internal fun <C, A : Action, CMD, E : Event> Machine<C, A, CMD, E>.sequenceFailure(
    inputs: List<MachineInput<A>>, decision: Decision<C, CMD, E>,
): SequenceFailure<C, A>? {
    val outcome = decision.outcome as? DecisionOutcome.Failed
    // Runtime enforcement has rolled back the invalid snapshot, but keeps invariant identities.
    val enforced = outcome?.cause as? InvariantViolationException
    val violations = enforced?.violations ?: checkInvariants(decision.snapshot)
    return if (violations.isEmpty() && outcome == null) null else SequenceFailure(
        inputs.toList(), decision.snapshot, violations,
        decisionFailure = outcome?.failure?.takeIf { enforced == null },
    )
}

/**
 * Executes model-based scenarios without IO. Starts an unstarted snapshot once, then explores
 * supplied typed inputs (use [withTimers] for timer choices). Each prefix is checked, including
 * ignored actions and action handlers. Paths are not merged by active configuration.
 *
 * This is a bounded search, not a proof for arbitrary depth or arbitrary action payloads.
 * Generators must be pure and return finite choices whose times do not go backwards.
 */
fun <C, A : Action, CMD, E : Event> Machine<C, A, CMD, E>.explore(
    initial: MachineSnapshot<C>,
    generator: MachineInputGenerator<C, A>,
    strategy: ExplorationStrategy = ExplorationStrategy.BreadthFirst,
    maxDepth: Int = 10,
    maxDecisions: Int = 10_000,
    maxFailures: Int = 1,
    now: MachineTime = MachineTime.Zero,
): ExplorationReport<C, A> = exploreDetailed(initial, generator, strategy, maxDepth, maxDecisions, maxFailures, now).report

/** Per-prefix state/guard observations captured during the same decisions, without replaying metadata. */
fun <C, A : Action, CMD, E : Event> Machine<C, A, CMD, E>.exploreDetailed(
    initial: MachineSnapshot<C>, generator: MachineInputGenerator<C, A>,
    strategy: ExplorationStrategy = ExplorationStrategy.BreadthFirst,
    maxDepth: Int = 10, maxDecisions: Int = 10_000, maxFailures: Int = 1, now: MachineTime = MachineTime.Zero,
): BehaviouralExplorationReport<C, A> {
    require(maxDepth >= 0 && maxDecisions > 0 && maxFailures > 0) { "[Actron] Invalid exploration limits" }
    val coverage = MachineCoverageRecorder(this)
    val failures = mutableListOf<SequenceFailure<C, A>>()
    var decisions = 0
    var checked = 0L
    var truncated = false
    val scenarios = linkedMapOf<MachineCoverage, MachineScenario<A>>()
    val emptyCoverage = coverage.snapshot()
    val requirements = when (strategy) {
        ExplorationStrategy.AllTransitions -> chart.requirements(CoverageTarget.AllTransitions)
        is ExplorationStrategy.Cover -> chart.requirements(strategy.target)
        else -> null
    }
    fun goalMet(): Boolean = requirements?.missing(coverage.snapshot())?.isEmpty == true
    data class Path<C, A : Action>(val snapshot: MachineSnapshot<C>, val inputs: List<MachineInput<A>>, val now: MachineTime, val depth: Int, val transitions: Set<TransitionId> = emptySet(), val localCoverage: MachineCoverage)

    fun decidePath(path: Path<C, A>, input: MachineInput<A>, depth: Int): Path<C, A>? {
        if (decisions >= maxDecisions || failures.size >= maxFailures) { truncated = true; return null }
        require(input.now >= path.now) { "[Actron] Generator moved virtual time backwards" }
        val explained = decideExplained(path.snapshot, input)
        decisions++
        coverage.accept(explained)
        checked += invariants.size
        val inputs = path.inputs + input
        val covered = path.transitions + explained.decision.transitions
        val local = path.localCoverage.copy(
            states = path.localCoverage.states.copy(covered = path.localCoverage.states.covered + explained.explanation.active +
                explained.decision.snapshot.configuration.active + explained.decision.entered.map { it.node }),
            transitions = path.localCoverage.transitions.copy(covered = covered),
            guards = path.localCoverage.guards.copy(covered = path.localCoverage.guards.covered + explained.explanation.guards.mapNotNull {
                it.result?.let { result -> GuardOutcome(it.transition, result) } }),
            timers = path.localCoverage.timers.copy(covered = covered intersect path.localCoverage.timers.expected),
        )
        val previous = scenarios[local]
        if (previous == null || inputs.size < previous.inputs.size) {
            scenarios[local] = MachineScenario(previous?.name ?: "scenario-${scenarios.size + 1}", inputs.toList())
        }
        val failure = sequenceFailure(inputs, explained.decision)
        if (failure != null) { failures += failure; return null }
        return Path(explained.decision.snapshot, inputs, input.now, depth, covered, local)
    }

    require(initial.definition == id && initial.version == version) { "[Actron] Snapshot belongs to another machine or version" }
    var start: Path<C, A>? = Path(initial, emptyList(), now, 0, localCoverage = emptyCoverage.copy(states = emptyCoverage.states.copy(covered = initial.configuration.active)))
    if (!initial.isStarted) {
        start = decidePath(start!!, MachineInput.Start(now), 0)
    } else {
        coverage.observeInitial(initial)
        scenarios[start!!.localCoverage] = MachineScenario("initial", emptyList())
        checked += invariants.size
        val violations = checkInvariants(initial)
        if (violations.isNotEmpty()) {
            failures += SequenceFailure(emptyList(), initial, violations)
            start = null
        }
    }
    when (strategy) {
        ExplorationStrategy.BreadthFirst, ExplorationStrategy.AllTransitions, is ExplorationStrategy.Cover -> {
            val queue = ArrayDeque<Path<C, A>>()
            start?.let(queue::addLast)
            while (queue.isNotEmpty() && !truncated) {
                if (goalMet()) break
                val path = queue.removeFirst()
                if (path.depth == maxDepth) continue
                for (input in generator.inputs(path.snapshot, path.now)) {
                    val next = decidePath(path, input, path.depth + 1)
                    if (next != null) queue.addLast(next)
                    if (truncated || goalMet()) break
                }
            }
        }
        is ExplorationStrategy.RandomWalk -> {
            val random = Random(strategy.seed)
            repeat(strategy.runs) {
                var path = start
                while (path != null && path.depth < maxDepth && !truncated) {
                    val choices = generator.inputs(path.snapshot, path.now)
                    if (choices.isEmpty()) break
                    path = decidePath(path, choices[random.nextInt(choices.size)], path.depth + 1)
                }
            }
        }
    }
    val report = ExplorationReport(decisions, checked, coverage.snapshot(), failures.toList(), truncated, maxDepth, scenarios.values.toList(),
        scenarios.map { (observations, scenario) -> ScenarioCoverage(scenario, observations.transitions.covered) })
    return BehaviouralExplorationReport(report, scenarios.map { (observations, scenario) -> BehaviouralScenarioCoverage(scenario, observations) })
}

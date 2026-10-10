package actron.statechart.test

import actron.core.Action
import actron.core.Event
import actron.observability.FailureDescriptor
import actron.statechart.StateId
import actron.statechart.machine.*
import kotlin.random.Random

/** Supplies typed payloads and command answers; a deterministic function of snapshot and time. */
fun interface MachineInputGenerator<C : Any, A : Action> {
    fun inputs(snapshot: MachineSnapshot<C>, now: MachineTime): List<MachineInput<A>>
}

/** Adds timers in deadline/id order. They fire at their deadline, or now when overdue. */
fun <C : Any, A : Action> MachineInputGenerator<C, A>.withTimers(): MachineInputGenerator<C, A> =
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
data class CoverageMetric<T : Any>(val expected: Set<T>, val covered: Set<T>) {
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
    val definition: DefinitionId,
    val version: DefinitionVersion,
)

/** Actual problems observed while assessing a prefix. Enforcement retains rollback identities. */
sealed interface SequenceProblem {
    val identities: Set<String>
    val violations: List<InvariantViolation>

    data class Invariants(override val violations: List<InvariantViolation>) : SequenceProblem {
        override val identities: Set<String> get() = violations.mapTo(linkedSetOf()) { it.identity }
    }
    data class Execution(val failure: FailureDescriptor) : SequenceProblem {
        override val identities: Set<String> get() = setOf("decision:${failure.typeLabel}")
        override val violations: List<InvariantViolation> = emptyList()
    }
}

/** The first failing executable prefix, with the stable snapshot and actual observed problems. */
data class SequenceFailure<C : Any, A : Action>(
    val inputs: List<MachineInput<A>>,
    val snapshot: MachineSnapshot<C>,
    val problems: List<SequenceProblem>,
) {
    val violations: List<InvariantViolation> get() = problems.flatMap { it.violations }
    /** Stable failure identities exclude payloads and exception messages. */
    val identities: Set<String> get() = problems.flatMapTo(linkedSetOf()) { it.identities }
}

/** An executable generated scenario with actual typed payloads and virtual times. */
data class MachineScenario<A : Action>(val name: String, val inputs: List<MachineInput<A>>)

/** Coverage of an executable prefix, captured during exploration rather than inferred from its graph. */
data class ScenarioCoverage<A : Action>(val scenario: MachineScenario<A>, val transitions: Set<TransitionId>)

/** Actual prefix observations, separate from the original transition-only record's stable ABI. */
data class BehaviouralScenarioCoverage<A : Action>(val scenario: MachineScenario<A>, val coverage: MachineCoverage)
data class BehaviouralExplorationReport<C : Any, A : Action>(val report: ExplorationReport<C, A>, val scenarios: List<BehaviouralScenarioCoverage<A>>)

/** Search results. [truncated] means a decision/failure budget stopped work, not proof of safety. */
data class ExplorationReport<C : Any, A : Action>(
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

internal fun <C : Any, A : Action, CMD : Any, E : Event> Machine<C, A, CMD, E>.sequenceFailure(
    inputs: List<MachineInput<A>>, decision: Decision<C, CMD, E>, accept: (SequenceFailure<C, A>) -> Unit,
): Boolean {
    val outcome = decision.outcome
    val problems = buildList {
        if (outcome is DecisionOutcome.Failed) {
            val cause = outcome.cause
            if (cause is InvariantViolationException) {
                // Enforcement has rolled back the invalid snapshot but retains the rejected identities.
                add(SequenceProblem.Invariants(cause.violations))
                return@buildList
            }
        }
        val violations = checkInvariants(decision.snapshot)
        if (violations.isNotEmpty()) add(SequenceProblem.Invariants(violations))
        if (outcome is DecisionOutcome.Failed) add(SequenceProblem.Execution(outcome.failure))
    }
    if (problems.isEmpty()) return false
    accept(SequenceFailure(inputs.toList(), decision.snapshot, problems))
    return true
}

/**
 * Executes model-based scenarios without IO. Starts an unstarted snapshot once, then explores
 * supplied typed inputs (use [withTimers] for timer choices). Each prefix is checked, including
 * ignored actions and action handlers. Paths are not merged by active configuration.
 *
 * This is a bounded search, not a proof for arbitrary depth or arbitrary action payloads.
 * Generators must be pure and return finite choices whose times do not go backwards.
 */
fun <C : Any, A : Action, CMD : Any, E : Event> Machine<C, A, CMD, E>.explore(
    initial: MachineSnapshot<C>,
    generator: MachineInputGenerator<C, A>,
    strategy: ExplorationStrategy = ExplorationStrategy.BreadthFirst,
    maxDepth: Int = 10,
    maxDecisions: Int = 10_000,
    maxFailures: Int = 1,
    now: MachineTime = MachineTime.Zero,
): ExplorationReport<C, A> = exploreDetailed(initial, generator, strategy, maxDepth, maxDecisions, maxFailures, now).report

/** Per-prefix state/guard observations captured during the same decisions, without replaying metadata. */
fun <C : Any, A : Action, CMD : Any, E : Event> Machine<C, A, CMD, E>.exploreDetailed(
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
    val goalMet: () -> Boolean = when (strategy) {
        ExplorationStrategy.AllTransitions -> {
            val requirements = chart.requirements(CoverageTarget.AllTransitions)
            fun(): Boolean = requirements.missing(coverage.snapshot()).isEmpty
        }
        is ExplorationStrategy.Cover -> {
            val requirements = chart.requirements(strategy.target)
            fun(): Boolean = requirements.missing(coverage.snapshot()).isEmpty
        }
        else -> { { false } }
    }
    data class Path<C : Any, A : Action>(val snapshot: MachineSnapshot<C>, val inputs: List<MachineInput<A>>, val now: MachineTime, val depth: Int, val transitions: Set<TransitionId> = emptySet(), val localCoverage: MachineCoverage)

    fun decidePath(path: Path<C, A>, input: MachineInput<A>, depth: Int, advance: (Path<C, A>) -> Unit): Boolean {
        if (decisions >= maxDecisions || failures.size >= maxFailures) { truncated = true; return false }
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
            guards = path.localCoverage.guards.copy(covered = path.localCoverage.guards.covered + explained.explanation.guards
                .filter { it.result == actron.statechart.GuardCheck.Allowed || it.result == actron.statechart.GuardCheck.Rejected }
                .map { GuardOutcome(it.transition, it.result == actron.statechart.GuardCheck.Allowed) }),
            timers = path.localCoverage.timers.copy(covered = covered intersect path.localCoverage.timers.expected),
        )
        if (local !in scenarios) {
            scenarios[local] = MachineScenario("scenario-${scenarios.size + 1}", inputs.toList())
        } else {
            val previous = scenarios.getValue(local)
            if (inputs.size < previous.inputs.size) scenarios[local] = previous.copy(inputs = inputs.toList())
        }
        if (sequenceFailure(inputs, explained.decision, failures::add)) return false
        advance(Path(explained.decision.snapshot, inputs, input.now, depth, covered, local))
        return true
    }

    require(initial.definition == id && initial.version == version) { "[Actron] Snapshot belongs to another machine or version" }
    val initialPath = Path<C, A>(initial, emptyList(), now, 0, localCoverage = emptyCoverage.copy(states = emptyCoverage.states.copy(covered = initial.configuration.active)))
    var start = initialPath
    var mayExplore = true
    if (!initial.isStarted) {
        mayExplore = decidePath(initialPath, MachineInput.Start(now), 0) { start = it }
    } else {
        coverage.observeInitial(initial)
        scenarios[initialPath.localCoverage] = MachineScenario("initial", emptyList())
        checked += invariants.size
        val violations = checkInvariants(initial)
        if (violations.isNotEmpty()) {
            failures += SequenceFailure(emptyList(), initial, listOf(SequenceProblem.Invariants(violations)))
            mayExplore = false
        }
    }
    when (strategy) {
        ExplorationStrategy.BreadthFirst, ExplorationStrategy.AllTransitions, is ExplorationStrategy.Cover -> {
            val queue = ArrayDeque<Path<C, A>>()
            if (mayExplore) queue.addLast(start)
            while (queue.isNotEmpty() && !truncated) {
                if (goalMet()) break
                val path = queue.removeFirst()
                if (path.depth == maxDepth) continue
                for (input in generator.inputs(path.snapshot, path.now)) {
                    decidePath(path, input, path.depth + 1, queue::addLast)
                    if (truncated || goalMet()) break
                }
            }
        }
        is ExplorationStrategy.RandomWalk -> {
            val random = Random(strategy.seed)
            repeat(strategy.runs) {
                var path = start
                var advancing = mayExplore
                while (advancing && path.depth < maxDepth && !truncated) {
                    val choices = generator.inputs(path.snapshot, path.now)
                    if (choices.isEmpty()) break
                    advancing = decidePath(path, choices[random.nextInt(choices.size)], path.depth + 1) { path = it }
                }
            }
        }
    }
    val report = ExplorationReport(decisions, checked, coverage.snapshot(), failures.toList(), truncated, maxDepth, scenarios.values.toList(),
        scenarios.map { (observations, scenario) -> ScenarioCoverage(scenario, observations.transitions.covered) })
    return BehaviouralExplorationReport(report, scenarios.map { (observations, scenario) -> BehaviouralScenarioCoverage(scenario, observations) })
}

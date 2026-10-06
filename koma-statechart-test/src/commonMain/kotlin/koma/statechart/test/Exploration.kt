package koma.statechart.test

import koma.core.Action
import koma.core.Event
import koma.observability.FailureDescriptor
import koma.statechart.StateId
import koma.statechart.machine.*
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

    /** Reproducible random walks through the application's generated input choices. */
    data class RandomWalk(val seed: Int, val runs: Int = 100) : ExplorationStrategy {
        init { require(runs > 0) { "[Koma] Random walks need at least one run" } }
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
            throw AssertionError("[Koma] Sequence failed: ${failure.identities}\nInputs: ${failure.inputs}\nSnapshot: ${failure.snapshot}")
        }
        if (truncated) throw AssertionError("[Koma] Exploration budget exhausted; coverage is incomplete")
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
): ExplorationReport<C, A> {
    require(maxDepth >= 0 && maxDecisions > 0 && maxFailures > 0) { "[Koma] Invalid exploration limits" }
    val coverage = MachineCoverageRecorder(this)
    val failures = mutableListOf<SequenceFailure<C, A>>()
    var decisions = 0
    var checked = 0L
    var truncated = false
    val scenarios = linkedMapOf<Set<TransitionId>, MachineScenario<A>>()
    data class Path<C, A : Action>(val snapshot: MachineSnapshot<C>, val inputs: List<MachineInput<A>>, val now: MachineTime, val depth: Int, val transitions: Set<TransitionId> = emptySet())

    fun decidePath(path: Path<C, A>, input: MachineInput<A>, depth: Int): Path<C, A>? {
        if (decisions >= maxDecisions || failures.size >= maxFailures) { truncated = true; return null }
        require(input.now >= path.now) { "[Koma] Generator moved virtual time backwards" }
        val explained = decideExplained(path.snapshot, input)
        decisions++
        coverage.accept(explained)
        checked += invariants.size
        val inputs = path.inputs + input
        val covered = path.transitions + explained.decision.transitions
        val previous = scenarios[covered]
        if (previous == null || inputs.size < previous.inputs.size) {
            scenarios[covered] = MachineScenario(previous?.name ?: "scenario-${scenarios.size + 1}", inputs.toList())
        }
        val failure = sequenceFailure(inputs, explained.decision)
        if (failure != null) { failures += failure; return null }
        return Path(explained.decision.snapshot, inputs, input.now, depth, covered)
    }

    require(initial.definition == id && initial.version == version) { "[Koma] Snapshot belongs to another machine or version" }
    var start: Path<C, A>? = Path(initial, emptyList(), now, 0)
    if (!initial.isStarted) {
        start = decidePath(start!!, MachineInput.Start(now), 0)
    } else {
        coverage.observeInitial(initial)
        checked += invariants.size
        val violations = checkInvariants(initial)
        if (violations.isNotEmpty()) {
            failures += SequenceFailure(emptyList(), initial, violations)
            start = null
        }
    }
    when (strategy) {
        ExplorationStrategy.BreadthFirst, ExplorationStrategy.AllTransitions -> {
            val queue = ArrayDeque<Path<C, A>>()
            start?.let(queue::addLast)
            while (queue.isNotEmpty() && !truncated) {
                if (strategy == ExplorationStrategy.AllTransitions && coverage.snapshot().transitions.missing.isEmpty()) break
                val path = queue.removeFirst()
                if (path.depth == maxDepth) continue
                for (input in generator.inputs(path.snapshot, path.now)) {
                    val next = decidePath(path, input, path.depth + 1)
                    if (next != null) queue.addLast(next)
                    if (truncated || (strategy == ExplorationStrategy.AllTransitions && coverage.snapshot().transitions.missing.isEmpty())) break
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
    return ExplorationReport(decisions, checked, coverage.snapshot(), failures.toList(), truncated, maxDepth, scenarios.values.toList(),
        scenarios.map { (transitions, scenario) -> ScenarioCoverage(scenario, transitions.toSet()) })
}

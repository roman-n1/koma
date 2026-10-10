package actron.statechart.test

import actron.core.Action
import actron.core.Event
import actron.core.InputId
import actron.statechart.HistoryState
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.machine.*
import kotlinx.coroutines.sync.Mutex

/**
 * Bounded behavioural coverage from actual MachineStore decisions. Attach as an observer of this
 * machine only, or use the test driver's built-in recorder. No guards are evaluated by the recorder.
 * Reports are safe to read across threads; only model ids and guard outcomes are retained.
 */
class MachineCoverageRecorder<C, A : Action, CMD, E : Event>(private val machine: Machine<C, A, CMD, E>) :
    DecisionTraceObserver<C, A, CMD, E> {
    private val lock = Mutex()
    private val expectedStates = machine.chart.states.filter { it !is HistoryState }.mapTo(linkedSetOf()) { it.id }
    private val expectedTransitions = machine.chart.transitions.indices.mapTo(linkedSetOf(), ::TransitionId)
    private val expectedTimers = machine.chart.transitions.withIndex().filter { it.value.isTimer }.mapTo(linkedSetOf()) { TransitionId(it.index) }
    private val expectedGuards = machine.chart.transitions.withIndex().filter { it.value.guard != null }.flatMap {
        listOf(GuardOutcome(TransitionId(it.index), true), GuardOutcome(TransitionId(it.index), false))
    }.toSet()
    private val states = linkedSetOf<StateId>()
    private val transitions = linkedSetOf<TransitionId>()
    private val guards = linkedSetOf<GuardOutcome>()

    override fun onDecided(input: InputId?, machineInput: MachineInput<A>, explained: ExplainedDecision<C, CMD, E>) = accept(explained)

    override fun onCommitted(input: InputId?, machineInput: MachineInput<A>, decision: Decision<C, CMD, E>) = locked {
        validate(decision.snapshot)
        states += decision.snapshot.configuration.active
        states += decision.entered.map { it.node }
        transitions += decision.transitions
    }

    internal fun accept(explained: ExplainedDecision<C, CMD, E>) = locked {
        validate(explained.decision.snapshot)
        observe(explained.explanation)
        states += explained.decision.snapshot.configuration.active
        states += explained.decision.entered.map { it.node }
        transitions += explained.decision.transitions
    }

    internal fun observeInitial(snapshot: MachineSnapshot<C>) = locked {
        validate(snapshot)
        states += snapshot.configuration.active
    }

    private fun validate(snapshot: MachineSnapshot<C>) {
        require(snapshot.definition == machine.id && snapshot.version == machine.version) { "[Actron] Coverage belongs to another machine or version" }
    }

    private fun observe(explanation: DecisionExplanation) {
        states += explanation.active
        guards += explanation.guards.mapNotNull { guard -> guard.result?.let { GuardOutcome(guard.transition, it) } }
    }

    /** Immutable snapshot; unreachable states and untested guard branches remain missing. */
    fun snapshot(): MachineCoverage = locked {
        MachineCoverage(
            CoverageMetric(expectedStates.toSet(), states.toSet()), CoverageMetric(expectedTransitions.toSet(), transitions.toSet()),
            CoverageMetric(expectedGuards, guards.toSet()), CoverageMetric(expectedTimers.toSet(), transitions intersect expectedTimers),
            machine.id, machine.version,
        )
    }

    private inline fun <T> locked(block: () -> T): T {
        while (!lock.tryLock()) { /* Only bounded metadata updates hold this lock. */ }
        try { return block() } finally { lock.unlock() }
    }
}

/** Combines identical declared universes and identity/version metadata. Definitions must version executable rule changes. */
fun MachineCoverage.merge(other: MachineCoverage): MachineCoverage {
    require(definition == other.definition && version == other.version) { "[Actron] Coverage machine identity/version differs" }
    fun <T> combine(first: CoverageMetric<T>, second: CoverageMetric<T>): CoverageMetric<T> {
        require(first.expected == second.expected) { "[Actron] Coverage declarations differ" }
        return CoverageMetric(first.expected, first.covered + second.covered)
    }
    return MachineCoverage(combine(states, other.states), combine(transitions, other.transitions), combine(guards, other.guards), combine(timers, other.timers), definition, version)
}

/** Fails on untested requested transitions; it never treats a false guard as a taken transition. */
fun MachineCoverage.assertTransitionsCovered(required: Set<TransitionId> = transitions.expected) {
    require(required.all { it in transitions.expected }) { "[Actron] Requested an undeclared transition" }
    val missing = required - transitions.covered
    if (missing.isNotEmpty()) throw AssertionError("[Actron] Uncovered transitions: ${missing.sortedBy { it.index }}")
}

/** Metadata-only counts and missing-transition rows suitable for a test report or CI artifact. */
fun MachineCoverage.describe(chart: StateChartDefinition? = null): String = buildString {
    fun <T> metric(label: String, metric: CoverageMetric<T>) {
        append(label).append(": ").append((metric.covered intersect metric.expected).size)
            .append(" / ").append(metric.expected.size).append(" = ").append(metric.percent).append("%\n")
    }
    metric("States", states); metric("Transitions", transitions); metric("Guard outcomes", guards); metric("Timers", timers)
    for (id in transitions.missing.sortedBy { it.index }) {
        append(id).append(" NOT COVERED")
        chart?.transitions?.getOrNull(id.index)?.let { transition ->
            append(": ").append(transition.source.value).append(" -> ").append(transition.target.value)
            transition.on?.let { append(" on ").append(it.name) }
            transition.guard?.let { append(" [").append(it).append(']') }
        }
        append('\n')
    }
}

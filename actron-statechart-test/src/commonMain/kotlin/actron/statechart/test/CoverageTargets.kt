package actron.statechart.test

import actron.statechart.*
import actron.statechart.machine.*

/** Finite obligations. Error semantics are supplied by the application, never inferred from names. */
sealed interface CoverageTarget {
    data object AllStates : CoverageTarget
    data object AllTransitions : CoverageTarget
    data object AllGuardOutcomes : CoverageTarget
    data object AllTimerPaths : CoverageTarget
    data object AllHistoryPaths : CoverageTarget
    data class AllErrorPaths(val errorStates: Set<StateId>) : CoverageTarget
    data class TargetStates(val states: Set<StateId>) : CoverageTarget
    data class Custom(val requirements: CoverageRequirements) : CoverageTarget
}

data class CoverageRequirements(
    val states: Set<StateId> = emptySet(),
    val transitions: Set<TransitionId> = emptySet(),
    val guards: Set<GuardOutcome> = emptySet(),
) {
    fun missing(coverage: MachineCoverage): CoverageRequirements = CoverageRequirements(
        states - coverage.states.covered, transitions - coverage.transitions.covered, guards - coverage.guards.covered)
    val isEmpty: Boolean get() = states.isEmpty() && transitions.isEmpty() && guards.isEmpty()
    operator fun plus(other: CoverageRequirements): CoverageRequirements = CoverageRequirements(states + other.states, transitions + other.transitions, guards + other.guards)
}

fun StateChartDefinition.requirements(target: CoverageTarget): CoverageRequirements {
    val ids = states.mapTo(linkedSetOf()) { it.id }
    fun checked(requested: Set<StateId>): Set<StateId> {
        require(requested.all { it in ids && node(it) !is HistoryState }) { "[Actron] Coverage target must name declared active states" }
        return requested
    }
    return when (target) {
        CoverageTarget.AllStates -> CoverageRequirements(states = states.filter { it !is HistoryState }.mapTo(linkedSetOf()) { it.id })
        CoverageTarget.AllTransitions -> CoverageRequirements(transitions = transitions.indices.mapTo(linkedSetOf(), ::TransitionId))
        CoverageTarget.AllGuardOutcomes -> CoverageRequirements(guards = transitions.withIndex().filter { it.value.guard != null }.flatMap {
            listOf(GuardOutcome(TransitionId(it.index), true), GuardOutcome(TransitionId(it.index), false)) }.toSet())
        CoverageTarget.AllTimerPaths -> CoverageRequirements(transitions = transitions.withIndex().filter { it.value.isTimer }.mapTo(linkedSetOf()) { TransitionId(it.index) })
        CoverageTarget.AllHistoryPaths -> CoverageRequirements(transitions = transitions.withIndex().filter { node(it.value.target) is HistoryState }.mapTo(linkedSetOf()) { TransitionId(it.index) })
        is CoverageTarget.TargetStates -> CoverageRequirements(states = checked(target.states))
        is CoverageTarget.Custom -> target.requirements.also { requirements ->
            checked(requirements.states)
            require(requirements.transitions.all { it.index in transitions.indices } && requirements.guards.all {
                transitions.getOrNull(it.transition.index)?.guard != null
            }) { "[Actron] Custom coverage references undeclared transitions/guard branches" }
        }
        is CoverageTarget.AllErrorPaths -> {
            val errors = checked(target.errorStates)
            CoverageRequirements(transitions = transitions.withIndex().filter { entry ->
                entry.value.target in errors || ancestorsOf(entry.value.target).any { it in errors }
            }.mapTo(linkedSetOf()) { TransitionId(it.index) })
        }
    }
}

/** Transition-based metrics; history pseudo-nodes are never incorrectly counted as active states. */
fun MachineCoverage.historyTransitions(chart: StateChartDefinition): CoverageMetric<TransitionId> {
    val expected = chart.requirements(CoverageTarget.AllHistoryPaths).transitions
    require(expected.all { it in transitions.expected }) { "[Actron] Coverage/chart universes differ" }
    return CoverageMetric(expected, transitions.covered intersect expected)
}

fun MachineCoverage.errorTransitions(chart: StateChartDefinition, errorStates: Set<StateId>): CoverageMetric<TransitionId> {
    val expected = chart.requirements(CoverageTarget.AllErrorPaths(errorStates)).transitions
    require(expected.all { it in transitions.expected }) { "[Actron] Coverage/chart universes differ" }
    return CoverageMetric(expected, transitions.covered intersect expected)
}

/** Per-transition guard branches, with labels retained for readable CI reports. */
fun MachineCoverage.describeGuards(chart: StateChartDefinition): String = guards.expected.sortedWith(compareBy({ it.transition.index }, { it.result }))
    .joinToString("\n") { "${it.transition} ${chart.transitions.getOrNull(it.transition.index)?.guard ?: "?"} ${it.result}: ${if (it in guards.covered) "COVERED" else "NOT COVERED"}" }

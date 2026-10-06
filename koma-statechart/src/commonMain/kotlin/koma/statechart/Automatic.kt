package koma.statechart

import koma.core.Action

/** The action seen by guards/effects/hooks of an automatic microstep. */
data class AutomaticTransition(val completion: Boolean) : Action

/** A bounded macrostep did not stabilize; [transitions] is its automatic transition trace. */
class MicrostepLimitException(val limit: Int, val transitions: List<Transition>) : IllegalStateException(
    "[Koma] Macrostep exceeded $limit automatic microsteps: " + transitions.joinToString { "${it.source} -> ${it.target}" },
)

/** Whether a state has completed in this configuration. */
fun StateChartDefinition.isComplete(configuration: StateConfiguration, id: StateId): Boolean {
    if (id !in configuration.active) return false
    return when (node(id)) {
        is FinalState -> true
        is CompoundState -> childrenOf(id).any { it.id in configuration.active && it is FinalState }
        is ParallelState -> childrenOf(id).filter { it !is HistoryState }.all { isComplete(configuration, it.id) }
        else -> false
    }
}

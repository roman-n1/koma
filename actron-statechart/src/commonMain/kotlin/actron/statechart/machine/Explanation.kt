package actron.statechart.machine

import actron.core.Event
import actron.observability.FailureDescriptor
import actron.statechart.StateId

/** One actual guard invocation, in execution order. A thrown guard has no [result]. */
data class GuardEvaluation(
    val transition: TransitionId,
    val label: String,
    val result: Boolean?,
    val failure: FailureDescriptor? = null,
)

/** Why a matching transition from an active node did or did not fire. */
enum class CandidateDisposition {
    /** Selected at least once in this macrostep attempt; later guard failures remain in [GuardEvaluation]. */
    Selected,
    GuardRejected,
    GuardFailed,
    /** An enabled candidate lost an exit-set conflict to a higher-priority selection. */
    ConflictLost,
    /** An earlier declaration or an inner node supplied the leaf's candidate. Guard not evaluated. */
    PrioritySkipped,
    /** Selection stopped because a guard threw; this candidate was never visited. */
    NotVisited,
}

/** A matching candidate; guard evaluations are stored separately, never inferred. */
data class TransitionCandidate(val transition: TransitionId, val disposition: CandidateDisposition)

/** Read-only explanation from one decision, without re-evaluating guards or handlers. */
data class DecisionExplanation(
    val active: Set<StateId>,
    val candidates: List<TransitionCandidate>,
    val guards: List<GuardEvaluation>,
    val handledBy: StateId? = null,
) {
    /** Compact text for logging and inspectors. It contains metadata, not action/context payloads. */
    fun describe(): String = buildString {
        append("Active: ").append(active.joinToString { it.value })
        for (candidate in candidates) {
            append("\n").append(candidate.transition).append(": ").append(candidate.disposition)
            for (guard in guards.filter { it.transition == candidate.transition }) {
                append("\n  ").append(guard.label).append(" = ").append(guard.result ?: "failed")
            }
        }
        handledBy?.let { append("\nHandled by: ").append(it.value) }
    }
}

/** The actual decision and the observations gathered while choosing it. */
data class ExplainedDecision<C, out CMD, out E : Event>(
    val decision: Decision<C, CMD, E>,
    val explanation: DecisionExplanation,
)

internal class SelectionTrace {
    val candidates = linkedSetOf<TransitionId>()
    val enabled = linkedSetOf<TransitionId>()
    val guards = mutableListOf<GuardEvaluation>()
    var selected: List<TransitionId> = emptyList()
    var handledBy: StateId? = null

    fun explanation(active: Set<StateId>, selected: List<TransitionId>): DecisionExplanation {
        val guardFailed = guards.any { it.failure != null }
        return DecisionExplanation(
            active.toSet(),
            candidates.map { id ->
                TransitionCandidate(id, when {
                    id in selected -> CandidateDisposition.Selected
                    guards.any { it.transition == id && it.failure != null } -> CandidateDisposition.GuardFailed
                    id in enabled -> CandidateDisposition.ConflictLost
                    guards.any { it.transition == id && it.result == false } -> CandidateDisposition.GuardRejected
                    guardFailed -> CandidateDisposition.NotVisited
                    else -> CandidateDisposition.PrioritySkipped
                })
            },
            guards.toList(),
            handledBy,
        )
    }
}

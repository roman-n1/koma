package actron.statechart.machine

import actron.core.Event
import actron.observability.FailureDescriptor
import actron.statechart.GuardCheck
import actron.statechart.StateId

/** One actual guard invocation, in execution order. A thrown guard has a [GuardCheck.Failed] result. */
data class GuardEvaluation(
    val transition: TransitionId,
    val label: String,
    val result: GuardCheck,
)

/** Why a matching transition from an active node did or did not fire. */
enum class CandidateDisposition {
    /** This transition was outside the candidates of the observed attempt. */
    NotCandidate,
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
    val handledBy: ActionHandling = ActionHandling.Unmatched,
) {
    /** Compact text for logging and inspectors. It contains metadata, not action/context payloads. */
    fun describe(): String = buildString {
        append("Active: ").append(active.joinToString { it.value })
        for (candidate in candidates) {
            append("\n").append(candidate.transition).append(": ").append(candidate.disposition)
            for (guard in guards.filter { it.transition == candidate.transition }) {
                append("\n  ").append(guard.label).append(" = ").append(guard.result)
            }
        }
        append(handledBy.description)
    }
}

/** The actual decision and the observations gathered while choosing it. */
data class ExplainedDecision<C : Any, out CMD : Any, out E : Event>(
    val decision: Decision<C, CMD, E>,
    val explanation: DecisionExplanation,
)

/** Whether action routing selected an explicit handler. */
sealed interface ActionHandling {
    val handled: Boolean
    val description: String
    data object Unmatched : ActionHandling {
        override val handled: Boolean = false
        override val description: String = ""
    }
    data class Handler(val node: StateId) : ActionHandling {
        override val handled: Boolean = true
        override val description: String get() = "\nHandled by: ${node.value}"
    }
}

internal interface SelectionObservation {
    val observing: Boolean
    fun candidates(ids: Iterable<TransitionId>)
    fun candidate(id: TransitionId)
    fun selected(ids: List<TransitionId>)
    fun enabled(id: TransitionId)
    fun handled(node: StateId)
    fun guard(id: TransitionId, label: String, result: GuardCheck)

    data object Unobserved : SelectionObservation {
        override val observing: Boolean = false
        override fun candidates(ids: Iterable<TransitionId>) {}
        override fun candidate(id: TransitionId) {}
        override fun selected(ids: List<TransitionId>) {}
        override fun enabled(id: TransitionId) {}
        override fun handled(node: StateId) {}
        override fun guard(id: TransitionId, label: String, result: GuardCheck) {}
    }
}

internal class SelectionTrace : SelectionObservation {
    override val observing: Boolean = true
    val candidates = linkedSetOf<TransitionId>()
    val enabled = linkedSetOf<TransitionId>()
    val guards = mutableListOf<GuardEvaluation>()
    var selected: List<TransitionId> = emptyList()
    var handledBy: ActionHandling = ActionHandling.Unmatched

    override fun candidates(ids: Iterable<TransitionId>) { candidates.addAll(ids) }
    override fun candidate(id: TransitionId) { candidates.add(id) }
    override fun selected(ids: List<TransitionId>) { selected = selected + ids }
    override fun handled(node: StateId) { handledBy = ActionHandling.Handler(node) }
    override fun guard(id: TransitionId, label: String, result: GuardCheck) {
        guards += GuardEvaluation(id, label, result)
    }

    override fun enabled(id: TransitionId) { enabled += id }

    fun explanation(active: Set<StateId>, selected: List<TransitionId>): DecisionExplanation {
        val guardFailed = guards.any { it.result is GuardCheck.Failed }
        return DecisionExplanation(
            active.toSet(),
            candidates.map { id ->
                TransitionCandidate(id, when {
                    id in selected -> CandidateDisposition.Selected
                    guards.any { it.transition == id && it.result is GuardCheck.Failed } -> CandidateDisposition.GuardFailed
                    id in enabled -> CandidateDisposition.ConflictLost
                    guards.any { it.transition == id && it.result == GuardCheck.Rejected } -> CandidateDisposition.GuardRejected
                    guardFailed -> CandidateDisposition.NotVisited
                    else -> CandidateDisposition.PrioritySkipped
                })
            },
            guards.toList(),
            handledBy,
        )
    }
}

package actron.statechart.machine

import actron.core.Action
import actron.core.Event

/** Selection eligibility for one supplied payload; a later macrostep can still fail. */
sealed interface ActionExplanation {
    data class Allowed(val selection: DecisionExplanation) : ActionExplanation
    data class Rejected(val rejection: RejectionExplanation) : ActionExplanation
    data class InvalidConfiguration(val problems: List<String>) : ActionExplanation
}

/** Guard-free structural validation followed by the normal selection query. No reducers or IO. */
fun <C, A : Action, CMD, E : Event> Machine<C, A, CMD, E>.whyNot(snapshot: MachineSnapshot<C>, action: A): ActionExplanation {
    val problems = validateSnapshotStructure(snapshot)
    if (problems.isNotEmpty()) return ActionExplanation.InvalidConfiguration(problems)
    val available = availableActions(snapshot, listOf(action)).actions.single()
    return available.rejection?.let(ActionExplanation::Rejected) ?: ActionExplanation.Allowed(available.selection)
}

/** Explain a completed attempt, including rule failures, without deciding again. */
fun <C, A : Action, CMD, E : Event> Machine<C, A, CMD, E>.whyNot(explained: ExplainedDecision<C, CMD, E>): ActionExplanation =
    explainWhyRejected(explained)?.let(ActionExplanation::Rejected) ?: ActionExplanation.Allowed(explained.explanation)

/** Selection and committed outcome are separate: an enabled transition may have been rolled back. */
data class TransitionSelectionExplanation(
    val transition: TransitionId,
    val disposition: CandidateDisposition?,
    val guards: List<GuardEvaluation>,
    val selectedInAttempt: Boolean,
    val committed: Boolean,
    val otherSelections: List<TransitionId>,
) {
    fun describe(): String = "$transition: ${disposition ?: "not a candidate"}; committed=$committed\n" +
        guards.joinToString("\n") { "${it.label} = ${it.result ?: "failed"}" }
}

/** Uses the observed priority/conflict/guard trace, never guesses unevaluated guard results. */
fun <C, A : Action, CMD, E : Event> Machine<C, A, CMD, E>.whyThisTransition(
    explained: ExplainedDecision<C, CMD, E>, transition: TransitionId,
): TransitionSelectionExplanation {
    require(explained.decision.snapshot.definition == id && explained.decision.snapshot.version == version) { "[Actron] Decision belongs to another machine/version" }
    require(transition.index in chart.transitions.indices) { "[Actron] Undeclared transition" }
    val candidate = explained.explanation.candidates.firstOrNull { it.transition == transition }
    return TransitionSelectionExplanation(transition, candidate?.disposition,
        explained.explanation.guards.filter { it.transition == transition },
        candidate?.disposition == CandidateDisposition.Selected, transition in explained.decision.transitions,
        explained.explanation.candidates.filter { it.transition != transition && it.disposition == CandidateDisposition.Selected }.map { it.transition })
}

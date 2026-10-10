package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import actron.observability.FailureDescriptor
import actron.statechart.ActionMatcher
import actron.statechart.StateId

/** An active transition, or an action handler when [transition] is null. */
data class ActionDeclaration(val node: StateId, val transition: TransitionId? = null)

/** A declared matcher, not a fabricated action payload. Matching subtypes may have different eligibility. */
data class DeclaredAction(val matcher: ActionMatcher, val declarations: List<ActionDeclaration>)

enum class ActionAvailability { Executable, Blocked, Undeclared, NotStarted, GuardFailed }

/** Why a supplied action did not select executable behaviour, or failed its complete decision. */
enum class ActionRejectionReason { NotStarted, NoMatchingAction, GuardRejected, GuardFailed, DecisionFailed, IgnoredInput }

/** A false guard with optional application-authored diagnostic metadata. */
data class GuardRejection(val transition: TransitionId, val label: String, val reason: String?)

/** Metadata-only explanation; no action/context payloads or exception messages are included in [describe]. */
data class RejectionExplanation(
    val reason: ActionRejectionReason,
    val selection: DecisionExplanation,
    val guards: List<GuardRejection> = emptyList(),
    val failure: FailureDescriptor? = null,
) {
    fun describe(): String = buildString {
        append("Reason: ").append(reason)
        append('\n').append(selection.describe())
        for (guard in guards) guard.reason?.let { append("\n  ").append(guard.label).append(": ").append(it) }
        failure?.let { append("\nFailure: ").append(it.type) }
    }
}

/** Eligibility for one actual typed payload. It is not a promise that hooks or the macrostep will succeed. */
data class AvailableAction<A : Action>(
    val action: A,
    val availability: ActionAvailability,
    val selection: DecisionExplanation,
    val rejection: RejectionExplanation? = null,
)

/** Declared matchers and eligibility of the finite action instances supplied by the application. */
data class AvailableActions<A : Action>(val declared: List<DeclaredAction>, val actions: List<AvailableAction<A>>) {
    val executable: List<AvailableAction<A>> get() = actions.filter { it.availability == ActionAvailability.Executable }
    val blocked: List<AvailableAction<A>> get() = actions.filter {
        it.availability == ActionAvailability.Blocked || it.availability == ActionAvailability.GuardFailed || it.availability == ActionAvailability.NotStarted
    }
    val undeclared: List<AvailableAction<A>> get() = actions.filter { it.availability == ActionAvailability.Undeclared }
}

internal data class ActionSelection(val explanation: DecisionExplanation, val notStarted: Boolean = false, val failure: FailureDescriptor? = null)

private fun <C, A : Action, CMD, E : Event> Machine<C, A, CMD, E>.rejection(
    reason: ActionRejectionReason, selection: DecisionExplanation, failure: FailureDescriptor? = null,
): RejectionExplanation = RejectionExplanation(reason, selection, selection.guards.mapNotNull {
    if (it.result == false) GuardRejection(it.transition, it.label, guardRejectionReason(it.label)) else null
}, failure)

/**
 * Evaluates transition selection once per supplied action, using the same priority/conflict rules as
 * dispatch. Does not run effects, entry/exit rules, action handlers, automatic steps, invariants or IO.
 * Supply actual payloads: a matcher cannot construct them or decide a payload-dependent guard.
 * Guards must be pure. Query again on a new snapshot; dispatch always rechecks eligibility.
 */
fun <C, A : Action, CMD, E : Event> Machine<C, A, CMD, E>.availableActions(
    snapshot: MachineSnapshot<C>, actions: Iterable<A>,
): AvailableActions<A> = AvailableActions(declaredActions(snapshot), actions.map { action ->
    val selected = selectAction(snapshot, action)
    val explanation = selected.explanation
    val status = when {
        selected.notStarted -> ActionAvailability.NotStarted
        selected.failure != null -> ActionAvailability.GuardFailed
        explanation.handledBy != null || explanation.candidates.any { it.disposition == CandidateDisposition.Selected } -> ActionAvailability.Executable
        explanation.candidates.isEmpty() -> ActionAvailability.Undeclared
        else -> ActionAvailability.Blocked
    }
    val why = when (status) {
        ActionAvailability.Executable -> null
        ActionAvailability.NotStarted -> rejection(ActionRejectionReason.NotStarted, explanation)
        ActionAvailability.Undeclared -> rejection(ActionRejectionReason.NoMatchingAction, explanation)
        ActionAvailability.GuardFailed -> rejection(ActionRejectionReason.GuardFailed, explanation, selected.failure)
        ActionAvailability.Blocked -> rejection(ActionRejectionReason.GuardRejected, explanation)
    }
    AvailableAction(action, status, explanation, why)
})

/** Converts an already observed decision into a rejection explanation; evaluates nothing again. */
fun <C, A : Action, CMD, E : Event> Machine<C, A, CMD, E>.explainWhyRejected(
    explained: ExplainedDecision<C, CMD, E>,
): RejectionExplanation? {
    require(explained.decision.snapshot.definition == id && explained.decision.snapshot.version == version) { "[Actron] Decision belongs to another machine or version" }
    val selection = explained.explanation
    return when (val outcome = explained.decision.outcome) {
        DecisionOutcome.Handled -> null
        is DecisionOutcome.Failed -> rejection(
            if (selection.guards.any { it.failure != null }) ActionRejectionReason.GuardFailed else ActionRejectionReason.DecisionFailed,
            selection, outcome.failure,
        )
        is DecisionOutcome.Ignored -> rejection(when {
            outcome.reason == IgnoreReason.NotStarted -> ActionRejectionReason.NotStarted
            outcome.reason != IgnoreReason.NoTransition -> ActionRejectionReason.IgnoredInput
            selection.candidates.isEmpty() -> ActionRejectionReason.NoMatchingAction
            else -> ActionRejectionReason.GuardRejected
        }, selection)
    }
}

/** Simulates one complete pure dispatch decision without executing its intents, then explains rejection. */
fun <C, A : Action, CMD, E : Event> Machine<C, A, CMD, E>.explainWhyRejected(
    snapshot: MachineSnapshot<C>, action: A, now: MachineTime,
): RejectionExplanation? = explainWhyRejected(decideExplained(snapshot, MachineInput.Dispatch(action, now)))

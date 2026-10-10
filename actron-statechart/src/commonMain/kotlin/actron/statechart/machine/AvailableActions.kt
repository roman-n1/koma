package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import actron.observability.FailureDescriptor
import actron.statechart.GuardCheck
import actron.statechart.ActionMatcher
import actron.statechart.StateId

/** The declared behaviour activated by an action matcher. */
sealed interface ActionDeclaration {
    val node: StateId
    data class Handler(override val node: StateId) : ActionDeclaration
    data class Transition(override val node: StateId, val transition: TransitionId) : ActionDeclaration
}

/** A declared matcher, not a fabricated action payload. Matching subtypes may have different eligibility. */
data class DeclaredAction(val matcher: ActionMatcher, val declarations: List<ActionDeclaration>)

enum class ActionAvailability { Executable, Blocked, Undeclared, NotStarted, GuardFailed }

/** Why a supplied action did not select executable behaviour, or failed its complete decision. */
enum class ActionRejectionReason { NotStarted, NoMatchingAction, GuardRejected, GuardFailed, DecisionFailed, IgnoredInput }

/** A false guard with application-authored diagnostic text; empty text adds no explanation. */
data class GuardRejection(val transition: TransitionId, val label: String, val reason: String = "")

/** A rejected input or an evaluation that failed. Descriptions contain metadata only. */
sealed interface RejectionExplanation {
    val reason: ActionRejectionReason
    val selection: DecisionExplanation
    val guards: List<GuardRejection>
    fun failureLine(): String

    data class Denied(
        override val reason: ActionRejectionReason,
        override val selection: DecisionExplanation,
        override val guards: List<GuardRejection> = emptyList(),
    ) : RejectionExplanation {
        override fun failureLine(): String = ""
    }
    data class Failed(
        override val reason: ActionRejectionReason,
        override val selection: DecisionExplanation,
        override val guards: List<GuardRejection>,
        val failure: FailureDescriptor,
    ) : RejectionExplanation {
        override fun failureLine(): String = "\nFailure: ${failure.typeLabel}"
    }

    fun describe(): String = buildString {
        append("Reason: ").append(reason)
        append('\n').append(selection.describe())
        for (guard in guards) if (guard.reason.isNotEmpty()) append("\n  ").append(guard.label).append(": ").append(guard.reason)
        append(failureLine())
    }
}

/** Eligibility for a typed payload. Hooks and the complete macrostep may still fail. */
data class AvailableAction<A : Action>(
    val action: A,
    val availability: ActionAvailability,
    val selection: DecisionExplanation,
    val explanation: ActionExplanation,
)

/** Declared matchers and eligibility of the finite action instances supplied by the application. */
data class AvailableActions<A : Action>(val declared: List<DeclaredAction>, val actions: List<AvailableAction<A>>) {
    val executable: List<AvailableAction<A>> get() = actions.filter { it.availability == ActionAvailability.Executable }
    val blocked: List<AvailableAction<A>> get() = actions.filter {
        it.availability == ActionAvailability.Blocked || it.availability == ActionAvailability.GuardFailed || it.availability == ActionAvailability.NotStarted
    }
    val undeclared: List<AvailableAction<A>> get() = actions.filter { it.availability == ActionAvailability.Undeclared }
}

internal sealed interface ActionSelection {
    val explanation: DecisionExplanation
    data class Evaluated(override val explanation: DecisionExplanation) : ActionSelection
    data class NotStarted(override val explanation: DecisionExplanation) : ActionSelection
    data class Failed(override val explanation: DecisionExplanation, val failure: FailureDescriptor) : ActionSelection
}

private fun <C : Any, A : Action, CMD : Any, E : Event> Machine<C, A, CMD, E>.rejectedGuards(selection: DecisionExplanation): List<GuardRejection> =
    selection.guards.filter { it.result == GuardCheck.Rejected }.map {
        GuardRejection(it.transition, it.label, guardRejectionReason(it.label))
    }

private fun <C : Any, A : Action, CMD : Any, E : Event> Machine<C, A, CMD, E>.rejection(
    reason: ActionRejectionReason, selection: DecisionExplanation,
): ActionExplanation.Rejected = ActionExplanation.Rejected(RejectionExplanation.Denied(reason, selection, rejectedGuards(selection)))

private fun <C : Any, A : Action, CMD : Any, E : Event> Machine<C, A, CMD, E>.rejection(
    reason: ActionRejectionReason, selection: DecisionExplanation, failure: FailureDescriptor,
): ActionExplanation.Rejected = ActionExplanation.Rejected(RejectionExplanation.Failed(reason, selection, rejectedGuards(selection), failure))

/**
 * Evaluates selection once per supplied action. Does not run effects, hooks, action handlers,
 * automatic steps, invariants or IO. Guards must be pure; dispatch rechecks eligibility.
 */
fun <C : Any, A : Action, CMD : Any, E : Event> Machine<C, A, CMD, E>.availableActions(
    snapshot: MachineSnapshot<C>, actions: Iterable<A>,
): AvailableActions<A> = AvailableActions(declaredActions(snapshot), actions.map { action ->
    val selected = selectAction(snapshot, action)
    val selection = selected.explanation
    val status = when {
        selected is ActionSelection.NotStarted -> ActionAvailability.NotStarted
        selected is ActionSelection.Failed -> ActionAvailability.GuardFailed
        selection.handledBy.handled || selection.candidates.any { it.disposition == CandidateDisposition.Selected } -> ActionAvailability.Executable
        selection.candidates.isEmpty() -> ActionAvailability.Undeclared
        else -> ActionAvailability.Blocked
    }
    val why = when (status) {
        ActionAvailability.Executable -> ActionExplanation.Allowed(selection)
        ActionAvailability.NotStarted -> rejection(ActionRejectionReason.NotStarted, selection)
        ActionAvailability.Undeclared -> rejection(ActionRejectionReason.NoMatchingAction, selection)
        ActionAvailability.GuardFailed -> rejection(ActionRejectionReason.GuardFailed, selection, (selected as ActionSelection.Failed).failure)
        ActionAvailability.Blocked -> rejection(ActionRejectionReason.GuardRejected, selection)
    }
    AvailableAction(action, status, selection, why)
})

/** Explains an observed decision; evaluates nothing again. Handled decisions report Allowed. */
fun <C : Any, A : Action, CMD : Any, E : Event> Machine<C, A, CMD, E>.explainWhyRejected(
    explained: ExplainedDecision<C, CMD, E>,
): ActionExplanation {
    require(explained.decision.snapshot.definition == id && explained.decision.snapshot.version == version) { "[Actron] Decision belongs to another machine or version" }
    val selection = explained.explanation
    return when (val outcome = explained.decision.outcome) {
        DecisionOutcome.Handled -> ActionExplanation.Allowed(selection)
        is DecisionOutcome.Failed -> rejection(
            if (selection.guards.any { it.result is GuardCheck.Failed }) ActionRejectionReason.GuardFailed else ActionRejectionReason.DecisionFailed,
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

/** Simulates one pure dispatch decision without executing its intents, then explains its outcome. */
fun <C : Any, A : Action, CMD : Any, E : Event> Machine<C, A, CMD, E>.explainWhyRejected(
    snapshot: MachineSnapshot<C>, action: A, now: MachineTime,
): ActionExplanation = explainWhyRejected(decideExplained(snapshot, MachineInput.Dispatch(action, now)))

package koma.statechart.machine

import koma.core.Action
import koma.core.Event
import koma.statechart.*
import kotlin.test.*

class AvailableActionsTest {
    private data class Send(val amount: Int) : Action
    private data object Edit : Action
    private data object Unknown : Action
    private val idle = StateId("idle")
    private val done = StateId("done")
    private val send = ActionMatcher.of<Send>("send")

    @Test fun declarationsDoNotEvaluateGuardsAndEligibilityUsesActualPayloadsWithoutRules() {
        var guards = 0
        var rules = 0
        val machine = Machine<Unit, Action, Nothing, Event>(DefinitionId("actions"), DefinitionVersion("1"),
            StateChartDefinition(idle, listOf(AtomicState(idle), FinalState(done)), listOf(Transition(idle, done, send, guard = "positive", effect = "side-effect")))) {
            guard("positive", "A positive amount is required") { _, action -> guards++; (action as Send).amount > 0 }
            effect("side-effect") { context, _ -> rules++; context }
            onAction(idle, ActionMatcher.of<Edit>("edit")) { rules++ }
        }
        val base = machine.decide(machine.initialSnapshot(Unit), MachineInput.Start(MachineTime.Zero)).snapshot
        assertEquals(listOf("send", "edit"), machine.declaredActions(base).map { it.matcher.name })
        assertEquals(0, guards)
        val available = machine.availableActions(base, listOf(Send(0), Send(2), Edit, Unknown))
        assertEquals(listOf(ActionAvailability.Blocked, ActionAvailability.Executable, ActionAvailability.Executable, ActionAvailability.Undeclared), available.actions.map { it.availability })
        assertEquals(2, guards)
        assertEquals(0, rules)
        assertEquals("A positive amount is required", available.blocked.single().rejection!!.guards.single().reason)
        assertEquals(listOf<Action>(Send(2), Edit), available.executable.map { it.action })
        assertEquals(Unknown, available.undeclared.single().action)
    }

    @Test fun handlerFallbackWinsAfterAFalseGuardAndIsNotExecutedByQuery() {
        var calls = 0
        val machine = Machine<Unit, Action, Nothing, Event>(DefinitionId("fallback"), DefinitionVersion("1"),
            StateChartDefinition(idle, listOf(AtomicState(idle), FinalState(done)), listOf(Transition(idle, done, send, guard = "blocked")))) {
            guard("blocked") { _, _ -> false }
            onAction(idle, send) { calls++ }
        }
        val base = machine.decide(machine.initialSnapshot(Unit), MachineInput.Start(MachineTime.Zero)).snapshot
        val query = machine.availableActions(base, listOf(Send(1))).actions.single()
        assertEquals(ActionAvailability.Executable, query.availability)
        assertEquals(idle, query.selection.handledBy)
        assertNull(query.rejection)
        assertEquals(0, calls)
        val actual = machine.decideExplained(base, MachineInput.Dispatch(Send(1), MachineTime.Zero))
        assertNull(machine.explainWhyRejected(actual))
        assertEquals(1, calls)
    }

    @Test fun explanationsReuseTheActualGuardResultAndDoNotInventAReason() {
        var calls = 0
        val machine = Machine<Unit, Action, Nothing, Event>(DefinitionId("rejected"), DefinitionVersion("1"),
            StateChartDefinition(idle, listOf(AtomicState(idle), FinalState(done)), listOf(Transition(idle, done, send, guard = "allowed")))) {
            guard("allowed") { _, _ -> calls++; false }
        }
        val base = machine.decide(machine.initialSnapshot(Unit), MachineInput.Start(MachineTime.Zero)).snapshot
        val actual = machine.decideExplained(base, MachineInput.Dispatch(Send(1), MachineTime.Zero))
        val explanation = machine.explainWhyRejected(actual)!!
        assertEquals(1, calls)
        assertEquals(ActionRejectionReason.GuardRejected, explanation.reason)
        assertNull(explanation.guards.single().reason)
        assertTrue(explanation.describe().contains("allowed = false"))
        assertEquals(ActionRejectionReason.NoMatchingAction, machine.explainWhyRejected(base, Unknown, MachineTime.Zero)!!.reason)
    }

    @Test fun eligibilityDoesNotEvaluateAnOuterGuardSkippedByAnInnerTransition() {
        val root = StateId("root")
        var calls = 0
        val machine = Machine<Unit, Action, Nothing, Event>(DefinitionId("priority"), DefinitionVersion("1"),
            StateChartDefinition(root, listOf(CompoundState(root, idle), AtomicState(idle, root)), listOf(
                Transition(root, root, send, guard = "outer"), Transition(idle, idle, send, kind = TransitionKind.Internal),
            ))) { guard("outer") { _, _ -> calls++; error("must be skipped") } }
        val base = machine.decide(machine.initialSnapshot(Unit), MachineInput.Start(MachineTime.Zero)).snapshot
        val query = machine.availableActions(base, listOf(Send(1))).actions.single()
        assertEquals(ActionAvailability.Executable, query.availability)
        assertEquals(0, calls)
        assertEquals(listOf(CandidateDisposition.PrioritySkipped, CandidateDisposition.Selected), query.selection.candidates.map { it.disposition })
    }

    @Test fun guardFailuresAndNotStartedAreSeparateFromAnUndeclaredAction() {
        var calls = 0
        val machine = Machine<Unit, Action, Nothing, Event>(DefinitionId("failure"), DefinitionVersion("1"),
            StateChartDefinition(idle, listOf(AtomicState(idle), FinalState(done)), listOf(Transition(idle, done, send, guard = "throws")))) {
            guard("throws") { _, _ -> calls++; error("sensitive exception text") }
        }
        val cold = machine.initialSnapshot(Unit)
        assertEquals(ActionAvailability.NotStarted, machine.availableActions(cold, listOf(Send(1))).blocked.single().availability)
        assertEquals(0, calls)
        val base = machine.decide(cold, MachineInput.Start(MachineTime.Zero)).snapshot
        val query = machine.availableActions(base, listOf(Send(1))).blocked.single()
        assertEquals(ActionAvailability.GuardFailed, query.availability)
        assertEquals(1, calls)
        assertFalse(query.rejection!!.describe().contains("sensitive exception text"))
        assertFailsWith<IllegalArgumentException> { machine.declaredActions(base.copy(version = DefinitionVersion("wrong"))) }
    }

    @Test fun eligibilityDoesNotPromiseThatTheCompleteDecisionWillSucceed() {
        val machine = Machine<Unit, Action, Nothing, Event>(DefinitionId("effect-failure"), DefinitionVersion("1"),
            StateChartDefinition(idle, listOf(AtomicState(idle), FinalState(done)), listOf(Transition(idle, done, send, effect = "throws")))) {
            effect("throws") { _, _ -> error("effect failed") }
        }
        val base = machine.decide(machine.initialSnapshot(Unit), MachineInput.Start(MachineTime.Zero)).snapshot
        assertEquals(ActionAvailability.Executable, machine.availableActions(base, listOf(Send(1))).actions.single().availability)
        assertEquals(ActionRejectionReason.DecisionFailed, machine.explainWhyRejected(base, Send(1), MachineTime.Zero)!!.reason)
        val stale = machine.decideExplained(base, MachineInput.CommandCompleted(CommandId(999), MachineTime.Zero))
        assertEquals(ActionRejectionReason.IgnoredInput, machine.explainWhyRejected(stale)!!.reason)
    }
}

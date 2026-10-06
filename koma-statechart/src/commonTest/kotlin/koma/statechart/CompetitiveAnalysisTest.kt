package koma.statechart

import koma.core.Action
import koma.core.Event
import koma.statechart.machine.*
import kotlin.test.*

class CompetitiveAnalysisTest {
    private data object Go : Action
    private val idle = StateId("idle"); private val done = StateId("done"); private val orphan = StateId("orphan")
    private val go = ActionMatcher.of<Go>("go")

    @Test fun incompleteStructuralSearchDoesNotProveUnreachabilityAndFinalsAreNotDeadEnds() {
        val chart = StateChartDefinition(idle, listOf(AtomicState(idle), FinalState(done), AtomicState(orphan)), listOf(Transition(idle, done, go)))
        val full = chart.analyzeReachability()
        assertEquals(setOf(orphan), full.structurallyUnreachable)
        assertEquals(1, full.terminal.size)
        assertTrue(full.deadEnds.isEmpty())
        val bounded = chart.analyzeReachability(maxConfigurations = 1)
        assertTrue(bounded.truncated)
        assertNull(bounded.structurallyUnreachable)
        assertEquals(setOf(done, orphan), bounded.unreached)
    }

    @Test fun queriesAndImpactRespectHierarchyAndTransitionOrder() {
        val root = StateId("root")
        val before = StateChartDefinition(root, listOf(CompoundState(root, idle), AtomicState(idle, root), AtomicState(done, root)),
            listOf(Transition(idle, done, go, guard = "g", effect = "e")))
        assertTrue(before.query(TransitionQuery(sources = setOf(root))).isEmpty())
        assertEquals(listOf(0), before.query(TransitionQuery(sources = setOf(root), includeDescendants = true, guard = "g")).map { it.index })
        val impact = before.impactTo(before.copy(transitions = listOf(before.transitions.single().copy(guard = "g2"))))
        assertEquals(setOf(idle, done), impact.changedStates)
        assertEquals(setOf("g", "g2"), impact.guards)
        assertEquals(setOf("e"), impact.effects)
    }

    @Test fun malformedHierarchyIsRejectedBeforeConfigurationEnumeration() {
        val invalid = StateChartDefinition(idle, listOf(CompoundState(idle, done, done), CompoundState(done, idle, idle)), emptyList())
        assertFailsWith<IllegalArgumentException> { invalid.analyzeReachability(maxConfigurations = 1) }
    }

    @Test fun selectionExplanationKeepsRollbackSeparateAndInvalidConfigurationInvokesNoGuard() {
        var guards = 0
        val machine = Machine<Unit, Action, Nothing, Event>(DefinitionId("why"), DefinitionVersion("1"),
            StateChartDefinition(idle, listOf(AtomicState(idle), FinalState(done)), listOf(Transition(idle, done, go, guard = "g", effect = "e")))) {
            guard("g") { _, _ -> guards++; true }
            effect("e") { _, _ -> error("rollback") }
        }
        val base = machine.decide(machine.initialSnapshot(Unit), MachineInput.Start(MachineTime.Zero)).snapshot
        assertTrue(machine.whyNot(base.copy(configuration = StateConfiguration(setOf(orphan))), Go) is ActionExplanation.InvalidConfiguration)
        assertEquals(0, guards)
        val actual = machine.decideExplained(base, MachineInput.Dispatch(Go, MachineTime.Zero))
        val selection = machine.whyThisTransition(actual, TransitionId(0))
        assertTrue(selection.selectedInAttempt)
        assertFalse(selection.committed)
        assertTrue(machine.whyNot(actual) is ActionExplanation.Rejected)
        assertEquals(1, guards)
    }

    @Test fun earlierAutomaticSelectionRemainsVisibleWhenItsGuardFailsOnTheNextMicrostep() {
        val machine = Machine<Int, Action, Nothing, Event>(DefinitionId("repeated-guard"), DefinitionVersion("1"),
            StateChartDefinition(idle, listOf(AtomicState(idle)), listOf(Transition(idle, idle, Trigger.Eventless, guard = "once", effect = "increment", kind = TransitionKind.Internal)))) {
            guard("once") { snapshot, _ -> if (snapshot.context == 1) error("second attempt") else true }
            effect("increment") { context, _ -> context + 1 }
        }
        val actual = machine.decideExplained(machine.initialSnapshot(0), MachineInput.Start(MachineTime.Zero))
        val explanation = machine.whyThisTransition(actual, TransitionId(0))
        assertTrue(explanation.selectedInAttempt)
        assertFalse(explanation.committed)
        assertTrue(explanation.guards.any { it.failure != null })
        assertEquals(0, actual.decision.snapshot.context)
    }
}

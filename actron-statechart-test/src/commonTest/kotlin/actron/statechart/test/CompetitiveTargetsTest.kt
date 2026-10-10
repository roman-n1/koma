@file:OptIn(actron.core.ExperimentalActronApi::class)
package actron.statechart.test

import actron.core.Action
import actron.core.Event
import actron.statechart.*
import actron.statechart.machine.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class CompetitiveTargetsTest {
    private data class Go(val allowed: Boolean) : Action
    private val idle = StateId("idle")
    private val done = StateId("done")
    private val go = ActionMatcher.of<Go>("go")
    private fun machine() = Machine<Unit, Action, Nothing, Event>(DefinitionId("targets"), DefinitionVersion("1"),
        StateChartDefinition(idle, listOf(AtomicState(idle), FinalState(done)), listOf(Transition(idle, done, go, guard = actron.statechart.GuardKey("allowed"))))) {
        guard("allowed") { _, action -> (action as Go).allowed }
    }

    @Test fun falseBranchIsNotLostWhenTwoPrefixesHaveEqualTransitionCoverage() {
        val machine = machine()
        val generator = machine.inputGenerator(listOf(actionGenerator<Unit, Go>("go") { _, _ -> listOf(Go(false), Go(true)) }))
        val plan = machine.generateTestPlan(machine.initialSnapshot(Unit), generator, CoverageTarget.AllGuardOutcomes, maxDepth = 2)
        plan.assertReady(true)
        assertEquals(1, plan.plan.scenarios.size, "One false then true prefix covers both branches")
        machine.runPlan(machine.initialSnapshot(Unit), plan).assertSuccess()
        val report = machine.explore(machine.initialSnapshot(Unit), generator, ExplorationStrategy.Cover(CoverageTarget.AllGuardOutcomes))
        assertTrue(report.coverage.guards.missing.isEmpty())
        assertTrue(report.coverage.describeGuards(machine.chart).contains("allowed false: COVERED"))
    }

    @Test fun missingTargetsAndExhaustedBudgetsNeverPassAsReady() {
        val machine = machine()
        val generator = MachineInputGenerator<Unit, Action> { _, now -> listOf(MachineInput.Dispatch(Go(false), now)) }
        val plan = machine.generateTestPlan(machine.initialSnapshot(Unit), generator, CoverageTarget.TargetStates(setOf(done)), maxDepth = 1)
        assertEquals(setOf(done), plan.missing.states)
        assertFailsWith<AssertionError> { plan.assertReady() }
        val truncated = machine.generateTestPlan(machine.initialSnapshot(Unit), generator, CoverageTarget.AllStates, maxDecisions = 1)
        assertTrue(truncated.plan.searchTruncated)
        assertFailsWith<AssertionError> { truncated.assertReady() }
        assertFailsWith<IllegalArgumentException> { machine.chart.requirements(CoverageTarget.TargetStates(setOf(StateId("missing")))) }
    }

    @Test fun generatorsMatchActualSubtypesRespectConstraintsAndPreserveTimerDeadlines() {
        val machine = Machine<Unit, Action, Nothing, Event>(DefinitionId("generator"), DefinitionVersion("1"),
            StateChartDefinition(idle, listOf(AtomicState(idle), FinalState(done)), listOf(Transition(idle, done, Trigger.After(5.seconds))))) {
            onAction(idle, ActionMatcher.of<Action>("any")) { }
        }
        val snapshot = machine.decide(machine.initialSnapshot(Unit), MachineInput.Start(MachineTime.Zero)).snapshot
        val generator = machine.inputGenerator(listOf(actionGenerator<Unit, Go>("different-label") { _, _ -> listOf(Go(false), Go(true)) }),
            constraint = { _, action -> (action as Go).allowed })
        val inputs = generator.inputs(snapshot, MachineTime.Zero)
        assertEquals(Go(true), (inputs.first() as MachineInput.Dispatch).action)
        assertEquals(MachineTime.Zero + 5.seconds, inputs.last().now)
        val plan = machine.generateTestPlan(machine.initialSnapshot(Unit), generator, CoverageTarget.AllTimerPaths, maxDepth = 1)
        machine.runPlan(machine.initialSnapshot(Unit), plan).assertSuccess()
    }

    @Test fun errorPathsAreExplicitAndHistoryPathsTargetPseudoNodesWithoutCountingThemAsActive() {
        val root = StateId("root"); val history = StateId("history"); val error = StateId("error")
        val chart = StateChartDefinition(root, listOf(CompoundState(root, idle), AtomicState(idle, root), HistoryState(history, root), AtomicState(error)), listOf(
            Transition(idle, error, go), Transition(error, history, go)))
        assertEquals(setOf(TransitionId(0)), chart.requirements(CoverageTarget.AllErrorPaths(setOf(error))).transitions)
        assertEquals(setOf(TransitionId(1)), chart.requirements(CoverageTarget.AllHistoryPaths).transitions)
        assertFalse(history in chart.requirements(CoverageTarget.AllStates).states)
    }
}

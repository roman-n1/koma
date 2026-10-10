@file:OptIn(actron.core.ExperimentalActronApi::class)

package actron.statechart.test

import actron.core.InputAttribution

import actron.core.Action
import actron.core.Event
import actron.statechart.*
import actron.statechart.machine.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class DerivedFeaturesTest {
    private data object Go : Action
    private data object Back : Action
    private data object Finish : Action
    private data object Noise : Action
    private val idle = StateId("idle")
    private val work = StateId("work")
    private val done = StateId("done")
    private val machine = Machine<Unit, Action, String, Event>(DefinitionId("plan"), DefinitionVersion("1"),
        StateChartDefinition(idle, listOf(AtomicState(idle), AtomicState(work), FinalState(done)), listOf(
            Transition(idle, work, ActionMatcher.of<Go>("go")),
            Transition(work, idle, ActionMatcher.of<Back>("back")),
            Transition(work, done, ActionMatcher.of<Finish>("finish")),
        ))) { onEnter(work) { command("fake-io") } }
    private val generator = MachineInputGenerator<Unit, Action> { snapshot, now ->
        when {
            snapshot.isActive(idle) -> listOf(MachineInput.Dispatch(Go, now))
            snapshot.isActive(work) -> listOf(MachineInput.Dispatch(Back, now), MachineInput.Dispatch(Finish, now))
            else -> emptyList()
        }
    }

    @Test fun planCombinesBranchCoverageIntoOneExecutableScenario() {
        val initial = machine.initialSnapshot(Unit)
        val plan = machine.generateTestPlan(initial, generator, maxDepth = 4)
        plan.assertReady(requireOptimal = true)
        assertEquals(1, plan.scenarios.size)
        assertEquals(listOf<Action>(Go, Back, Go, Finish), plan.scenarios.single().inputs.filterIsInstance<MachineInput.Dispatch<Action>>().map { it.action })
        val result = machine.runPlan(initial, plan)
        result.assertSuccess()
        assertEquals(100.0, result.coverage.transitions.percent)
        assertTrue(result.scenarios.single().snapshot.isActive(done))
    }

    @Test fun allTransitionsStrategyStopsAtItsGoalAndStillReturnsAnExecutablePlan() {
        val initial = machine.initialSnapshot(Unit)
        val report = machine.explore(initial, generator, ExplorationStrategy.AllTransitions, maxDepth = 100)
        assertFalse(report.truncated)
        assertEquals(100.0, report.coverage.transitions.percent)
        assertEquals(4, report.decisions)
        val plan = machine.generateTestPlan(initial, generator, ExplorationStrategy.AllTransitions, maxDepth = 100)
        assertEquals(2, plan.scenarios.size, "Optimal only within the early search's discovered prefixes")
        machine.runPlan(initial, plan).assertSuccess()
    }

    @Test fun selectionBudgetKeepsCoverageWhileReportingUnknownOptimality() {
        val initial = machine.initialSnapshot(Unit)
        val plan = machine.generateTestPlan(initial, generator, ExplorationStrategy.AllTransitions, maxDepth = 100, maxSelectionAttempts = 1)
        assertFalse(plan.selectionOptimal)
        plan.assertReady()
        assertFailsWith<AssertionError> { plan.assertReady(requireOptimal = true) }
        machine.runPlan(initial, plan).assertSuccess()
    }

    @Test fun missingPayloadsAndDiscoveryBudgetsCannotPretendToProvideCompleteCoverage() {
        val initial = machine.initialSnapshot(Unit)
        val missing = machine.generateTestPlan(initial, MachineInputGenerator { _, _ -> emptyList() })
        assertEquals(setOf(TransitionId(0), TransitionId(1), TransitionId(2)), missing.uncoveredTransitions)
        assertFailsWith<AssertionError> { missing.assertReady() }
        val truncated = machine.generateTestPlan(initial, generator, maxDepth = 100, maxDecisions = 2)
        assertTrue(truncated.searchTruncated)
        assertFailsWith<AssertionError> { machine.runPlan(initial, truncated).assertSuccess() }
    }

    @Test fun actualDriverCoverageIncludesGuardRejectionsAndCanBeMergedAcrossTests() = runTest {
        var calls = 0
        val guarded = Machine<Boolean, Action, Nothing, Event>(DefinitionId("coverage"), DefinitionVersion("1"),
            StateChartDefinition(idle, listOf(AtomicState(idle), FinalState(done)), listOf(Transition(idle, done, ActionMatcher.of<Go>("go"), guard = actron.statechart.GuardKey("allowed"))))) {
            guard("allowed") { snapshot, _ -> calls++; snapshot.context }
        }
        val denied = MachineTestDriver(guarded, false, this)
        val allowed = MachineTestDriver(guarded, true, this)
        try {
            denied.start(); denied.send(Go)
            assertEquals(0.0, denied.coverage.transitions.percent)
            assertEquals(50.0, denied.coverage.guards.percent)
            assertTrue(denied.coverage.describe(guarded.chart).contains("idle -> done on go [allowed]"))
            allowed.start(); allowed.send(Go)
            val combined = denied.coverage.merge(allowed.coverage)
            combined.assertTransitionsCovered()
            assertEquals(100.0, combined.guards.percent)
            assertEquals(100.0, combined.states.percent)
            assertEquals(2, calls, "Recording coverage must not evaluate guards again")
        } finally { denied.close(); allowed.close() }
    }

    @Test fun transientAutomaticStatesCountAsVisitedButFailedTransitionsDoNotCountAsTaken() {
        val chart = StateChartDefinition(idle, listOf(AtomicState(idle), AtomicState(work), FinalState(done)), listOf(
            Transition(idle, work, ActionMatcher.of<Go>("go")), Transition(work, done, Trigger.Eventless),
        ))
        val automatic = Machine<Unit, Action, Nothing, Event>(machine.id, machine.version, chart)
        val report = automatic.explore(automatic.initialSnapshot(Unit), generator, maxDepth = 1)
        assertEquals(100.0, report.coverage.states.percent)
        assertEquals(100.0, report.coverage.transitions.percent)
        val broken = Machine<Unit, Action, Nothing, Event>(machine.id, machine.version, chart) {
            onEnter(done) { error("failed macrostep") }
        }
        val failed = broken.explore(broken.initialSnapshot(Unit), generator, maxDepth = 1)
        assertEquals(0.0, failed.coverage.transitions.percent)
        assertEquals(setOf(idle), failed.coverage.states.covered)
    }

    @Test fun generatedTimerPlanPreservesVirtualTimeAndRunsWithoutIo() {
        val timed = Machine<Unit, Action, Nothing, Event>(DefinitionId("timed-plan"), machine.version,
            StateChartDefinition(idle, listOf(AtomicState(idle), FinalState(done)), listOf(Transition(idle, done, Trigger.After(5.seconds)))))
        val initial = timed.initialSnapshot(Unit)
        val plan = timed.generateTestPlan(initial, MachineInputGenerator<Unit, Action> { _, _ -> emptyList() }.withTimers())
        val fired = plan.scenarios.single().inputs.filterIsInstance<MachineInput.TimerFired>().single()
        assertEquals(MachineTime.Zero + 5.seconds, fired.now)
        val result = timed.runPlan(initial, plan)
        result.assertSuccess()
        assertEquals(100.0, result.coverage.timers.percent)
    }

    @Test fun driverVerifiesGeneratedPlansWithoutStartingLiveWork() = runTest {
        val driver = MachineTestDriver(machine, Unit, this)
        try {
            val plan = driver.generateTestPlan(generator, maxDepth = 4)
            driver.verifyPlan(plan).assertSuccess()
            assertFalse(driver.snapshot.isStarted)
            assertTrue(driver.runningCommands.isEmpty())
            assertTrue(driver.handler.cancelled.isEmpty())
            assertFailsWith<IllegalArgumentException> { machine.runPlan(driver.initialSnapshot, plan.copy(version = DefinitionVersion("wrong"))) }
        } finally { driver.close() }
    }

    @Test fun planKeepsInvariantFailuresEvenWhenAnotherScenarioCouldCoverTheSameTransition() {
        val invalid = Machine<Unit, Action, String, Event>(machine.id, machine.version, machine.chart) {
            invariant("never-done") { !it.isActive(done) }
        }
        val plan = invalid.generateTestPlan(invalid.initialSnapshot(Unit), generator, maxDepth = 4)
        assertTrue(plan.discoveryFailures.isNotEmpty())
        assertFailsWith<AssertionError> { plan.assertReady() }
        assertFailsWith<AssertionError> { invalid.runPlan(invalid.initialSnapshot(Unit), plan).assertSuccess() }
    }

    @Test fun customShrinkingDoesNotSubstituteAnotherBugOfTheSameExceptionType() {
        val counter = Machine<Int, Action, Nothing, Event>(DefinitionId("shrink"), machine.version,
            StateChartDefinition(idle, listOf(AtomicState(idle)), emptyList())) {
            onAction(idle, ActionMatcher.of<Go>("go")) { context++ }
            onAction(idle, ActionMatcher.of<Finish>("finish")) { if (context == 0) error("unrelated") else error("target") }
        }
        val inputs = listOf<MachineInput<Action>>(MachineInput.Start(MachineTime.Zero), MachineInput.Dispatch(Go, MachineTime.Zero),
            MachineInput.Dispatch(Noise, MachineTime.Zero), MachineInput.Dispatch(Finish, MachineTime.Zero))
        val minimal = counter.shrink(counter.initialSnapshot(0), inputs) { original, candidate ->
            original.identities == candidate.identities && original.snapshot.context == candidate.snapshot.context
        }
        assertFalse(minimal.truncated)
        assertEquals(3, minimal.failure.inputs.size)
        assertEquals(1, minimal.failure.snapshot.context)
        assertEquals("IllegalStateException", minimal.failure.problems.filterIsInstance<SequenceProblem.Execution>().single().failure.typeLabel)
    }

    @Test fun allTransitionsStopsImmediatelyWithoutEvaluatingRemainingSiblingChoices() {
        val single = Machine<Unit, Action, Nothing, Event>(machine.id, machine.version,
            StateChartDefinition(idle, listOf(AtomicState(idle), FinalState(done)), listOf(Transition(idle, done, ActionMatcher.of<Go>("go"))))) {
            onAction(idle, ActionMatcher.of<Noise>("noise")) { error("Outside the completed coverage goal") }
        }
        val report = single.explore(single.initialSnapshot(Unit), MachineInputGenerator { _, now ->
            listOf(MachineInput.Dispatch(Go, now), MachineInput.Dispatch(Noise, now))
        }, ExplorationStrategy.AllTransitions)
        assertTrue(report.failures.isEmpty())
        assertEquals(2, report.decisions)
    }

    @Test fun coverageCannotMergeDifferentVersionsEvenWhenTheirGraphsMatch() {
        val next = Machine<Unit, Action, String, Event>(machine.id, DefinitionVersion("2"), machine.chart)
        val first = machine.explore(machine.initialSnapshot(Unit), generator, maxDepth = 4).coverage
        val second = next.explore(next.initialSnapshot(Unit), generator, maxDepth = 4).coverage
        assertFailsWith<IllegalArgumentException> { first.merge(second) }
        val recorder = MachineCoverageRecorder(machine)
        val foreign = next.decideExplained(next.initialSnapshot(Unit), MachineInput.Start(MachineTime.Zero))
        assertFailsWith<IllegalArgumentException> { recorder.onDecided(InputAttribution.Unattributed, MachineInput.Start(MachineTime.Zero), foreign) }
        assertTrue(recorder.snapshot().states.covered.isEmpty())
    }

    @Test fun commandAnswersInAPlanRetainTheirActualCommandIdentity() {
        val chart = machine.chart.copy(transitions = listOf(machine.chart.transitions[0], machine.chart.transitions[2]))
        val commanded = Machine<Unit, Action, String, Event>(machine.id, machine.version, chart) {
            onEnter(work) { command("fake-io") }
        }
        val inputs = MachineInputGenerator<Unit, Action> { snapshot, now ->
            if (snapshot.isActive(idle)) listOf(MachineInput.Dispatch(Go, now))
            else snapshot.commands.keys.map { MachineInput.CommandResult(it, Finish, now) }
        }
        val initial = commanded.initialSnapshot(Unit)
        val plan = commanded.generateTestPlan(initial, inputs)
        assertEquals(CommandId(1), plan.scenarios.single().inputs.filterIsInstance<MachineInput.CommandResult<Action>>().single().command)
        commanded.runPlan(initial, plan).assertSuccess()
    }
}

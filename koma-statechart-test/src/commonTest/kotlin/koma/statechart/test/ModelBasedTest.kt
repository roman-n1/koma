package koma.statechart.test

import koma.core.Action
import koma.core.Event
import koma.statechart.*
import koma.statechart.machine.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class ModelBasedTest {
    private data object Tick : Action
    private data object Noise : Action
    private data object Go : Action
    private val idle = StateId("idle")
    private val done = StateId("done")
    private val tick = ActionMatcher.of<Tick>("tick")
    private val go = ActionMatcher.of<Go>("go")

    private fun counter(enforce: Boolean = false) = Machine<Int, Action, String, Event>(
        DefinitionId("counter"), DefinitionVersion("1"),
        StateChartDefinition(idle, listOf(AtomicState(idle)), emptyList()),
    ) {
        invariant("less-than-three") { it.context < 3 }
        onAction(idle, tick) { context++; command("work"); event(object : Event {}) }
        if (enforce) enforceInvariants()
    }

    @Test fun explorationDoesNotMergeIdenticalConfigurationsWithDifferentContext() {
        val machine = counter()
        val report = machine.explore(machine.initialSnapshot(0), MachineInputGenerator { _, now ->
            listOf(MachineInput.Dispatch(Tick, now))
        }, maxDepth = 5)
        assertEquals(listOf("invariant:less-than-three:false"), report.failures.single().identities.toList())
        assertEquals(3, report.failures.single().snapshot.context)
        assertEquals(4, report.failures.single().inputs.size, "start plus three ticks")
        assertEquals(100.0, report.coverage.states.percent)
        assertFailsWith<AssertionError> { report.assertSuccess() }
    }

    @Test fun shrinkingPreservesTheFailureAndReturnsADeletionMinimalReproducer() {
        val machine = counter()
        val inputs = listOf<MachineInput<Action>>(MachineInput.Start(MachineTime.Zero)) +
            listOf(Noise, Tick, Noise, Tick, Noise, Tick, Noise).map { MachineInput.Dispatch(it, MachineTime.Zero) }
        val shrink = machine.shrink(machine.initialSnapshot(0), inputs)
        assertFalse(shrink.truncated)
        assertEquals(4, shrink.failure.inputs.size)
        for (position in shrink.failure.inputs.indices) {
            assertNull(machine.replaySequence(machine.initialSnapshot(0), shrink.failure.inputs.filterIndexed { index, _ -> index != position }))
        }
        assertTrue(machine.shrink(machine.initialSnapshot(0), inputs, maxAttempts = 1).truncated)
    }

    @Test fun enforcementRollsBackContextCommandsEffectsAndRevision() {
        val machine = counter(enforce = true)
        var snapshot = machine.decide(machine.initialSnapshot(0), MachineInput.Start(MachineTime.Zero)).snapshot
        repeat(2) { snapshot = machine.decide(snapshot, MachineInput.Dispatch(Tick, MachineTime.Zero)).snapshot }
        val failed = machine.decide(snapshot, MachineInput.Dispatch(Tick, MachineTime.Zero))
        assertSame(snapshot, failed.snapshot)
        assertTrue((failed.outcome as DecisionOutcome.Failed).cause is InvariantViolationException)
        assertTrue(failed.commands.isEmpty())
        assertTrue(failed.effects.isEmpty())
        val report = machine.explore(machine.initialSnapshot(0), MachineInputGenerator { _, now -> listOf(MachineInput.Dispatch(Tick, now)) })
        assertEquals(setOf("invariant:less-than-three:false"), report.failures.single().identities)
    }

    @Test fun guardCoverageAndExplanationComeFromOneDecision() {
        var calls = 0
        val machine = Machine<Boolean, Action, Nothing, Event>(DefinitionId("guard"), DefinitionVersion("1"),
            StateChartDefinition(idle, listOf(AtomicState(idle), AtomicState(done)), listOf(
                Transition(idle, done, go, guard = "allowed"),
                Transition(idle, idle, go),
            ))) { guard("allowed") { snapshot, _ -> calls++; snapshot.context } }
        val start = machine.decide(machine.initialSnapshot(false), MachineInput.Start(MachineTime.Zero)).snapshot
        val explained = machine.decideExplained(start, MachineInput.Dispatch(Go, MachineTime.Zero))
        assertEquals(1, calls)
        assertEquals(listOf(CandidateDisposition.GuardRejected, CandidateDisposition.Selected), explained.explanation.candidates.map { it.disposition })
        assertEquals(false, explained.explanation.guards.single().result)
        val report = machine.explore(machine.initialSnapshot(false), MachineInputGenerator { _, now -> listOf(MachineInput.Dispatch(Go, now)) }, maxDepth = 1)
        assertEquals(50.0, report.coverage.guards.percent)
        assertEquals(50.0, report.coverage.transitions.percent)
        assertTrue(report.coverage.states.missing.contains(done))
    }

    @Test fun innerTransitionsSkipOuterGuards_andActionHandlersAreVisible() {
        val root = StateId("root")
        var calls = 0
        val machine = Machine<Unit, Action, Nothing, Event>(DefinitionId("hierarchy"), DefinitionVersion("1"),
            StateChartDefinition(root, listOf(CompoundState(root, idle), AtomicState(idle, root)), listOf(
                Transition(root, root, go, guard = "outer"), Transition(idle, idle, go),
            ))) {
            guard("outer") { _, _ -> calls++; true }
            onAction(root, tick) {}
        }
        val snapshot = machine.decide(machine.initialSnapshot(Unit), MachineInput.Start(MachineTime.Zero)).snapshot
        val explanation = machine.decideExplained(snapshot, MachineInput.Dispatch(Go, MachineTime.Zero)).explanation
        assertEquals(0, calls)
        assertEquals(CandidateDisposition.PrioritySkipped, explanation.candidates.first().disposition)
        assertTrue(explanation.guards.isEmpty())
        assertEquals(root, machine.decideExplained(snapshot, MachineInput.Dispatch(Tick, MachineTime.Zero)).explanation.handledBy)
        assertEquals(IgnoreReason.NoTransition, (machine.decideExplained(snapshot, MachineInput.Dispatch(Noise, MachineTime.Zero)).decision.outcome as DecisionOutcome.Ignored).reason)
    }

    @Test fun timerChoicesUseVirtualDeadlines_andCountTransitionCoverage() {
        val machine = Machine<Unit, Action, Nothing, Event>(DefinitionId("timer"), DefinitionVersion("1"),
            StateChartDefinition(idle, listOf(AtomicState(idle), AtomicState(done)), listOf(Transition(idle, done, Trigger.After(5.seconds)))))
        val report = machine.explore(machine.initialSnapshot(Unit), MachineInputGenerator<Unit, Action> { _, _ -> emptyList() }.withTimers())
        report.assertSuccess()
        assertEquals(100.0, report.coverage.timers.percent)
        assertEquals(100.0, report.coverage.transitions.percent)
        assertEquals(2, report.decisions)
    }

    @Test fun budgetAndSeedAreExplicit() {
        val machine = counter()
        val generator = MachineInputGenerator<Int, Action> { _, now -> listOf(MachineInput.Dispatch(Tick, now), MachineInput.Dispatch(Noise, now)) }
        val limited = machine.explore(machine.initialSnapshot(0), generator, maxDepth = 8, maxDecisions = 2)
        assertEquals(2, limited.decisions)
        assertTrue(limited.truncated)
        assertFailsWith<AssertionError> { limited.assertSuccess() }
        val strategy = ExplorationStrategy.RandomWalk(seed = 42, runs = 20)
        val first = machine.explore(machine.initialSnapshot(0), generator, strategy)
        val second = machine.explore(machine.initialSnapshot(0), generator, strategy)
        assertEquals(first.failures.first().inputs, second.failures.first().inputs)
        assertEquals(first.coverage, second.coverage)
    }

    @Test fun predicateExceptionsAreViolations_andNamesAreUnique() {
        val machine = Machine<Unit, Action, Nothing, Event>(DefinitionId("invariants"), DefinitionVersion("1"),
            StateChartDefinition(idle, listOf(AtomicState(idle)), emptyList())) {
            invariant("throws") { error("predicate failed") }
        }
        assertEquals("IllegalStateException", machine.checkInvariants(machine.initialSnapshot(Unit)).single().failure?.type)
        assertFailsWith<IllegalArgumentException> {
            Machine<Unit, Action, Nothing, Event>(machine.id, machine.version, machine.chart) {
                invariant("duplicate") { true }; invariant("duplicate") { true }
            }
        }
    }

    @Test fun shrinkingDoesNotReplaceAnInvariantFailureWithAnUnrelatedException() {
        val machine = Machine<Int, Action, Nothing, Event>(DefinitionId("identity"), DefinitionVersion("1"),
            StateChartDefinition(idle, listOf(AtomicState(idle)), emptyList())) {
            invariant("two") { it.context < 2 }
            onAction(idle, tick) { context++ }
            onAction(idle, go) { if (context == 0) error("unrelated"); context++ }
        }
        val result = machine.shrink(machine.initialSnapshot(0), listOf(MachineInput.Start(MachineTime.Zero), MachineInput.Dispatch(Tick, MachineTime.Zero), MachineInput.Dispatch(Go, MachineTime.Zero)))
        assertEquals(setOf("invariant:two:false"), result.failure.identities)
        assertEquals(3, result.failure.inputs.size)
    }
}

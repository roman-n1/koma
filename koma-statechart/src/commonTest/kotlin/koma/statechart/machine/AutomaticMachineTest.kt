@file:OptIn(koma.core.ExperimentalKomaApi::class)
package koma.statechart.machine

import koma.core.Action
import koma.core.Event
import koma.statechart.*
import koma.test.startAndAwait
import koma.test.dispatchAndAwait
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class AutomaticMachineTest {
    private data object Go : Action
    private val matcher = ActionMatcher.of<Go>("go")
    private val idle = StateId("idle")
    private val checking = StateId("checking")
    private val done = StateId("done")

    @Test fun automaticStepsCommitOnce_andTransientCommandsAndTimersNeverExecute() {
        val chart = StateChartDefinition(idle, listOf(AtomicState(idle), AtomicState(checking), FinalState(done)), listOf(
            Transition(idle, checking, matcher), Transition(checking, done, Trigger.Eventless, guard = "valid", effect = "count"),
            Transition(checking, idle, Trigger.After(5.seconds)),
        ))
        val machine = Machine<Int, Go, String, Event>(DefinitionId("auto"), DefinitionVersion("1"), chart) {
            guard("valid") { snapshot, action -> snapshot.context == 1 && action == AutomaticTransition(false) }
            effect("count") { context, _ -> context + 1 }
            onEnter(checking) { context++; command("transient") }
            invariant("stable") { !it.isActive(checking) }
            enforceInvariants()
        }
        val start = machine.decide(machine.initialSnapshot(0), MachineInput.Start(MachineTime.Zero)).snapshot
        val decision = machine.decide(start, MachineInput.Dispatch(Go, MachineTime.Zero))
        assertTrue(decision.isHandled)
        assertEquals(2, decision.snapshot.revision)
        assertEquals(2, decision.snapshot.context)
        assertEquals(listOf(TransitionId(0), TransitionId(1)), decision.transitions)
        assertTrue(decision.commands.isEmpty())
        assertTrue(decision.timersScheduled.isEmpty())
        assertTrue(decision.snapshot.commands.isEmpty())
        assertTrue(machine.isComplete(decision.snapshot))
    }

    @Test fun internalTransitionsKeepActivationsCommandsAndTimers() {
        val chart = StateChartDefinition(idle, listOf(AtomicState(idle), AtomicState(done)), listOf(
            Transition(idle, idle, matcher, effect = "count", kind = TransitionKind.Internal),
            Transition(idle, done, Trigger.After(5.seconds)),
        ))
        val machine = Machine<Int, Go, String, Event>(DefinitionId("internal"), DefinitionVersion("1"), chart) {
            onEnter(idle) { command("load") }
            effect("count") { context, _ -> context + 1 }
        }
        val start = machine.decide(machine.initialSnapshot(0), MachineInput.Start(MachineTime.Zero)).snapshot
        val decision = machine.decide(start, MachineInput.Dispatch(Go, MachineTime.Zero))
        assertEquals(start.activations, decision.snapshot.activations)
        assertEquals(start.commands, decision.snapshot.commands)
        assertEquals(start.timers, decision.snapshot.timers)
        assertEquals(1, decision.snapshot.context)
        assertTrue(decision.exited.isEmpty())
        assertTrue(decision.entered.isEmpty())
    }

    @Test fun allParallelRegionsMustFinishBeforeCompletionFires() {
        val parallel = StateId("parallel")
        val left = StateId("left"); val right = StateId("right")
        val leftWork = StateId("leftWork"); val rightWork = StateId("rightWork")
        val leftDone = StateId("leftDone"); val rightDone = StateId("rightDone")
        val chart = StateChartDefinition(parallel, listOf(
            ParallelState(parallel), CompoundState(left, leftWork, parallel), CompoundState(right, rightWork, parallel),
            AtomicState(leftWork, left), AtomicState(rightWork, right), FinalState(leftDone, left), FinalState(rightDone, right), FinalState(done),
        ), listOf(
            Transition(leftWork, leftDone, matcher), Transition(rightWork, rightDone, matcher),
            Transition(parallel, done, Trigger.Completion),
        ))
        val machine = Machine<Unit, Go, Nothing, Event>(DefinitionId("completion"), DefinitionVersion("1"), chart)
        val start = machine.decide(machine.initialSnapshot(Unit), MachineInput.Start(MachineTime.Zero)).snapshot
        assertFalse(chart.isComplete(start.configuration, parallel))
        val result = machine.decide(start, MachineInput.Dispatch(Go, MachineTime.Zero))
        assertEquals(listOf(TransitionId(0), TransitionId(1), TransitionId(2)), result.transitions)
        assertEquals(setOf(done), result.snapshot.configuration.active)
        assertEquals(2, result.snapshot.revision)
    }

    @Test fun livelockLimitRollsBackTheWholeMacrostep() {
        val chart = StateChartDefinition(idle, listOf(AtomicState(idle), AtomicState(checking)), listOf(
            Transition(idle, checking, matcher), Transition(checking, checking, Trigger.Eventless, effect = "count", kind = TransitionKind.Internal),
        ))
        val machine = Machine<Int, Go, Nothing, Event>(DefinitionId("loop"), DefinitionVersion("1"), chart) {
            maxMicrosteps(3); effect("count") { context, _ -> context + 1 }
        }
        val start = machine.decide(machine.initialSnapshot(0), MachineInput.Start(MachineTime.Zero)).snapshot
        val result = machine.decide(start, MachineInput.Dispatch(Go, MachineTime.Zero))
        assertSame(start, result.snapshot)
        val error = (result.outcome as DecisionOutcome.Failed).cause as MicrostepLimitException
        assertEquals(3, error.limit)
        assertEquals(4, error.transitions.size)
        assertTrue(result.transitions.isEmpty())
    }

    @Test fun guardsSeeOnePendingRevisionThroughoutTheMacrostep() {
        val chart = StateChartDefinition(idle, listOf(AtomicState(idle), AtomicState(checking), FinalState(done)), listOf(
            Transition(idle, checking, matcher),
            Transition(checking, checking, Trigger.Eventless, guard = "advance", effect = "increment", kind = TransitionKind.Internal),
            Transition(checking, done, Trigger.Eventless, guard = "finished"),
        ))
        val machine = Machine<Int, Go, Nothing, Event>(DefinitionId("revision"), DefinitionVersion("1"), chart) {
            guard("advance") { snapshot, _ -> snapshot.revision == 2L && snapshot.context < 2 }
            guard("finished") { snapshot, _ -> snapshot.context == 2 }
            effect("increment") { context, _ -> context + 1 }
        }
        val start = machine.decide(machine.initialSnapshot(0), MachineInput.Start(MachineTime.Zero)).snapshot
        val result = machine.decide(start, MachineInput.Dispatch(Go, MachineTime.Zero))
        assertEquals(2, result.snapshot.revision)
        assertEquals(2, result.snapshot.context)
        assertEquals(setOf(done), result.snapshot.configuration.active)
    }

    @Test fun startStabilizesBeforeTheFirstCommit() {
        val chart = StateChartDefinition(idle, listOf(AtomicState(idle), FinalState(done)), listOf(Transition(idle, done, Trigger.Eventless)))
        val machine = Machine<Unit, Go, Nothing, Event>(DefinitionId("start"), DefinitionVersion("1"), chart)
        val result = machine.decide(machine.initialSnapshot(Unit), MachineInput.Start(MachineTime.Zero))
        assertEquals(1, result.snapshot.revision)
        assertEquals(setOf(done), result.snapshot.configuration.active)
    }

    @Test fun chartStoreStabilizesContextAndStagesItsLifetimes() = runTest {
        var transientRuns = 0
        val chart = StateChartDefinition(idle, listOf(AtomicState(idle), AtomicState(checking), FinalState(done)), listOf(
            Transition(idle, checking, matcher), Transition(checking, done, Trigger.Eventless, guard = "ready"),
        ))
        val store = StateChartStore<Int, Go, Event>(chart, 0, coroutineContext) {
            guard("ready") { state, _ -> state.context == 1 }
            onEnter(checking) { context++; launch { transientRuns++ } }
        }
        store.startAndAwait()
        store.dispatchAndAwait(Go)
        assertEquals(setOf(done), store.currentState.configuration.active)
        assertEquals(1, store.currentState.context)
        assertEquals(0, transientRuns)
        store.close()
    }

    @Test fun initialTransientActivityDoesNotStartBeforeAutomaticStability() = runTest {
        var runs = 0
        val chart = StateChartDefinition(idle, listOf(AtomicState(idle), FinalState(done)), listOf(Transition(idle, done, Trigger.Eventless)))
        val store = StateChartStore<Unit, Go, Event>(chart, Unit, coroutineContext) {
            activity(idle) { runs++ }
        }
        store.startAndAwait()
        assertEquals(setOf(done), store.currentState.configuration.active)
        assertEquals(0, runs)
        store.close()
    }

    @Test fun conformanceAcceptsAnActionFollowedByAutomaticMicrosteps() = runTest {
        val chart = StateChartDefinition(idle, listOf(AtomicState(idle), AtomicState(checking), FinalState(done)), listOf(
            Transition(idle, checking, matcher), Transition(checking, done, Trigger.Eventless),
        ))
        val conformance = StateChartConformance<ChartState<Unit>, Go, Event>(chart) { it.activeLeaves(chart).single() }
        val store = StateChartStore<Unit, Go, Event>(chart, Unit, coroutineContext) {
            store { plugin(conformance) }
        }
        store.startAndAwait()
        store.dispatchAndAwait(Go)
        assertTrue(conformance.violations.isEmpty(), conformance.violations.toString())
        assertEquals(chart.transitions.toSet(), conformance.coveredTransitions)
        store.close()
    }

    @Test fun internalTimerIsSpentWithoutRestarting() {
        val chart = StateChartDefinition(idle, listOf(AtomicState(idle)), listOf(Transition(idle, idle, Trigger.After(1.seconds), kind = TransitionKind.Internal)))
        val machine = Machine<Unit, Go, Nothing, Event>(DefinitionId("timer"), DefinitionVersion("1"), chart)
        val start = machine.decide(machine.initialSnapshot(Unit), MachineInput.Start(MachineTime.Zero)).snapshot
        val result = machine.decide(start, MachineInput.TimerFired(start.timers.keys.single(), MachineTime.Zero + 1.seconds))
        assertEquals(start.activations, result.snapshot.activations)
        assertTrue(result.snapshot.timers.isEmpty())
        assertTrue(result.timersScheduled.isEmpty())
    }
}

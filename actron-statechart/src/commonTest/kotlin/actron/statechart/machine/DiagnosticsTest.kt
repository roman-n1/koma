@file:OptIn(actron.core.ExperimentalActronApi::class)
package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import actron.core.InputId
import actron.observability.StoreInstanceId
import actron.statechart.*
import actron.test.startAndAwait
import actron.test.dispatchAndAwait
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class DiagnosticsTest {
    private data object Go : Action
    private val idle = StateId("idle")
    private val done = StateId("done")
    @Test fun liveObserverSeesTheActualGuardInvocationOnce() = runTest {
        var calls = 0
        val machine = Machine<Unit, Go, Nothing, Event>(DefinitionId("diagnostic"), DefinitionVersion("1"),
            StateChartDefinition(idle, listOf(AtomicState(idle), FinalState(done)), listOf(Transition(idle, done, ActionMatcher.of<Go>("go"), guard = actron.statechart.GuardKey("allowed"))))) {
            guard("allowed") { _, _ -> calls++; false }
        }
        val explanations = mutableListOf<DecisionExplanation>()
        val store = MachineStore(machine, Unit, CommandHandler<Nothing, Go> { _, _ -> }, this,
            observers = listOf(decisionDiagnostics { _, explanation -> explanations += explanation }))
        store.startAndAwait()
        store.dispatchAndAwait(Go)
        assertEquals(1, calls)
        assertEquals(actron.statechart.GuardCheck.Rejected, explanations.last().guards.single().result)
        assertEquals(CandidateDisposition.GuardRejected, explanations.last().candidates.single().disposition)
        store.close()
    }

    @Test fun commandCausalityIsBoundedAndNamesEvictedAncestry() {
        val machine = Machine<Unit, Go, String, Event>(DefinitionId("causal"), DefinitionVersion("1"), StateChartDefinition(idle, listOf(AtomicState(idle)), emptyList())) {
            onEnter(idle) { command("fetch") }
        }
        val tracker = CausalityTracker<Unit, Go, String, Event>(StoreInstanceId("one"), capacity = 2)
        val startInput = MachineInput.Start(MachineTime.Zero)
        val start = machine.decide(machine.initialSnapshot(Unit), startInput)
        tracker.onCommitted(InputId(10), startInput, start)
        val command = start.commands.single().id
        val resultInput = MachineInput.CommandResult(command, Go, MachineTime.Zero)
        tracker.onIgnored(InputId(11), resultInput, IgnoreReason.NoTransition)
        assertEquals(InputId(10), tracker.inputs.value.last().parent)
        assertEquals(InputId(10), tracker.inputs.value.last().root)
        assertFalse(tracker.inputs.value.last().incomplete)
        val completion = MachineInput.CommandCompleted(command, MachineTime.Zero)
        tracker.onCommitted(InputId(12), completion, machine.decide(start.snapshot, completion))
        tracker.onIgnored(InputId(13), resultInput, IgnoreReason.StaleCommand)
        assertEquals(2, tracker.inputs.value.size)
        assertTrue(tracker.inputs.value.last().incomplete)
    }
}

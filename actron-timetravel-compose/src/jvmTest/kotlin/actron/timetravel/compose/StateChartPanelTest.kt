@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package actron.timetravel.compose

import androidx.compose.ui.test.*
import actron.core.Action
import actron.core.Event
import actron.statechart.*
import actron.statechart.machine.*
import kotlin.test.Test

class StateChartPanelTest {
    private data object Go : Action
    @Test fun selectingNodesAndTransitionsShowsActivityAndActualGuardResults() = runComposeUiTest {
        val idle = StateId("idle"); val done = StateId("done")
        val chart = StateChartDefinition(idle, listOf(AtomicState(idle), FinalState(done)), listOf(Transition(idle, done, ActionMatcher.of<Go>("go"), guard = actron.statechart.GuardKey("allowed"))))
        val machine = Machine<Unit, Go, Nothing, Event>(DefinitionId("ui"), DefinitionVersion("1"), chart) {
            guard("allowed") { _, _ -> false }
        }
        val snapshot = machine.decide(machine.initialSnapshot(Unit), MachineInput.Start(MachineTime.Zero)).snapshot
        val explained = machine.decideExplained(snapshot, MachineInput.Dispatch(Go, MachineTime.Zero))
        setContent { StateChartPanel(chart, snapshot, inspection = ChartInspection.Decided(explained.explanation, explained.decision.outcome.toString(), emptyList())) }
        onNodeWithTag("chart-node-idle").performClick()
        onNodeWithTag("chart-detail").assertTextContains("activation a1", substring = true)
        onNodeWithTag("chart-transition-0").performClick()
        onNodeWithTag("chart-detail").assertTextContains("GuardRejected", substring = true)
        onNodeWithText("allowed = false").assertExists()
    }
}

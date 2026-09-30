@file:OptIn(ExperimentalKomaApi::class, ExperimentalTestApi::class)

package koma.timetravel.compose

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import koma.core.Action
import koma.core.Event
import koma.core.ExperimentalKomaApi
import koma.core.InputId
import koma.observability.Capability
import koma.observability.ExecutionMode
import koma.observability.GroupSeq
import koma.observability.InputDescriptor
import koma.observability.JOURNAL_FORMAT_VERSION
import koma.observability.JournalEntry
import koma.observability.JournalRecord
import koma.observability.MachineGroupId
import koma.observability.OutcomeDescriptor
import koma.observability.OutcomeKind
import koma.observability.Payload
import koma.observability.RuntimeSessionId
import koma.observability.StoreInstanceId
import koma.observability.StoreSeq
import koma.statechart.ActionMatcher
import koma.statechart.AtomicState
import koma.statechart.CompoundState
import koma.statechart.StateChartDefinition
import koma.statechart.StateId
import koma.statechart.Transition
import koma.statechart.machine.DefinitionId
import koma.statechart.machine.DefinitionVersion
import koma.statechart.machine.Machine
import koma.statechart.machine.MachineInput
import koma.statechart.machine.MachineTime
import koma.timetravel.GroupBranch
import koma.timetravel.RecordedStep
import koma.timetravel.Recording
import koma.timetravel.ReplaySession
import koma.timetravel.inspect.Inspector
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * The inspector screen over a hand-made journal and a recording replayed by the pure machine:
 * the Stores with their completeness and reasons, the timeline, a selected position's detail,
 * the replay bar stepping and telling why forward is disabled, the definition drawn with the
 * replay's active states, and the branch panel deciding scripted inputs.
 */
class InspectorScreenTest {

    private val store = StoreInstanceId("chat-7")

    private fun journal(): List<JournalRecord<*, *, *>> {
        var seq = 0L
        fun record(entry: JournalEntry<*, *, *>, forStore: StoreInstanceId? = store) =
            JournalRecord(JOURNAL_FORMAT_VERSION, RuntimeSessionId("run"), MachineGroupId("chat"), forStore, ExecutionMode.Live, GroupSeq(++seq), forStore?.let { StoreSeq(seq) }, seq.milliseconds, entry)
        return listOf(
            record(JournalEntry.StoreRegistered(Capability.InspectOnly)),
            record(JournalEntry.InputAccepted(InputId(1), InputDescriptor.Startup)),
            record(JournalEntry.ProcessingStarted(InputId(1), 1)),
            record(JournalEntry.StateCommitted(InputId(1), 1, Payload.Omitted, Payload.Omitted)),
            record(JournalEntry.ProcessingFinished(InputId(1), 1, OutcomeDescriptor(OutcomeKind.Handled, 1), 1.milliseconds)),
            record(JournalEntry.InputAccepted(InputId(2), InputDescriptor.Dispatch(Payload.Projected("Send", mapOf("chars" to "12"))))),
            record(JournalEntry.ProcessingStarted(InputId(2), 2)),
            record(JournalEntry.ProcessingFinished(InputId(2), 2, OutcomeDescriptor(OutcomeKind.Unchanged), 2.milliseconds)),
            record(JournalEntry.JournalGap(3), forStore = null),
            record(JournalEntry.StoreClosed),
        )
    }

    @Test
    fun storesTimelineAndCompleteness_areShown_andAPositionOpensItsDetail() = runComposeUiTest {
        val state = InspectorState(Inspector.of(journal()))
        setContent { InspectorScreen(state) }

        onNodeWithTag("mode-bar").assertTextContains("INSPECT", substring = true)
        onNodeWithTag("completeness", useUnmergedTree = true).assertTextContains("partial", substring = true)
        onNodeWithTag("store-chat-7").assertTextContains("chat-7", substring = true)
        onNodeWithTag("store-chat-7").assertTextContains("payloads omitted", substring = true)
        onNodeWithTag("store-chat-7").assertTextContains("replay unavailable", substring = true)
        onNodeWithText("3 records dropped", substring = true).assertExists()
        onNodeWithTag("position").assertTextContains("select a position", substring = true)

        onNodeWithTag("timeline-1").performClick()

        onNodeWithTag("position").assertTextContains("Processing #1 Startup", substring = true)
        onNodeWithTag("position").assertTextContains("commit revision=1", substring = true)
        onNodeWithTag("position").assertTextContains("attach the Store's recording", substring = true)
    }

    // --- replay ---

    data class Ctx(val n: Int = 0)

    sealed interface Act : Action {
        data object Inc : Act
    }

    data object Nothing : Event

    private val root = StateId("Root")
    private val idle = StateId("Idle")
    private val machine = Machine<Ctx, Act, Nothing, Nothing>(
        DefinitionId("counter"), DefinitionVersion("1"),
        StateChartDefinition(root, listOf(CompoundState(root, initial = idle), AtomicState(idle, parent = root)), listOf(Transition(idle, idle, ActionMatcher.of<Act.Inc>("Inc"), effect = "inc"))),
    ) { effect("inc") { c, _ -> c.copy(n = c.n + 1) } }

    private fun recording(): Recording<Ctx, Act, Nothing, Nothing> {
        val inputs = listOf(MachineInput.Start(MachineTime.Zero), MachineInput.Dispatch(Act.Inc, MachineTime(10.milliseconds)), MachineInput.Dispatch(Act.Inc, MachineTime(20.milliseconds)))
        var snapshot = machine.initialSnapshot(Ctx())
        val steps = inputs.map { input ->
            val decision = machine.decide(snapshot, input)
            snapshot = decision.snapshot
            RecordedStep.Committed<Ctx, Act, Nothing, Nothing>(input, decision)
        }
        return Recording(machine.id, machine.version, machine.initialSnapshot(Ctx()), steps)
    }

    @Test
    fun theReplayBar_stepsSeeksAndVerifies_andSaysWhyForwardIsDisabled() = runComposeUiTest {
        val recording = recording()
        val controls = ReplayControls(store, ReplaySession(machine, recording))
        val state = InspectorState(Inspector.of(journal(), recordings = mapOf(store to recording)), InspectorMode.Replay)
        setContent { InspectorScreen(state, replay = controls) }

        onNodeWithTag("mode-bar").assertTextContains("REPLAY", substring = true)
        onNodeWithTag("replay-position").assertTextContains("0 / 3", substring = true)
        onNodeWithTag("replay-back").assertIsNotEnabled()

        onNodeWithTag("replay-forward").performClick()
        onNodeWithTag("replay-position").assertTextContains("1 / 3", substring = true)
        onNodeWithTag("replay-back").assertIsEnabled()
        onNodeWithTag("replay-forward").performClick()
        onNodeWithTag("replay-forward").performClick()
        onNodeWithTag("replay-position").assertTextContains("3 / 3", substring = true)
        onNodeWithTag("replay-forward").assertIsNotEnabled()
        onNodeWithTag("replay-why").assertTextContains("at the end of the recording", substring = true)

        onNodeWithTag("replay-back").performClick()
        onNodeWithTag("replay-position").assertTextContains("2 / 3", substring = true)
        onNodeWithTag("replay-verify").performClick()
        onNodeWithTag("replay-verdict").assertTextContains("no divergence in 3 steps", substring = true)
        onNode(hasTestTag("store-chat-7")).assertTextContains("mismatch", substring = true)
        onNodeWithTag("definition").assertTextContains("stateDiagram-v2", substring = true)
        onNodeWithTag("definition").assertTextContains("class Root,Idle koma_active", substring = true)
    }

    @Test
    fun theBranchPanel_decidesAScriptedInput_andDrawsTheDefinition() = runComposeUiTest {
        val recording = recording()
        val session = ReplaySession(machine, recording).also { it.seek(1) }
        val branch = BranchControls(GroupBranch(mapOf(store to session.branch()), emptyList()), mapOf(store to machine), listOf(BranchInput.Dispatch(store, "Inc", Act.Inc)))
        val state = InspectorState(Inspector.of(journal(), recordings = mapOf(store to recording)), InspectorMode.Branch)
        setContent { InspectorScreen(state, branch = branch) }

        onNodeWithTag("mode-bar").assertTextContains("BRANCH", substring = true)
        onNodeWithTag("branch-chat-7").assertTextContains("revision 1", substring = true)
        onNodeWithTag("branch-definition-chat-7").assertTextContains("class Root,Idle koma_active", substring = true)

        onNodeWithTag("branch-input-chat-7-Inc").performClick()

        onNodeWithTag("branch-chat-7").assertTextContains("revision 2", substring = true)
        onNodeWithTag("branch-chat-7").assertTextContains("Ctx(n=1)", substring = true)
        onNodeWithTag("branch-decision-0").assertTextContains("#0 chat-7 Inc: handled revision=2; context", substring = true)
        onNodeWithTag("branch-advance").performClick()
        onNodeWithTag("branch-chat-7").assertTextContains("now 1s", substring = true)
    }
}

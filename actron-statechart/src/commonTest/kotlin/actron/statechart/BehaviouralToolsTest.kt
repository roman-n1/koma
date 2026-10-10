package actron.statechart

import actron.core.Action
import actron.core.Event
import actron.statechart.machine.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class BehaviouralToolsTest {
    private data object Go : Action
    private data object Finish : Action
    private val idle = StateId("idle")
    private val done = StateId("done")
    private val go = ActionMatcher.of<Go>("go")

    @Test fun diffShowsGuardEffectAndInternalSemanticsEvenWhenEndpointsStayEqual() {
        val before = StateChartDefinition(idle, listOf(AtomicState(idle)), listOf(Transition(idle, idle, go, guard = actron.statechart.GuardKey("old"), effect = actron.statechart.EffectKey("before"))))
        val after = before.copy(transitions = listOf(Transition(idle, idle, go, guard = actron.statechart.GuardKey("new"), effect = actron.statechart.EffectKey("after"), kind = TransitionKind.Internal)))
        val description = before.diffTo(after).describe()
        assertTrue(description.contains("- idle --go [old] / before--> idle"), description)
        assertTrue(description.contains("+ idle --go [new] / after (internal)--> idle"), description)
    }

    @Test fun persistedTimerIdentitiesRequireMigrationReviewWhenTheirDefinitionMovesOrChanges() {
        val timer = Transition(idle, done, Trigger.After(5.seconds))
        val action = Transition(idle, done, go)
        val before = StateChartDefinition(idle, listOf(AtomicState(idle), FinalState(done)), listOf(timer, action))
        assertTrue(before.diffTo(before.copy(transitions = listOf(action, timer))).snapshotMigrationRequired)
        assertTrue(before.diffTo(before.copy(transitions = listOf(timer.copy(trigger = Trigger.After(3.seconds)), action))).snapshotMigrationRequired)
        assertFalse(before.diffTo(before.copy(transitions = before.transitions + Transition(idle, done, ActionMatcher.of<Finish>("finish")))).snapshotMigrationRequired)
        val noTimers = before.copy(transitions = listOf(action))
        assertTrue(noTimers.diffTo(noTimers.copy(transitions = noTimers.transitions + timer)).snapshotMigrationRequired)
    }

    @Test fun versionedDiffDoesNotExecuteOrPretendToCompareOpaqueRules() {
        var calls = 0
        val chart = StateChartDefinition(idle, listOf(AtomicState(idle), FinalState(done)), listOf(Transition(idle, done, go, guard = actron.statechart.GuardKey("allowed"))))
        fun machine(version: String, model: StateChartDefinition = chart, identity: String = "versions") =
            Machine<Unit, Action, Nothing, Event>(DefinitionId(identity), DefinitionVersion(version), model) {
                guard("allowed") { _, _ -> calls++; true }
                invariant("valid") { calls++; true }
                onEnter(idle) { calls++ }
            }
        val before = machine("1")
        val versioned = before.behaviouralDiffTo(machine("2"))
        assertFalse(versioned.implementationsCompared)
        assertTrue(versioned.snapshotMigrationReviewRequired)
        assertTrue(versioned.describe().contains("versions: 1 -> 2"))
        assertEquals(0, calls)
        val changed = chart.copy(transitions = listOf(chart.transitions.single().copy(kind = TransitionKind.Internal, target = idle)))
        assertTrue(before.behaviouralDiffTo(machine("1", changed)).versionBumpRequired)
        assertFailsWith<IllegalArgumentException> { before.behaviouralDiffTo(machine("2", identity = "other")) }
    }

    @Test fun matrixRetainsGuardAlternativesTimersAndTransitionOrder() {
        val chart = StateChartDefinition(idle, listOf(AtomicState(idle), FinalState(done)), listOf(
            Transition(idle, done, go, guard = actron.statechart.GuardKey("allowed"), effect = actron.statechart.EffectKey("save")),
            Transition(idle, idle, go, guard = actron.statechart.GuardKey("retry"), kind = TransitionKind.Internal),
            Transition(idle, done, Trigger.After(5.seconds)),
        ))
        val matrix = chart.transitionMatrix()
        assertEquals(2, matrix.triggers.size)
        assertEquals(listOf(0, 1), matrix.cell(idle, Trigger.OnAction(go)).map { it.index })
        assertTrue(matrix.cell(done, Trigger.OnAction(go)).isEmpty())
        val compact = matrix.toMarkdown()
        assertTrue(compact.contains("done [allowed] / save (#0)<br>idle [retry] (internal) (#1)"), compact)
        assertTrue(compact.contains("after 5s"), compact)
        val detailed = matrix.toTransitionTableMarkdown()
        assertTrue(detailed.contains("| 0 | idle | go | allowed | save | External | done |"), detailed)
    }

    @Test fun matrixShowsDeclarationsWithoutCopyingParentTransitionsIntoChildRows() {
        val root = StateId("root")
        val history = StateId("history")
        val chart = StateChartDefinition(root, listOf(CompoundState(root, idle), AtomicState(idle, root),
            FinalState(done, root), HistoryState(history, root)), listOf(
            Transition(root, root, go, kind = TransitionKind.Internal),
            Transition(idle, done, Trigger.Eventless),
            Transition(root, root, Trigger.Completion, kind = TransitionKind.Internal),
        ))
        val matrix = chart.transitionMatrix()
        assertEquals(listOf(root, idle, done, history), matrix.states)
        assertTrue(matrix.cell(idle, Trigger.OnAction(go)).isEmpty())
        assertEquals(1, matrix.cell(root, Trigger.OnAction(go)).size)
        assertTrue(matrix.toMarkdown().contains("always"))
        assertTrue(matrix.toMarkdown().contains("onDone"))
    }

    @Test fun markdownEscapesNamesAndKeepsDifferentMatchersWithTheSameDisplayName() {
        val unsafe = StateId("draft | <script>\nnext")
        val chart = StateChartDefinition(unsafe, listOf(AtomicState(unsafe), FinalState(done)), listOf(
            Transition(unsafe, done, ActionMatcher.of<Go>("same")),
            Transition(unsafe, done, ActionMatcher.of<Finish>("same")),
        ))
        val matrix = chart.transitionMatrix()
        assertEquals(2, matrix.triggers.size)
        val markdown = matrix.toMarkdown()
        assertTrue(markdown.contains("same (#1) | same (#2)"), markdown)
        assertTrue(markdown.contains("draft \\| &lt;script&gt;<br>next"), markdown)
        assertFalse(markdown.contains("<script>"))
    }
}

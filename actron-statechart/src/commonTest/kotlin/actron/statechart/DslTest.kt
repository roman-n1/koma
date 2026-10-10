package actron.statechart

import actron.core.Action
import actron.core.Event
import actron.statechart.machine.*
import kotlin.test.*

class DslTest {
    private data object Go : Action
    private val root = StateId("root"); private val idle = StateId("idle"); private val done = StateId("done")
    private val allowed = GuardKey("allowed"); private val count = EffectKey("count")

    @Test fun dslBuildsTheSameIntrospectableImmutableDefinition() {
        val chart = stateChart(root) {
            compound(root, idle) {
                state(idle) { on<Go>("go", done) { guard(allowed); effect(count) } }
                final(done)
            }
        }
        val expected = StateChartDefinition(root, listOf(CompoundState(root, idle), AtomicState(idle, root), FinalState(done, root)),
            listOf(Transition(idle, done, ActionMatcher.of<Go>("go"), allowed.name, count.name)))
        assertEquals(expected, chart)
        assertTrue(chart.validate().isEmpty())
        assertTrue(chart.toMermaid().contains("[final]"))
        val machine = Machine<Int, Go, Nothing, Event>(DefinitionId("dsl"), DefinitionVersion("1"), chart) {
            guard(allowed) { _, _ -> true }; effect(count) { context, _ -> context + 1 }
        }
        val start = machine.decide(machine.initialSnapshot(0), MachineInput.Start(MachineTime.Zero)).snapshot
        assertEquals(1, machine.decide(start, MachineInput.Dispatch(Go, MachineTime.Zero)).snapshot.context)
        assertFailsWith<IllegalArgumentException> { GuardKey("") }
    }

    @Test fun definitionDiffFlagsPersistedHierarchyChangesAndTransitionIdentityChanges() {
        val old = stateChart(idle) { state(idle) { on<Go>("go", done) }; final(done) }
        val removed = old.copy(states = listOf(AtomicState(idle)))
        assertTrue(old.diffTo(removed).snapshotMigrationRequired)
        assertTrue(old.diffTo(removed).replayMayChange)
        val added = old.copy(transitions = old.transitions + Transition(idle, idle, ActionMatcher.of<Go>("go")))
        assertFalse(old.diffTo(added).snapshotMigrationRequired)
        assertTrue(old.diffTo(added).transitionOrderChanged)
        assertFalse(old.diffTo(old).replayMayChange)
    }
}

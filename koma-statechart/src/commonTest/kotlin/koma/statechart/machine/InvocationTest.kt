package koma.statechart.machine

import koma.core.Action
import koma.core.Event
import koma.statechart.*
import kotlin.test.*

class InvocationTest {
    private data object Open : Action
    private data object Close : Action
    private data object Finish : Action
    private data object Finished : Event
    private val idle = StateId("idle")
    private val active = StateId("active")
    private val working = StateId("working")
    private val done = StateId("done")
    private val parent = Machine<Int, Action, String, Event>(DefinitionId("parent"), DefinitionVersion("1"),
        StateChartDefinition(idle, listOf(AtomicState(idle), AtomicState(active)), listOf(
            Transition(idle, active, ActionMatcher.of<Open>("open")),
            Transition(active, active, ActionMatcher.of<Open>("open")),
            Transition(active, idle, ActionMatcher.of<Close>("close")),
        )))
    private val child = Machine<Int, Action, String, Event>(DefinitionId("child"), DefinitionVersion("1"),
        StateChartDefinition(working, listOf(AtomicState(working), FinalState(done)), listOf(Transition(working, done, ActionMatcher.of<Finish>("finish"))))) {
        onEnter(working) { command("fetch") }
        onEnter(done) { event(Finished) }
    }
    private fun invocation(childMachine: Machine<Int, Action, String, Event> = child) = InvokedMachine(parent, active, childMachine, { it.context }, toParent = { if (it == Finished) Close else null })
    private fun started(composition: InvokedMachine<Int, Action, String, Event, Int, Action, String, Event>) =
        composition.decide(composition.initialSnapshot(0), InvocationInput.Parent(MachineInput.Start(MachineTime.Zero))).snapshot

    @Test fun childLifetimesAndLateInputsAreQualifiedByParentActivation() {
        val composition = invocation()
        val opened = composition.decide(started(composition), InvocationInput.Parent(MachineInput.Dispatch(Open, MachineTime.Zero)))
        assertNotNull(opened.snapshot.child)
        assertEquals(1, opened.childCommands.size)
        val oldOwner = opened.snapshot.owner!!
        val reopened = composition.decide(opened.snapshot, InvocationInput.Parent(MachineInput.Dispatch(Open, MachineTime.Zero)))
        assertNotEquals(oldOwner, reopened.snapshot.owner)
        assertEquals(oldOwner, reopened.childCancellations.single().owner)
        assertEquals(1, reopened.childCommands.size)
        val stale = composition.decide(reopened.snapshot, InvocationInput.Child(oldOwner, MachineInput.Dispatch(Finish, MachineTime.Zero)))
        assertEquals(reopened.snapshot, stale.snapshot)
        assertTrue(stale.outcome is DecisionOutcome.Ignored)
        val finished = composition.decide(reopened.snapshot, InvocationInput.Child(reopened.snapshot.owner!!, MachineInput.Dispatch(Finish, MachineTime.Zero)))
        assertTrue(finished.snapshot.parent.isActive(idle))
        assertNull(finished.snapshot.child)
        assertTrue(finished.childCommands.isEmpty())
        assertEquals(reopened.snapshot.owner, finished.childCancellations.single().owner)
    }

    @Test fun failedChildStartRollsBackParentAndAllIntents() {
        val broken = Machine<Int, Action, String, Event>(child.id, child.version, child.chart) {
            onEnter(working) { command("must-not-execute"); error("child start failed") }
        }
        val composition = invocation(broken)
        val base = started(composition)
        val failed = composition.decide(base, InvocationInput.Parent(MachineInput.Dispatch(Open, MachineTime.Zero)))
        assertSame(base, failed.snapshot)
        assertTrue(failed.outcome is DecisionOutcome.Failed)
        assertTrue(failed.parentDecisions.isEmpty())
        assertTrue(failed.childDecisions.isEmpty())
    }
}

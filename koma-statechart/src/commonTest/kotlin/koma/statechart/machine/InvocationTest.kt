package koma.statechart.machine

import koma.core.Action
import koma.core.Event
import koma.statechart.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class InvocationTest {
    private data object Open : Action
    private data object Close : Action
    private data object Finish : Action
    private data object Finished : Event
    private data object Ping : Action
    private data object Again : Event
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

    @Test fun temporaryParentAndChildWorkIsFilteredBeforeCompositeCommit() {
        val owningParent = Machine<Int, Action, String, Event>(parent.id, parent.version,
            parent.chart.copy(transitions = parent.chart.transitions + Transition(active, idle, Trigger.After(10.seconds)))) {
            onEnter(active) { command("parent-fetch") }
        }
        val immediateChild = Machine<Int, Action, String, Event>(child.id, child.version,
            child.chart.copy(transitions = child.chart.transitions + Transition(working, done, Trigger.After(5.seconds)))) {
            onEnter(working) { command("child-fetch"); event(Finished) }
        }
        val composition = InvokedMachine(owningParent, active, immediateChild, { it.context }, toParent = { Close })
        val base = started(composition)
        val result = composition.decide(base, InvocationInput.Parent(MachineInput.Dispatch(Open, MachineTime.Zero)))
        assertTrue(result.outcome is DecisionOutcome.Handled)
        assertTrue(result.snapshot.parent.isActive(idle))
        assertNull(result.snapshot.child)
        assertTrue(result.parentCommands.isEmpty())
        assertTrue(result.parentTimers.isEmpty())
        assertTrue(result.childCommands.isEmpty())
        assertTrue(result.childTimers.isEmpty())
        assertTrue(result.childCancellations.single().scopes.isNotEmpty())
    }

    @Test fun queuedChildEventsCannotReopenTheirExpiredOwner() {
        val emitting = Machine<Int, Action, String, Event>(child.id, child.version, child.chart) {
            onEnter(working) { event(Finished); event(Again) }
        }
        val composition = InvokedMachine(parent, active, emitting, { it.context }, toParent = {
            when (it) { Finished -> Close; Again -> Open; else -> null }
        })
        val result = composition.decide(started(composition), InvocationInput.Parent(MachineInput.Dispatch(Open, MachineTime.Zero)))
        assertTrue(result.snapshot.parent.isActive(idle))
        assertNull(result.snapshot.child)
        assertEquals(1, result.childDecisions.size)
    }

    @Test fun feedbackLimitRollsBackBothMachinesAndAllIntents() {
        val bouncingParent = Machine<Int, Action, String, Event>(parent.id, parent.version, parent.chart) {
            onAction(active, ActionMatcher.of<Ping>("ping")) { context++; command("parent-work"); event(Again) }
        }
        val bouncingChild = Machine<Int, Action, String, Event>(child.id, child.version, child.chart) {
            onEnter(working) { event(Again) }
            onAction(working, ActionMatcher.of<Ping>("ping")) { context++; command("child-work"); event(Again) }
        }
        val composition = InvokedMachine(bouncingParent, active, bouncingChild, { it.context },
            toParent = { Ping }, toChild = { Ping }, maxDeliveries = 4)
        val base = started(composition)
        val result = composition.decide(base, InvocationInput.Parent(MachineInput.Dispatch(Open, MachineTime.Zero)))
        assertSame(base, result.snapshot)
        assertEquals(4, ((result.outcome as DecisionOutcome.Failed).cause as InvocationLimitException).limit)
        assertTrue(result.parentDecisions.isEmpty())
        assertTrue(result.childDecisions.isEmpty())
        assertTrue(result.childCancellations.isEmpty())
        assertTrue(result.parentCommands.isEmpty())
        assertTrue(result.childCommands.isEmpty())
    }

    @Test fun throwingEventMapperRollsBackBeforeTheChildCanRun() {
        val emitting = Machine<Int, Action, String, Event>(child.id, child.version, child.chart) {
            onEnter(working) { command("child-work"); event(Finished) }
        }
        val failure = IllegalStateException("mapping failed")
        val composition = InvokedMachine(parent, active, emitting, { it.context }, toParent = { throw failure })
        val base = started(composition)
        val result = composition.decide(base, InvocationInput.Parent(MachineInput.Dispatch(Open, MachineTime.Zero)))
        assertSame(base, result.snapshot)
        assertSame(failure, (result.outcome as DecisionOutcome.Failed).cause)
        assertTrue(result.childCommands.isEmpty())
    }

    @Test fun restoredChildMustHaveBeenStartedByItsOwner() {
        val composition = invocation()
        val opened = composition.decide(started(composition), InvocationInput.Parent(MachineInput.Dispatch(Open, MachineTime.Zero))).snapshot
        val malformed = opened.copy(child = child.initialSnapshot(0))
        assertFailsWith<IllegalArgumentException> {
            composition.decide(malformed, InvocationInput.Child(opened.owner!!, MachineInput.Dispatch(Finish, MachineTime.Zero)))
        }
    }
}

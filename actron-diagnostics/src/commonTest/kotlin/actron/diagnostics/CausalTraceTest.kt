package actron.diagnostics

import actron.core.InputAttribution

import actron.core.*
import actron.observability.StoreInstanceId
import actron.observability.FailureDescriptor
import actron.statechart.*
import actron.statechart.machine.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class CausalTraceTest {
    private data object Go : Action
    private data object Changed : Event
    private val idle = StateId("idle")
    private fun machine() = Machine<Int, Action, String, Event>(DefinitionId("trace"), DefinitionVersion("1"),
        StateChartDefinition(idle, listOf(AtomicState(idle)), emptyList())) {
        onAction(idle, ActionMatcher.of<Go>("go")) { context++; command("sensitive-payload"); event(Changed) }
    }

    @Test fun commandAndCrossStoreBridgeResultsLinkToOriginalInputWithoutRetainingPayloads() {
        val machine = machine(); val hub = CausalTraceHub()
        val root = StoreInstanceId("root"); val child = StoreInstanceId("child")
        val observer = hub.observer(machine, root)
        var snapshot = machine.initialSnapshot(0)
        fun decide(input: MachineInput<Action>, id: Long): Decision<Int, String, Event> {
            val explained = machine.decideExplained(snapshot, input)
            observer.onDecided(InputId(id), input, explained)
            snapshot = explained.decision.snapshot
            return explained.decision
        }
        decide(MachineInput.Start(MachineTime.Zero), 1)
        val sent = decide(MachineInput.Dispatch(Go, MachineTime.Zero), 2)
        decide(MachineInput.CommandResult(sent.commands.single().id, Go, MachineTime.Zero), 3)
        val childBase = machine.decide(machine.initialSnapshot(0), MachineInput.Start(MachineTime.Zero)).snapshot
        val input = MachineInput.BridgeReceived(MessageId(root, sent.effects.single().id), Go, MachineTime.Zero)
        hub.observer(machine, child).onDecided(InputId(1), input, machine.decideExplained(childBase, input))
        val events = hub.snapshot()
        assertEquals(events[1].origin, events[2].parent)
        assertEquals(events[1].origin, events[3].parent)
        assertEquals(events[1].trace, events[3].trace)
        assertEquals(root, events[3].root.store)
        assertFalse(events[3].incomplete)
        assertFalse(events.toString().contains("sensitive-payload"))
        assertEquals("command-result", events[2].attributes()["actron.input.kind"])
    }

    @Test fun evictedUnknownAndMissingInputCausesAreExplicitAndHistoryStaysBounded() {
        val machine = machine(); val hub = CausalTraceHub(capacity = 1)
        val store = StoreInstanceId("s"); val observer = hub.observer(machine, store)
        val base = machine.decide(machine.initialSnapshot(0), MachineInput.Start(MachineTime.Zero)).snapshot
        val send = MachineInput.Dispatch(Go, MachineTime.Zero)
        observer.onDecided(InputId(1), send, machine.decideExplained(base, send))
        val stale = MachineInput.CommandCompleted(CommandId(999), MachineTime.Zero)
        observer.onDecided(InputAttribution.Unattributed, stale, machine.decideExplained(base, stale))
        val event = hub.snapshot().single()
        assertTrue(event.incomplete)
        assertEquals(InputAttribution.Unattributed, event.origin.input)
        assertTrue(event.trace.value.contains("unknown"))
        assertEquals("ignored:StaleCommand", event.outcome)
    }

    @Test fun unexpectedIoFailureIsReportedEvenWhenTheModelHandlesItsFailureInput() {
        val machine = machine(); val hub = CausalTraceHub(); val observer = hub.observer(machine, StoreInstanceId("io"))
        val initial = machine.decide(machine.initialSnapshot(0), MachineInput.Start(MachineTime.Zero)).snapshot
        val dispatch = MachineInput.Dispatch(Go, MachineTime.Zero)
        val sent = machine.decideExplained(initial, dispatch)
        observer.onDecided(InputId(1), dispatch, sent)
        val failed = MachineInput.CommandFailed(sent.decision.commands.single().id, FailureDescriptor("RemoteFailure"), MachineTime.Zero)
        observer.onDecided(InputId(2), failed, machine.decideExplained(sent.decision.snapshot, failed))
        val event = hub.snapshot().last()
        assertEquals("handled", event.outcome)
        assertEquals("RemoteFailure", event.failureType)
        assertEquals(FailureSource.Command, event.failureSource)
        assertEquals(hub.snapshot().first().origin, event.parent)
    }

    @Test fun redactedDecisionTypeFallsBackToCommandMetadataWithoutRetainingMessages() {
        val origin = TraceOrigin(StoreInstanceId("privacy"), InputId(1), 1)
        val event = CausalTraceEvent(DefinitionId("trace"), DefinitionVersion("1"), origin, TraceLineage.Root(origin),
            "command-failed", MachineTime.Zero, TraceProvenance.Command(CommandId(2)), emptyList(), emptyList(), emptyList(),
            TraceAssessment.Receipt("failed", listOf(
                TraceFault.of(FailureSource.Decision, FailureDescriptor.Unavailable),
                TraceFault.of(FailureSource.Command, FailureDescriptor("RemoteFailure", "message-secret")),
            )))
        val attributes = event.attributes()
        assertEquals("RemoteFailure", attributes["actron.failure.type"])
        assertEquals("Decision", attributes["actron.failure.source"], "the decision is the primary failure even when its type was withheld")
        assertFalse(attributes.containsKey("actron.failure.typeLabel"), "the established exporter key is unchanged")
        assertFalse(event.toString().contains("message-secret"), "a trace must discard private messages before retention")
    }

    private class Storage : DurableMachineStorage<Int, String> {
        var checkpoint: DurableCheckpoint<Int, String>? = null
        var reject = false
        override suspend fun read(accept: (DurableCheckpoint<Int, String>) -> Unit): Boolean { checkpoint?.let(accept); return checkpoint != null }
        override suspend fun commit(expectedGeneration: actron.statechart.machine.DurableGeneration, checkpoint: DurableCheckpoint<Int, String>): Boolean {
            if (reject || expectedGeneration != (this.checkpoint?.generation?.let { actron.statechart.machine.DurableGeneration.Existing(it) } ?: actron.statechart.machine.DurableGeneration.Fresh)) return false
            this.checkpoint = checkpoint
            return true
        }
    }

    @Test fun persistedCommitSupportsExplicitOutboxProvenanceWithoutInventingGuardObservations() = runTest {
        val machine = machine(); val hub = CausalTraceHub(); val store = StoreInstanceId("durable")
        val storage = Storage()
        val durable = DurableMachine(machine, storage) { _, classify -> classify(IdempotencyKey("operation")) }
        durable.initialize(0, MachineTime.Zero)
        val sent = durable.commit(MachineInput.Dispatch(Go, MachineTime.Zero))
        val registration = sent.checkpoint.outbox.values.single().registration
        val first = hub.recordPersisted(machine, store, InputId(1), sent)
        val completed = assertNotNull(durable.executeObserved(DurableCommandHandler { _, _, _, emit -> }) { MachineTime.Zero })
        val next = hub.recordPersisted(machine, store, InputId(2), completed, registration)
        assertEquals(first.origin, next.parent)
        assertEquals(first.trace, next.trace)
        assertNull(next.selection)
        assertFalse(next.incomplete)
        assertEquals("persisted", next.outcome)
    }

    @Test fun failedStorageCommitProducesNoPersistedDiagnosticEvent() = runTest {
        val machine = machine(); val storage = Storage(); val hub = CausalTraceHub()
        val durable = DurableMachine(machine, storage) { _, classify -> classify(IdempotencyKey("operation")) }
        durable.initialize(0, MachineTime.Zero)
        storage.reject = true
        assertFailsWith<DurableConflictException> {
            val commit = durable.commit(MachineInput.Dispatch(Go, MachineTime.Zero))
            hub.recordPersisted(machine, StoreInstanceId("cas"), InputId(1), commit)
        }
        assertTrue(hub.snapshot().isEmpty())
    }
}

private suspend fun <C : Any, A : actron.core.Action, CMD : Any, E : actron.core.Event> DurableMachine<C, A, CMD, E>.executeObserved(
    handler: DurableCommandHandler<CMD, A>, now: () -> MachineTime,
): DurableCommit<C, CMD, E>? {
    var observed: DurableCommit<C, CMD, E>? = null
    val executed = executeNext(handler, { check(observed == null); observed = it }, now)
    kotlin.test.assertEquals(observed != null, executed)
    return observed
}

private suspend fun <C : Any, A : actron.core.Action, CMD : Any, E : actron.core.Event> DurableMachine<C, A, CMD, E>.restoreObserved(): DurableCheckpoint<C, CMD>? {
    var observed: DurableCheckpoint<C, CMD>? = null
    val found = restore { check(observed == null); observed = it }
    kotlin.test.assertEquals(observed != null, found)
    return observed
}

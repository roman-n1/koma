package koma.diagnostics

import koma.core.*
import koma.observability.StoreInstanceId
import koma.observability.FailureDescriptor
import koma.statechart.*
import koma.statechart.machine.*
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
        assertEquals("command-result", events[2].attributes()["koma.input.kind"])
    }

    @Test fun evictedUnknownAndMissingInputCausesAreExplicitAndHistoryStaysBounded() {
        val machine = machine(); val hub = CausalTraceHub(capacity = 1)
        val store = StoreInstanceId("s"); val observer = hub.observer(machine, store)
        val base = machine.decide(machine.initialSnapshot(0), MachineInput.Start(MachineTime.Zero)).snapshot
        val send = MachineInput.Dispatch(Go, MachineTime.Zero)
        observer.onDecided(InputId(1), send, machine.decideExplained(base, send))
        val stale = MachineInput.CommandCompleted(CommandId(999), MachineTime.Zero)
        observer.onDecided(null, stale, machine.decideExplained(base, stale))
        val event = hub.snapshot().single()
        assertTrue(event.incomplete)
        assertNull(event.origin.input)
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

    private class Storage : DurableMachineStorage<Int, String> {
        var checkpoint: DurableCheckpoint<Int, String>? = null
        var reject = false
        override suspend fun load(): DurableCheckpoint<Int, String>? = checkpoint
        override suspend fun commit(expectedGeneration: Long?, checkpoint: DurableCheckpoint<Int, String>): Boolean {
            if (reject || expectedGeneration != this.checkpoint?.generation) return false
            this.checkpoint = checkpoint
            return true
        }
    }

    @Test fun persistedCommitSupportsExplicitOutboxProvenanceWithoutInventingGuardObservations() = runTest {
        val machine = machine(); val hub = CausalTraceHub(); val store = StoreInstanceId("durable")
        val storage = Storage()
        val durable = DurableMachine(machine, storage) { IdempotencyKey("operation") }
        durable.initialize(0, MachineTime.Zero)
        val sent = durable.commit(MachineInput.Dispatch(Go, MachineTime.Zero))
        val registration = sent.checkpoint.outbox.values.single().registration
        val first = hub.recordPersisted(machine, store, InputId(1), sent)
        val completed = assertNotNull(durable.executeNext(DurableCommandHandler { _, _, _ -> null }) { MachineTime.Zero })
        val next = hub.recordPersisted(machine, store, InputId(2), completed, registration)
        assertEquals(first.origin, next.parent)
        assertEquals(first.trace, next.trace)
        assertNull(next.selection)
        assertFalse(next.incomplete)
        assertEquals("persisted", next.outcome)
    }

    @Test fun failedStorageCommitProducesNoPersistedDiagnosticEvent() = runTest {
        val machine = machine(); val storage = Storage(); val hub = CausalTraceHub()
        val durable = DurableMachine(machine, storage) { IdempotencyKey("operation") }
        durable.initialize(0, MachineTime.Zero)
        storage.reject = true
        assertFailsWith<DurableConflictException> {
            val commit = durable.commit(MachineInput.Dispatch(Go, MachineTime.Zero))
            hub.recordPersisted(machine, StoreInstanceId("cas"), InputId(1), commit)
        }
        assertTrue(hub.snapshot().isEmpty())
    }
}

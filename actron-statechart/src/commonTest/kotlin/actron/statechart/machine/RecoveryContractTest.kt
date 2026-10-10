package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import actron.statechart.*
import kotlin.test.*

class RecoveryContractTest {
    @Test fun ordinaryIoRequiresApplicationRepeatDecisionWhileDurableReceiptsSuppressReexecution() {
        val idle = StateId("idle")
        val machine = Machine<Unit, Action, String, Event>(DefinitionId("recovery-report"), DefinitionVersion("1"),
            StateChartDefinition(idle, listOf(AtomicState(idle)), emptyList())) { onEnter(idle) { command("payload") } }
        val start = machine.decide(machine.initialSnapshot(Unit), MachineInput.Start(MachineTime.Zero))
        val registration = start.commands.single()
        val executor = ExecutorCheckpoint(start.snapshot, MachineTime.Zero, Lanes<String>().admit(registration).lanes)
        val ordinary = executor.recoveryContract().commands.single()
        assertEquals(RecoveryPolicy.CancelOnExit, ordinary.policy)
        assertFalse(ordinary.survivesExit)
        assertEquals(RepeatSafety.ApplicationDecision, ordinary.repeatSafety)
        assertTrue(ordinary.hasPersistedPayload)
        val key = IdempotencyKey("business-operation")
        val checkpoint = DurableCheckpoint(0, start.snapshot, MachineTime.Zero,
            mapOf(key to DurableCommand(key, "payload", registration.id, attempts = 2)))
        val durable = checkpoint.recoveryContract().commands.single()
        assertEquals(RecoveryPolicy.DurableIdempotent, durable.policy)
        assertTrue(durable.survivesExit)
        assertEquals(RepeatSafety.IdempotentRetry, durable.repeatSafety)
        assertEquals(2, durable.attempts)
        val receipt = checkpoint.copy(snapshot = start.snapshot.copy(commands = emptyMap()),
            outbox = mapOf(key to checkpoint.outbox.getValue(key).copy(status = DurableStatus.Completed)))
            .recoveryContract().commands.single()
        assertEquals(RecoveryPolicy.CompletedReceipt, receipt.policy)
        assertEquals(RepeatSafety.ReceiptSuppresses, receipt.repeatSafety)
    }
}

package koma.statechart.machine

import koma.core.Action
import koma.core.Event
import koma.statechart.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class DurableMachineTest {
    private data object Leave : Action
    private data object Applied : Action
    private data object Retry : Action
    private data class Pay(val amount: Int)
    private val active = StateId("active")
    private val left = StateId("left")
    private val machine = Machine<Int, Action, Pay, Event>(DefinitionId("pay"), DefinitionVersion("1"),
        StateChartDefinition(active, listOf(AtomicState(active), AtomicState(left)), listOf(
            Transition(active, left, ActionMatcher.of<Leave>("leave")),
            Transition(active, left, Trigger.After(10.seconds)),
        ))) {
        onEnter(active) { command(Pay(10)) }
        onAction(active, ActionMatcher.of<Applied>("applied")) { context++ }
        onAction(left, ActionMatcher.of<Applied>("applied")) { context++ }
    }

    private class Storage<C, CMD> : DurableMachineStorage<C, CMD> {
        var saved: DurableCheckpoint<C, CMD>? = null
        var fail = false
        override suspend fun load() = saved
        override suspend fun commit(expectedGeneration: Long?, checkpoint: DurableCheckpoint<C, CMD>): Boolean {
            if (fail) { fail = false; error("disk failed") }
            if (saved?.generation != expectedGeneration) return false
            saved = checkpoint
            return true
        }
    }
    private fun runner(storage: Storage<Int, Pay>) = DurableMachine(machine, storage) { IdempotencyKey("order-1") }

    @Test fun crashAfterExternalEffectRetriesTheSameKey_andReceiptPreventsDoublePayment() = runTest {
        val storage = Storage<Int, Pay>()
        runner(storage).initialize(0, MachineTime.Zero)
        val receipts = mutableSetOf<IdempotencyKey>()
        var balance = 0
        val attempts = mutableListOf<Long>()
        val handler = DurableCommandHandler<Pay, Action> { key, command, attempt ->
            attempts += attempt
            if (receipts.add(key)) balance += command.amount
            storage.fail = attempt == 1L // external side effect succeeded, atomic ack fails
            Applied
        }
        assertFailsWith<IllegalStateException> { runner(storage).executeNext(handler) { MachineTime.Zero } }
        assertEquals(DurableStatus.Pending, storage.saved!!.outbox.values.single().status)
        assertEquals(10, balance)
        val restarted = runner(storage)
        val result = restarted.executeNext(handler) { MachineTime.Zero }
        assertEquals(listOf(1L, 2L), attempts)
        assertEquals(10, balance)
        assertEquals(1, result!!.checkpoint.snapshot.context)
        assertEquals(DurableStatus.Completed, result.checkpoint.outbox.values.single().status)
        assertNull(restarted.executeNext(handler) { MachineTime.Zero })
    }

    @Test fun stateExitWhileIoAwaitsDoesNotLoseTheDurableIntent() = runTest {
        val storage = Storage<Int, Pay>()
        val runner = runner(storage)
        runner.initialize(0, MachineTime.Zero)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val drain = async {
            runner.executeNext(DurableCommandHandler { _, _, _ -> started.complete(Unit); release.await(); Applied }) { MachineTime.Zero }
        }
        started.await()
        runner.commit(MachineInput.Dispatch(Leave, MachineTime.Zero))
        assertTrue(storage.saved!!.snapshot.commands.isEmpty())
        assertEquals(DurableStatus.Pending, storage.saved!!.outbox.values.single().status)
        release.complete(Unit)
        val result = drain.await()!!
        assertTrue(result.checkpoint.snapshot.isActive(left))
        assertEquals(1, result.checkpoint.snapshot.context)
    }

    @Test fun failedInitialPersistenceReturnsNoIntents_andRestoreKeepsTimers() = runTest {
        val storage = Storage<Int, Pay>()
        storage.fail = true
        assertFailsWith<IllegalStateException> { runner(storage).initialize(0, MachineTime.Zero) }
        assertNull(storage.saved)
        val first = runner(storage).initialize(0, MachineTime.Zero)
        val restored = runner(storage).restore()!!
        assertEquals(first.checkpoint, restored)
        assertTrue(runner(storage).dueTimers(MachineTime.Zero + 9.seconds).isEmpty())
        assertEquals(1, runner(storage).dueTimers(MachineTime.Zero + 11.seconds).size)
        assertFailsWith<IllegalArgumentException> { runner(storage).commit(MachineInput.Dispatch(Leave, MachineTime.Zero + (-1).seconds)) }
    }

    @Test fun duplicatePendingAndCompletedKeysNeverLeaveUnownedCommandRegistrations() = runTest {
        val retryMachine = Machine<Int, Action, Pay, Event>(machine.id, machine.version,
            StateChartDefinition(active, listOf(AtomicState(active)), emptyList())) {
            onAction(active, ActionMatcher.of<Retry>("retry")) { command(Pay(10)) }
        }
        val storage = Storage<Int, Pay>()
        val durable = DurableMachine(retryMachine, storage) { IdempotencyKey("same") }
        durable.initialize(0, MachineTime.Zero)
        repeat(2) { durable.commit(MachineInput.Dispatch(Retry, MachineTime.Zero)) }
        assertEquals(2, storage.saved!!.snapshot.commands.size)
        assertEquals(1, storage.saved!!.outbox.size)
        var calls = 0
        durable.executeNext(DurableCommandHandler { _, _, _ -> calls++; null }) { MachineTime.Zero }
        assertTrue(storage.saved!!.snapshot.commands.isEmpty())
        durable.commit(MachineInput.Dispatch(Retry, MachineTime.Zero))
        assertTrue(storage.saved!!.snapshot.commands.isEmpty())
        assertNull(durable.executeNext(DurableCommandHandler { _, _, _ -> calls++; null }) { MachineTime.Zero })
        assertEquals(1, calls)
    }

    @Test fun ordinaryWorkCanBeRestartedFromStoredPayloads() = runTest {
        val storage = Storage<Int, Pay>()
        val durable = DurableMachine(machine, storage) { null }
        val first = durable.initialize(0, MachineTime.Zero)
        assertEquals(1, first.ephemeralCommands.size)
        val restored = DurableMachine(machine, storage) { null }.initialize(100, MachineTime.Zero)
        assertEquals(first.ephemeralCommands, restored.ephemeralCommands)
        assertEquals(0, restored.checkpoint.snapshot.context)
    }

    @Test fun semanticMigrationValidatesConfigurationWorkAndInvariants() {
        val old = machine.decide(machine.initialSnapshot(2), MachineInput.Start(MachineTime.Zero)).snapshot
        val newMachine = Machine<String, Action, Pay, Event>(machine.id, DefinitionVersion("2"), machine.chart) {
            invariant("not-empty") { it.context.isNotEmpty() }
        }
        val migration = SnapshotMigration<Int, String>(machine.version, newMachine.version) { snapshot ->
            MachineSnapshot(snapshot.definition, newMachine.version, snapshot.revision, snapshot.configuration, snapshot.context.toString(),
                snapshot.activations, snapshot.commands, snapshot.timers, snapshot.counters)
        }
        val result = newMachine.migrateSnapshot(old, migration)
        assertEquals("2", result.context)
        assertEquals(old.timers, result.timers)
        assertFailsWith<IllegalArgumentException> {
            newMachine.migrateSnapshot(old, SnapshotMigration(machine.version, newMachine.version) { migration.transform(it).copy(activations = emptyMap()) })
        }
        assertFailsWith<IllegalArgumentException> {
            SnapshotMigrations(listOf(SnapshotMigration<Int, Int>(DefinitionVersion("1"), DefinitionVersion("2")) { it.copy(version = DefinitionVersion("2")) },
                SnapshotMigration(DefinitionVersion("2"), DefinitionVersion("1")) { it.copy(version = DefinitionVersion("1")) }))
        }
    }
}

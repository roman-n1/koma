package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import actron.statechart.*
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
        var conflict = false
        override suspend fun load() = saved
        override suspend fun commit(expectedGeneration: Long?, checkpoint: DurableCheckpoint<C, CMD>): Boolean {
            if (fail) { fail = false; error("disk failed") }
            if (conflict) return false
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

    @Test fun completedReceiptCleanupPersistsWorkCreatedByAutomaticTransitions() = runTest {
        val chart = StateChartDefinition(active, listOf(AtomicState(active), AtomicState(left)), listOf(
            Transition(active, left, Trigger.Eventless, guard = "retry-completed"),
        ))
        val retryMachine = Machine<Int, Action, Pay, Event>(machine.id, machine.version, chart) {
            onEnter(active) { command(Pay(10)) }
            onAction(active, ActionMatcher.of<Retry>("retry")) { context++; command(Pay(10)) }
            guard("retry-completed") { snapshot, _ -> snapshot.context == 1 && snapshot.commands.isEmpty() }
            onEnter(left) { command(Pay(20)) }
        }
        val storage = Storage<Int, Pay>()
        val durable = DurableMachine(retryMachine, storage) { if (it.amount == 10) IdempotencyKey("same") else null }
        durable.initialize(0, MachineTime.Zero)
        durable.executeNext(DurableCommandHandler { _, _, _ -> null }) { MachineTime.Zero }
        val committed = durable.commit(MachineInput.Dispatch(Retry, MachineTime.Zero))
        assertTrue(committed.checkpoint.snapshot.isActive(left))
        assertEquals(Pay(20), committed.ephemeralCommands.single().command)
        assertEquals(committed.ephemeralCommands, durable.initialize(0, MachineTime.Zero).ephemeralCommands)
        assertNull(durable.executeNext(DurableCommandHandler { _, _, _ -> error("Receipt must suppress IO") }) { MachineTime.Zero })
    }

    @Test fun receiptCleanupFailureRollsBackWithTheOriginalCause() = runTest {
        val failure = IllegalStateException("automatic cleanup failed")
        val retryMachine = Machine<Int, Action, Pay, Event>(machine.id, machine.version,
            StateChartDefinition(active, listOf(AtomicState(active), AtomicState(left)), listOf(
                Transition(active, left, Trigger.Eventless, guard = "cleanup", effect = "fail"),
            ))) {
            onEnter(active) { command(Pay(10)) }
            onAction(active, ActionMatcher.of<Retry>("retry")) { context++; command(Pay(10)) }
            guard("cleanup") { snapshot, _ -> snapshot.context == 1 && snapshot.commands.isEmpty() }
            effect("fail") { _, _ -> throw failure }
        }
        val storage = Storage<Int, Pay>()
        val durable = DurableMachine(retryMachine, storage) { IdempotencyKey("same") }
        durable.initialize(0, MachineTime.Zero)
        durable.executeNext(DurableCommandHandler { _, _, _ -> null }) { MachineTime.Zero }
        val before = storage.saved
        assertSame(failure, assertFailsWith<IllegalStateException> { durable.commit(MachineInput.Dispatch(Retry, MachineTime.Zero)) })
        assertSame(before, storage.saved)
    }

    @Test fun restoreRejectsMalformedPayloadOwnershipAndOutboxAliases() = runTest {
        val storage = Storage<Int, Pay>()
        val durable = runner(storage)
        durable.initialize(0, MachineTime.Zero)
        val base = storage.saved!!
        val command = base.outbox.values.single()
        val ordinary = CommandRegistration(command.registration, command.command,
            base.snapshot.commands.getValue(command.registration).scope)
        val otherKey = IdempotencyKey("other")
        val cases = listOf(
            "unissued alias" to base.copy(outbox = mapOf(command.key to command.copy(registrations = command.registrations + CommandId(999)))),
            "missing original registration" to base.copy(outbox = mapOf(command.key to command.copy(registrations = emptySet()))),
            "two outbox owners" to base.copy(outbox = base.outbox + (otherKey to command.copy(key = otherKey))),
            "ordinary and durable owners" to base.copy(ephemeral = mapOf(ordinary.id to ordinary)),
            "wrong ordinary lane" to base.copy(outbox = emptyMap(), ephemeral = mapOf(ordinary.id to ordinary.copy(lane = LaneId("wrong"), policy = ConcurrencyPolicy.Latest))),
            "completed receipt owns live work" to base.copy(outbox = mapOf(command.key to command.copy(status = DurableStatus.Completed))),
            "unstarted checkpoint" to base.copy(snapshot = machine.initialSnapshot(0), outbox = emptyMap()),
        )
        for ((label, invalid) in cases) {
            storage.saved = invalid
            assertFailsWith<IllegalArgumentException>(label) { durable.restore() }
        }
    }

    @Test fun cachedCommandFeedbackIsBoundedAndRollsBackTheEntireCommit() = runTest {
        val retryMachine = Machine<Int, Action, Pay, Event>(machine.id, machine.version,
            StateChartDefinition(active, listOf(AtomicState(active)), listOf(
                Transition(active, active, Trigger.Eventless, guard = "cleanup"),
            ))) {
            onEnter(active) { command(Pay(10)) }
            onAction(active, ActionMatcher.of<Retry>("retry")) { context++; command(Pay(10)) }
            guard("cleanup") { snapshot, _ -> snapshot.context > 0 && snapshot.commands.isEmpty() }
        }
        val storage = Storage<Int, Pay>()
        val durable = DurableMachine(retryMachine, storage, maxReceiptCompletions = 3) { IdempotencyKey("same") }
        durable.initialize(0, MachineTime.Zero)
        durable.executeNext(DurableCommandHandler { _, _, _ -> null }) { MachineTime.Zero }
        val before = storage.saved
        val failure = assertFailsWith<IllegalStateException> { durable.commit(MachineInput.Dispatch(Retry, MachineTime.Zero)) }
        assertTrue(failure.message.orEmpty().contains("3 receipt completions"))
        assertSame(before, storage.saved)
    }

    @Test fun reservationConflictPreventsExternalIo() = runTest {
        val storage = Storage<Int, Pay>()
        val durable = runner(storage)
        durable.initialize(0, MachineTime.Zero)
        val before = storage.saved
        var calls = 0
        val handler = DurableCommandHandler<Pay, Action> { _, _, _ -> calls++; null }
        storage.conflict = true
        assertFailsWith<DurableConflictException> { durable.executeNext(handler) { MachineTime.Zero } }
        assertSame(before, storage.saved)
        assertEquals(0, calls)
        storage.conflict = false
        durable.executeNext(handler) { MachineTime.Zero }
        assertEquals(1, calls)
    }

    @Test fun failedResultDecisionKeepsTheOutboxPendingAndCanBeRetried() = runTest {
        val failure = IllegalStateException("result failed")
        val failingMachine = Machine<Int, Action, Pay, Event>(machine.id, machine.version, machine.chart) {
            onEnter(active) { command(Pay(10)) }
            onAction(active, ActionMatcher.of<Applied>("applied")) { context++; command(Pay(20)); throw failure }
        }
        val storage = Storage<Int, Pay>()
        val durable = DurableMachine(failingMachine, storage) { IdempotencyKey("same") }
        val initial = durable.initialize(0, MachineTime.Zero).checkpoint.snapshot
        assertSame(failure, assertFailsWith<IllegalStateException> {
            durable.executeNext(DurableCommandHandler { _, _, _ -> Applied }) { MachineTime.Zero }
        })
        assertEquals(initial, storage.saved!!.snapshot)
        assertEquals(DurableStatus.Pending, storage.saved!!.outbox.values.single().status)
        assertEquals(1L, storage.saved!!.outbox.values.single().attempts)
        val completed = durable.executeNext(DurableCommandHandler { _, _, _ -> Leave }) { MachineTime.Zero }!!
        assertTrue(completed.checkpoint.snapshot.isActive(left))
        assertEquals(DurableStatus.Completed, completed.checkpoint.outbox.values.single().status)
        assertEquals(2L, completed.checkpoint.outbox.values.single().attempts)
        assertTrue(completed.ephemeralCommands.isEmpty())
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

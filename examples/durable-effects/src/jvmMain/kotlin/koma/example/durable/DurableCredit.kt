package koma.example.durable

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import koma.core.Action
import koma.core.Event
import koma.core.ExceptionHandler
import koma.core.StateSaver
import koma.statechart.ActionMatcher
import koma.statechart.AtomicState
import koma.statechart.StateChartDefinition
import koma.statechart.StateId
import koma.statechart.machine.CommandHandler
import koma.statechart.machine.Delivery
import koma.statechart.machine.DefinitionId
import koma.statechart.machine.DefinitionVersion
import koma.statechart.machine.EffectPolicy
import koma.statechart.machine.Machine
import koma.statechart.machine.MachineSnapshot
import koma.statechart.machine.MachineStore
import koma.statechart.machine.MailboxConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A business key created by the caller; it must survive every Store/process recreation. */
@Serializable
data class CreditIntent(val id: String, val amount: Long) {
    init { require(id.isNotBlank() && amount > 0) }
}

@Serializable
data class Outbox(val pending: CreditIntent? = null, val completed: CreditIntent? = null) {
    init { require(pending == null || completed == null || pending.id != completed.id) }
}

sealed interface CreditAction : Action {
    data class Request(val intent: CreditIntent) : CreditAction
    data class Applied(val intent: CreditIntent) : CreditAction
}

data class ApplyCredit(val intent: CreditIntent) : Event

val creditMachine: Machine<Outbox, CreditAction, Nothing, ApplyCredit> = run {
    val ready = StateId("Ready")
    Machine(DefinitionId("durable-credit"), DefinitionVersion("1"),
        StateChartDefinition(ready, listOf(AtomicState(ready)), emptyList())) {
        onEnter(ready) { context.pending?.let { event(ApplyCredit(it)) } }
        onAction(ready, ActionMatcher.of<CreditAction.Request>("Request")) {
            val intent = (action as CreditAction.Request).intent
            if (context.pending == null && context.completed?.id != intent.id) {
                context = context.copy(pending = intent)
                event(ApplyCredit(intent))
            } else {
                require(context.pending == intent || context.completed == intent) { "Another intent is pending" }
            }
        }
        onAction(ready, ActionMatcher.of<CreditAction.Applied>("Applied")) {
            val intent = (action as CreditAction.Applied).intent
            if (context.pending == intent) context = Outbox(completed = intent)
        }
    }
}

/** Single writer only. Both the actual balance and the idempotency receipt share one commit. */
@Serializable
data class Ledger(val balance: Long = 0, val receipts: Map<String, Long> = emptyMap()) {
    init {
        require(receipts.all { (id, amount) -> id.isNotBlank() && amount > 0 })
        require(balance == receipts.values.fold(0L) { total, amount -> Math.addExact(total, amount) })
    }
}

@Serializable
private data class DomainFile(val schema: Int = 1, val outbox: Outbox)

@Serializable
private data class LedgerFile(val schema: Int = 1, val ledger: Ledger)

private val storageJson = Json { encodeDefaults = true }

/** POSIX local filesystem recipe; fails closed if atomic move or directory fsync is unsupported. */
internal fun durableReplace(path: Path, text: String) {
    val temporary = Files.createTempFile(path.parent, "pending-", ".tmp")
    try {
        FileChannel.open(temporary, WRITE).use { channel ->
            val bytes = ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
        Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
        FileChannel.open(path.parent, READ).use { it.force(true) }
    } finally {
        Files.deleteIfExists(temporary)
    }
}

/** Persist only business data; live executor/mailbox identity is intentionally not restored. */
class DiskOutbox(private val path: Path) : StateSaver<MachineSnapshot<Outbox>> {
    val durable = MutableStateFlow(read())
    val savedRevision = MutableStateFlow(0L)
    private val barriers = mutableMapOf<CompletableDeferred<Unit>, (Outbox) -> Boolean>()

    /** Register before dispatch: later saves must not hide a successful durable completion. */
    internal suspend fun awaitSaved(predicate: (Outbox) -> Boolean, dispatch: () -> Unit) {
        val barrier = CompletableDeferred<Unit>()
        synchronized(barriers) { barriers[barrier] = predicate }
        try {
            dispatch()
            barrier.await()
        } finally {
            synchronized(barriers) { barriers.remove(barrier) }
        }
    }

    private fun read(): Outbox = if (!Files.exists(path)) Outbox() else {
        val decoded = storageJson.decodeFromString<DomainFile>(Files.readString(path))
        require(decoded.schema == 1) { "Unsupported domain schema" }
        decoded.outbox
    }

    override fun restore(): MachineSnapshot<Outbox> = creditMachine.initialSnapshot(durable.value)

    override fun save(state: MachineSnapshot<Outbox>) {
        durableReplace(path, storageJson.encodeToString(DomainFile(outbox = state.context)))
        durable.value = state.context // Publish only after persistence actually succeeded.
        savedRevision.value = state.revision
        synchronized(barriers) {
            barriers.filterValues { it(state.context) }.keys.forEach { it.complete(Unit) }
        }
    }
}

class DiskLedger(private val path: Path) {
    // Test seam inside the synchronous ledger operation, immediately before its real commit.
    internal var beforeCommit: () -> Unit = {}
    fun read(): Ledger = if (!Files.exists(path)) Ledger() else {
        val decoded = storageJson.decodeFromString<LedgerFile>(Files.readString(path))
        require(decoded.schema == 1) { "Unsupported ledger schema" }
        decoded.ledger
    }

    /** Returns false on redelivery, rejects reuse of an id for a different payload. */
    @Synchronized
    fun apply(intent: CreditIntent): Boolean {
        val previous = read()
        previous.receipts[intent.id]?.let {
            require(it == intent.amount) { "Intent id reused with another amount" }
            return false
        }
        val next = Ledger(Math.addExact(previous.balance, intent.amount), previous.receipts + (intent.id to intent.amount))
        beforeCommit()
        durableReplace(path, storageJson.encodeToString(LedgerFile(ledger = next)))
        return true
    }
}

enum class Boundary { BeforeEffect, AfterEffect, AfterDomainAck, AfterMailboxAck }

private class SessionFiles(val channel: FileChannel, val lock: FileLock, val saver: DiskOutbox, val ledger: DiskLedger) {
    fun close() { try { lock.release() } finally { channel.close() } }
}

private fun acquireSessionFiles(directory: Path): SessionFiles {
    // An OS lock survives screen changes and is released even by Runtime.halt/process death.
    val channel = FileChannel.open(directory.resolve("owner.lock"), CREATE, WRITE)
    try {
        val lock = checkNotNull(channel.tryLock()) { "Another process owns this outbox" }
        try {
            return SessionFiles(channel, lock, DiskOutbox(directory.resolve("domain.json")), DiskLedger(directory.resolve("ledger.json")))
        } catch (failure: Throwable) { lock.release(); throw failure }
    } catch (failure: Throwable) { channel.close(); throw failure }
}

/** One owner/collector. Close a screen's subscription separately from this retained Store. */
class CreditSession private constructor(private val files: SessionFiles) : AutoCloseable {
    constructor(directory: Path) : this(acquireSessionFiles(directory))
    @Volatile private var closed = false
    internal val isClosing: Boolean get() = closed
    private val ownerJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + ownerJob)
    val saver = files.saver
    val ledger = files.ledger
    val failures = MutableStateFlow<Throwable?>(null)
    val store = MachineStore(
        creditMachine, Outbox(), CommandHandler<Nothing, CreditAction> { _, _ -> }, scope,
        coroutineContext = scope.coroutineContext,
        mailbox = MailboxConfig(policy = { EffectPolicy.Retained() }),
    ) {
        stateSaver(saver)
        exceptionHandler(ExceptionHandler { failures.value = it })
    }

    suspend fun start() = withTimeout(10_000) {
        store.start()
        saver.savedRevision.first { it > 0 }
    }

    suspend fun request(intent: CreditIntent) = withTimeout(10_000) {
        saver.awaitSaved({ it.pending == intent || it.completed == intent }) {
            store.dispatch(CreditAction.Request(intent))
        }
    }

    /** Hooks are test seams at real commit boundaries; production uses the default empty hook. */
    suspend fun handle(delivery: Delivery<ApplyCredit>, boundary: (Boundary) -> Unit = {}) {
        val handler = synchronized(this) {
            check(!closed) { "CreditSession is closed" }
            scope.async { handleOwned(delivery, boundary) }
        }
        try {
            handler.await()
        } finally {
            // Screen cancellation must also stop (and join) the retained owner's handler.
            // A blocking filesystem operation may finish despite cancellation; keep ownership
            // until it returns, so a new session cannot write alongside it.
            withContext(NonCancellable) { handler.cancelAndJoin() }
        }
    }

    private suspend fun handleOwned(delivery: Delivery<ApplyCredit>, boundary: (Boundary) -> Unit) = withTimeout(10_000) {
        val intent = delivery.event.intent
        // StateSaver failures do not abort a Koma commit. Never apply an unpersisted intent.
        if (saver.durable.value.completed == intent) {
            check(ledger.read().receipts[intent.id] == intent.amount) { "Completed intent has no matching receipt" }
            check(delivery.acknowledge())
            return@withTimeout
        }
        check(saver.durable.value.pending == intent) { "Intent is not durably pending" }
        boundary(Boundary.BeforeEffect)
        withContext(Dispatchers.IO) { ledger.apply(intent) }
        boundary(Boundary.AfterEffect)
        saver.awaitSaved({ it.completed == intent }) {
            store.dispatch(CreditAction.Applied(intent))
        }
        boundary(Boundary.AfterDomainAck)
        check(delivery.acknowledge()) { "Mailbox no longer owns the delivery" }
        boundary(Boundary.AfterMailboxAck)
    }

    // CLI owner teardown runs outside the Store's job and waits before releasing the writer lock.
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            store.close()
        } finally {
            try { runBlocking { ownerJob.cancelAndJoin() } } finally { files.close() }
        }
    }
}

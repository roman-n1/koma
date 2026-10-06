package koma.statechart.machine

import koma.core.Action
import koma.core.Event
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.jvm.JvmInline

/** A business-operation identity, stable across attempts and process restarts. */
@JvmInline
value class IdempotencyKey(val value: String) {
    init { require(value.isNotBlank()) { "[Koma] Idempotency key must not be blank" } }
}

/** Outbox delivery state; completed entries are receipts, retained for the application's retry horizon. */
enum class DurableStatus { Pending, Completed }

/** Durable intent independent of its original activation; [attempts] survives process death. */
data class DurableCommand<CMD>(
    val key: IdempotencyKey,
    val command: CMD,
    val registration: CommandId,
    val status: DurableStatus = DurableStatus.Pending,
    val attempts: Long = 0,
    val registrations: Set<CommandId> = setOf(registration),
)

/** The atomic persistence boundary: model snapshot, logical clock and outbox/receipts. */
data class DurableCheckpoint<C, CMD>(
    val generation: Long,
    val snapshot: MachineSnapshot<C>,
    val now: MachineTime,
    val outbox: Map<IdempotencyKey, DurableCommand<CMD>> = emptyMap(),
    val ephemeral: Map<CommandId, CommandRegistration<CMD>> = emptyMap(),
)

/**
 * Application transaction adapter. `commit` atomically compares generation and writes the entire
 * checkpoint. It must be durably committed before returning true; false means a competing writer.
 */
interface DurableMachineStorage<C, CMD> {
    suspend fun load(): DurableCheckpoint<C, CMD>?
    suspend fun commit(expectedGeneration: Long?, checkpoint: DurableCheckpoint<C, CMD>): Boolean
}

/** External IO honours [key] as an idempotency key; nullable result means successful completion. */
fun interface DurableCommandHandler<CMD, A : Action> {
    suspend fun execute(key: IdempotencyKey, command: CMD, attempt: Long): A?
}

/** Nothing may execute from a commit that lost its storage compare-and-swap. */
class DurableConflictException : IllegalStateException("[Koma] Durable checkpoint changed concurrently")

/**
 * Committed data; the caller executes work only after persistence succeeds.
 * [decisions] is the ordered trace, including durable registrations and temporary work. Start
 * ordinary commands from [ephemeralCommands], and schedule only timers in [checkpoint]'s final
 * snapshot. Apply trace cancellation intents to previously running work; deliver events in order.
 */
data class DurableCommit<C, CMD, E : Event>(
    val checkpoint: DurableCheckpoint<C, CMD>,
    val decisions: List<Decision<C, CMD, E>>,
    val ephemeralCommands: List<CommandRegistration<CMD>>,
)

/**
 * Persistence executor with an atomic outbox. Use instead of MachineStore for this workflow.
 * Commands classified with a business key are delivered by [executeNext], survive state exit,
 * and retry that key after a crash. Ordinary command intents are returned after persistence.
 * Storage and external idempotency are application contracts, never inferred from CommandId.
 */
class DurableMachine<C, A : Action, CMD, E : Event>(
    val machine: Machine<C, A, CMD, E>,
    private val storage: DurableMachineStorage<C, CMD>,
    private val maxReceiptCompletions: Int,
    private val keyOf: (CMD) -> IdempotencyKey?,
) {
    /** Uses the default receipt completion bound, preserving the original constructor contract. */
    constructor(
        machine: Machine<C, A, CMD, E>,
        storage: DurableMachineStorage<C, CMD>,
        keyOf: (CMD) -> IdempotencyKey?,
    ) : this(machine, storage, 100, keyOf)

    init { require(maxReceiptCompletions > 0) { "[Koma] Receipt completion limit must be positive" } }
    private val commits = Mutex()
    private val execution = Mutex()
    private val source = SourceId("durable:${machine.id.value}")

    private fun validate(checkpoint: DurableCheckpoint<C, CMD>) {
        require(checkpoint.generation >= 0) { "[Koma] Negative storage generation" }
        require(checkpoint.snapshot.isStarted) { "[Koma] Durable checkpoint must be started" }
        val issues = machine.validateSnapshot(checkpoint.snapshot)
        require(issues.isEmpty()) { "[Koma] Invalid durable snapshot: ${issues.joinToString()}" }
        require(checkpoint.ephemeral.all { (id, command) -> id == command.id && checkpoint.snapshot.commands[id] == CommandRecord(command.scope, command.lane) }) { "[Koma] Invalid ephemeral registrations" }
        val durableIds = checkpoint.outbox.values.filter { it.status == DurableStatus.Pending }.flatMap { it.registrations }.toSet()
        require(checkpoint.snapshot.commands.keys.all { it in checkpoint.ephemeral || it in durableIds }) { "[Koma] Command payload missing from durable checkpoint" }
        require(checkpoint.outbox.all { (key, command) ->
            key == command.key && command.attempts >= 0 && command.registration in command.registrations &&
                command.registrations.all { it.value > 0 && it.value <= checkpoint.snapshot.counters.commands } &&
                (command.status != DurableStatus.Completed || command.registrations.none { it in checkpoint.snapshot.commands })
        }) { "[Koma] Invalid outbox" }
        val aliases = checkpoint.outbox.values.flatMap { it.registrations }
        require(aliases.toSet().size == aliases.size && aliases.none { it in checkpoint.ephemeral }) { "[Koma] Command registration has multiple payload owners" }
    }

    /** Loads and validates the persisted checkpoint; no enter hooks or commands are repeated. */
    suspend fun restore(): DurableCheckpoint<C, CMD>? = storage.load()?.also(::validate)

    /** Initializes exactly once; an existing checkpoint is restored rather than restarted. */
    suspend fun initialize(context: C, now: MachineTime): DurableCommit<C, CMD, E> = commits.withLock {
        val existing = storage.load()
        if (existing != null) {
            validate(existing)
            return@withLock DurableCommit(existing, emptyList(), existing.ephemeral.values.toList())
        }
        val decision = machine.decide(machine.initialSnapshot(context), MachineInput.Start(now))
        persist(null, DurableCheckpoint(0, decision.snapshot, now), listOf(decision))
    }

    /** Persists the entire decision and its durable registrations before returning any intents. */
    suspend fun commit(input: MachineInput<A>): DurableCommit<C, CMD, E> = commits.withLock {
        val base = requireNotNull(storage.load()) { "[Koma] Initialize the durable machine first" }
        validate(base)
        require(input.now >= base.now) { "[Koma] Durable logical time moved backwards" }
        val decision = machine.decide(base.snapshot, input)
        persist(base.generation, base.copy(snapshot = decision.snapshot, now = input.now), listOf(decision))
    }

    private suspend fun persist(expected: Long?, base: DurableCheckpoint<C, CMD>, decisions: List<Decision<C, CMD, E>>): DurableCommit<C, CMD, E> {
        val failed = decisions.firstOrNull { it.outcome is DecisionOutcome.Failed }
        if (failed != null) throw (failed.outcome as DecisionOutcome.Failed).cause
        val outbox = base.outbox.toMutableMap()
        var snapshot = base.snapshot
        val accepted = decisions.toMutableList()
        val ephemeral = mutableListOf<CommandRegistration<CMD>>()
        // A receipt completion is a real Machine input: automatic transitions may create more
        // commands. Classify those too, before the single atomic storage commit.
        val pending = ArrayDeque(decisions.flatMap { it.commands })
        var receiptCompletions = 0
        while (pending.isNotEmpty()) {
            val registration = pending.removeFirst()
            val key = keyOf(registration.command)
            if (key == null) {
                ephemeral += registration
            } else {
                val previous = outbox[key]
                require(previous == null || previous.command == registration.command) { "[Koma] Idempotency key reused with different command: ${key.value}" }
                if (previous == null) outbox[key] = DurableCommand(key, registration.command, registration.id)
                else if (previous.status == DurableStatus.Pending) outbox[key] = previous.copy(registrations = previous.registrations + registration.id)
                else {
                    check(++receiptCompletions <= maxReceiptCompletions) { "[Koma] Durable commit exceeded $maxReceiptCompletions receipt completions" }
                    val completion = machine.decide(snapshot, MachineInput.CommandCompleted(registration.id, base.now))
                    if (completion.outcome is DecisionOutcome.Failed) throw completion.outcome.cause
                    accepted += completion
                    snapshot = completion.snapshot
                    pending.addAll(completion.commands)
                }
            }
        }
        require(expected == null || expected < Long.MAX_VALUE) { "[Koma] Storage generation exhausted" }
        val registrations = (base.ephemeral + ephemeral.associateBy { it.id }).filterKeys { it in snapshot.commands }
        val next = base.copy(generation = if (expected == null) 0 else expected + 1, snapshot = snapshot, outbox = outbox.toMap(), ephemeral = registrations)
        validate(next)
        if (!storage.commit(expected, next)) throw DurableConflictException()
        return DurableCommit(next, accepted.toList(), ephemeral.filter { it.id in snapshot.commands })
    }

    /** Overdue virtual timers in deterministic deadline/id order, ready to [commit] on recovery. */
    suspend fun dueTimers(now: MachineTime): List<MachineInput.TimerFired> {
        val checkpoint = restore() ?: return emptyList()
        require(now >= checkpoint.now) { "[Koma] Durable logical time moved backwards" }
        return checkpoint.snapshot.timers.entries.filter { it.value.deadline <= now }
            .sortedWith(compareBy({ it.value.deadline }, { it.key.value }))
            .map { MachineInput.TimerFired(it.key, now) }
    }

    /**
     * Executes one pending operation. Persist attempt, perform IO outside the commit lock, then
     * atomically decide its output and acknowledge the outbox. A failed ack leaves it pending.
     * Calls are serialized per executor; input commits remain possible while IO awaits.
     */
    suspend fun executeNext(handler: DurableCommandHandler<CMD, A>, now: () -> MachineTime): DurableCommit<C, CMD, E>? = execution.withLock drain@ {
        val command = commits.withLock reserve@ {
            val base = requireNotNull(storage.load()) { "[Koma] Initialize the durable machine first" }
            validate(base)
            val pending = base.outbox.values.firstOrNull { it.status == DurableStatus.Pending } ?: return@reserve null
            require(pending.attempts < Long.MAX_VALUE) { "[Koma] Attempt counter exhausted" }
            val attempted = pending.copy(attempts = pending.attempts + 1)
            persist(base.generation, base.copy(outbox = base.outbox + (pending.key to attempted)), emptyList())
            attempted
        } ?: return@drain null
        val result = handler.execute(command.key, command.command, command.attempts)
        commits.withLock acknowledge@ {
            val base = requireNotNull(storage.load()) { "[Koma] Durable checkpoint disappeared" }
            validate(base)
            val current = requireNotNull(base.outbox[command.key]) { "[Koma] Pending outbox entry disappeared" }
            require(current.command == command.command) { "[Koma] Pending command changed" }
            if (current.status == DurableStatus.Completed) return@acknowledge DurableCommit(base, emptyList(), emptyList())
            val time = now()
            require(time >= base.now) { "[Koma] Durable logical time moved backwards" }
            val decisions = mutableListOf<Decision<C, CMD, E>>()
            var snapshot = base.snapshot
            if (result != null) {
                val decision = machine.decide(snapshot, MachineInput.External(source, result, time))
                decisions += decision
                snapshot = decision.snapshot
            }
            for (registration in current.registrations) {
                val completed = machine.decide(snapshot, MachineInput.CommandCompleted(registration, time))
                decisions += completed
                snapshot = completed.snapshot
            }
            persist(base.generation, base.copy(snapshot = snapshot, now = time,
                outbox = base.outbox + (command.key to current.copy(status = DurableStatus.Completed))), decisions)
        }
    }
}

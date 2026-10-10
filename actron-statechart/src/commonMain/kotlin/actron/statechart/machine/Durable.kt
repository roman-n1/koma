package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.jvm.JvmInline

/** A business-operation identity, stable across attempts and process restarts. */
@JvmInline
value class IdempotencyKey(val value: String) {
    init { require(value.isNotBlank()) { "[Actron] Idempotency key must not be blank" } }
}

/** Outbox delivery state; completed entries are receipts, retained for the application's retry horizon. */
enum class DurableStatus { Pending, Completed }

/** Durable intent independent of its original activation; [attempts] survives process death. */
data class DurableCommand<CMD : Any>(
    val key: IdempotencyKey,
    val command: CMD,
    val registration: CommandId,
    val status: DurableStatus = DurableStatus.Pending,
    val attempts: Long = 0,
    val registrations: Set<CommandId> = setOf(registration),
)

/** The atomic persistence boundary: model snapshot, logical clock and outbox/receipts. */
data class DurableCheckpoint<C : Any, CMD : Any>(
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
interface DurableMachineStorage<C : Any, CMD : Any> {
    /** Calls [accept] exactly once for an existing checkpoint, before returning true. */
    suspend fun read(accept: (DurableCheckpoint<C, CMD>) -> Unit): Boolean
    suspend fun commit(expectedGeneration: DurableGeneration, checkpoint: DurableCheckpoint<C, CMD>): Boolean
}

/** The compare-and-swap precondition for initializing or advancing durable storage. */
sealed interface DurableGeneration {
    fun next(): Long

    data object Fresh : DurableGeneration {
        override fun next(): Long = 0
    }

    data class Existing(val value: Long) : DurableGeneration {
        init { require(value >= 0) }
        override fun next(): Long {
            require(value < Long.MAX_VALUE) { "[Actron] Storage generation exhausted" }
            return value + 1
        }
    }
}

/** External IO honours [key]; it may emit one result during this suspended call. */
fun interface DurableCommandHandler<CMD : Any, A : Action> {
    suspend fun execute(key: IdempotencyKey, command: CMD, attempt: Long, emit: (A) -> Unit)
}

/** Nothing may execute from a commit that lost its storage compare-and-swap. */
class DurableConflictException : IllegalStateException("[Actron] Durable checkpoint changed concurrently")

/**
 * Committed data; the caller executes work only after persistence succeeds.
 * [decisions] is the ordered trace, including durable registrations and temporary work. Start
 * ordinary commands from [ephemeralCommands], and schedule only timers in [checkpoint]'s final
 * snapshot. Apply trace cancellation intents to previously running work; deliver events in order.
 */
data class DurableCommit<C : Any, CMD : Any, E : Event>(
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
class DurableMachine<C : Any, A : Action, CMD : Any, E : Event>(
    val machine: Machine<C, A, CMD, E>,
    private val storage: DurableMachineStorage<C, CMD>,
    private val maxReceiptCompletions: Int,
    private val keyOf: (CMD, (IdempotencyKey) -> Unit) -> Unit,
) {
    /** Uses the default receipt completion bound, preserving the original constructor contract. */
    constructor(
        machine: Machine<C, A, CMD, E>,
        storage: DurableMachineStorage<C, CMD>,
        keyOf: (CMD, (IdempotencyKey) -> Unit) -> Unit,
    ) : this(machine, storage, 100, keyOf)

    init { require(maxReceiptCompletions > 0) { "[Actron] Receipt completion limit must be positive" } }
    private val commits = Mutex()
    private val execution = Mutex()
    private val source = SourceId("durable:${machine.id.value}")

    private fun validate(checkpoint: DurableCheckpoint<C, CMD>) {
        require(checkpoint.generation >= 0) { "[Actron] Negative storage generation" }
        require(checkpoint.snapshot.isStarted) { "[Actron] Durable checkpoint must be started" }
        val issues = machine.validateSnapshot(checkpoint.snapshot)
        require(issues.isEmpty()) { "[Actron] Invalid durable snapshot: ${issues.joinToString()}" }
        require(checkpoint.ephemeral.all { (id, command) -> id == command.id && checkpoint.snapshot.commands[id] == CommandRecord(command.scope, command.lane) }) { "[Actron] Invalid ephemeral registrations" }
        val durableIds = checkpoint.outbox.values.filter { it.status == DurableStatus.Pending }.flatMap { it.registrations }.toSet()
        require(checkpoint.snapshot.commands.keys.all { it in checkpoint.ephemeral || it in durableIds }) { "[Actron] Command payload missing from durable checkpoint" }
        require(checkpoint.outbox.all { (key, command) ->
            key == command.key && command.attempts >= 0 && command.registration in command.registrations &&
                command.registrations.all { it.value > 0 && it.value <= checkpoint.snapshot.counters.commands } &&
                (command.status != DurableStatus.Completed || command.registrations.none { it in checkpoint.snapshot.commands })
        }) { "[Actron] Invalid outbox" }
        val aliases = checkpoint.outbox.values.flatMap { it.registrations }
        require(aliases.toSet().size == aliases.size && aliases.none { it in checkpoint.ephemeral }) { "[Actron] Command registration has multiple payload owners" }
    }

    /** Loads and validates the persisted checkpoint; no enter hooks or commands are repeated. */
    suspend fun restore(accept: (DurableCheckpoint<C, CMD>) -> Unit): Boolean {
        var reading = true
        var accepted = false
        val found = try {
            storage.read { checkpoint ->
                check(reading && !accepted) { "[Actron] Storage must return one checkpoint during read" }
                accepted = true
                validate(checkpoint)
                accept(checkpoint)
            }
        } finally {
            reading = false
        }
        check(found == accepted) { "[Actron] Storage read result disagrees with its checkpoint" }
        return found
    }

    private suspend fun stored(): DurableCheckpoint<C, CMD> {
        var loaded: () -> DurableCheckpoint<C, CMD> = { error("[Actron] Initialize the durable machine first") }
        restore { checkpoint -> loaded = { checkpoint } }
        return loaded()
    }

    /** Initializes exactly once; an existing checkpoint is restored rather than restarted. */
    suspend fun initialize(context: C, now: MachineTime): DurableCommit<C, CMD, E> = commits.withLock {
        var initialize: suspend () -> DurableCommit<C, CMD, E> = {
            val decision = machine.decide(machine.initialSnapshot(context), MachineInput.Start(now))
            persist(DurableGeneration.Fresh, DurableCheckpoint(0, decision.snapshot, now), listOf(decision))
        }
        restore { existing ->
            initialize = { DurableCommit(existing, emptyList(), existing.ephemeral.values.toList()) }
        }
        initialize()
    }

    /** Persists the entire decision and its durable registrations before returning any intents. */
    suspend fun commit(input: MachineInput<A>): DurableCommit<C, CMD, E> = commits.withLock {
        val base = stored()
        require(input.now >= base.now) { "[Actron] Durable logical time moved backwards" }
        val decision = machine.decide(base.snapshot, input)
        persist(DurableGeneration.Existing(base.generation), base.copy(snapshot = decision.snapshot, now = input.now), listOf(decision))
    }

    private suspend fun persist(expected: DurableGeneration, base: DurableCheckpoint<C, CMD>, decisions: List<Decision<C, CMD, E>>): DurableCommit<C, CMD, E> {
        for (decision in decisions) {
            val outcome = decision.outcome
            if (outcome is DecisionOutcome.Failed) throw outcome.cause
        }
        val outbox = base.outbox.toMutableMap()
        var snapshot = base.snapshot
        val accepted = decisions.toMutableList()
        val ephemeral = mutableListOf<CommandRegistration<CMD>>()
        // A receipt completion is a real Machine input: automatic transitions may create more
        // commands. Classify those too, before the single atomic storage commit.
        val pending = ArrayDeque(decisions.flatMap { it.commands })
        var receiptCompletions = 0
        fun registerDurable(key: IdempotencyKey, registration: CommandRegistration<CMD>) {
            if (key !in outbox) {
                outbox[key] = DurableCommand(key, registration.command, registration.id)
                return
            }
            val previous = outbox.getValue(key)
            require(previous.command == registration.command) { "[Actron] Idempotency key reused with different command: ${key.value}" }
            if (previous.status == DurableStatus.Pending) {
                outbox[key] = previous.copy(registrations = previous.registrations + registration.id)
            } else {
                check(++receiptCompletions <= maxReceiptCompletions) { "[Actron] Durable commit exceeded $maxReceiptCompletions receipt completions" }
                val completion = machine.decide(snapshot, MachineInput.CommandCompleted(registration.id, base.now))
                if (completion.outcome is DecisionOutcome.Failed) throw completion.outcome.cause
                accepted += completion
                snapshot = completion.snapshot
                pending.addAll(completion.commands)
            }
        }
        while (pending.isNotEmpty()) {
            val registration = pending.removeFirst()
            var classified = false
            var classifying = true
            var classify: () -> Unit = { ephemeral += registration }
            try {
                keyOf(registration.command) { key ->
                    check(classifying && !classified) { "[Actron] Classify a command synchronously with one key" }
                    classified = true
                    classify = { registerDurable(key, registration) }
                }
            } finally {
                classifying = false
            }
            classify()
        }
        val registrations = (base.ephemeral + ephemeral.associateBy { it.id }).filterKeys { it in snapshot.commands }
        val next = base.copy(generation = expected.next(), snapshot = snapshot, outbox = outbox.toMap(), ephemeral = registrations)
        validate(next)
        if (!storage.commit(expected, next)) throw DurableConflictException()
        return DurableCommit(next, accepted.toList(), ephemeral.filter { it.id in snapshot.commands })
    }
    /** Overdue virtual timers in deterministic deadline/id order, ready to [commit] on recovery. */
    suspend fun dueTimers(now: MachineTime): List<MachineInput.TimerFired> {
        var timers = emptyList<MachineInput.TimerFired>()
        restore { checkpoint ->
            require(now >= checkpoint.now) { "[Actron] Durable logical time moved backwards" }
            timers = checkpoint.snapshot.timers.entries.filter { it.value.deadline <= now }
                .sortedWith(compareBy({ it.value.deadline }, { it.key.value }))
                .map { MachineInput.TimerFired(it.key, now) }
        }
        return timers
    }

    /**
     * Executes one pending operation. Persist attempt, perform IO outside the commit lock, then
     * atomically decide its output and acknowledge the outbox. A failed ack leaves it pending.
     * Calls are serialized per executor; input commits remain possible while IO awaits.
     */
    suspend fun executeNext(
        handler: DurableCommandHandler<CMD, A>,
        onCommitted: (DurableCommit<C, CMD, E>) -> Unit = {},
        now: () -> MachineTime,
    ): Boolean = execution.withLock {
        var execute: suspend () -> Boolean = { false }
        commits.withLock {
            val base = stored()
            for (pending in base.outbox.values) {
                if (pending.status != DurableStatus.Pending) continue
                require(pending.attempts < Long.MAX_VALUE) { "[Actron] Attempt counter exhausted" }
                val attempted = pending.copy(attempts = pending.attempts + 1)
                persist(DurableGeneration.Existing(base.generation), base.copy(outbox = base.outbox + (pending.key to attempted)), emptyList())
                execute = {
                    val committed = executeReserved(attempted, handler, now)
                    onCommitted(committed)
                    true
                }
                break
            }
        }
        execute()
    }

    private suspend fun executeReserved(
        command: DurableCommand<CMD>,
        handler: DurableCommandHandler<CMD, A>,
        now: () -> MachineTime,
    ): DurableCommit<C, CMD, E> {
        var emitting = true
        var emitted = false
        var decideResult: (MachineSnapshot<C>, MachineTime, MutableList<Decision<C, CMD, E>>) -> MachineSnapshot<C> = { snapshot, _, _ -> snapshot }
        try {
            handler.execute(command.key, command.command, command.attempts) { result ->
                check(emitting && !emitted) { "[Actron] Emit one durable result during execute" }
                emitted = true
                decideResult = { snapshot, time, decisions ->
                    val decision = machine.decide(snapshot, MachineInput.External(source, result, time))
                    decisions += decision
                    decision.snapshot
                }
            }
        } finally {
            emitting = false
        }
        return commits.withLock acknowledge@ {
            val base = stored()
            require(command.key in base.outbox) { "[Actron] Pending outbox entry disappeared" }
            val current = base.outbox.getValue(command.key)
            require(current.command == command.command) { "[Actron] Pending command changed" }
            if (current.status == DurableStatus.Completed) return@acknowledge DurableCommit(base, emptyList(), emptyList())
            val time = now()
            require(time >= base.now) { "[Actron] Durable logical time moved backwards" }
            val decisions = mutableListOf<Decision<C, CMD, E>>()
            var snapshot = decideResult(base.snapshot, time, decisions)
            for (registration in current.registrations) {
                val completed = machine.decide(snapshot, MachineInput.CommandCompleted(registration, time))
                decisions += completed
                snapshot = completed.snapshot
            }
            persist(DurableGeneration.Existing(base.generation), base.copy(snapshot = snapshot, now = time,
                outbox = base.outbox + (command.key to current.copy(status = DurableStatus.Completed))), decisions)
        }
    }
}

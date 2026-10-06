package koma.diagnostics

import koma.core.Action
import koma.core.Event
import koma.core.InputId
import koma.observability.StoreInstanceId
import koma.statechart.machine.*
import kotlinx.coroutines.sync.Mutex

data class TraceOrigin(val store: StoreInstanceId, val input: InputId?, val sequence: Long)
data class TraceId(val value: String)
enum class FailureSource { Decision, Command }

/** Metadata-only event. selected is the attempted selection; transitions is committed work. */
data class CausalTraceEvent(
    val trace: TraceId, val definition: DefinitionId, val version: DefinitionVersion,
    val origin: TraceOrigin, val root: TraceOrigin, val parent: TraceOrigin?,
    val inputKind: String, val now: MachineTime, val command: CommandId?, val timer: TimerId?,
    val transitions: List<TransitionId>, val commands: List<CommandId>, val effects: List<EffectId>,
    val selection: DecisionExplanation?, val outcome: String, val failureType: String?, val incomplete: Boolean,
    val failureSource: FailureSource? = null,
)

/** Synchronous bounded callback. SDK/network exporters must enqueue, never call a Store back. */
fun interface DiagnosticSink { fun emit(event: CausalTraceEvent) }

/**
 * Shared across Store observers to correlate command, timer and bridge effect inputs. Bounded
 * metadata only, safe across Store threads. Eviction/unknown ids are explicit incomplete traces.
 * It observes the actual decision once and never evaluates guards or executable rules.
 */
class CausalTraceHub(private val capacity: Int = 100, private val sink: DiagnosticSink = DiagnosticSink { }) {
    init { require(capacity > 0) { "[Koma] Trace capacity must be positive" } }
    private val lock = Mutex()
    private var sequence = 0L
    private val events = linkedMapOf<TraceOrigin, CausalTraceEvent>()
    private val commands = linkedMapOf<Pair<StoreInstanceId, CommandId>, CausalTraceEvent>()
    private val timers = linkedMapOf<Pair<StoreInstanceId, TimerId>, CausalTraceEvent>()
    private val effects = linkedMapOf<MessageId, CausalTraceEvent>()

    fun snapshot(): List<CausalTraceEvent> = locked { events.values.toList() }

    /**
     * Observe an authentic DurableCommit only AFTER storage CAS succeeds. Application admission
     * supplies the input id and optional command provenance (for an outbox result). No guards are
     * re-decided: their observations are unavailable in this commit and selection is explicitly null.
     */
    fun <C, A : Action, CMD, E : Event> recordPersisted(
        machine: Machine<C, A, CMD, E>, store: StoreInstanceId, input: InputId?,
        commit: DurableCommit<C, CMD, E>, parentCommand: CommandId? = null,
    ): CausalTraceEvent {
        require(commit.checkpoint.snapshot.definition == machine.id && commit.checkpoint.snapshot.version == machine.version)
        require(commit.decisions.all { it.snapshot.definition == machine.id && it.snapshot.version == machine.version && it.outcome !is DecisionOutcome.Failed }) { "[Koma] Persisted trace contains a foreign/failed decision" }
        val event = locked {
            check(sequence < Long.MAX_VALUE)
            val origin = TraceOrigin(store, input, ++sequence)
            val parent = parentCommand?.let { commands[store to it] }
            val root = parent?.root ?: origin
            val event = CausalTraceEvent(parent?.trace ?: TraceId("${root.store.value}/${root.input?.value ?: "unknown-${root.sequence}"}"),
                machine.id, machine.version, origin, root, parent?.origin, "durable-commit", commit.checkpoint.now,
                parentCommand, null, commit.decisions.flatMap { it.transitions }, commit.decisions.flatMap { it.commands }.map { it.id },
                commit.decisions.flatMap { it.effects }.map { it.id }, null, "persisted", null,
                input == null || parent?.incomplete == true || (parentCommand != null && parent == null) ||
                    (parent != null && (parent.origin !in events || root !in events)))
            events[origin] = event
            event.commands.forEach { commands[store to it] = event }
            commit.decisions.flatMap { it.timersScheduled }.forEach { timers[store to it.id] = event }
            event.effects.forEach { effects[MessageId(store, it)] = event }
            trim(events); trim(commands); trim(timers); trim(effects)
            event
        }
        sink.emit(event)
        return event
    }

    fun <C, A : Action, CMD, E : Event> observer(machine: Machine<C, A, CMD, E>, store: StoreInstanceId): DecisionTraceObserver<C, A, CMD, E> =
        object : DecisionTraceObserver<C, A, CMD, E> {
            override fun onDecided(input: InputId?, machineInput: MachineInput<A>, explained: ExplainedDecision<C, CMD, E>) {
                val decision = explained.decision
                require(decision.snapshot.definition == machine.id && decision.snapshot.version == machine.version) { "[Koma] Trace belongs to another definition/version" }
                val event = locked {
                    check(sequence < Long.MAX_VALUE) { "[Koma] Trace sequence exhausted" }
                    val origin = TraceOrigin(store, input, ++sequence)
                    val command = when (machineInput) {
                        is MachineInput.CommandResult -> machineInput.command
                        is MachineInput.CommandCompleted -> machineInput.command
                        is MachineInput.CommandFailed -> machineInput.command
                        is MachineInput.CommandAbandoned -> machineInput.command
                        else -> null
                    }
                    val timer = (machineInput as? MachineInput.TimerFired)?.timer
                    val message = (machineInput as? MachineInput.BridgeReceived)?.message
                    val parent = command?.let { commands[store to it] } ?: timer?.let { timers[store to it] } ?: message?.let(effects::get)
                    val root = parent?.root ?: origin
                    val outcome = when (val result = decision.outcome) {
                        DecisionOutcome.Handled -> "handled"
                        is DecisionOutcome.Ignored -> "ignored:${result.reason}"
                        is DecisionOutcome.Failed -> "failed"
                    }
                    val kind = when (machineInput) {
                        is MachineInput.Start -> "start"; is MachineInput.Dispatch -> "dispatch"; is MachineInput.BridgeReceived -> "bridge"
                        is MachineInput.External -> "external"; is MachineInput.TimerFired -> "timer"; is MachineInput.CommandResult -> "command-result"
                        is MachineInput.CommandCompleted -> "command-completed"; is MachineInput.CommandFailed -> "command-failed"; is MachineInput.CommandAbandoned -> "command-abandoned"
                    }
                    val decisionFailure = (decision.outcome as? DecisionOutcome.Failed)?.failure
                    val commandFailure = (machineInput as? MachineInput.CommandFailed)?.failure
                    val event = CausalTraceEvent(parent?.trace ?: TraceId("${root.store.value}/${root.input?.value ?: "unknown-${root.sequence}"}"),
                        machine.id, machine.version, origin, root, parent?.origin, kind, machineInput.now, command, timer,
                        decision.transitions.toList(), decision.commands.map { it.id }, decision.effects.map { it.id }, explained.explanation,
                        outcome, decisionFailure?.type ?: commandFailure?.type,
                        input == null || parent?.incomplete == true || (parent != null && (parent.origin !in events || root !in events)) ||
                            ((command != null || timer != null || message != null) && parent == null),
                        if (decisionFailure != null) FailureSource.Decision else if (commandFailure != null) FailureSource.Command else null)
                    events[origin] = event
                    decision.commands.forEach { commands[store to it.id] = event }
                    decision.timersScheduled.forEach { timers[store to it.id] = event }
                    decision.effects.forEach { effects[MessageId(store, it.id)] = event }
                    trim(events); trim(commands); trim(timers); trim(effects)
                    event
                }
                sink.emit(event)
            }
        }

    private fun <K, V> trim(map: MutableMap<K, V>) { while (map.size > capacity) map.remove(map.keys.first()) }
    private inline fun <T> locked(block: () -> T): T {
        while (!lock.tryLock()) { /* bounded metadata updates only */ }
        try { return block() } finally { lock.unlock() }
    }
}

/** Deliberately excludes payloads, exception messages and business idempotency keys. */
fun CausalTraceEvent.attributes(): Map<String, String> = linkedMapOf(
    "koma.definition" to definition.value, "koma.version" to version.value, "koma.store" to origin.store.value,
    "koma.trace" to trace.value, "koma.sequence" to origin.sequence.toString(), "koma.input.kind" to inputKind,
    "koma.outcome" to outcome, "koma.incomplete" to incomplete.toString(), "koma.time" to now.toString(),
    "koma.transitions" to transitions.joinToString { it.index.toString() },
    "koma.commands" to commands.joinToString { it.value.toString() }, "koma.effects" to effects.joinToString { it.value.toString() },
    "koma.root.store" to root.store.value, "koma.root.sequence" to root.sequence.toString(),
).also { values ->
    origin.input?.let { values["koma.input"] = it.value.toString() }
    parent?.let { values["koma.parent.store"] = it.store.value; values["koma.parent.sequence"] = it.sequence.toString()
        it.input?.let { id -> values["koma.parent.input"] = id.value.toString() } }
    root.input?.let { values["koma.root.input"] = it.value.toString() }
    command?.let { values["koma.command"] = it.value.toString() }; timer?.let { values["koma.timer"] = it.value.toString() }
    failureType?.let { values["koma.failure.type"] = it }
    failureSource?.let { values["koma.failure.source"] = it.name }
}

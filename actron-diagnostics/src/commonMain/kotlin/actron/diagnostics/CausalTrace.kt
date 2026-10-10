package actron.diagnostics

import actron.core.InputAttribution

import actron.core.Action
import actron.core.Event
import actron.core.InputId
import actron.observability.StoreInstanceId
import actron.statechart.machine.*
import kotlinx.coroutines.sync.Mutex

/** Metadata-only decision receipt; selection is an actual observation, not inferred work. */
data class CausalTraceEvent(
    val definition: DefinitionId, val version: DefinitionVersion, val origin: TraceOrigin,
    val lineage: TraceLineage, val inputKind: String, val now: MachineTime, val provenance: TraceProvenance,
    val transitions: List<TransitionId>, val commands: List<CommandId>, val effects: List<EffectId>,
    val assessment: TraceAssessment,
) {
    val trace: TraceId get() = lineage.trace
    val root: TraceOrigin get() = lineage.root
    val parent: TraceParent get() = lineage.parent
    val outcome: String get() = assessment.outcome
    val incomplete: Boolean get() = origin.input !is InputId || lineage.incomplete
    fun withFailureType(accept: (String) -> Unit): Boolean {
        var reported = false
        for (fault in assessment.faults) {
            fault.withType { reported = true; accept(it) }
            if (reported) break
        }
        return reported
    }
}

/** Synchronous bounded callback. SDK/network exporters must enqueue, never call a Store back. */
fun interface DiagnosticSink { fun emit(event: CausalTraceEvent) }

/**
 * Shared across Store observers to correlate command, timer and bridge effect inputs. Bounded
 * metadata only, safe across Store threads. Eviction/unknown ids are explicit incomplete traces.
 * It observes the actual decision once and never evaluates guards or executable rules.
 */
class CausalTraceHub(private val capacity: Int = 100, private val sink: DiagnosticSink = DiagnosticSink { }) {
    init { require(capacity > 0) { "[Actron] Trace capacity must be positive" } }
    private val lock = Mutex()
    private var sequence = 0L
    private val events = linkedMapOf<TraceOrigin, CausalTraceEvent>()
    private val commands = linkedMapOf<Pair<StoreInstanceId, CommandId>, CausalTraceEvent>()
    private val timers = linkedMapOf<Pair<StoreInstanceId, TimerId>, CausalTraceEvent>()
    private val effects = linkedMapOf<MessageId, CausalTraceEvent>()

    fun snapshot(): List<CausalTraceEvent> = locked { events.values.toList() }

    /** Observes an authentic commit after CAS succeeds, without inventing guard observations. */
    fun <C : Any, A : Action, CMD : Any, E : Event> recordPersisted(
        machine: Machine<C, A, CMD, E>, store: StoreInstanceId, input: InputAttribution, commit: DurableCommit<C, CMD, E>,
    ): CausalTraceEvent = persisted(machine, store, input, commit, TraceProvenance.Local)

    /** Correlates an outbox receipt with its real registering command. */
    fun <C : Any, A : Action, CMD : Any, E : Event> recordPersisted(
        machine: Machine<C, A, CMD, E>, store: StoreInstanceId, input: InputAttribution,
        commit: DurableCommit<C, CMD, E>, parentCommand: CommandId,
    ): CausalTraceEvent = persisted(machine, store, input, commit, TraceProvenance.Command(parentCommand))

    private fun <C : Any, A : Action, CMD : Any, E : Event> persisted(
        machine: Machine<C, A, CMD, E>, store: StoreInstanceId, input: InputAttribution,
        commit: DurableCommit<C, CMD, E>, provenance: TraceProvenance,
    ): CausalTraceEvent {
        require(commit.checkpoint.snapshot.definition == machine.id && commit.checkpoint.snapshot.version == machine.version)
        require(commit.decisions.all { it.snapshot.definition == machine.id && it.snapshot.version == machine.version && it.outcome !is DecisionOutcome.Failed }) { "[Actron] Persisted trace contains a foreign/failed decision" }
        val event = locked {
            check(sequence < Long.MAX_VALUE)
            val origin = TraceOrigin(store, input, ++sequence)
            val event = CausalTraceEvent(machine.id, machine.version, origin, ancestry(origin, provenance),
                "durable-commit", commit.checkpoint.now, provenance, commit.decisions.flatMap { it.transitions },
                commit.decisions.flatMap { it.commands }.map { it.id }, commit.decisions.flatMap { it.effects }.map { it.id },
                TraceAssessment.Receipt("persisted"))
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

    fun <C : Any, A : Action, CMD : Any, E : Event> observer(machine: Machine<C, A, CMD, E>, store: StoreInstanceId): DecisionTraceObserver<C, A, CMD, E> =
        object : DecisionTraceObserver<C, A, CMD, E> {
            override fun onDecided(input: InputAttribution, machineInput: MachineInput<A>, explained: ExplainedDecision<C, CMD, E>) {
                val decision = explained.decision
                require(decision.snapshot.definition == machine.id && decision.snapshot.version == machine.version) { "[Actron] Trace belongs to another definition/version" }
                val event = locked {
                    check(sequence < Long.MAX_VALUE) { "[Actron] Trace sequence exhausted" }
                    val origin = TraceOrigin(store, input, ++sequence)
                    val provenance = when (machineInput) {
                        is MachineInput.CommandResult -> TraceProvenance.Command(machineInput.command)
                        is MachineInput.CommandCompleted -> TraceProvenance.Command(machineInput.command)
                        is MachineInput.CommandFailed -> TraceProvenance.Command(machineInput.command)
                        is MachineInput.CommandAbandoned -> TraceProvenance.Command(machineInput.command)
                        is MachineInput.TimerFired -> TraceProvenance.Timer(machineInput.timer)
                        is MachineInput.BridgeReceived -> TraceProvenance.Bridge(machineInput.message)
                        else -> TraceProvenance.Local
                    }
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
                    val faults = buildList {
                        val result = decision.outcome
                        if (result is DecisionOutcome.Failed) add(TraceFault.of(FailureSource.Decision, result.failure))
                        if (machineInput is MachineInput.CommandFailed) add(TraceFault.of(FailureSource.Command, machineInput.failure))
                    }
                    val event = CausalTraceEvent(machine.id, machine.version, origin, ancestry(origin, provenance), kind,
                        machineInput.now, provenance, decision.transitions.toList(), decision.commands.map { it.id },
                        decision.effects.map { it.id }, TraceAssessment.Decided(explained.explanation, outcome, faults))
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

    private fun ancestry(origin: TraceOrigin, provenance: TraceProvenance): TraceLineage {
        fun linked(parent: CausalTraceEvent): TraceLineage = TraceLineage.Linked(parent.trace, parent.root, parent.origin,
            parent.incomplete || parent.origin !in events || parent.root !in events)
        return when (provenance) {
            TraceProvenance.Local -> TraceLineage.Root(origin)
            is TraceProvenance.Command -> {
                val key = origin.store to provenance.id
                if (key in commands) linked(commands.getValue(key)) else TraceLineage.Unresolved(origin)
            }
            is TraceProvenance.Timer -> {
                val key = origin.store to provenance.id
                if (key in timers) linked(timers.getValue(key)) else TraceLineage.Unresolved(origin)
            }
            is TraceProvenance.Bridge ->
                if (provenance.message in effects) linked(effects.getValue(provenance.message)) else TraceLineage.Unresolved(origin)
        }
    }
    private fun <K : Any, V : Any> trim(map: MutableMap<K, V>) { while (map.size > capacity) map.remove(map.keys.first()) }
    private inline fun <T : Any> locked(block: () -> T): T {
        while (!lock.tryLock()) { /* bounded metadata updates only */ }
        try { return block() } finally { lock.unlock() }
    }
}

/** Deliberately excludes payloads, exception messages and business idempotency keys. */
fun CausalTraceEvent.attributes(): Map<String, String> = linkedMapOf(
    "actron.definition" to definition.value, "actron.version" to version.value, "actron.store" to origin.store.value,
    "actron.trace" to trace.value, "actron.sequence" to origin.sequence.toString(), "actron.input.kind" to inputKind,
    "actron.outcome" to outcome, "actron.incomplete" to incomplete.toString(), "actron.time" to now.toString(),
    "actron.transitions" to transitions.joinToString { it.index.toString() },
    "actron.commands" to commands.joinToString { it.value.toString() }, "actron.effects" to effects.joinToString { it.value.toString() },
    "actron.root.store" to root.store.value, "actron.root.sequence" to root.sequence.toString(),
).also { values ->
    origin.input.correlate { values["actron.input"] = it.value.toString() }
    parent.withParent { values["actron.parent.store"] = it.store.value; values["actron.parent.sequence"] = it.sequence.toString()
        it.input.correlate { id -> values["actron.parent.input"] = id.value.toString() } }
    root.input.correlate { values["actron.root.input"] = it.value.toString() }
    provenance.attributes(values)
    withFailureType { values["actron.failure.type"] = it }
    if (assessment.faults.isNotEmpty()) values["actron.failure.source"] = assessment.faults.first().source.name
}

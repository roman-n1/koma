@file:OptIn(koma.core.InternalKomaApi::class, koma.core.ExperimentalKomaApi::class)

package koma.soak

import koma.core.Action
import koma.core.Event
import koma.core.ExceptionHandler
import koma.core.InputId
import koma.core.StoreInternalApi
import koma.observability.JournalConfig
import koma.observability.JournalRecord
import koma.observability.JournalSink
import koma.observability.Payload
import koma.observability.PayloadPolicy
import koma.observability.RecordingSession
import koma.observability.StoreInstanceId
import koma.observability.file.FileSegmentStorage
import koma.observability.file.JournalFileConfig
import koma.observability.file.JournalFileSink
import koma.observability.file.SegmentOutput
import koma.observability.file.SegmentStorage
import koma.statechart.ActionMatcher
import koma.statechart.AtomicState
import koma.statechart.StateChartDefinition
import koma.statechart.StateId
import koma.statechart.Transition
import koma.statechart.Trigger
import koma.statechart.recordTo
import koma.statechart.machine.Admission
import koma.statechart.machine.AdmissionPolicy
import koma.statechart.machine.CommandHandler
import koma.statechart.machine.Decision
import koma.statechart.machine.DecisionObserver
import koma.statechart.machine.DefinitionId
import koma.statechart.machine.DefinitionVersion
import koma.statechart.machine.EffectPolicy
import koma.statechart.machine.Machine
import koma.statechart.machine.MachineInput
import koma.statechart.machine.MachineSnapshot
import koma.statechart.machine.MachineStore
import koma.statechart.machine.MailboxConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration
import kotlin.time.TimeSource

private const val STORES = 16
private const val ADMISSION = 32
private const val MAILBOX = 16
private const val RETAINED_RECORDS = 4000
private const val WRITER_QUEUE = 256
private const val PAYLOAD_BYTES = 4096
private const val CONTEXT_MESSAGES = 64
private val clock = TimeSource.Monotonic.markNow()
private fun nowNanos() = clock.elapsedNow().inWholeNanoseconds

private data class Message(val sequence: Long, val sentNanos: Long, val body: String) : Action
private data class History(val messages: List<String> = emptyList())
private data class Render(val body: String) : Event
private data object Poll

private val machine = run {
    val active = StateId("Active")
    Machine<History, Message, Poll, Render>(DefinitionId("resource-soak"), DefinitionVersion("1"),
        StateChartDefinition(active, listOf(AtomicState(active)), listOf(Transition(active, active, Trigger.After(100.milliseconds))))) {
        onEnter(active) { command(Poll); event(Render(context.messages.lastOrNull().orEmpty())) }
        onAction(active, ActionMatcher.of<Message>("Message")) {
            val message = action as Message
            context = History((context.messages + message.body).takeLast(CONTEXT_MESSAGES))
            event(Render(message.body))
        }
    }
}

/** Bounded measurement state: never retains a Store, Message or journal payload. */
private class Latencies {
    private val lock = Mutex()
    private val values = ArrayDeque<Long>()
    private var total = 0L
    fun add(nanos: Long) = locked {
        total++
        values.addLast(nanos)
        if (values.size > 4096) values.removeFirst()
    }
    fun summary(): Triple<Long, Long, Long> = locked {
        val sorted = values.sorted()
        check(sorted.size >= 100) { "Insufficient latency samples: ${sorted.size}" }
        Triple(total, sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.lastIndex)] / 1000,
            sorted[(sorted.size * 0.99).toInt().coerceAtMost(sorted.lastIndex)] / 1000)
    }
    private inline fun <T> locked(block: () -> T): T {
        while (!lock.tryLock()) { }
        try { return block() } finally { lock.unlock() }
    }
}

private class SlowStorage(private val delegate: SegmentStorage) : SegmentStorage by delegate {
    val outputs = MutableStateFlow(0)
    val bytes = MutableStateFlow(0L)
    override fun append(name: String): SegmentOutput {
        val output = delegate.append(name)
        outputs.update { it + 1 }
        return object : SegmentOutput {
            private var closed = false
            override fun write(bytes: ByteArray) {
                SoakPlatform.blockingDelay(2)
                output.write(bytes)
                this@SlowStorage.bytes.update { it + bytes.size }
            }
            override fun flush() = output.flush()
            override fun close() {
                if (closed) return
                closed = true
                try { output.close() } finally { outputs.update { it - 1 } }
            }
        }
    }
}

@Serializable
data class BatchResult(val batch: Int, val recording: Boolean, val accepted: Long, val rejected: Long,
    val p95Micros: Long, val p99Micros: Long, val sampledInFlightHighWater: Int,
    val sampledCorePendingHighWater: Int, val sampledMailboxHighWater: Int, val retainedHighWater: Int, val sinkBusyHighWater: Int,
    val droppedForSinks: Long, val bytesWritten: Long, val jobsAfterClose: Int, val outputsAfterClose: Int,
    val pendingInputsAfterClose: Int, val commandsAfterClose: Int, val mailboxAfterClose: Int, val ownedWorkersAfterClose: Int)

@Serializable
data class Sample(val startedMillis: Long, val elapsedMillis: Long, val result: BatchResult, val memory: MemorySample)

@Serializable
data class BudgetReport(val metadata: Map<String, String>, val budgets: Map<String, Long>, val samples: List<Sample>,
    val warmupSamples: List<Sample>, val plateauEvaluated: Boolean, val memoryGrowthByMode: Map<String, MemoryGrowth>?,
    val heapGrowthBytes: Long?, val nativeGrowthBytes: Long?, val residentGrowthBytes: Long?, val threadGrowth: Long?, val failures: List<String>)

private val budgets = mapOf("storeCount" to STORES.toLong(), "admissionPending" to ADMISSION.toLong(),
    "mailboxRetained" to MAILBOX.toLong(), "journalRetained" to RETAINED_RECORDS.toLong(), "writerQueueConfigured" to WRITER_QUEUE.toLong(),
    "contextMessages" to CONTEXT_MESSAGES.toLong(), "messageBytes" to PAYLOAD_BYTES.toLong(), "latencyRingSamples" to 4096L,
    "corePendingInputsSampled" to (ADMISSION + 8).toLong(), "segmentBytes" to 128L * 1024, "segments" to 2L,
    "p95Micros" to 500_000L, "p99Micros" to 1_000_000L, "heapPlateauGrowthBytes" to 8L * 1024 * 1024,
    "nativePlateauGrowthBytes" to 8L * 1024 * 1024, "residentPlateauGrowthBytes" to 64L * 1024 * 1024)

/** Always called from a test/host owner, never from one of the measured Stores' jobs. */
suspend fun runResourceSoak() {
    val started = TimeSource.Monotonic.markNow()
    val samples = mutableListOf<Sample>()
    val warmupSamples = mutableListOf<Sample>()
    val failures = mutableListOf<String>()
    val assessPerformance = SOAK_SECONDS >= 30
    val warmupSeconds = minOf(30, SOAK_SECONDS / 5)
    val minimumBatches = if (SOAK_SECONDS < 30) 3 else 12
    val minimumSamples = if (SOAK_SECONDS < 30) 2 else MINIMUM_PLATEAU_SAMPLES_PER_MODE * 2
    SoakPlatform.afterGc()
    var batch = 0
    fun hasModeCoverage() = !assessPerformance || listOf(false, true).all { mode ->
        samples.count { it.result.recording == mode } >= MINIMUM_PLATEAU_SAMPLES_PER_MODE
    }
    try {
        while (started.elapsedNow() < SOAK_SECONDS.seconds || batch < minimumBatches || samples.size < minimumSamples || !hasModeCoverage()) {
            val batchStarted = started.elapsedNow()
            val result = runControlledBatch(batch)
            val memory = SoakPlatform.afterGc()
            val sample = Sample(batchStarted.inWholeMilliseconds, started.elapsedNow().inWholeMilliseconds, result, memory)
            if (batch > 0 && batchStarted >= warmupSeconds.seconds) {
                samples += sample
                if (assessPerformance) {
                    check(result.p95Micros <= budgets.getValue("p95Micros") && result.p99Micros <= budgets.getValue("p99Micros")) {
                        "Steady latency budget exceeded in batch $batch: p95=${result.p95Micros} us, p99=${result.p99Micros} us"
                    }
                }
            } else warmupSamples += sample
            batch++
        }
    } catch (failure: Throwable) {
        failures += failure.toString()
        throw failure
    } finally {
        val growthByMode = if (assessPerformance) memoryGrowthByMode(samples) else null
        val heapGrowth = growthByMode?.values?.maxOf { it.heapBytes }
        val nativeGrowth = growthByMode?.values?.maxOf { it.nativeBytes }
        val residentGrowth = growthByMode?.values?.maxOf { it.residentBytes }
        val threadGrowth = growthByMode?.values?.maxOf { it.threads }
        if (samples.size < minimumSamples) failures += "Insufficient measurement samples"
        if (assessPerformance && growthByMode == null) failures += "Insufficient comparable post-warmup samples: need at least $MINIMUM_PLATEAU_SAMPLES_PER_MODE per recording mode"
        if (heapGrowth != null && heapGrowth > budgets.getValue("heapPlateauGrowthBytes")) failures += "Managed heap did not plateau: +$heapGrowth bytes"
        if (nativeGrowth != null && nativeGrowth > budgets.getValue("nativePlateauGrowthBytes")) failures += "Native heap did not plateau: +$nativeGrowth bytes"
        if (residentGrowth != null && residentGrowth > budgets.getValue("residentPlateauGrowthBytes")) failures += "Resident memory did not plateau: +$residentGrowth bytes"
        if (threadGrowth != null && threadGrowth > 16) failures += "OS thread count did not plateau: +$threadGrowth threads"
        val report = BudgetReport(SoakPlatform.metadata() + mapOf("durationSeconds" to SOAK_SECONDS.toString(), "warmupSeconds" to warmupSeconds.toString(),
            "dispatcher" to "owned fixed pool: stores=4, writer=1; terminated before GC", "storeWorkers" to "4", "writerWorkers" to "1", "slowWriteMillis" to "2", "latency" to "accepted offer to committed decision; bounded rolling tail per batch",
            "assessment" to if (assessPerformance) "steady-soak" else "harness-smoke",
            "memorySampling" to "after teardown and two requested GCs; same-recording-mode first/last-third medians; worst mode growth", "sourceCount" to "0"),
            budgets + ("threadPlateauGrowth" to 16L), samples, warmupSamples, growthByMode != null, growthByMode, heapGrowth, nativeGrowth, residentGrowth, threadGrowth, failures)
        val json = Json { prettyPrint = true }.encodeToString(report)
        SoakPlatform.writeReport("report.json", json)
        val csv = "phase,started_ms,elapsed_ms,batch,recording,accepted,rejected,p95_us,p99_us,inflight_sampled_highwater,core_pending_sampled_highwater,mailbox_sampled_highwater,retained_highwater,sink_busy_highwater,dropped,bytes_written,heap_bytes,native_bytes,resident_bytes,threads,jobs_after_close,outputs_after_close,pending_inputs_after_close,commands_after_close,mailbox_after_close,owned_workers_after_close\n" +
            (warmupSamples + samples).sortedBy { it.result.batch }.joinToString("\n") { s -> s.result.run {
                val phase = if (s in warmupSamples) "warmup" else if (assessPerformance) "steady" else "smoke"
                "$phase,${s.startedMillis},${s.elapsedMillis},${this.batch},$recording,$accepted,$rejected,$p95Micros,$p99Micros,$sampledInFlightHighWater,$sampledCorePendingHighWater,$sampledMailboxHighWater,$retainedHighWater,$sinkBusyHighWater,$droppedForSinks,$bytesWritten,${s.memory.heapBytes},${s.memory.nativeBytes},${s.memory.residentBytes},${s.memory.threads},$jobsAfterClose,$outputsAfterClose,$pendingInputsAfterClose,$commandsAfterClose,$mailboxAfterClose,$ownedWorkersAfterClose"
            } }
        SoakPlatform.writeReport("samples.csv", csv)
        println("SOAK_REPORT ${SoakPlatform.reportDirectory}/report.json (${samples.size} ${if (assessPerformance) "steady" else "smoke"} samples, ${warmupSamples.size} warmup)")
        check(failures.isEmpty()) { failures.joinToString("; ") }
    }
}

internal suspend fun runBatch(batch: Int, storeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    writerDispatcher: CoroutineDispatcher = Dispatchers.Default, witnesses: MutableList<NamedWitness>? = null,
    activeMillis: Long = if (SOAK_SECONDS < 30) 300 else 2000): BatchResult {
    fun observe(label: String, value: Any) { witnesses?.add(NamedWitness("batch-$batch:$label", weakWitness(value))) }
    val owner = SupervisorJob()
    val scope = CoroutineScope(storeDispatcher + owner)
    val writerOwner = SupervisorJob()
    val writerScope = CoroutineScope(writerDispatcher + writerOwner)
    val directory = SoakPlatform.directory(batch)
    val storage = SlowStorage(FileSegmentStorage(directory))
    val sink = JournalFileSink(storage, JournalFileConfig(maxSegmentBytes = 128 * 1024, maxSegments = 2, flushEveryRecords = 8))
    val sinkBusy = MutableStateFlow(0)
    val sinkHigh = MutableStateFlow(0)
    val errors = MutableStateFlow<List<String>>(emptyList())
    val recording = batch % 2 == 0
    val session = if (recording) RecordingSession(writerScope,
        config = JournalConfig(RETAINED_RECORDS, WRITER_QUEUE, onFailure = { errors.update { e -> (e + it.toString()).takeLast(20) } }),
        sinks = listOf(JournalSink { record: JournalRecord<*, *, *> ->
            sinkBusy.update { it + 1 }; sinkHigh.update { maxOf(it, sinkBusy.value) }
            try { sink.write(record) } finally { sinkBusy.update { it - 1 } }
        })) else null
    observe("owner", owner)
    observe("writerOwner", writerOwner)
    session?.let { observe("recording", it) }
    val latencies = Latencies()
    val commands = MutableStateFlow(0)
    val stores = mutableListOf<MachineStore<History, Message, Poll, Render>>()
    val inFlight = List(STORES) { MutableStateFlow(0) }
    val subscriberJobs = mutableListOf<Job>()
    var accepted = 0L
    var rejected = 0L
    var inFlightHigh = 0
    var mailboxHigh = 0
    var corePendingHigh = 0
    var pendingAfterClose = 0
    var mailboxAfterClose = 0
    var retainedHigh = 0
    var sequence = 0L
    val payload = "x".repeat(PAYLOAD_BYTES - 32)
    try {
        repeat(STORES) { index ->
            val observer = object : DecisionObserver<History, Message, Poll, Render> {
                override fun onCommitted(input: InputId?, machineInput: MachineInput<Message>, decision: Decision<History, Poll, Render>) {
                    if (machineInput is MachineInput.Dispatch) {
                        latencies.add(nowNanos() - machineInput.action.sentNanos)
                        inFlight[index].update { it - 1 }
                    }
                }
            }
            val store = MachineStore(machine, History(), CommandHandler<Poll, Message> { _, _ ->
                commands.update { it + 1 }
                try { delay(40) } finally { commands.update { it - 1 } }
            }, scope, coroutineContext = scope.coroutineContext, admission = AdmissionPolicy.Bounded(ADMISSION), observers = listOf(observer),
                mailbox = MailboxConfig(policy = { EffectPolicy.Retained() }, maxRetained = MAILBOX)) {
                exceptionHandler(ExceptionHandler { error -> errors.update { (it + error.toString()).takeLast(20) } })
                session?.let { recordTo(it, StoreInstanceId("store-$index"), PayloadPolicy(
                    state = { s: MachineSnapshot<History> -> Payload.Projected("history", mapOf("latest" to s.context.messages.lastOrNull().orEmpty())) },
                    action = { a: MachineInput<Message> -> if (a is MachineInput.Dispatch) Payload.Projected("message", mapOf("body" to a.action.body)) else Payload.Omitted },
                    event = { e: Render -> Payload.Projected("render", mapOf("body" to e.body)) },
                )) }
            }
            observe("store-$index", store)
            stores += store
            (store as StoreInternalApi<MachineSnapshot<History>, Message, Render>).startAndAwait()
            subscriberJobs += scope.launch { store.state.collect { check(it.context.messages.size <= CONTEXT_MESSAGES) } }
            subscriberJobs += scope.launch { store.mailbox.subscribe().collect { delay(2); it.acknowledge() } }
        }
        val active = TimeSource.Monotonic.markNow()
        var ticks = 0
        while (active.elapsedNow() < activeMillis.milliseconds) {
            stores.forEachIndexed { index, store ->
                repeat(48) {
                    val action = Message(++sequence, nowNanos(), "$sequence:$payload")
                    inFlight[index].update { it + 1 }
                    if (store.admit(action) == Admission.Accepted) {
                        accepted++
                        inFlightHigh = maxOf(inFlightHigh, inFlight[index].value)
                    } else { rejected++; inFlight[index].update { it - 1 } }
                }
                mailboxHigh = maxOf(mailboxHigh, store.mailbox.pending.size)
                corePendingHigh = maxOf(corePendingHigh, (store as StoreInternalApi<MachineSnapshot<History>, Message, Render>).awaitIdle(Duration.ZERO).inputs)
            }
            retainedHigh = maxOf(retainedHigh, session?.stats?.retained ?: 0)
            if (++ticks % 4 == 0) {
                // Leave and reenter one screen. Unacknowledged retained effects can be retried.
                val index = ticks % STORES
                subscriberJobs[index * 2 + 1].cancelAndJoin()
                subscriberJobs[index * 2 + 1] = scope.launch { stores[index].mailbox.subscribe().collect { delay(2); it.acknowledge() } }
            }
            delay(40)
        }
        withTimeout(10_000) { while (inFlight.any { it.value != 0 }) delay(10) }
    } finally {
        withContext(NonCancellable) {
            try {
                stores.forEach { it.close() }
            } finally {
                try {
                    withTimeout(10_000) { owner.cancelAndJoin() }
                } finally {
                    closeRecordingResources(session, sink, writerOwner)
                }
            }
        }
    }
    // Assertions follow all release attempts, so a regression never skips writer/file cleanup.
    stores.forEachIndexed { index, store ->
        observe("context-$index", store.currentState.context)
        store.currentState.context.messages.lastOrNull()?.let { observe("payload-$index", it) }
        val pending = (store as StoreInternalApi<MachineSnapshot<History>, Message, Render>).awaitIdle(1.seconds)
        pendingAfterClose += pending.inputs
        mailboxAfterClose += store.mailbox.pending.size
        check(pending.isIdle && store.mailbox.pending.isEmpty())
    }
    check(commands.value == 0 && storage.outputs.value == 0)
    check(owner.children.none() && writerOwner.children.none())
    val (count, p95, p99) = latencies.summary()
    check(count == accepted) { "Accepted inputs did not all commit: $accepted offered, $count committed" }
    check(errors.value.isEmpty()) { errors.value.joinToString() }
    check(inFlightHigh <= ADMISSION + 1) { "Accepted in-flight exceeded admission bound: $inFlightHigh" }
    check(corePendingHigh <= ADMISSION + 8) { "Core pending input budget exceeded: $corePendingHigh" }
    check(mailboxHigh <= MAILBOX && retainedHigh <= RETAINED_RECORDS && sinkHigh.value <= 1)
    val stats = session?.stats
    check(stats == null || stats.sinkFailures == 0L && stats.policyFailures == 0L)
    if (recording) check(storage.bytes.value > 0 && stats!!.droppedForSinks > 0) { "Slow disk pressure was not exercised" }
    check(sink.segments.size <= 2)
    check(storage.list().all { it.size <= 128 * 1024 + 8 }) { "Segment byte budget exceeded" }
    val result = BatchResult(batch, recording, count, rejected, p95, p99, inFlightHigh, corePendingHigh, mailboxHigh, retainedHigh, sinkHigh.value,
        stats?.droppedForSinks ?: 0, storage.bytes.value, owner.children.count() + writerOwner.children.count(), storage.outputs.value,
        pendingAfterClose, commands.value, mailboxAfterClose, 0)
    SoakPlatform.removeDirectory(directory)
    return result
}

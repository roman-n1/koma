@file:OptIn(InternalKomaApi::class)

package koma.observability

import koma.core.Action
import koma.core.Event
import koma.core.ExperimentalKomaApi
import koma.core.InternalKomaApi
import koma.core.State
import koma.core.StoreProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Receives the records of a [RecordingSession], in [GroupSeq] order, from the session's writer
 * coroutine. A sink may block or suspend on I/O: it delays the sinks after it and the queue, never
 * a Store. An exception thrown by [write] is counted and reported to the session's failure
 * handler; the record is skipped for that sink only, and nothing is written about the failure.
 */
@ExperimentalKomaApi
fun interface JournalSink {
    suspend fun write(record: JournalRecord<*, *, *>)
}

/**
 * A failure of the recording itself. It is reported to [JournalConfig.onFailure], never recorded
 * as a journal entry and never sent to a Store's exception handler, so a broken sink or policy
 * cannot feed on its own reports.
 */
@ExperimentalKomaApi
sealed interface JournalFailure {
    data class SinkFailed(val sink: JournalSink, val error: Throwable) : JournalFailure

    data class PolicyFailed(val store: StoreInstanceId, val error: Throwable) : JournalFailure
}

/**
 * Budgets of a [RecordingSession].
 *
 * The defaults come from `JournalBudgetJvmTest` (2026-09-30, JVM): a retained record under the
 * production policy (envelope, entry, descriptors, no payload) costs about 120 bytes, a publish
 * about 0.3 µs, and a Store's dispatch about 12 % more with the journal than without (it
 * publishes four records per dispatch). So the default ring of 4 000 records is about 0.5 MB and
 * holds roughly the last 1 000 dispatches of a group, and a full writer queue of 4 096 records is
 * about 0.5 MB more. A policy that retains payloads changes the arithmetic; measure with
 * [JournalStats] on the target devices and set the budgets in the application.
 *
 * @property retainedRecords How many records the session keeps in memory for inspection; the
 * oldest are evicted first, and [JournalStats.evicted] counts them
 * @property writerQueueCapacity How many records may wait for the sinks; a record that finds the
 * queue full is dropped for the sinks, counted in [JournalStats.droppedForSinks], and the next
 * record that fits is preceded by a [JournalEntry.JournalGap]
 * @property onFailure Called for every [JournalFailure]; must not throw (an exception it throws
 * is swallowed)
 */
@ExperimentalKomaApi
data class JournalConfig(
    val retainedRecords: Int = 4_000,
    val writerQueueCapacity: Int = 4_096,
    val onFailure: (JournalFailure) -> Unit = {},
) {
    init {
        require(retainedRecords >= 0) { "[Koma] retainedRecords must not be negative" }
        require(writerQueueCapacity >= 1) { "[Koma] writerQueueCapacity must be at least 1" }
    }
}

/**
 * Counters of a [RecordingSession]. Loss is visible here even when no [JournalEntry.JournalGap]
 * could be queued.
 *
 * @property published Records that received a [GroupSeq]
 * @property retained Records in memory now
 * @property evicted Records pushed out of memory by [JournalConfig.retainedRecords]
 * @property droppedForSinks Records the writer's queue had no room for, gap records included
 * @property sinkFailures Exceptions thrown by sinks
 * @property policyFailures Exceptions thrown by payload policies
 * @property publishedAfterStop Records offered after [RecordingSession.close]; they were not kept
 */
@ExperimentalKomaApi
data class JournalStats(
    val published: Long,
    val retained: Int,
    val evicted: Long,
    val droppedForSinks: Long,
    val sinkFailures: Long,
    val policyFailures: Long,
    val publishedAfterStop: Long,
)

/**
 * The journal of one [MachineGroupId] in one [RuntimeSessionId]: assigns [GroupSeq] and
 * [StoreSeq] at publication, keeps the last [JournalConfig.retainedRecords] records for
 * inspection, and hands every record to the [sinks] from one writer coroutine, in order.
 *
 * Publishing is the hot path: a short critical section that assigns the sequence numbers, appends
 * to the retained records and offers the record to the writer's bounded queue. No callback, I/O
 * or Store lock is touched inside it. The section is entered from the Stores' threads and does
 * not suspend, so it spins on contention; keep the number of Stores per session and the size of
 * what a policy retains within reason.
 *
 * The writer belongs to [scope], not to any Store, so records of a Store that closed are still
 * written. Closing the session ([close]) publishes [JournalEntry.RecordingStopped], lets the
 * writer drain the queue and stops it; records published after that are counted and dropped.
 *
 * @param scope Runs the writer coroutine; choose a dispatcher fit for the sinks' I/O
 * @param id The session; a new one per process run
 * @param group The group this journal orders
 * @param mode Whether the recorded Stores run live or replay
 * @param config Budgets and the failure handler
 * @param sinks Receive every record in [GroupSeq] order
 * @param timeSource Source of [JournalRecord.elapsed] and of processing durations
 */
@ExperimentalKomaApi
class RecordingSession(
    scope: CoroutineScope,
    val id: RuntimeSessionId = RuntimeSessionId.random(),
    val group: MachineGroupId = MachineGroupId("default"),
    val mode: ExecutionMode = ExecutionMode.Live,
    private val config: JournalConfig = JournalConfig(),
    private val sinks: List<JournalSink> = emptyList(),
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val startedAt: TimeMark = timeSource.markNow()

    // Guards everything below down to `stopped`. Entered without suspending (tryLock), so the
    // section must stay tiny; see the class documentation.
    private val lock = Mutex()
    private val retained = ArrayDeque<JournalRecord<*, *, *>>()
    private val storeSeqs = mutableMapOf<StoreInstanceId, Long>()
    private val probed = mutableSetOf<StoreInstanceId>()
    private var nextGroupSeq = 0L
    private var published = 0L
    private var evicted = 0L
    private var droppedForSinks = 0L
    private var pendingGap = 0L
    private var sinkFailures = 0L
    private var policyFailures = 0L
    private var publishedAfterStop = 0L
    private var stopped = false

    private val queue = Channel<JournalRecord<*, *, *>>(config.writerQueueCapacity)
    private val writer: Job = scope.launch {
        for (record in queue) deliver(record)
    }

    /**
     * Creates the probe that records a Store into this session as [store], and publishes its
     * [JournalEntry.StoreRegistered]. Prefer `StoreBuilder.recordTo` or
     * `StorePatchBuilder.recordTo`, which register the probe for you; calling this directly
     * means handling the internal probe API yourself.
     *
     * @throws IllegalArgumentException if [store] is already recorded by this session
     */
    @InternalKomaApi
    fun <S : State, A : Action, E : Event> probe(store: StoreInstanceId, policy: PayloadPolicy<S, A, E>): StoreProbe<S, A, E> {
        locked {
            require(probed.add(store)) { "[Koma] Store $store is already recorded by this session; give every instance its own StoreInstanceId" }
        }
        publishRecord(store, JournalEntry.StoreRegistered(Capability.InspectOnly))
        return JournalProbe(this, store, policy)
    }

    /**
     * The retained records, oldest first, in [GroupSeq] order. A copy; safe to read while Stores
     * keep publishing.
     */
    fun records(): List<JournalRecord<*, *, *>> = locked { retained.toList() }

    /**
     * The counters as of now.
     */
    val stats: JournalStats
        get() = locked {
            JournalStats(
                published = published,
                retained = retained.size,
                evicted = evicted,
                droppedForSinks = droppedForSinks,
                sinkFailures = sinkFailures,
                policyFailures = policyFailures,
                publishedAfterStop = publishedAfterStop,
            )
        }

    /**
     * Publishes [JournalEntry.RecordingStopped], stops accepting records, writes what is queued
     * and ends the writer. Idempotent.
     */
    suspend fun close() {
        val alreadyStopped = locked { stopped }
        if (alreadyStopped) {
            writer.join()
            return
        }
        publishRecord(null, JournalEntry.RecordingStopped)
        locked { stopped = true }
        queue.close()
        writer.join()
    }

    internal fun now(): TimeMark = timeSource.markNow()

    internal fun reportPolicyFailure(store: StoreInstanceId, error: Throwable) {
        locked { policyFailures++ }
        notify(JournalFailure.PolicyFailed(store, error))
    }

    /**
     * Publishes [entry] about [store]: assigns the sequence numbers ([StoreSeq] starts with the
     * store's first record, from a probe or from here), retains the record and offers it to the
     * writer, all in the session's short critical section. For records that no probe produces,
     * such as the decisions of a replay-ready machine. Returns the record, or `null` after
     * [close].
     */
    fun publish(store: StoreInstanceId, entry: JournalEntry<*, *, *>): JournalRecord<*, *, *>? = publishRecord(store, entry)

    /**
     * Assigns the sequence numbers, retains the record and offers it to the writer, all under the
     * lock; `null` for a record of the session itself. Returns the record, or `null` after [close].
     */
    internal fun publishRecord(store: StoreInstanceId?, entry: JournalEntry<*, *, *>): JournalRecord<*, *, *>? {
        return locked {
            if (stopped) {
                publishedAfterStop++
                return@locked null
            }
            if (pendingGap > 0) {
                val gap = allocate(null, JournalEntry.JournalGap(pendingGap))
                retain(gap)
                if (queue.trySend(gap).isSuccess) pendingGap = 0 else lost()
            }
            val record = allocate(store, entry)
            retain(record)
            if (queue.trySend(record).isFailure) lost()
            record
        }
    }

    private fun allocate(store: StoreInstanceId?, entry: JournalEntry<*, *, *>): JournalRecord<*, *, *> {
        published++
        val groupSeq = GroupSeq(++nextGroupSeq)
        val storeSeq = store?.let { id ->
            val next = (storeSeqs[id] ?: 0L) + 1
            storeSeqs[id] = next
            StoreSeq(next)
        }
        return JournalRecord(
            formatVersion = JOURNAL_FORMAT_VERSION,
            session = id,
            group = group,
            store = store,
            mode = mode,
            groupSeq = groupSeq,
            storeSeq = storeSeq,
            elapsed = startedAt.elapsedNow(),
            entry = entry,
        )
    }

    private fun retain(record: JournalRecord<*, *, *>) {
        if (config.retainedRecords == 0) return
        while (retained.size >= config.retainedRecords) {
            retained.removeFirst()
            evicted++
        }
        retained.addLast(record)
    }

    private fun lost() {
        pendingGap++
        droppedForSinks++
    }

    private suspend fun deliver(record: JournalRecord<*, *, *>) {
        for (sink in sinks) {
            try {
                sink.write(record)
            } catch (e: Exception) {
                // The writer's own cancellation ends it; any other exception is the sink's failure.
                if (e is CancellationException && !currentCoroutineContext().isActive) throw e
                locked { sinkFailures++ }
                notify(JournalFailure.SinkFailed(sink, e))
            }
        }
    }

    private fun notify(failure: JournalFailure) {
        try {
            config.onFailure(failure)
        } catch (_: Exception) {
            // The failure handler is the last resort; its own failure has nowhere to go.
        }
    }

    private inline fun <T> locked(block: () -> T): T {
        while (!lock.tryLock()) {
            // Spin: the holder is inside a section of a few field writes.
        }
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}

/**
 * The duration of a processing, or zero when its start was not observed.
 */
internal fun TimeMark?.elapsedOrZero(): Duration = this?.elapsedNow() ?: Duration.ZERO

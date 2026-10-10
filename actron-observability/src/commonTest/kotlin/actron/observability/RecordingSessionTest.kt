@file:OptIn(ExperimentalActronApi::class, ExperimentalCoroutinesApi::class, InternalActronApi::class)

package actron.observability

import actron.core.ExperimentalActronApi
import actron.core.InternalActronApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The journal itself: sequence numbers at publication, bounded retention, the writer's queue and
 * its gaps, sink isolation and closing.
 *
 * The writer runs in a background scope; `runCurrent()` runs it (a plain `advanceUntilIdle()`
 * stops when only background work is left).
 */
class RecordingSessionTest {

    private class CollectingSink : JournalSink {
        val records = mutableListOf<JournalRecord<*, *, *>>()

        override suspend fun write(record: JournalRecord<*, *, *>) {
            records += record
        }
    }

    private val a = StoreInstanceId("a")
    private val b = StoreInstanceId("b")

    private fun List<JournalRecord<*, *, *>>.groupSeqs() = map { it.groupSeq.value }

    private fun List<JournalRecord<*, *, *>>.storeSeqs(store: StoreInstanceId) = filter { it.store == store }.map { it.storeSeq?.value }

    /**
     * What a sink may rely on: records arrive in order, every hole in the sequence is followed by a
     * gap record that says exactly how many records are missing, a gap never appears without a
     * hole, and delivered plus dropped is everything published.
     */
    private fun assertEveryHoleIsExplained(delivered: List<JournalRecord<*, *, *>>, stats: JournalStats) {
        val seqs = delivered.groupSeqs()
        assertEquals(seqs.sorted(), seqs, "delivered in order")
        var explained = 0L
        (listOf(0L) + seqs).zipWithNext().forEach { (prev, next) ->
            val hole = next - prev - 1
            val entry = delivered.single { it.groupSeq.value == next }.entry
            if (hole > 0) {
                assertEquals(hole, assertIs<JournalEntry.JournalGap>(entry, "a hole before $next is followed by a gap").dropped)
                explained += hole
            } else {
                assertTrue(entry !is JournalEntry.JournalGap, "no gap without a hole before $next")
            }
        }
        assertEquals(stats.droppedForSinks, explained, "every dropped record is explained by a gap")
        assertEquals(stats.published, delivered.size + stats.droppedForSinks, "delivered plus dropped is everything published")
    }

    @Test
    fun publish_assignsDenseGroupSeq_andDensePerStoreStoreSeq_inPublicationOrder() = runTest {
        val sink = CollectingSink()
        val session = RecordingSession(backgroundScope, sinks = listOf(sink))

        session.publish(a, JournalEntry.StoreClosed)
        session.publish(b, JournalEntry.StoreClosed)
        session.publish(a, JournalEntry.StoreClosed)
        session.publishRecord(null, JournalEntry.RecordingStopped)
        runCurrent()

        val records = session.records()
        assertEquals(listOf(1L, 2L, 3L, 4L), records.groupSeqs())
        assertEquals(listOf(1L, 2L), records.storeSeqs(a))
        assertEquals(listOf(1L), records.storeSeqs(b))
        assertNull(records.last().storeSeq, "a record of the session has no store sequence")
        assertEquals(records, sink.records, "the sink sees the same records in the same order")
        assertTrue(records.zipWithNext().all { (x, y) -> x.elapsed <= y.elapsed }, "elapsed never decreases along the sequence")
        assertEquals(JOURNAL_FORMAT_VERSION, records.first().formatVersion)
    }

    @Test
    fun retainedRecords_boundsMemory_andCountsEvictions() = runTest {
        val session = RecordingSession(backgroundScope, config = JournalConfig(retainedRecords = 3))

        repeat(5) { session.publish(a, JournalEntry.StoreClosed) }

        assertEquals(listOf(3L, 4L, 5L), session.records().groupSeqs())
        val stats = session.stats
        assertEquals(5, stats.published)
        assertEquals(3, stats.retained)
        assertEquals(2, stats.evicted)
    }

    @Test
    fun retainedRecordsZero_keepsNothing_butStillPublishesToSinks() = runTest {
        val sink = CollectingSink()
        val session = RecordingSession(backgroundScope, config = JournalConfig(retainedRecords = 0), sinks = listOf(sink))

        session.publish(a, JournalEntry.StoreClosed)
        runCurrent()

        assertEquals(emptyList(), session.records())
        assertEquals(listOf(1L), sink.records.groupSeqs())
    }

    @Test
    fun writerQueueOverflow_dropsForTheSinks_andEveryHoleIsExplainedByAGap() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val writerScope = CoroutineScope(dispatcher + SupervisorJob())
        val sink = CollectingSink()
        val session = RecordingSession(writerScope, config = JournalConfig(writerQueueCapacity = 2), sinks = listOf(sink))

        // The writer has not run yet: two records fit the queue, the rest is lost for the sinks.
        repeat(4) { session.publish(a, JournalEntry.StoreClosed) }
        assertTrue(session.stats.droppedForSinks > 0)
        runCurrent()
        assertEquals(listOf(1L, 2L), sink.records.groupSeqs())
        // Room again: the next publication is preceded by a gap that explains the hole.
        session.publish(a, JournalEntry.StoreClosed)
        runCurrent()
        // And again, with the writer idle in between.
        repeat(5) { session.publish(a, JournalEntry.StoreClosed) }
        runCurrent()
        session.publish(a, JournalEntry.StoreClosed)
        runCurrent()

        assertTrue(sink.records.count { it.entry is JournalEntry.JournalGap } >= 2)
        assertTrue(sink.records.filter { it.entry is JournalEntry.JournalGap }.all { it.store == null })
        assertEveryHoleIsExplained(sink.records, session.stats)
        assertEquals((1L..session.stats.published).toList(), session.records().groupSeqs(), "the retained records are complete; gaps are about the sinks")
        writerScope.cancel()
    }

    @Test
    fun withAQueueOfOne_gapRecordsAreLostToo_andStillAccountedFor() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val writerScope = CoroutineScope(dispatcher + SupervisorJob())
        val sink = CollectingSink()
        val session = RecordingSession(writerScope, config = JournalConfig(writerQueueCapacity = 1), sinks = listOf(sink))

        repeat(3) { session.publish(a, JournalEntry.StoreClosed) }
        val droppedBeforeRoom = session.stats.droppedForSinks
        assertTrue(droppedBeforeRoom >= 3, "the second record, the gap attempted for it and the third record")
        runCurrent()
        session.publish(a, JournalEntry.StoreClosed)
        runCurrent()
        session.publish(a, JournalEntry.StoreClosed)
        runCurrent()

        assertTrue(sink.records.any { it.entry is JournalEntry.JournalGap })
        assertEveryHoleIsExplained(sink.records, session.stats)
        writerScope.cancel()
    }

    @Test
    fun failingSink_isCountedAndReported_neverRecorded_andTheOtherSinksStillWrite() = runTest {
        val failures = mutableListOf<JournalFailure>()
        val broken = JournalSink { throw IllegalStateException("disk") }
        val sink = CollectingSink()
        val session = RecordingSession(
            backgroundScope,
            config = JournalConfig(onFailure = { failures += it }),
            sinks = listOf(broken, sink),
        )

        repeat(3) { session.publish(a, JournalEntry.StoreClosed) }
        runCurrent()

        assertEquals(listOf(1L, 2L, 3L), sink.records.groupSeqs())
        assertEquals(3, session.stats.sinkFailures)
        assertEquals(3, failures.size)
        assertTrue(failures.all { it is JournalFailure.SinkFailed && it.sink === broken })
        assertEquals(3, session.stats.published, "a sink failure produces no record")
    }

    @Test
    fun aThrowingFailureHandler_isSwallowed() = runTest {
        val session = RecordingSession(
            backgroundScope,
            config = JournalConfig(onFailure = { throw IllegalStateException("handler") }),
            sinks = listOf(JournalSink { throw IllegalStateException("disk") }),
        )

        session.publish(a, JournalEntry.StoreClosed)
        runCurrent()

        assertEquals(1, session.stats.sinkFailures)
    }

    @Test
    fun aSlowSink_delaysTheQueue_notThePublisher() = runTest {
        val gate = CompletableDeferred<Unit>()
        val sink = CollectingSink()
        val slow = JournalSink { gate.await() }
        val session = RecordingSession(backgroundScope, sinks = listOf(slow, sink))

        session.publish(a, JournalEntry.StoreClosed)
        session.publish(a, JournalEntry.StoreClosed)
        runCurrent()

        assertEquals(2, session.stats.published)
        assertEquals(emptyList(), sink.records.groupSeqs(), "the second sink waits behind the slow one")
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(1L, 2L), sink.records.groupSeqs())
    }

    @Test
    fun close_publishesRecordingStopped_drainsTheQueue_andDropsWhatComesLater() = runTest {
        val sink = CollectingSink()
        val session = RecordingSession(backgroundScope, sinks = listOf(sink))
        session.publish(a, JournalEntry.StoreClosed)
        session.publish(a, JournalEntry.StoreClosed)

        session.close()

        assertEquals(listOf(1L, 2L, 3L), sink.records.groupSeqs())
        assertEquals(JournalEntry.RecordingStopped, sink.records.last().entry)
        assertNull(session.publish(a, JournalEntry.StoreClosed))
        assertEquals(1, session.stats.publishedAfterStop)
        assertEquals(3, session.records().size)
        session.close()
    }

    @Test
    fun probe_rejectsASecondRegistrationOfTheSameStore() = runTest {
        val session = RecordingSession(backgroundScope)
        session.probe(a, PayloadPolicy.metadataOnly<actron.core.State, actron.core.Action, actron.core.Event>())

        assertFailsWith<IllegalArgumentException> {
            session.probe(a, PayloadPolicy.metadataOnly<actron.core.State, actron.core.Action, actron.core.Event>())
        }
        assertEquals(listOf(JournalEntry.StoreRegistered(Capability.InspectOnly)), session.records().map { it.entry })
    }

    /**
     * Publications from many threads: the group sequence stays dense and is the order of the
     * retained records and of what the sink receives; each store's sequence stays dense too.
     */
    @Test
    fun manyThreads_keepTheGroupSequenceDense_andTheOrderConsistent() = runTest {
        val publishers = 8
        val perPublisher = 500
        val total = publishers * perPublisher
        val sink = CollectingSink()
        val writerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val session = RecordingSession(
            writerScope,
            config = JournalConfig(retainedRecords = total + 1, writerQueueCapacity = total + 1),
            sinks = listOf(sink),
        )
        val stores = List(publishers) { StoreInstanceId("store-$it") }

        withContext(Dispatchers.Default) {
            coroutineScope {
                stores.forEach { store ->
                    launch { repeat(perPublisher) { session.publish(store, JournalEntry.StoreClosed) } }
                }
            }
        }
        session.close()

        val records = session.records()
        assertEquals((1L..total).toList(), records.groupSeqs().dropLast(1), "dense and in order, RecordingStopped last")
        assertEquals(JournalEntry.RecordingStopped, records.last().entry)
        stores.forEach { store -> assertEquals((1L..perPublisher).toList(), records.storeSeqs(store)) }
        assertEquals(records.groupSeqs(), sink.records.groupSeqs(), "the sink received every record in order")
        assertEquals(0, session.stats.droppedForSinks)
        writerScope.cancel()
    }
}

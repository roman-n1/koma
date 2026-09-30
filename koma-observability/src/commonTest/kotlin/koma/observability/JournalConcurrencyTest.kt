@file:OptIn(ExperimentalKomaApi::class, InternalKomaApi::class)

package koma.observability

import koma.core.ExperimentalKomaApi
import koma.core.InternalKomaApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Eight publishers on [Dispatchers.Default] against a queue of sixteen and a sink that is slow
 * at random and throws at random, over several seeds. Whatever was lost, the sink can tell:
 * records arrive in order, every hole is followed by a gap record that explains it exactly, and
 * published equals delivered plus dropped. The sink's own failures are counted, never recorded.
 */
class JournalConcurrencyTest {

    private class FlakySink(seed: Int) : JournalSink {
        private val random = Random(seed)
        val received = mutableListOf<JournalRecord<*, *, *>>()
        val failed = mutableListOf<JournalRecord<*, *, *>>()

        override suspend fun write(record: JournalRecord<*, *, *>) {
            if (random.nextInt(50) == 0) delay(random.nextLong(1, 3).milliseconds)
            if (random.nextInt(20) == 0) {
                failed += record
                throw IllegalStateException("flaky")
            }
            received += record
        }
    }

    @Test
    fun publishersAgainstASmallQueueAndAFlakySink_loseNothingSilently() = runTest {
        for (seed in listOf(1, 7, 42)) {
            val publishers = 8
            val perPublisher = 400
            val sink = FlakySink(seed)
            val failures = mutableListOf<JournalFailure>()
            val writerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val session = RecordingSession(
                writerScope,
                config = JournalConfig(retainedRecords = 100, writerQueueCapacity = 16, onFailure = { failures += it }),
                sinks = listOf(sink),
            )
            val stores = List(publishers) { StoreInstanceId("s$it") }

            withContext(Dispatchers.Default) {
                coroutineScope {
                    stores.forEach { store ->
                        launch {
                            repeat(perPublisher) { i ->
                                session.publish(store, JournalEntry.ProcessingStarted(koma.core.InputId(i.toLong()), i.toLong()))
                                if (i % 50 == 0) delay(1.milliseconds)
                            }
                        }
                    }
                }
            }
            session.close()
            writerScope.cancel()

            // What the writer handed to the sink, whether the sink took it or threw: the writer's
            // guarantee is about this sequence, the sink's own failures are its own.
            val delivered = (sink.received + sink.failed).sortedBy { it.groupSeq.value }
            val stats = session.stats
            val seqs = delivered.map { it.groupSeq.value }
            assertEquals(sink.received.map { it.groupSeq.value }, sink.received.map { it.groupSeq.value }.sorted(), "seed $seed: received in order")
            var explained = 0L
            (listOf(0L) + seqs).zipWithNext().forEach { (prev, next) ->
                val hole = next - prev - 1
                val record = delivered.single { it.groupSeq.value == next }
                if (hole > 0) {
                    val gap = assertIs<JournalEntry.JournalGap>(record.entry, "seed $seed: a hole of $hole before $next without a gap in front")
                    assertEquals(hole, gap.dropped, "seed $seed: the gap before $next explains the hole exactly")
                    explained += hole
                } else {
                    assertTrue(record.entry !is JournalEntry.JournalGap, "seed $seed: a gap without a hole before $next")
                }
            }
            assertEquals(stats.droppedForSinks, explained, "seed $seed: every dropped record is explained by a gap, the closing one included")
            assertEquals(stats.published, delivered.size.toLong() + stats.droppedForSinks, "seed $seed: published = delivered + dropped")
            assertEquals(sink.failed.size.toLong(), stats.sinkFailures, "seed $seed")
            assertEquals(sink.failed.size, failures.count { it is JournalFailure.SinkFailed }, "seed $seed")
            assertTrue(delivered.none { it.entry is JournalEntry.StoreRegistered }, "seed $seed: nothing was registered through probes here")
            assertIs<JournalEntry.RecordingStopped>(delivered.last().entry, "seed $seed: the stop record was written last")
            assertTrue(stats.droppedForSinks > 0, "seed $seed: the small queue did overflow, or the test proves nothing")
        }
    }
}

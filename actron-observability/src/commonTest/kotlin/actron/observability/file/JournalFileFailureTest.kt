@file:OptIn(actron.core.ExperimentalActronApi::class)

package actron.observability.file

import actron.core.InputId
import actron.observability.ExecutionMode
import actron.observability.GroupSeq
import actron.observability.JOURNAL_FORMAT_VERSION
import actron.observability.JournalEntry
import actron.observability.JournalRecord
import actron.observability.MachineGroupId
import actron.observability.RuntimeSessionId
import actron.observability.StoreInstanceId
import actron.observability.StoreSeq
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.milliseconds

/** Storage faults must release handles, preserve the original failure, and stop appending. */
class JournalFileFailureTest {
    private val failure = IllegalStateException("injected I/O failure")
    private val cleanupFailure = IllegalArgumentException("injected close failure")

    private fun record(seq: Long = 1) = JournalRecord(
        JOURNAL_FORMAT_VERSION, RuntimeSessionId("failures"), MachineGroupId("g"), StoreInstanceId("s"),
        ExecutionMode.Live, GroupSeq(seq), StoreSeq(seq), seq.milliseconds,
        JournalEntry.ProcessingStarted(InputId(seq), seq),
    )

    private class Storage(val fail: (String, Int) -> Throwable?) : SegmentStorage {
        private val memory = InMemorySegmentStorage()
        private val calls = mutableMapOf<String, Int>()
        var opened = 0
        var closed = 0
        private fun check(operation: String) {
            val call = (calls[operation] ?: 0) + 1
            calls[operation] = call
            fail(operation, call)?.let { throw it }
        }
        override fun list() = memory.list()
        override fun read(name: String) = memory.read(name)
        override fun delete(name: String) { check("delete"); memory.delete(name) }
        override fun append(name: String): SegmentOutput {
            check("append")
            val output = memory.append(name)
            opened++
            return object : SegmentOutput {
                override fun write(bytes: ByteArray) { check("write"); output.write(bytes) }
                override fun flush() { check("flush"); output.flush() }
                override fun close() {
                    closed++
                    try { check("close") } finally { output.close() }
                }
            }
        }
    }

    @Test
    fun aHeaderWriteFailureClosesTheOpenedOutput() = runTest {
        val storage = Storage { op, call -> failure.takeIf { op == "write" && call == 1 } }
        val sink = JournalFileSink(storage)
        assertSame(failure, assertFailsWith<IllegalStateException> { sink.write(record()) })
        sink.close()
        assertEquals(1, storage.closed)
    }

    @Test
    fun anEndFrameWriteFailureStillClosesTheOutputOnce() = runTest {
        val storage = Storage { op, call -> failure.takeIf { op == "write" && call == 3 } }
        val sink = JournalFileSink(storage)
        sink.write(record())
        assertSame(failure, assertFailsWith<IllegalStateException> { sink.close() })
        sink.close()
        assertEquals(1, storage.closed)
        assertEquals(SegmentActivity.Closed, sink.activeSegment)
    }

    @Test
    fun aRecordWriteFailureStopsTheSinkAndClosesItsOutput() = runTest {
        val storage = Storage { op, call -> failure.takeIf { op == "write" && call == 2 } }
        val sink = JournalFileSink(storage)
        assertSame(failure, assertFailsWith<IllegalStateException> { sink.write(record()) })
        val bytes = storage.read(storage.list().single().name)
        sink.write(record(2))
        sink.close()
        assertEquals(bytes.toList(), storage.read(storage.list().single().name).toList())
        assertEquals(1, storage.closed)
    }

    @Test
    fun appendFailureDoesNotLeaveAHandleAndCloseIsSafe() = runTest {
        val storage = Storage { op, _ -> failure.takeIf { op == "append" } }
        val sink = JournalFileSink(storage)
        assertSame(failure, assertFailsWith<IllegalStateException> { sink.write(record()) })
        sink.close()
        assertEquals(0, storage.opened)
    }

    @Test
    fun periodicFlushFailureClosesTheOutput() = runTest {
        val storage = Storage { op, _ -> failure.takeIf { op == "flush" } }
        val sink = JournalFileSink(storage, JournalFileConfig(flushEveryRecords = 1))
        assertSame(failure, assertFailsWith<IllegalStateException> { sink.write(record()) })
        sink.close()
        assertEquals(1, storage.closed)
    }

    @Test
    fun explicitFlushFailureClosesTheOutput() = runTest {
        val storage = Storage { op, _ -> failure.takeIf { op == "flush" } }
        val sink = JournalFileSink(storage)
        sink.write(record())
        assertSame(failure, assertFailsWith<IllegalStateException> { sink.flush() })
        sink.close()
        assertEquals(1, storage.closed)
    }

    @Test
    fun aCloseFailureIsNotRetriedByAnIdempotentClose() = runTest {
        val storage = Storage { op, _ -> failure.takeIf { op == "close" } }
        val sink = JournalFileSink(storage)
        sink.write(record())
        assertSame(failure, assertFailsWith<IllegalStateException> { sink.close() })
        sink.close()
        assertEquals(1, storage.closed)
        assertEquals(SegmentActivity.Closed, sink.activeSegment)
    }

    @Test
    fun failedRotationReleasesTheNewOutputAndDoesNotAppendAfterwards() = runTest {
        val storage = Storage { op, call -> failure.takeIf { op == "write" && call == 4 } }
        val sink = JournalFileSink(storage, JournalFileConfig(maxSegmentBytes = 1))
        sink.write(record())
        assertSame(failure, assertFailsWith<IllegalStateException> { sink.write(record(2)) })
        sink.close()
        assertEquals(2, storage.opened)
        assertEquals(2, storage.closed)
    }

    @Test
    fun failedPruningReleasesTheNewOutput() = runTest {
        val storage = Storage { op, _ -> failure.takeIf { op == "delete" } }
        val sink = JournalFileSink(storage, JournalFileConfig(maxSegmentBytes = 1, maxSegments = 1))
        sink.write(record())
        assertSame(failure, assertFailsWith<IllegalStateException> { sink.write(record(2)) })
        sink.close()
        assertEquals(2, storage.closed)
    }

    @Test
    fun aCleanupFailureDoesNotHideTheHeaderWriteFailure() = runTest {
        val storage = Storage { op, call -> when {
            op == "write" && call == 1 -> failure
            op == "close" -> cleanupFailure
            else -> null
        } }
        val sink = JournalFileSink(storage)
        assertSame(failure, assertFailsWith<IllegalStateException> { sink.write(record()) })
        assertEquals(listOf(cleanupFailure), failure.suppressedExceptions)
        sink.close()
        assertEquals(1, storage.closed)
    }
}

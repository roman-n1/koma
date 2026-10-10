@file:OptIn(ExperimentalActronApi::class, ExperimentalCoroutinesApi::class, ExperimentalTime::class)

package actron.observability.file

import actron.core.Action
import actron.core.Event
import actron.core.ExceptionHandler
import actron.core.ExperimentalActronApi
import actron.core.InputId
import actron.core.State
import actron.core.Store
import actron.observability.ExecutionMode
import actron.observability.GroupSeq
import actron.observability.JOURNAL_FORMAT_VERSION
import actron.observability.JournalEntry
import actron.observability.JournalFailure
import actron.observability.JournalFormat
import actron.observability.JournalRecord
import actron.observability.MachineGroupId
import actron.observability.Payload
import actron.observability.PayloadPolicy
import actron.observability.RecordingSession
import actron.observability.RuntimeSessionId
import actron.observability.StoreInstanceId
import actron.observability.StoreSeq
import actron.observability.recordTo
import actron.test.dispatchAndAwait
import actron.test.startAndAwait
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlin.time.TestTimeSource

/**
 * The file sink under a session and the reader over its segments: what was published is read
 * back, segments rotate by size and the oldest go with a mark, a crash mid-write loses only
 * the partial frame, a damaged frame skips the rest of its segment and no more, a hole is
 * explained by a gap record or marked, and the tail, the export and the pruning.
 *
 * ```
 * Loading --enter--> Ready(count)
 * Ready --Increment--> Ready(count + 1)     emits Ping
 * ```
 */
class JournalFileSinkTest {

    sealed interface AppState : State {
        data object Loading : AppState
        data class Ready(val count: Int = 0) : AppState
    }

    sealed interface AppAction : Action {
        data object Increment : AppAction
    }

    sealed interface AppEvent : Event {
        data object Ping : AppEvent
    }

    private val session = RuntimeSessionId("run")
    private val group = MachineGroupId("g")
    private val store = StoreInstanceId("s")

    private fun record(seq: Long, entry: JournalEntry<*, *, *>, session: RuntimeSessionId = this.session, store: StoreInstanceId? = this.store) =
        JournalRecord(JOURNAL_FORMAT_VERSION, session, group, store ?: actron.observability.RecordSubject.Session, ExecutionMode.Live, GroupSeq(seq), store?.let { StoreSeq(seq) } ?: actron.observability.RecordOrdinal.Session, seq.milliseconds, entry)

    @Test
    fun whatTheSessionPublishes_isReadBack_withRetainedPayloadsAsText() = runTest {
        val storage = InMemorySegmentStorage()
        val sink = JournalFileSink(storage)
        val failures = mutableListOf<JournalFailure>()
        val session = RecordingSession(backgroundScope, id = session, group = group, sinks = listOf(sink), timeSource = TestTimeSource(), config = actron.observability.JournalConfig(onFailure = { failures += it }))
        val store = Store<AppState, AppAction, AppEvent>(AppState.Loading) {
            coroutineContext(UnconfinedTestDispatcher(testScheduler))
            exceptionHandler(ExceptionHandler.Ignore)
            recordTo(session, store, PayloadPolicy.retainAll())
            state<AppState.Loading> { enter { nextState { AppState.Ready() } } }
            state<AppState.Ready> {
                action<AppAction.Increment> {
                    event(AppEvent.Ping)
                    nextState { state.copy(count = state.count + 1) }
                }
            }
        }
        store.startAndAwait()
        repeat(3) { store.dispatchAndAwait(AppAction.Increment) }
        store.close()
        session.close()
        sink.close()

        val contents = JournalFiles(storage).read(this@JournalFileSinkTest.session)

        assertTrue(failures.isEmpty(), failures.toString())
        assertTrue(contents.isComplete, contents.marks.toString())
        assertEquals(session.records().size, contents.records.size)
        assertEquals(session.records().map { JournalFormat.line(it) }, contents.records.map { JournalFormat.line(it) }, "the text of a retained object is what the file keeps")
        val committed = contents.records.map { it.entry }.filterIsInstance<JournalEntry.StateCommitted<*>>().last()
        assertEquals(Payload.Described("Ready(count=3)"), committed.state)
        assertEquals(listOf("run-000000.journal"), storage.list().map { it.name })
        assertTrue(JournalFileFormat.decodeSegment("x", storage.read("run-000000.journal")).finished, "closing the sink finishes the segment")
        assertEquals(listOf(this@JournalFileSinkTest.session), JournalFiles(storage).sessions())
    }

    @Test
    fun segmentsRotateBySize_theOldestAreDeleted_andTheReaderMarksWhatIsGone() = runTest {
        val storage = InMemorySegmentStorage()
        val sink = JournalFileSink(storage, JournalFileConfig(maxSegmentBytes = 200, maxSegments = 3))
        val records = (1L..40L).map { record(it, JournalEntry.ProcessingStarted(InputId(it), it)) }
        for (record in records) sink.write(record)
        val before = sink.segments
        assertEquals(3, before.size, before.toString())
        assertEquals(SegmentActivity.Writing(before.last()), sink.activeSegment)
        sink.close()

        val contents = JournalFiles(storage).read(session)

        val first = assertIs<SegmentMark.MissingSegments>(contents.events.first().let { (it as JournalFileEvent.Mark).mark })
        assertEquals(0, first.fromIndex)
        assertTrue(first.toIndex >= 2, "the first segments rotated away: $first")
        assertEquals(listOf(first), contents.marks, "nothing else is amiss")
        val read = contents.records.map { it.groupSeq.value }
        assertEquals((read.first()..40L).toList(), read, "a contiguous tail of the run")
        assertTrue(read.size < 40 && read.size > 3)
        assertTrue(storage.list().all { JournalFileFormat.decodeSegment("x", storage.read(it.name)).finished })
    }

    @Test
    fun aCrashMidWrite_losesOnlyThePartialFrame() = runTest {
        val storage = InMemorySegmentStorage()
        val sink = JournalFileSink(storage)
        val records = (1L..5L).map { record(it, JournalEntry.ProcessingStarted(InputId(it), it)) }
        for (record in records) sink.write(record)
        val active = assertIs<SegmentActivity.Writing>(sink.activeSegment).name
        val size = storage.list().single().size.toInt()
        // The process died three bytes before the last frame was complete.
        storage.truncate(active, size - 3)

        val contents = JournalFiles(storage).read(session)

        assertEquals(records.take(4), contents.records)
        assertEquals(listOf<SegmentMark>(SegmentMark.TruncatedTail(active, recordsRead = 4, trailingBytes = JournalFileFormat.frame(records[4]).size - 3)), contents.marks)
        assertTrue(!contents.isComplete)

        // Died between two frames: nothing lost, the segment just never got its end frame.
        storage.truncate(active, size - JournalFileFormat.frame(records[4]).size)
        val boundary = JournalFiles(storage).read(session)
        assertEquals(records.take(4), boundary.records)
        assertEquals(listOf<SegmentMark>(SegmentMark.Unfinished(active, 4)), boundary.marks)
        assertTrue(boundary.isComplete, "an unfinished segment lost nothing")
    }

    @Test
    fun aDamagedFrame_skipsTheRestOfItsSegment_andTheNextSegmentIsStillRead() = runTest {
        val storage = InMemorySegmentStorage()
        val sink = JournalFileSink(storage, JournalFileConfig(maxSegmentBytes = 400, maxSegments = 10))
        val records = (1L..20L).map { record(it, JournalEntry.ProcessingStarted(InputId(it), it)) }
        for (record in records) sink.write(record)
        sink.close()
        val segments = sink.segments
        assertTrue(segments.size >= 2, segments.toString())
        val firstSize = storage.list().first { it.name == segments[0] }.size.toInt()
        storage.corrupt(segments[0], firstSize / 2)

        val contents = JournalFiles(storage).read(session)

        val corrupt = assertIs<SegmentMark.Corrupt>(contents.marks[0])
        assertEquals(segments[0], corrupt.segment)
        val hole = assertIs<SegmentMark.SequenceHole>(contents.marks[1])
        assertEquals(corrupt.recordsRead.toLong(), hole.afterGroupSeq)
        assertTrue(hole.missing >= 1)
        assertEquals(2, contents.marks.size, contents.marks.toString())
        assertEquals(records.take(corrupt.recordsRead) + records.drop(hole.nextGroupSeq.toInt() - 1), contents.records)
    }

    @Test
    fun aGapRecordExplainsAHole_anUnexplainedHoleIsMarked() = runTest {
        val storage = InMemorySegmentStorage()
        val sink = JournalFileSink(storage)
        sink.write(record(1, JournalEntry.ProcessingStarted(InputId(1), 1)))
        sink.write(record(2, JournalEntry.ProcessingStarted(InputId(2), 2)))
        // Records 3 and 4 were dropped for the sinks; the gap record has the next number.
        sink.write(record(5, JournalEntry.JournalGap(2), store = null))
        sink.write(record(6, JournalEntry.ProcessingStarted(InputId(3), 3)))
        // Records 7 and 8 are simply not there.
        sink.write(record(9, JournalEntry.ProcessingStarted(InputId(4), 4)))
        sink.close()

        val contents = JournalFiles(storage).read(session)

        assertEquals(listOf<SegmentMark>(SegmentMark.SequenceHole(session, afterGroupSeq = 6, nextGroupSeq = 9)), contents.marks)
        assertEquals(2, (contents.marks.single() as SegmentMark.SequenceHole).missing)
        assertEquals(listOf(1L, 2L, 5L, 6L, 9L), contents.records.map { it.groupSeq.value })
    }

    @Test
    fun aRecordOfAnotherSession_isRefused_andWritesAfterCloseAreIgnored() = runTest {
        val storage = InMemorySegmentStorage()
        val sink = JournalFileSink(storage)
        sink.write(record(1, JournalEntry.StoreClosed))

        assertFailsWith<IllegalArgumentException> { sink.write(record(2, JournalEntry.StoreClosed, session = RuntimeSessionId("other"))) }

        sink.flush()
        sink.close()
        sink.close()
        sink.write(record(3, JournalEntry.StoreClosed))
        assertEquals(SegmentActivity.Closed, sink.activeSegment)
        assertEquals(listOf(1L), JournalFiles(storage).read(session).records.map { it.groupSeq.value })
    }

    @Test
    fun tail_export_andPruning() = runTest {
        val storage = InMemorySegmentStorage()
        val older = RuntimeSessionId("older")
        val olderSink = JournalFileSink(storage)
        for (seq in 1L..10L) olderSink.write(record(seq, JournalEntry.ProcessingStarted(InputId(seq), seq), session = older))
        olderSink.close()
        val sink = JournalFileSink(storage)
        for (seq in 1L..6L) sink.write(record(seq, JournalEntry.ProcessingStarted(InputId(seq), seq)))
        sink.close()
        val files = JournalFiles(storage)

        assertEquals(listOf(older, session), files.sessions(), "oldest first")
        assertEquals(listOf(5L, 6L), files.tail(session, 2).map { it.groupSeq.value })
        val lines = files.exportLines(session)
        assertEquals(6, lines.size)
        assertTrue(lines.all { it.startsWith("[run g s #") }, lines.toString())
        storage.truncate(sink.segments.single(), storage.list().first { it.name == sink.segments.single() }.size.toInt() - 1)
        assertTrue(files.exportLines(session).last().startsWith("! TruncatedTail"), files.exportLines(session).last())

        val deleted = files.prune(maxTotalBytes = 1)
        assertEquals(listOf("older-000000.journal"), deleted, "the newest session is never pruned")
        assertEquals(listOf(session), files.sessions())
        assertTrue(files.prune(maxTotalBytes = 1).isEmpty())
    }
}

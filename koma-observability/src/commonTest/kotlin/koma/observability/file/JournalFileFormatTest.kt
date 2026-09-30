@file:OptIn(ExperimentalKomaApi::class)

package koma.observability.file

import koma.core.ExperimentalKomaApi
import koma.core.InputId
import koma.observability.ActivationRef
import koma.observability.Capability
import koma.observability.CommandRef
import koma.observability.DiscardDescriptor
import koma.observability.DiscardKind
import koma.observability.ExecutionMode
import koma.observability.FailureDescriptor
import koma.observability.GroupSeq
import koma.observability.InputDescriptor
import koma.observability.JOURNAL_FORMAT_VERSION
import koma.observability.JournalEntry
import koma.observability.JournalRecord
import koma.observability.MachineGroupId
import koma.observability.OutcomeDescriptor
import koma.observability.OutcomeKind
import koma.observability.Payload
import koma.observability.RuntimeSessionId
import koma.observability.StoreInstanceId
import koma.observability.StoreSeq
import koma.observability.TimerRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The segment layout: every entry kind round-trips, the bytes of a small segment are pinned as
 * a golden fixture, a newer format is refused, and, for what no hand reproduces, every cut of a
 * segment and every flipped bit yields a prefix of the records with a mark, never an exception
 * and never a wrong record.
 */
class JournalFileFormatTest {

    private val session = RuntimeSessionId("s1")
    private val group = MachineGroupId("g")
    private val store = StoreInstanceId("store-a")

    private fun record(seq: Long, entry: JournalEntry<*, *, *>, store: StoreInstanceId? = this.store, storeSeq: Long? = seq, elapsed: Duration = seq.milliseconds) =
        JournalRecord(JOURNAL_FORMAT_VERSION, session, group, store, ExecutionMode.Live, GroupSeq(seq), storeSeq?.let(::StoreSeq), elapsed, entry)

    private data class Loaded(val items: List<String>) : koma.core.Event

    /** Every entry kind, every payload kind, failures with cause and suppressed, optional fields present and absent. */
    private val all: List<JournalRecord<*, *, *>> = listOf(
        record(1, JournalEntry.StoreRegistered(Capability.InspectOnly)),
        record(2, JournalEntry.InputAccepted(InputId(1), InputDescriptor.Startup)),
        record(3, JournalEntry.InputAccepted(InputId(2), InputDescriptor.Dispatch(Payload.Projected("Load", mapOf("q" to "cats", "n" to "3"))))),
        record(4, JournalEntry.InputAccepted(InputId(3), InputDescriptor.Transaction(InputId(2)))),
        record(5, JournalEntry.InputAccepted(InputId(4), InputDescriptor.Recovery(FailureDescriptor("IOException", "boom", FailureDescriptor("SocketException"), listOf(FailureDescriptor("Suppressed"))), null))),
        record(6, JournalEntry.InputDiscarded(InputId(4), DiscardDescriptor(DiscardKind.Rejected, FailureDescriptor("IllegalStateException")))),
        record(7, JournalEntry.InputDiscarded(InputId(5), DiscardDescriptor(DiscardKind.StoreClosed))),
        record(8, JournalEntry.ProcessingStarted(InputId(2), 1)),
        record(9, JournalEntry.StateCommitted(InputId(2), 1, Payload.Omitted, Payload.Unavailable)),
        record(10, JournalEntry.EventEmitted(null, Payload.Retained(Loaded(listOf("tom", "felix"))))),
        record(11, JournalEntry.FailureReported(InputId(2), FailureDescriptor(null))),
        record(12, JournalEntry.ProcessingFinished(InputId(2), 1, OutcomeDescriptor(OutcomeKind.Recovered, 2, FailureDescriptor("X")), 812.microseconds)),
        record(13, JournalEntry.ProcessingFinished(InputId(3), 2, OutcomeDescriptor(OutcomeKind.Unchanged), Duration.ZERO)),
        record(14, JournalEntry.StoreClosed),
        record(15, JournalEntry.InputRejected(Payload.Projected("Tap"), "pending=8 limit=8")),
        record(
            16,
            JournalEntry.DecisionCommitted(
                InputId(5), 3, listOf("Root", "Loading"), listOf(0, 2), listOf(ActivationRef("Idle", 2)), listOf(ActivationRef("Loading", 3)),
                listOf(CommandRef(1, 3, "net", "Latest", Payload.Described("Fetch(cats)")), CommandRef(2, 3, null, null, Payload.Omitted)),
                listOf(2L), listOf(TimerRef(1, 3, 3, 10.seconds)), listOf(7L), 2,
            ),
        ),
        record(17, JournalEntry.DecisionIgnored(null, "NoTransition")),
        record(18, JournalEntry.JournalGap(3), store = null, storeSeq = null),
        record(19, JournalEntry.RecordingStopped, store = null, storeSeq = null),
    )

    /** What the file keeps of [all]: a retained object becomes its text. */
    private val expected: List<JournalRecord<*, *, *>> = all.map { record ->
        val entry = record.entry
        if (entry is JournalEntry.EventEmitted && entry.event is Payload.Retained) record.copy(entry = JournalEntry.EventEmitted(entry.input, Payload.Described(entry.event.value.toString()))) else record
    }

    private fun segment(records: List<JournalRecord<*, *, *>> = all, finished: Boolean = true, index: Int = 0): ByteArray = ByteWriter().apply {
        raw(JournalFileFormat.header(session, group, ExecutionMode.Live, index))
        for (record in records) raw(JournalFileFormat.frame(record))
        if (finished) raw(JournalFileFormat.END)
    }.toByteArray()

    @Test
    fun everyEntryKind_roundTripsThroughASegment() {
        val decoded = JournalFileFormat.decodeSegment("s1-000000.journal", segment())

        assertEquals(SegmentHeader(JOURNAL_FILE_FORMAT_VERSION, JOURNAL_FORMAT_VERSION, session, group, ExecutionMode.Live, 0), decoded.header)
        assertEquals(expected, decoded.records)
        assertNull(decoded.mark)
        assertTrue(decoded.finished)
        assertEquals(Payload.Described("Loaded(items=[tom, felix])"), (decoded.records[9].entry as JournalEntry.EventEmitted<*>).event, "a retained object is kept as its text")
    }

    @Test
    fun theGoldenSegment_isTheseBytes() {
        val bytes = segment(listOf(all[0], all[11]))

        assertEquals(GOLDEN, bytes.toHex(), "the segment layout changed; if intended, bump JOURNAL_FILE_FORMAT_VERSION.\nACTUAL:\n${bytes.toHex()}")
        assertEquals(listOf(expected[0], expected[11]), JournalFileFormat.decodeSegment("golden", GOLDEN.fromHex()).records)
    }

    @Test
    fun aNewerFormat_isRefused_notGuessed() {
        val newerFile = ByteWriter().apply {
            raw(JournalFileFormat.header(session, group, ExecutionMode.Replay, 4, fileFormatVersion = JOURNAL_FILE_FORMAT_VERSION + 1))
            raw(JournalFileFormat.frame(all[0]))
            raw(JournalFileFormat.END)
        }.toByteArray()
        val decoded = JournalFileFormat.decodeSegment("s1-000004.journal", newerFile)

        assertEquals(SegmentMark.UnsupportedFormat("s1-000004.journal", JOURNAL_FILE_FORMAT_VERSION + 1, JOURNAL_FORMAT_VERSION), decoded.mark)
        assertTrue(decoded.records.isEmpty())
        assertEquals(ExecutionMode.Replay, decoded.header?.mode, "the header is still readable")
        val newerRecords = JournalFileFormat.header(session, group, ExecutionMode.Live, 0, recordFormatVersion = JOURNAL_FORMAT_VERSION + 1)
        assertIs<SegmentMark.UnsupportedFormat>(JournalFileFormat.decodeSegment("x", newerRecords).mark)
    }

    @Test
    fun everyCut_ofASegment_yieldsAPrefixOfTheRecords_andAMark() {
        val bytes = segment()
        var unfinishedCuts = 0
        var truncatedCuts = 0
        for (length in 0 until bytes.size) {
            val decoded = JournalFileFormat.decodeSegment("cut", bytes.copyOf(length))
            assertEquals(expected.take(decoded.records.size), decoded.records, "cut at $length")
            assertTrue(!decoded.finished, "cut at $length is not a finished segment")
            when (val mark = decoded.mark) {
                is SegmentMark.Unfinished -> unfinishedCuts++
                is SegmentMark.TruncatedTail -> {
                    truncatedCuts++
                    assertEquals(decoded.records.size, mark.recordsRead, "cut at $length")
                    // Zero trailing bytes: cut right after the magic, before the header frame began.
                    assertTrue(mark.trailingBytes in 0..bytes.size, "cut at $length: $mark")
                }
                is SegmentMark.NotASegment -> assertTrue(length < JournalFileFormat.MAGIC.size, "cut at $length")
                else -> throw AssertionError("cut at $length: $mark")
            }
        }
        assertEquals(all.size + 1, unfinishedCuts, "one cut per frame boundary: after the header and after each record")
        assertTrue(truncatedCuts > all.size, "truncated cuts: $truncatedCuts")
        val whole = JournalFileFormat.decodeSegment("whole", bytes)
        assertTrue(whole.finished && whole.mark == null, "the whole segment: ${whole.mark}")
    }

    @Test
    fun everyFlippedBit_isCaught_andNeverYieldsAWrongRecord() {
        val bytes = segment()
        var caught = 0
        for (offset in bytes.indices) {
            for (bit in 0 until 8) {
                val damaged = bytes.copyOf()
                damaged[offset] = (damaged[offset].toInt() xor (1 shl bit)).toByte()
                val decoded = JournalFileFormat.decodeSegment("bit", damaged)
                assertEquals(expected.take(decoded.records.size), decoded.records, "flip at $offset bit $bit")
                assertTrue(decoded.mark != null || decoded.records.size == expected.size, "flip at $offset bit $bit went unnoticed and lost records")
                if (decoded.mark != null) caught++
            }
        }
        assertTrue(caught >= bytes.size * 8 - 8, "every flip but those in the end frame's trailing bytes is marked: $caught of ${bytes.size * 8}")
    }

    @Test
    fun segmentNames_carryTheSessionAndTheIndex() {
        assertEquals("s1-000042.journal", JournalFileFormat.segmentName(session, 42))
        assertEquals(session to 42, JournalFileFormat.parseSegmentName("s1-000042.journal"))
        assertEquals(RuntimeSessionId("a-b-c") to 7, JournalFileFormat.parseSegmentName("a-b-c-000007.journal"), "a session id may contain dashes")
        assertNull(JournalFileFormat.parseSegmentName("notes.txt"))
        assertNull(JournalFileFormat.parseSegmentName("s1-42.journal"))
        assertNull(JournalFileFormat.parseSegmentName("-000001.journal"))
    }

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun String.fromHex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val GOLDEN: String = "4b4f4d414a524e4c000000204bad6a440000000001000000010000000273310000000167000000044c69766500000000000000359dc4bb4701010000000773746f72652d61000000000000000101000000000000000100000000000f42400000000b496e73706563744f6e6c790000005c486ce2b508010000000773746f72652d61000000000000000c01000000000000000c0000000000b71b0000000000000000020000000000000001000000095265636f7665726564000000020101000000015800000000000000000000000c63e00000000000000000"
    }
}

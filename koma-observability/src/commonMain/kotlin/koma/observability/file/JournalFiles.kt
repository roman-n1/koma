package koma.observability.file

import koma.observability.JournalEntry
import koma.observability.JournalFormat
import koma.observability.JournalRecord
import koma.observability.RuntimeSessionId

/**
 * One thing read from the segments of a session: a record, or a mark where records could not be.
 */
sealed interface JournalFileEvent {
    data class Record(val record: JournalRecord<Nothing, Nothing, Nothing>) : JournalFileEvent

    data class Mark(val mark: SegmentMark) : JournalFileEvent
}

/**
 * What [JournalFiles.read] found: the records in [koma.observability.GroupSeq] order with the
 * marks between them where segments or frames were missing or damaged.
 */
data class JournalFileContents(val events: List<JournalFileEvent>) {
    val records: List<JournalRecord<Nothing, Nothing, Nothing>> get() = events.mapNotNull { (it as? JournalFileEvent.Record)?.record }

    val marks: List<SegmentMark> get() = events.mapNotNull { (it as? JournalFileEvent.Mark)?.mark }

    /** Whether every record of the session's segments was read with nothing missing or damaged. */
    val isComplete: Boolean get() = marks.none { it !is SegmentMark.Unfinished }
}

/**
 * Reads the segments a [JournalFileSink] wrote into a [SegmentStorage]: the sessions present,
 * the records of a session with the marks of whatever could not be read, the safe tail for a
 * crash reporter, an export as lines, and pruning by total size. Reading never throws on
 * damaged segments: what could be read is returned, the rest is marked.
 */
class JournalFiles(private val storage: SegmentStorage) {
    /** The sessions that have segments, oldest first by the storage's clock. */
    fun sessions(): List<RuntimeSessionId> = segments().groupBy { it.session }.entries
        .sortedBy { (_, infos) -> infos.minOf { it.modified } }
        .map { it.key }

    /**
     * The records of [session] in order, with a mark wherever a segment is missing, a frame is
     * damaged or cut short, or records are absent that no [JournalEntry.JournalGap] accounts for.
     */
    fun read(session: RuntimeSessionId): JournalFileContents {
        val events = mutableListOf<JournalFileEvent>()
        val segments = segments().filter { it.session == session }.sortedBy { it.index }
        // A session's segments begin at index 0: what is absent before the first present one was rotated away.
        var expectedIndex = 0
        var previousSeq: Long? = null
        for (segment in segments) {
            if (segment.index > expectedIndex) events += JournalFileEvent.Mark(SegmentMark.MissingSegments(session, expectedIndex, segment.index - 1))
            expectedIndex = segment.index + 1
            val decoded = JournalFileFormat.decodeSegment(segment.name, storage.read(segment.name))
            for (record in decoded.records) {
                val seq = record.groupSeq.value
                val previous = previousSeq
                if (previous != null && seq != previous + 1) {
                    val explained = record.entry is JournalEntry.JournalGap && (record.entry as JournalEntry.JournalGap).dropped == seq - previous - 1
                    if (!explained) events += JournalFileEvent.Mark(SegmentMark.SequenceHole(session, previous, seq))
                }
                previousSeq = seq
                events += JournalFileEvent.Record(record)
            }
            decoded.mark?.let { events += JournalFileEvent.Mark(it) }
        }
        return JournalFileContents(events)
    }

    /** The last [count] records of [session]: what a crash reporter may attach, as the policy left them. */
    fun tail(session: RuntimeSessionId, count: Int): List<JournalRecord<Nothing, Nothing, Nothing>> = read(session).records.takeLast(count)

    /** The session as lines: [JournalFormat.line] for records, `! ` and the mark for marks. */
    fun exportLines(session: RuntimeSessionId): List<String> = read(session).events.map { event ->
        when (event) {
            is JournalFileEvent.Record -> JournalFormat.line(event.record)
            is JournalFileEvent.Mark -> "! ${event.mark}"
        }
    }

    /**
     * Deletes the oldest sessions, whole, until the segments take at most [maxTotalBytes]; the
     * newest session is never deleted. Returns the names deleted.
     */
    fun prune(maxTotalBytes: Long): List<String> {
        val bySession = segments().groupBy { it.session }.entries.sortedBy { (_, infos) -> infos.maxOf { it.modified } }
        var total = bySession.sumOf { (_, infos) -> infos.sumOf { it.size } }
        val deleted = mutableListOf<String>()
        for ((_, infos) in bySession.dropLast(1)) {
            if (total <= maxTotalBytes) break
            for (info in infos) {
                storage.delete(info.name)
                total -= info.size
                deleted += info.name
            }
        }
        return deleted
    }

    private class Segment(val name: String, val session: RuntimeSessionId, val index: Int, val size: Long, val modified: Long)

    private fun segments(): List<Segment> = storage.list().mapNotNull { info ->
        JournalFileFormat.parseSegmentName(info.name)?.let { (session, index) -> Segment(info.name, session, index, info.size, info.modified) }
    }
}

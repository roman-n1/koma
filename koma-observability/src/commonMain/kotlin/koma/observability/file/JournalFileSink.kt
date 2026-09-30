package koma.observability.file

import koma.observability.ExecutionMode
import koma.observability.JournalRecord
import koma.observability.JournalSink
import koma.observability.MachineGroupId
import koma.observability.RuntimeSessionId
import kotlinx.coroutines.sync.Mutex

/**
 * Budgets of a [JournalFileSink].
 *
 * @property maxSegmentBytes A segment that would grow past this with the next record is finished
 * and a new one begun; the record that does not fit an empty segment is written anyway
 * @property maxSegments How many segments of the session are kept; when a new one begins, the
 * oldest beyond this are deleted, and a reader marks the hole
 * @property flushEveryRecords How many records are written before the storage is asked to flush;
 * [JournalFileSink.flush] flushes on demand, for a background or crash path
 */
data class JournalFileConfig(
    val maxSegmentBytes: Int = 512 * 1024,
    val maxSegments: Int = 8,
    val flushEveryRecords: Int = 32,
) {
    init {
        require(maxSegmentBytes >= 1) { "[Koma] maxSegmentBytes must be at least 1" }
        require(maxSegments >= 1) { "[Koma] maxSegments must be at least 1" }
        require(flushEveryRecords >= 1) { "[Koma] flushEveryRecords must be at least 1" }
    }
}

/**
 * A [JournalSink] that writes the records of one session into segments of a [SegmentStorage] in
 * the [JournalFileFormat]: a bounded ring of files that a [JournalFiles] reads back, after a
 * crash as well.
 *
 * The first record fixes the session, the group and the mode; a record of another session is
 * refused (reported by the session as a sink failure). Segments are rotated by size and the
 * oldest are deleted beyond [JournalFileConfig.maxSegments]; a finished segment ends with the
 * end frame, so a reader tells it from one cut short by a crash. Writing happens on the
 * session's writer; [flush] and [close] may be called from anywhere, for example from a
 * lifecycle or crash hook, and never wait for a Store.
 */
class JournalFileSink(
    private val storage: SegmentStorage,
    private val config: JournalFileConfig = JournalFileConfig(),
) : JournalSink {
    private val lock = Mutex()
    private var session: RuntimeSessionId? = null
    private var group: MachineGroupId? = null
    private var mode: ExecutionMode? = null
    private var index = -1
    private var output: SegmentOutput? = null
    private var segmentBytes = 0
    private var segmentRecords = 0
    private var unflushed = 0
    private var closed = false

    /** The segment being written, or `null` before the first record and after [close]. */
    val activeSegment: String?
        get() = locked { if (output != null) JournalFileFormat.segmentName(checkNotNull(session), index) else null }

    /** The session's segments present in the storage, oldest first. */
    val segments: List<String>
        get() {
            val session = locked { session } ?: return emptyList()
            return storage.list().mapNotNull { info -> JournalFileFormat.parseSegmentName(info.name)?.takeIf { it.first == session }?.let { it.second to info.name } }
                .sortedBy { it.first }.map { it.second }
        }

    override suspend fun write(record: JournalRecord<*, *, *>) {
        locked {
            if (closed) return
            val session = session
            if (session == null) begin(record) else require(record.session == session && record.group == group) {
                "[Koma] JournalFileSink writes session $session group $group; got ${record.session} ${record.group}"
            }
            val frame = JournalFileFormat.frame(record)
            if (segmentRecords > 0 && segmentBytes + frame.size > config.maxSegmentBytes) {
                finish()
                index++
                open()
                retain()
            }
            val output = checkNotNull(output)
            output.write(frame)
            segmentBytes += frame.size
            segmentRecords++
            if (++unflushed >= config.flushEveryRecords) {
                output.flush()
                unflushed = 0
            }
        }
    }

    /** Hands what was written to the storage; for a background or crash path. */
    fun flush() {
        locked {
            output?.flush()
            unflushed = 0
        }
    }

    /** Finishes the active segment with the end frame and releases it; idempotent. Writes after it are ignored. */
    fun close() {
        locked {
            if (closed) return
            closed = true
            finish()
        }
    }

    private fun begin(record: JournalRecord<*, *, *>) {
        session = record.session
        group = record.group
        mode = record.mode
        // After the session's own segments, should the same session id write again.
        index = storage.list().mapNotNull { JournalFileFormat.parseSegmentName(it.name) }.filter { it.first == record.session }.maxOfOrNull { it.second }?.plus(1) ?: 0
        open()
    }

    private fun open() {
        val header = JournalFileFormat.header(checkNotNull(session), checkNotNull(group), checkNotNull(mode), index)
        val output = storage.append(JournalFileFormat.segmentName(checkNotNull(session), index))
        output.write(header)
        this.output = output
        segmentBytes = header.size
        segmentRecords = 0
        unflushed = 0
    }

    private fun finish() {
        val output = output ?: return
        output.write(JournalFileFormat.END)
        output.close()
        this.output = null
    }

    private fun retain() {
        val session = checkNotNull(session)
        val mine = storage.list().mapNotNull { info -> JournalFileFormat.parseSegmentName(info.name)?.takeIf { it.first == session }?.let { it.second to info.name } }.sortedBy { it.first }
        for ((_, name) in mine.dropLast(config.maxSegments)) storage.delete(name)
    }

    private inline fun <T> locked(block: () -> T): T {
        while (!lock.tryLock()) {
            // Spin: the holder writes one frame, or flushes.
        }
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}

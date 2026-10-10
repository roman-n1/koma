package actron.observability.file

import actron.observability.ExecutionMode
import actron.observability.JournalRecord
import actron.observability.JournalSink
import actron.observability.MachineGroupId
import actron.observability.RuntimeSessionId
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
        require(maxSegmentBytes >= 1) { "[Actron] maxSegmentBytes must be at least 1" }
        require(maxSegments >= 1) { "[Actron] maxSegments must be at least 1" }
        require(flushEveryRecords >= 1) { "[Actron] flushEveryRecords must be at least 1" }
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
 * A storage failure stops this sink and releases its output; subsequent writes are ignored.
 */
class JournalFileSink(
    private val storage: SegmentStorage,
    private val config: JournalFileConfig = JournalFileConfig(),
) : JournalSink {
    private val lock = Mutex()
    private sealed interface SessionBinding {
        data object Unbound : SessionBinding
        data class Bound(val session: RuntimeSessionId, val group: MachineGroupId, val mode: ExecutionMode) : SessionBinding
    }
    private var binding: SessionBinding = SessionBinding.Unbound
    private var index = -1
    private sealed interface SegmentWriter {
        data object Detached : SegmentWriter
        data class Active(val output: SegmentOutput) : SegmentWriter
    }
    private var writer: SegmentWriter = SegmentWriter.Detached
    private var segmentBytes = 0
    private var segmentRecords = 0
    private var unflushed = 0
    private var closed = false

    /** The observed lifecycle of the writer. */
    val activeSegment: SegmentActivity
        get() = locked {
            if (writer is SegmentWriter.Active) {
                val current = binding
                check(current is SessionBinding.Bound)
                SegmentActivity.Writing(JournalFileFormat.segmentName(current.session, index))
            }
            else if (closed) SegmentActivity.Closed else SegmentActivity.AwaitingFirstRecord
        }

    /** The session's segments present in the storage, oldest first. */
    val segments: List<String>
        get() {
            val readSegments: () -> List<String> = locked {
                val current = binding
                if (current is SessionBinding.Unbound) ({ emptyList() }) else {
                    check(current is SessionBinding.Bound)
                    ({
                    JournalFileFormat.namedSegments(storage, current.session)
                        .sortedBy { it.first }.map { it.second }
                    })
                }
            }
            return readSegments()
        }

    override suspend fun write(record: JournalRecord<*, *, *>) {
        locked {
            if (closed) return
            val current = binding
            if (current is SessionBinding.Bound) require(record.session == current.session && record.group == current.group) {
                "[Actron] JournalFileSink writes session ${current.session} group ${current.group}; got ${record.session} ${record.group}"
            }
            try {
                if (current is SessionBinding.Unbound) begin(record)
                val frame = JournalFileFormat.frame(record)
                if (segmentRecords > 0 && segmentBytes + frame.size > config.maxSegmentBytes) {
                    finish()
                    index++
                    open()
                    retain()
                }
                val active = writer
                check(active is SegmentWriter.Active)
                val output = active.output
                output.write(frame)
                segmentBytes += frame.size
                segmentRecords++
                if (++unflushed >= config.flushEveryRecords) {
                    output.flush()
                    unflushed = 0
                }
            } catch (t: Throwable) {
                abort(t)
                throw t
            }
        }
    }

    /** Hands what was written to the storage; for a background or crash path. */
    fun flush() {
        locked {
            try {
                val active = writer
                if (active is SegmentWriter.Active) active.output.flush()
                unflushed = 0
            } catch (t: Throwable) {
                abort(t)
                throw t
            }
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
        binding = SessionBinding.Bound(record.session, record.group, record.mode)
        // After the session's own segments, should the same session id write again.
        index = JournalFileFormat.namedSegments(storage, record.session).maxOfOrNull { it.first }?.plus(1) ?: 0
        open()
    }

    private fun open() {
        val current = binding
        check(current is SessionBinding.Bound)
        val header = JournalFileFormat.header(current.session, current.group, current.mode, index)
        val output = storage.append(JournalFileFormat.segmentName(current.session, index))
        writer = SegmentWriter.Active(output)
        output.write(header)
        segmentBytes = header.size
        segmentRecords = 0
        unflushed = 0
    }

    private fun finish() {
        val active = writer
        if (active !is SegmentWriter.Active) return
        val output = active.output
        writer = SegmentWriter.Detached
        var failure: Result<Unit> = Result.success(Unit)
        try {
            output.write(JournalFileFormat.END)
        } catch (t: Throwable) {
            failure = Result.failure(t)
            throw t
        } finally {
            closeOutput(output, failure)
        }
    }

    private fun abort(failure: Throwable) {
        closed = true
        val active = writer
        if (active !is SegmentWriter.Active) return
        writer = SegmentWriter.Detached
        closeOutput(active.output, Result.failure(failure))
    }

    private fun closeOutput(output: SegmentOutput, failure: Result<Unit>) {
        try {
            output.close()
        } catch (t: Throwable) {
            failure.fold(
                onSuccess = { throw t },
                onFailure = { primary -> if (primary !== t) primary.addSuppressed(t) },
            )
        }
    }

    private fun retain() {
        val current = binding
        check(current is SessionBinding.Bound)
        val mine = JournalFileFormat.namedSegments(storage, current.session).sortedBy { it.first }
        for ((_, name) in mine.dropLast(config.maxSegments)) storage.delete(name)
    }

    private inline fun <T : Any> locked(block: () -> T): T {
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

/** Observation of a segment writer's lifecycle. */
sealed interface SegmentActivity {
    data object AwaitingFirstRecord : SegmentActivity
    data object Closed : SegmentActivity
    data class Writing(val name: String) : SegmentActivity
}

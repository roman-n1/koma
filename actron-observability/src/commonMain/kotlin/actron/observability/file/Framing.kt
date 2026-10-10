package actron.observability.file


/**
 * The framing every segment file of Actron shares: a magic, then frames of
 * `[i32 length][i32 crc32][payload]`, then an end frame of length 0 once the segment is
 * finished. The journal and the replay recordings put different payloads into the frames; the
 * damage they can suffer, a write cut short, a flipped byte, bytes after the end, is told apart
 * here, once.
 */
object Framing {
    /** The end frame: a length of zero and the CRC of nothing. */
    val END: ByteArray = ByteArray(8)

    /** A frame longer than this is corruption, whatever its checksum says. */
    const val MAX_FRAME_BYTES: Int = 16 * 1024 * 1024

    /** [payload] framed: its length, its CRC-32 and itself. */
    fun frame(payload: ByteArray): ByteArray = ByteWriter(8 + payload.size).apply {
        i32(payload.size)
        i32(Crc32.of(payload))
        raw(payload)
    }.toByteArray()

    /**
     * Reads [bytes] as a segment beginning with [magic]: every frame whose checksum held, in
     * order with its offset, and how the reading ended. Never throws on damage.
     */
    fun read(name: String, bytes: ByteArray, magic: ByteArray): FramesRead {
        if (bytes.size < magic.size || !bytes.copyOf(magic.size).contentEquals(magic)) {
            return FramesRead(emptyList(), SegmentEnding.Stopped(SegmentMark.NotASegment(name, "no magic")))
        }
        val reader = ByteReader(bytes, magic.size)
        val frames = mutableListOf<Frame>()
        fun stopped(mark: SegmentMark): FramesRead = FramesRead(frames, SegmentEnding.Stopped(mark))
        while (true) {
            val offset = reader.position
            if (reader.remaining == 0) return stopped(SegmentMark.Unfinished(name, frames.size))
            if (reader.remaining < 8) return stopped(SegmentMark.TruncatedTail(name, frames.size, reader.remaining))
            val length = reader.i32()
            val crc = reader.i32()
            if (length == 0) {
                if (crc != 0) return stopped(SegmentMark.Corrupt(name, offset, frames.size, "an end frame with a checksum"))
                val trailing = reader.remaining
                val ending = if (trailing > 0) SegmentEnding.Stopped(SegmentMark.Corrupt(name, reader.position, frames.size, "$trailing bytes after the end frame"), finished = true) else SegmentEnding.Finalized
                return FramesRead(frames, ending)
            }
            if (length < 0 || length > MAX_FRAME_BYTES) return stopped(SegmentMark.Corrupt(name, offset, frames.size, "frame length $length"))
            if (length > reader.remaining) return stopped(SegmentMark.TruncatedTail(name, frames.size, 8 + reader.remaining))
            val payload = ByteArray(length)
            reader.copyInto(reader.position, length, payload)
            reader.position += length
            if (Crc32.of(payload) != crc) return stopped(SegmentMark.Corrupt(name, offset, frames.size, "checksum"))
            frames += Frame(offset, payload)
        }
    }

    /** A frame as read: where it began in the segment, and its payload. */
    class Frame(val offset: Int, val payload: ByteArray)
}

/**
 * What [Framing.read] found: the frames that held, the mark of what stopped the reading (with
 * `recordsRead` counting frames), and whether the end frame was there.
 */
class FramesRead(val frames: List<Framing.Frame>, val ending: SegmentEnding) {
    val finished: Boolean get() = ending.finished
}

/** A reader reached the end frame, or stopped with a concrete diagnostic. */
sealed interface SegmentEnding {
    val finished: Boolean
    fun withIssue(accept: (SegmentMark) -> Unit): Boolean
    fun countRecords(count: Int): SegmentEnding

    data object Finalized : SegmentEnding {
        override val finished: Boolean = true
        override fun withIssue(accept: (SegmentMark) -> Unit): Boolean = false
        override fun countRecords(count: Int): SegmentEnding = this
    }
    data class Stopped(val issue: SegmentMark, override val finished: Boolean = false) : SegmentEnding {
        override fun withIssue(accept: (SegmentMark) -> Unit): Boolean { accept(issue); return true }
        override fun countRecords(count: Int): SegmentEnding = copy(issue = when (issue) {
            is SegmentMark.TruncatedTail -> issue.copy(recordsRead = count)
            is SegmentMark.Unfinished -> issue.copy(recordsRead = count)
            is SegmentMark.Corrupt -> issue.copy(recordsRead = count)
            else -> issue
        })
    }
}

/** Missing or malformed header bytes always represent a concrete failed read. */
fun SegmentEnding.beforeHeader(name: String, magicSize: Int): SegmentMark {
    var issue: SegmentMark = SegmentMark.Corrupt(name, magicSize, 0, "ended before the header")
    withIssue { issue = if (it is SegmentMark.Unfinished) SegmentMark.TruncatedTail(name, 0, 0) else it }
    return issue
}

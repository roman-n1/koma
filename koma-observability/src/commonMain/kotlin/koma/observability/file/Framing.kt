package koma.observability.file

import koma.core.ExperimentalKomaApi

/**
 * The framing every segment file of Koma shares: a magic, then frames of
 * `[i32 length][i32 crc32][payload]`, then an end frame of length 0 once the segment is
 * finished. The journal and the replay recordings put different payloads into the frames; the
 * damage they can suffer, a write cut short, a flipped byte, bytes after the end, is told apart
 * here, once.
 */
@ExperimentalKomaApi
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
            return FramesRead(emptyList(), SegmentMark.NotASegment(name, "no magic"), finished = false)
        }
        val reader = ByteReader(bytes, magic.size)
        val frames = mutableListOf<Frame>()
        while (true) {
            val offset = reader.position
            if (reader.remaining == 0) return FramesRead(frames, SegmentMark.Unfinished(name, frames.size), finished = false)
            if (reader.remaining < 8) return FramesRead(frames, SegmentMark.TruncatedTail(name, frames.size, reader.remaining), finished = false)
            val length = reader.i32()
            val crc = reader.i32()
            if (length == 0) {
                if (crc != 0) return FramesRead(frames, SegmentMark.Corrupt(name, offset, frames.size, "an end frame with a checksum"), finished = false)
                val trailing = reader.remaining
                val mark = if (trailing > 0) SegmentMark.Corrupt(name, reader.position, frames.size, "$trailing bytes after the end frame") else null
                return FramesRead(frames, mark, finished = true)
            }
            if (length < 0 || length > MAX_FRAME_BYTES) return FramesRead(frames, SegmentMark.Corrupt(name, offset, frames.size, "frame length $length"), finished = false)
            if (length > reader.remaining) return FramesRead(frames, SegmentMark.TruncatedTail(name, frames.size, 8 + reader.remaining), finished = false)
            val payload = ByteArray(length)
            reader.copyInto(reader.position, length, payload)
            reader.position += length
            if (Crc32.of(payload) != crc) return FramesRead(frames, SegmentMark.Corrupt(name, offset, frames.size, "checksum"), finished = false)
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
@ExperimentalKomaApi
class FramesRead(val frames: List<Framing.Frame>, val mark: SegmentMark?, val finished: Boolean)

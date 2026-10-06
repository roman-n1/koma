package koma.observability.file

import kotlinx.coroutines.sync.Mutex

/**
 * Where the segments of a journal live: a directory of the file system, a database, memory.
 * The format (framing, checksums, rotation, recovery) is the same over any of them; this is the
 * only thing the platform supplies. Segments are named by the format ([JournalFileFormat.segmentName]),
 * appended to by the sink and read whole by the reader; a segment of the sink's configured size
 * fits in memory.
 *
 * [FileSegmentStorage] is the file-system storage of the platform, [InMemorySegmentStorage] the
 * one for tests and for platforms without a file system.
 */
interface SegmentStorage {
    /** Every segment present, in any order. */
    fun list(): List<SegmentInfo>

    /** The whole segment; throws when it does not exist. */
    fun read(name: String): ByteArray

    /** Removes the segment; nothing happens when it does not exist. */
    fun delete(name: String)

    /** Opens the segment for appending, creating it when absent. Close it when done. */
    fun append(name: String): SegmentOutput
}

/**
 * An open segment being appended to. [flush] hands what was written to the platform (the OS, not
 * necessarily the disk); [close] flushes and releases the handle. Successful flush makes
 * complete frames visible to a fresh process on [FileSegmentStorage]. Buffered writes can be
 * lost when a process is killed. Neither flush nor close promises persistence through power
 * loss: the JVM implementation does not call `fsync`, and iOS uses `fflush`.
 */
interface SegmentOutput : AutoCloseable {
    fun write(bytes: ByteArray)

    fun flush()

    override fun close()
}

/**
 * A segment as the storage lists it.
 *
 * @property name The segment's name
 * @property size Its size in bytes
 * @property modified When it was last written, in milliseconds of the platform's clock: only
 * compared between segments of one storage, to prune the oldest sessions first
 */
data class SegmentInfo(val name: String, val size: Long, val modified: Long)

/**
 * The file-system storage of the platform: the segments are files of [directory], created when
 * absent. JVM and Android use `java.io`, iOS the POSIX file API; JS and Wasm have no file system
 * and throw at construction.
 */
expect class FileSegmentStorage(directory: String) : SegmentStorage {
    override fun list(): List<SegmentInfo>

    override fun read(name: String): ByteArray

    override fun delete(name: String)

    override fun append(name: String): SegmentOutput
}

/**
 * Segments in memory: for tests, and for platforms without a file system. [truncate] and
 * [corrupt] damage a segment the way a crash or a bad disk would, so recovery can be tested.
 */
class InMemorySegmentStorage : SegmentStorage {
    private val lock = Mutex()
    private val segments = linkedMapOf<String, ByteWriter>()
    private val modified = mutableMapOf<String, Long>()
    private var clock = 0L

    override fun list(): List<SegmentInfo> = locked { segments.map { (name, bytes) -> SegmentInfo(name, bytes.size.toLong(), modified.getValue(name)) } }

    override fun read(name: String): ByteArray = locked { requireNotNull(segments[name]) { "[Koma] No segment $name" }.toByteArray() }

    override fun delete(name: String) {
        locked {
            segments.remove(name)
            modified.remove(name)
        }
    }

    override fun append(name: String): SegmentOutput = object : SegmentOutput {
        override fun write(bytes: ByteArray) {
            locked {
                segments.getOrPut(name) { ByteWriter() }.raw(bytes)
                modified[name] = ++clock
            }
        }

        override fun flush() = Unit

        override fun close() = Unit
    }

    /** Cuts the segment to its first [size] bytes, as a crash in the middle of a write would. */
    fun truncate(name: String, size: Int) {
        locked {
            val bytes = requireNotNull(segments[name]) { "[Koma] No segment $name" }.toByteArray()
            segments[name] = ByteWriter().apply { raw(bytes.copyOf(minOf(size, bytes.size))) }
        }
    }

    /** Flips one bit of the byte at [offset], as a bad disk would. */
    fun corrupt(name: String, offset: Int) {
        locked {
            val bytes = requireNotNull(segments[name]) { "[Koma] No segment $name" }.toByteArray()
            bytes[offset] = (bytes[offset].toInt() xor 0x01).toByte()
            segments[name] = ByteWriter().apply { raw(bytes) }
        }
    }

    private inline fun <T> locked(block: () -> T): T {
        while (!lock.tryLock()) {
            // Spin: the holder copies a segment or appends to one.
        }
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}

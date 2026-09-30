package koma.observability.file

/**
 * A growable byte buffer with the primitive encodings of the journal file format: big-endian
 * integers, `u8` booleans and flags, strings as a byte length and UTF-8.
 */
internal class ByteWriter(initial: Int = 256) {
    private var buffer = ByteArray(initial)

    var size: Int = 0
        private set

    fun u8(value: Int) {
        ensure(1)
        buffer[size++] = value.toByte()
    }

    fun i32(value: Int) {
        ensure(4)
        buffer[size++] = (value ushr 24).toByte()
        buffer[size++] = (value ushr 16).toByte()
        buffer[size++] = (value ushr 8).toByte()
        buffer[size++] = value.toByte()
    }

    fun i64(value: Long) {
        i32((value ushr 32).toInt())
        i32(value.toInt())
    }

    fun bool(value: Boolean) = u8(if (value) 1 else 0)

    fun string(value: String) = bytes(value.encodeToByteArray())

    fun bytes(value: ByteArray) {
        i32(value.size)
        raw(value)
    }

    fun raw(value: ByteArray) {
        ensure(value.size)
        value.copyInto(buffer, size)
        size += value.size
    }

    fun <T> nullable(value: T?, write: (T) -> Unit) {
        bool(value != null)
        if (value != null) write(value)
    }

    fun <T> list(values: List<T>, write: (T) -> Unit) {
        i32(values.size)
        for (value in values) write(value)
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)

    private fun ensure(more: Int) {
        if (size + more <= buffer.size) return
        var capacity = buffer.size * 2
        while (capacity < size + more) capacity *= 2
        buffer = buffer.copyOf(capacity)
    }
}

/**
 * Reads what [ByteWriter] wrote; every read is bounds-checked and a read past [limit] throws
 * [Malformed], which the segment decoder turns into a mark rather than a crash.
 */
internal class ByteReader(private val bytes: ByteArray, var position: Int = 0, private val limit: Int = bytes.size) {
    class Malformed(message: String) : RuntimeException(message)

    val remaining: Int get() = limit - position

    fun u8(): Int {
        need(1)
        return bytes[position++].toInt() and 0xFF
    }

    fun i32(): Int {
        need(4)
        val value = ((bytes[position].toInt() and 0xFF) shl 24) or
            ((bytes[position + 1].toInt() and 0xFF) shl 16) or
            ((bytes[position + 2].toInt() and 0xFF) shl 8) or
            (bytes[position + 3].toInt() and 0xFF)
        position += 4
        return value
    }

    fun i64(): Long = (i32().toLong() shl 32) or (i32().toLong() and 0xFFFFFFFFL)

    fun bool(): Boolean = when (val value = u8()) {
        0 -> false
        1 -> true
        else -> throw Malformed("not a boolean: $value")
    }

    fun string(): String = bytes().decodeToString()

    fun bytes(): ByteArray {
        val length = i32()
        if (length < 0 || length > remaining) throw Malformed("length $length with $remaining bytes left")
        val value = bytes.copyOfRange(position, position + length)
        position += length
        return value
    }

    fun <T> nullable(read: () -> T): T? = if (bool()) read() else null

    fun <T> list(read: () -> T): List<T> {
        val count = i32()
        // Every element takes at least one byte: a larger count is corruption, not a large list.
        if (count < 0 || count > remaining) throw Malformed("list of $count with $remaining bytes left")
        return List(count) { read() }
    }

    fun expectEnd() {
        if (remaining != 0) throw Malformed("$remaining bytes left after the payload")
    }

    /** Copies [length] bytes from [start] into [into]; the caller has checked the bounds. */
    fun copyInto(start: Int, length: Int, into: ByteArray) {
        bytes.copyInto(into, 0, start, start + length)
    }

    private fun need(count: Int) {
        if (remaining < count) throw Malformed("needs $count bytes, $remaining left")
    }
}

/**
 * CRC-32 (IEEE 802.3, the one of zip and PNG), for the frames of a segment.
 */
internal object Crc32 {
    private val table = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
        c
    }

    fun of(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): Int {
        var crc = -1
        for (i in from until to) crc = table[(crc xor bytes[i].toInt()) and 0xFF] xor (crc ushr 8)
        return crc.inv()
    }
}

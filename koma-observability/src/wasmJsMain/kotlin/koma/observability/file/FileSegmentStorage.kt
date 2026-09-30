package koma.observability.file

import koma.core.ExperimentalKomaApi

/**
 * No file system here: use [InMemorySegmentStorage], or a [SegmentStorage] over the platform's own storage.
 */
@ExperimentalKomaApi
actual class FileSegmentStorage actual constructor(directory: String) : SegmentStorage {
    init {
        throw UnsupportedOperationException("[Koma] FileSegmentStorage needs a file system; this platform has none. Use InMemorySegmentStorage or a SegmentStorage of your own.")
    }

    actual override fun list(): List<SegmentInfo> = throw UnsupportedOperationException()

    actual override fun read(name: String): ByteArray = throw UnsupportedOperationException()

    actual override fun delete(name: String): Unit = throw UnsupportedOperationException()

    actual override fun append(name: String): SegmentOutput = throw UnsupportedOperationException()
}

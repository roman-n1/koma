package actron.observability.file

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Segments as files of [directory], created when absent.
 */
actual class FileSegmentStorage actual constructor(directory: String) : SegmentStorage {
    private val directory = File(directory)

    init {
        require(this.directory.isDirectory || this.directory.mkdirs()) { "[Actron] Cannot create directory $directory" }
    }

    actual override fun list(): List<SegmentInfo> =
        directory.listFiles()?.filter { file: File -> file.isFile }
            ?.map { file: File -> SegmentInfo(file.name, file.length(), file.lastModified()) } ?: emptyList()

    actual override fun read(name: String): ByteArray = File(directory, name).readBytes()

    actual override fun delete(name: String) {
        File(directory, name).delete()
    }

    actual override fun append(name: String): SegmentOutput = object : SegmentOutput {
        private val stream = BufferedOutputStream(FileOutputStream(File(directory, name), true))

        override fun write(bytes: ByteArray) = stream.write(bytes)

        override fun flush() = stream.flush()

        override fun close() = stream.close()
    }
}

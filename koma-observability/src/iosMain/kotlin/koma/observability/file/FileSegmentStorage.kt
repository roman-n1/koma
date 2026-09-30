@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)

package koma.observability.file

import koma.core.ExperimentalKomaApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.DT_REG
import platform.posix.EEXIST
import platform.posix.S_IRWXU
import platform.posix.closedir
import platform.posix.errno
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fwrite
import platform.posix.mkdir
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.remove
import platform.posix.stat

/**
 * Segments as files of [directory], created when absent, through the POSIX file API.
 */
@ExperimentalKomaApi
actual class FileSegmentStorage actual constructor(private val directory: String) : SegmentStorage {
    init {
        if (mkdir(directory, S_IRWXU.convert()) != 0) {
            require(errno == EEXIST) { "[Koma] Cannot create directory $directory (errno $errno)" }
        }
    }

    actual override fun list(): List<SegmentInfo> {
        val dir = opendir(directory) ?: return emptyList()
        try {
            val infos = mutableListOf<SegmentInfo>()
            while (true) {
                val entry = readdir(dir) ?: break
                if (entry.pointed.d_type.toInt() != DT_REG) continue
                val name = entry.pointed.d_name.toKString()
                memScoped {
                    val info = alloc<stat>()
                    if (stat(path(name), info.ptr) == 0) {
                        infos += SegmentInfo(name, info.st_size.toLong(), info.st_mtimespec.tv_sec.toLong() * 1000 + info.st_mtimespec.tv_nsec.toLong() / 1_000_000)
                    }
                }
            }
            return infos
        } finally {
            closedir(dir)
        }
    }

    actual override fun read(name: String): ByteArray {
        val file = fopen(path(name), "rb") ?: throw IllegalStateException("[Koma] Cannot open ${path(name)} (errno $errno)")
        try {
            val chunks = mutableListOf<ByteArray>()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = buffer.usePinned { fread(it.addressOf(0), 1u, buffer.size.convert(), file) }.toInt()
                if (read <= 0) break
                chunks += buffer.copyOf(read)
            }
            val total = chunks.sumOf { it.size }
            val bytes = ByteArray(total)
            var offset = 0
            for (chunk in chunks) {
                chunk.copyInto(bytes, offset)
                offset += chunk.size
            }
            return bytes
        } finally {
            fclose(file)
        }
    }

    actual override fun delete(name: String) {
        remove(path(name))
    }

    actual override fun append(name: String): SegmentOutput = object : SegmentOutput {
        private val file = fopen(path(name), "ab") ?: throw IllegalStateException("[Koma] Cannot open ${path(name)} for appending (errno $errno)")

        override fun write(bytes: ByteArray) {
            if (bytes.isEmpty()) return
            val written = bytes.usePinned { fwrite(it.addressOf(0), 1u, bytes.size.convert(), file) }.toInt()
            check(written == bytes.size) { "[Koma] Wrote $written of ${bytes.size} bytes to ${path(name)}" }
        }

        override fun flush() {
            fflush(file)
        }

        override fun close() {
            fclose(file)
        }
    }

    private fun path(name: String): String = "$directory/$name"
}

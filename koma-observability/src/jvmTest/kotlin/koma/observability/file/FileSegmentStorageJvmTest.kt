@file:OptIn(ExperimentalKomaApi::class)

package koma.observability.file

import koma.core.ExperimentalKomaApi
import koma.core.InputId
import koma.observability.ExecutionMode
import koma.observability.GroupSeq
import koma.observability.JOURNAL_FORMAT_VERSION
import koma.observability.JournalEntry
import koma.observability.JournalRecord
import koma.observability.MachineGroupId
import koma.observability.RuntimeSessionId
import koma.observability.StoreInstanceId
import koma.observability.StoreSeq
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The file-system storage on the JVM: segments are files, a second storage over the same
 * directory reads what the first wrote, a file cut short by a crash is recovered up to the last
 * whole frame, and pruning deletes files.
 */
class FileSegmentStorageJvmTest {

    @Test
    fun invalidDirectoryAndFileOperationsReportErrorsWithoutCreatingSegments() {
        val directory = Files.createTempDirectory("koma-file-errors").toFile()
        try {
            val storage = FileSegmentStorage(directory.path)
            storage.append("plain").close()
            assertFailsWith<IllegalArgumentException> { FileSegmentStorage(File(directory, "plain").path) }
            storage.delete("plain")
            Files.createDirectory(File(directory, "child").toPath())
            assertFailsWith<java.io.IOException> { storage.read("child") }
            assertFailsWith<java.io.IOException> { storage.read("missing") }
            assertFailsWith<java.io.IOException> { storage.append("child") }
            assertTrue(storage.list().isEmpty())
        } finally { directory.deleteRecursively() }
    }

    private val session = RuntimeSessionId("jvm")

    private fun record(seq: Long) = JournalRecord(
        JOURNAL_FORMAT_VERSION, session, MachineGroupId("g"), StoreInstanceId("s"), ExecutionMode.Live, GroupSeq(seq), StoreSeq(seq), seq.milliseconds,
        JournalEntry.ProcessingStarted(InputId(seq), seq),
    )

    @Test
    fun segmentsAreFilesOfTheDirectory_readableByAnotherStorage_andRecoveredAfterACut() = runTest {
        val directory = Files.createTempDirectory("koma-journal").toFile()
        try {
            val nested = File(directory, "journal/segments").path
            val sink = JournalFileSink(FileSegmentStorage(nested), JournalFileConfig(maxSegmentBytes = 300, maxSegments = 4, flushEveryRecords = 1))
            val records = (1L..30L).map { record(it) }
            for (record in records) sink.write(record)
            sink.flush()
            // Not closed: the process dies here, three bytes into the last frame.
            val active = File(nested, checkNotNull(sink.activeSegment))
            RandomAccessFile(active, "rw").use { it.setLength(it.length() - 3) }

            val files = JournalFiles(FileSegmentStorage(nested))
            val contents = files.read(session)

            assertEquals(listOf(session), files.sessions())
            assertTrue(File(nested).listFiles()!!.all { it.name.endsWith(JournalFileFormat.EXTENSION) })
            assertEquals(4, File(nested).listFiles()!!.size, "four segments kept")
            val marks = contents.marks
            assertIs<SegmentMark.MissingSegments>(marks[0])
            val tail = assertIs<SegmentMark.TruncatedTail>(marks[1])
            assertEquals(active.name, tail.segment)
            assertEquals(2, marks.size, marks.toString())
            val read = contents.records.map { it.groupSeq.value }
            assertEquals((read.first()..29L).toList(), read, "everything but the frame that was cut")

            assertEquals(listOf(File(nested, "jvm-000000.journal").name).filter { File(nested, it).exists() }, emptyList(), "segment 0 rotated away")
            assertTrue(files.prune(maxTotalBytes = 0).isEmpty(), "the newest session stays")
            val other = JournalFileSink(FileSegmentStorage(nested))
            other.write(record(1).copy(session = RuntimeSessionId("later")))
            other.close()
            assertEquals(4, JournalFiles(FileSegmentStorage(nested)).prune(maxTotalBytes = 0).size, "the older session's four files went")
            assertEquals(listOf(RuntimeSessionId("later")), JournalFiles(FileSegmentStorage(nested)).sessions())
        } finally {
            directory.deleteRecursively()
        }
    }
}

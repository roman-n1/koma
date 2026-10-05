@file:OptIn(ExperimentalKomaApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

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
import platform.Foundation.NSTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The POSIX storage on iOS: segments are files of a directory the storage creates, a second
 * storage over it reads them, sizes and deletion work.
 */
class FileSegmentStorageIosTest {

    @Test
    fun aRegularFileCannotBeUsedAsTheStorageDirectory() {
        val directory = NSTemporaryDirectory() + "koma-directory-" + Random.nextLong().toULong().toString(16)
        val storage = FileSegmentStorage(directory)
        try {
            storage.append("plain").close()
            assertFailsWith<IllegalArgumentException> { FileSegmentStorage("$directory/plain") }
        } finally {
            storage.delete("plain")
            platform.posix.remove(directory)
        }
    }

    @Test
    fun readingADirectoryReportsAnIoErrorInsteadOfReturningEmptyBytes() {
        val directory = NSTemporaryDirectory() + "koma-read-error-" + Random.nextLong().toULong().toString(16)
        val storage = FileSegmentStorage(directory)
        val child = "$directory/child"
        FileSegmentStorage(child)
        try {
            assertFailsWith<IllegalStateException> { storage.read("child") }
            assertFailsWith<IllegalStateException> { storage.read("missing") }
            assertFailsWith<IllegalStateException> { storage.append("child") }
            assertTrue(storage.list().isEmpty(), "failed operations created no segment")
        } finally {
            platform.posix.remove(child)
            platform.posix.remove(directory)
        }
    }

    private val session = RuntimeSessionId("ios")

    private fun record(seq: Long) = JournalRecord(
        JOURNAL_FORMAT_VERSION, session, MachineGroupId("g"), StoreInstanceId("s"), ExecutionMode.Live, GroupSeq(seq), StoreSeq(seq), seq.milliseconds,
        JournalEntry.ProcessingStarted(InputId(seq), seq),
    )

    @Test
    fun segmentsAreFilesOfTheDirectory_readableByAnotherStorage() = runTest {
        val directory = NSTemporaryDirectory() + "koma-journal-" + Random.nextLong().toULong().toString(16)
        val sink = JournalFileSink(FileSegmentStorage(directory), JournalFileConfig(maxSegmentBytes = 300, maxSegments = 3))
        val records = (1L..20L).map { record(it) }
        for (record in records) sink.write(record)
        sink.close()

        val storage = FileSegmentStorage(directory)
        val contents = JournalFiles(storage).read(session)

        assertEquals(3, storage.list().size, storage.list().toString())
        assertTrue(storage.list().all { it.size > 0 && it.name.endsWith(JournalFileFormat.EXTENSION) })
        val read = contents.records.map { it.groupSeq.value }
        assertEquals((read.first()..20L).toList(), read)
        assertTrue(contents.marks.single() is SegmentMark.MissingSegments, contents.marks.toString())
        for (info in storage.list()) storage.delete(info.name)
        assertTrue(storage.list().isEmpty())
    }
}

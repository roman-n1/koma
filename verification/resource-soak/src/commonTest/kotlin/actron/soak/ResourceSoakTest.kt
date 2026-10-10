package actron.soak

import actron.observability.JournalEntry
import actron.observability.JournalSink
import actron.observability.RecordingSession
import actron.observability.file.FileSegmentStorage
import actron.observability.file.JournalFileSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResourceSoakTest {
    @Test fun resourcesRemainBoundedAcrossStoreAndRecordingLifecycles() = runBlocking { runResourceSoak() }

    @Test fun timedOutRecordingDrainStillClosesTheRealFileAndWriterJob() = runBlocking {
        val directory = SoakPlatform.directory(999999)
        val writerOwner = SupervisorJob()
        val sink = JournalFileSink(FileSegmentStorage(directory))
        val opened = CompletableDeferred<Unit>()
        val session = RecordingSession(CoroutineScope(Dispatchers.Default + writerOwner), sinks = listOf(JournalSink {
            sink.write(it) // Real header/frame and file output are open before the stall.
            opened.complete(Unit)
            awaitCancellation()
        }))
        try {
            session.publish(JournalEntry.RecordingStopped)
            withTimeout(10_000) { opened.await() }
            assertNotNull(sink.activeSegment)
            assertFailsWith<TimeoutCancellationException> { closeRecordingResources(session, sink, writerOwner, timeoutMillis = 100) }
            assertTrue(writerOwner.isCompleted && writerOwner.children.none())
            assertNull(sink.activeSegment)
        } finally {
            writerOwner.cancelAndJoin()
            sink.close()
            SoakPlatform.removeDirectory(directory)
        }
    }
}

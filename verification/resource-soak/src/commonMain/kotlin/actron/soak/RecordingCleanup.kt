package actron.soak

import actron.observability.RecordingSession
import actron.observability.file.JournalFileSink
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** A failed/timed-out journal drain must still stop its writer and release the real file output. */
internal suspend fun closeRecordingResources(
    session: RecordingSession?,
    sink: JournalFileSink,
    writerOwner: Job,
    timeoutMillis: Long = 10_000,
) = withContext(NonCancellable) {
    try {
        withTimeout(timeoutMillis) { session?.close() }
    } finally {
        try {
            withTimeout(timeoutMillis) { writerOwner.cancelAndJoin() }
        } finally {
            sink.close()
        }
    }
}

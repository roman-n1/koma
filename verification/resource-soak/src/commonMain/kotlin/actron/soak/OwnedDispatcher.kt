package actron.soak

import kotlinx.coroutines.CoroutineDispatcher

/** The benchmark owns and terminates its workers before collecting allocator measurements. */
internal expect class OwnedDispatcher(workers: Int, name: String) {
    val dispatcher: CoroutineDispatcher
    val remainingWorkers: Int
    fun closeAndJoin()
}

internal suspend fun runControlledBatch(batch: Int): BatchResult {
    val stores = OwnedDispatcher(4, "soak-stores-$batch")
    var result: BatchResult
    val writer: OwnedDispatcher
    try {
        writer = OwnedDispatcher(1, "soak-writer-$batch")
        try {
            result = runBatch(batch, stores.dispatcher, writer.dispatcher)
        } finally {
            writer.closeAndJoin()
        }
    } finally {
        stores.closeAndJoin()
    }
    val workers = stores.remainingWorkers + writer.remainingWorkers
    check(workers == 0) { "Owned dispatcher workers remain after close: $workers" }
    return result.copy(ownedWorkersAfterClose = workers)
}

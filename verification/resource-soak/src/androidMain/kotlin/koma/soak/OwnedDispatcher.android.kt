package koma.soak

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

internal actual class OwnedDispatcher actual constructor(workers: Int, name: String) {
    private val threads = ConcurrentLinkedQueue<Thread>()
    private val executor = Executors.newFixedThreadPool(workers) { task ->
        Thread(task, name).also { threads.add(it) }
    }
    actual val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()
    actual val remainingWorkers: Int get() = threads.count { it.isAlive }
    actual fun closeAndJoin() {
        (dispatcher as kotlinx.coroutines.ExecutorCoroutineDispatcher).close()
        check(executor.awaitTermination(10, TimeUnit.SECONDS)) { "Owned executor did not terminate" }
        threads.forEach { it.join(10_000) }
        check(remainingWorkers == 0) { "Owned executor threads did not exit" }
    }
}

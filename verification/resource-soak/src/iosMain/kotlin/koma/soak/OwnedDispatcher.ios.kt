@file:OptIn(kotlin.ExperimentalStdlibApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class, kotlin.native.concurrent.ObsoleteWorkersApi::class)

package koma.soak

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlin.native.concurrent.Worker

internal actual class OwnedDispatcher actual constructor(workers: Int, private val name: String) {
    private val pool = newFixedThreadPoolContext(workers, name)
    actual val dispatcher: CoroutineDispatcher get() = pool
    actual val remainingWorkers: Int get() = Worker.activeWorkers.count { it.name.startsWith("$name-") }
    actual fun closeAndJoin() {
        // Native MultiWorkerDispatcher.close waits for every requestTermination().result.
        pool.close()
        check(remainingWorkers == 0) { "Owned Native workers did not terminate" }
    }
}

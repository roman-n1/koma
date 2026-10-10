package actron.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.Volatile
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A handler or transaction that is past its last suspension point when the Store is closed used
 * to commit anyway: state updated, saver called, plugins run after close(). Nothing commits after
 * close() now. JVM only: the handler spins on a flag to model a CPU-bound tail.
 */
class StoreCloseJvmTest {

    data class Box(val n: Int = 0) : State

    data object Bump : Action

    @Volatile
    private var release = false

    @Test
    fun nothingCommitsAfterClose() = runTest {
        val saved = mutableListOf<Box>()
        val entered = CompletableDeferred<Unit>()
        val store: Store<Box, Bump, Nothing> = Store(Box()) {
            coroutineContext(Dispatchers.Default)
            stateSaver(StateSaver(save = { synchronized(saved) { saved += it } }, restore = { null }))
            state<Box> {
                enter {
                    launch {
                        transaction {
                            entered.complete(Unit)
                            @Suppress("ControlFlowWithEmptyBody")
                            while (!release) {}
                            nextState { Box(1) }
                        }
                    }
                }
            }
        }

        withContext(Dispatchers.Default) {
            withTimeout(10_000) {
                store.start()
                entered.await()
                store.close()
                release = true
                delay(300)
            }
        }

        assertEquals(Box(0), store.currentState)
        assertEquals(emptyList(), saved)
    }
}

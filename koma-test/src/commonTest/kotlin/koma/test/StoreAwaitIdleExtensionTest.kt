package koma.test

import koma.core.Action
import koma.core.State
import koma.core.Store
import koma.core.StorePendingWork
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The koma-test wrappers: `awaitIdle` fails with what is pending, `pendingWork` reports without
 * waiting, and both pass once the launch ended.
 */
class StoreAwaitIdleExtensionTest {

    data object Idle : State

    data object Block : Action

    @Test
    fun awaitIdle_failsWithWhatIsPending_andPassesOnceTheLaunchEnded() = runTest {
        val gate = CompletableDeferred<Unit>()
        val store: Store<Idle, Block, Nothing> = Store(Idle) {
            coroutineContext(Dispatchers.Default)
            state<Idle> { action<Block> { launch { gate.await() } } }
        }
        store.dispatchAndAwait(Block)

        // The Store runs on a real dispatcher, so the waits do too (runTest's clock is virtual).
        withContext(Dispatchers.Default) {
            assertEquals(StorePendingWork(inputs = 0, launches = 1), store.pendingWork())
            val failure = assertFailsWith<IllegalStateException> { store.awaitIdle(timeout = 50.milliseconds) }
            assertTrue("0 input(s) pending, 1 launch(es) running" in failure.message.orEmpty(), failure.message)

            gate.complete(Unit)
            store.awaitIdle()

            assertEquals(StorePendingWork(0, 0), store.pendingWork())
        }
        store.close()
    }
}

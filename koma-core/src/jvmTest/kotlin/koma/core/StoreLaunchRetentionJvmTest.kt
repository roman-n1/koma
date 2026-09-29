package koma.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StoreLaunchRetentionJvmTest {
    private data object Active : State
    private data class Work(val lane: LaunchLane, val wait: CompletableDeferred<Unit>? = null) : Action

    @Test
    fun completedWorkDoesNotRetainPerRequestLanesInALongLivedState() = runTest {
        val release = CompletableDeferred<Unit>()
        val store = Store<Active, Work, Nothing>(Active, backgroundScope.coroutineContext) {
            state<Active> {
                action<Work> {
                    launch(control = LaunchControl.DropIfRunning(action.lane)) { action.wait?.await() }
                }
            }
        }
        try {
            repeat(1000) { store.dispatchAndAwaitForTest(Work(LaunchLane(), release)) }
            release.complete(Unit)
            runCurrent()

            // Completion, without another dispatch or state exit, must release every lane.
            assertEquals(0, trackedJobs(store).size)
        } finally {
            store.close()
        }
    }

    @Test
    fun anOldLaunchCompletingDoesNotUntrackItsReplacement() = runTest {
        val lane = LaunchLane()
        val store = Store<Active, Work, Nothing>(Active, backgroundScope.coroutineContext) {
            state<Active> {
                action<Work> { launch(control = LaunchControl.CancelPrevious(action.lane)) { awaitCancellation() } }
            }
        }
        try {
            store.dispatchAndAwaitForTest(Work(lane))
            runCurrent()
            store.dispatchAndAwaitForTest(Work(lane))
            runCurrent()

            assertEquals(1, trackedJobs(store).size)
            assertTrue(trackedJobs(store).values.single().isActive)
        } finally {
            store.close()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun trackedJobs(store: Store<*, *, *>): Map<Any, Job> {
        val runtimes = store.javaClass.superclass.getDeclaredField("stateRuntimes").apply { isAccessible = true }.get(store) as Map<*, *>
        val runtime = runtimes.values.single()!!
        return runtime.javaClass.getDeclaredField("actionLaunchJobs").apply { isAccessible = true }.get(runtime) as Map<Any, Job>
    }
}

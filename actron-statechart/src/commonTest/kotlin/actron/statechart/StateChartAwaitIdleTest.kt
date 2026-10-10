@file:OptIn(ExperimentalActronApi::class)

package actron.statechart

import actron.core.Action
import actron.core.ExperimentalActronApi
import actron.core.StorePendingWork
import actron.test.awaitIdle
import actron.test.dispatchAndAwait
import actron.test.pendingWork
import actron.test.startAndAwait
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A chart store's activities and timers are the chart's data, run by a subscribed task runner:
 * `awaitIdle` returns while an activity awaits its node's exit and while a timer waits for its delay.
 *
 * ```
 * [*] --> A
 * A --Go--> B           activity(A): awaitCancellation
 * B --after 10s--> A
 * ```
 */
class StateChartAwaitIdleTest {

    data object Go : Action

    private val a = StateId("A")
    private val b = StateId("B")
    private val chart = StateChartDefinition(a, listOf(AtomicState(a), AtomicState(b)), listOf(Transition(a, b, ActionMatcher.of<Go>("Go")), Transition(b, a, Trigger.After(10.seconds))))

    @Test
    fun awaitIdle_returnsWhileAnActivityRuns_andWhileATimerWaits() = runTest {
        var active = false
        val store = StateChartStore<Int, Go, Nothing>(chart, 0, Dispatchers.Default) {
            activity(a) { active = true; try { awaitCancellation() } finally { active = false } }
        }
        store.startAndAwait()

        withContext(Dispatchers.Default) {
            // The task runner is a subscription, so nothing waits for the activity to start; wait for it here, then the store must still be idle.
            withTimeout(2.seconds) { while (!active) delay(1) }

            store.awaitIdle(timeout = 2.seconds)

            assertTrue(store.currentState.isActive(a))
            assertTrue(active, "the activity runs while the store is idle: it is a subscription of the chart")
            store.dispatchAndAwait(Go)
            store.awaitIdle(timeout = 2.seconds)
            assertTrue(store.currentState.isActive(b))
            assertEquals(StorePendingWork(0, 0), store.pendingWork(), "the timer waits as data, not as a launch")
        }
        store.close()
    }
}

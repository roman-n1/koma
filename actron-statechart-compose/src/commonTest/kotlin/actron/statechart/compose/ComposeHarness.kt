package actron.statechart.compose

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.withRunningRecomposer
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withContext

/**
 * A composition without a UI, driven by the test's scheduler: a `Recomposer` on the test
 * dispatcher, frames sent by hand, an applier that draws nothing. What a `LaunchedEffect` does
 * is observable through the test dispatcher's queue: [pumpFrame] runs what is due, sends a
 * frame, runs what the frame scheduled.
 */
internal class ComposeHarness(
    private val scheduler: TestCoroutineScheduler,
    private val recomposer: Recomposer,
    private val frameClock: BroadcastFrameClock,
) {
    private var frameTimeNanos = 0L
    private var composition: Composition? = null

    /** Disposes the current composition, if any, and composes [content] anew. */
    fun compose(content: @Composable () -> Unit) {
        composition?.dispose()
        composition = Composition(NoOpApplier(), recomposer).also { it.setContent(content) }
    }

    /** Recomposes the current composition with [content]: the same composition, new lambdas. */
    fun recompose(content: @Composable () -> Unit) {
        checkNotNull(composition) { "compose first" }.setContent(content)
    }

    fun dispose() {
        composition?.dispose()
        composition = null
    }

    fun pumpFrame() {
        scheduler.runCurrent()
        frameTimeNanos += 16_000_000L
        frameClock.sendFrame(frameTimeNanos)
        scheduler.runCurrent()
    }
}

/** Runs [block] with a running recomposer on the test dispatcher; the composition is disposed after. */
internal suspend fun TestScope.withComposeHarness(block: suspend ComposeHarness.() -> Unit) {
    val frameClock = BroadcastFrameClock()
    withContext(frameClock) {
        withRunningRecomposer { recomposer ->
            val harness = ComposeHarness(testScheduler, recomposer, frameClock)
            try {
                harness.block()
            } finally {
                harness.dispose()
                harness.pumpFrame()
            }
        }
    }
}

private class NoOpApplier : AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(index: Int, instance: Unit) = Unit

    override fun insertBottomUp(index: Int, instance: Unit) = Unit

    override fun move(from: Int, to: Int, count: Int) = Unit

    override fun remove(index: Int, count: Int) = Unit

    override fun onClear() = Unit
}

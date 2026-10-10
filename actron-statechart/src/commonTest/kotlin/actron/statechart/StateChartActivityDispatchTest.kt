package actron.statechart

import actron.core.Action
import actron.core.ExperimentalActronApi
import actron.core.Plugin
import actron.test.dispatchAndAwait
import actron.test.startAndAwait
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalActronApi::class, ExperimentalCoroutinesApi::class)
class StateChartActivityDispatchTest {
    private sealed interface Input : Action {
        data object Move : Input
        data object Result : Input
    }
    private val a = StateId("A")
    private val b = StateId("B")
    private val done = StateId("Done")
    private val chart = StateChartDefinition(
        a,
        listOf(AtomicState(a), AtomicState(b), AtomicState(done)),
        listOf(
            Transition(a, b, ActionMatcher.of<Input.Move>("Move")),
            Transition(b, a, ActionMatcher.of<Input.Move>("Move")),
            Transition(a, done, ActionMatcher.of<Input.Result>("Result")),
            Transition(b, done, ActionMatcher.of<Input.Result>("Result")),
        ),
    )

    @Test
    fun anActivityActionQueuedDuringExitIsIgnoredAfterItsActivationEnds() = runTest {
        val enteringB = CompletableDeferred<Unit>()
        val finishEntry = CompletableDeferred<Unit>()
        lateinit var oldScope: ChartLaunchScope<Unit, Input, Nothing>
        val seen = mutableListOf<Input>()
        val store = StateChartStore<Unit, Input, Nothing>(chart, Unit, backgroundScope.coroutineContext) {
            activity(a) { oldScope = this }
            onEnter(b) { enteringB.complete(Unit); finishEntry.await() }
            store { plugin(Plugin(onAction = { _, action -> seen += action })) }
        }
        try {
            store.startAndAwait()
            runCurrent()
            store.dispatch(Input.Move)
            enteringB.await()
            assertTrue(oldScope.isActive)
            oldScope.dispatch(Input.Result)
            finishEntry.complete(Unit)
            runCurrent()

            assertEquals(listOf(b), store.currentState.activeLeaves(chart))
            assertEquals(listOf<Input>(Input.Move), seen)
        } finally {
            store.close()
        }
    }

    @Test
    fun aPreviousActivationCannotDispatchIntoAReenteredNode() = runTest {
        val scopes = mutableListOf<ChartLaunchScope<Unit, Input, Nothing>>()
        val store = StateChartStore<Unit, Input, Nothing>(chart, Unit, backgroundScope.coroutineContext) {
            activity(a) { scopes += this }
        }
        try {
            store.startAndAwait()
            runCurrent()
            store.dispatchAndAwait(Input.Move)
            store.dispatchAndAwait(Input.Move)
            runCurrent()
            assertEquals(2, scopes.size)
            scopes.first().dispatch(Input.Result)
            runCurrent()
            assertEquals(listOf(a), store.currentState.activeLeaves(chart))

            scopes.last().dispatch(Input.Result)
            runCurrent()
            assertEquals(listOf(done), store.currentState.activeLeaves(chart))
        } finally {
            store.close()
        }
    }
}

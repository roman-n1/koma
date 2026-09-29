package koma.statechart

import koma.core.Action
import koma.core.Event
import koma.core.ExperimentalKomaApi
import koma.core.Plugin
import koma.test.dispatchAndAwait
import koma.test.startAndAwait
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalKomaApi::class, ExperimentalCoroutinesApi::class)
class StateChartActivityEventTest {
    private data object Move : Action
    private data object Output : Event

    @Test
    fun expiredActivityCannotEmitIntoTheNextActivation() = runTest {
        val a = StateId("A")
        val b = StateId("B")
        val go = ActionMatcher.of<Move>("Move")
        val chart = StateChartDefinition(a, listOf(AtomicState(a), AtomicState(b)), listOf(Transition(a, b, go), Transition(b, a, go)))
        val scopes = mutableListOf<ChartLaunchScope<Unit, Move, Output>>()
        val events = mutableListOf<Output>()
        val store = StateChartStore<Unit, Move, Output>(chart, Unit, backgroundScope.coroutineContext) {
            activity(a) { scopes += this }
            store { plugin(Plugin(onEvent = { _, event -> events += event })) }
        }
        try {
            store.startAndAwait()
            runCurrent()
            store.dispatchAndAwait(Move)
            store.dispatchAndAwait(Move)
            runCurrent()
            scopes.first().event(Output)
            assertEquals(emptyList(), events)
            scopes.last().event(Output)
            assertEquals(listOf(Output), events)
        } finally {
            store.close()
        }
    }
}

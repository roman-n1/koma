package koma.statechart

import koma.core.Action
import koma.core.Event
import koma.core.ExperimentalKomaApi
import koma.core.Plugin
import koma.test.dispatchAndAwait
import koma.test.startAndAwait
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalKomaApi::class, ExperimentalCoroutinesApi::class)
class StateChartInstanceIsolationTest {
    private data object Refresh : Action
    private data class Output(val value: Int) : Event

    @Test
    fun sharedDefinitionKeepsContextEventsActivitiesAndTimersPerStore() = runTest {
        val active = StateId("Active")
        val done = StateId("Done")
        val definition = StateChartDefinition(active, listOf(AtomicState(active), AtomicState(done)), listOf(
            Transition(active, active, ActionMatcher.of<Refresh>("Refresh"), effect = "increment"),
            Transition(active, done, Trigger.After(100.milliseconds)),
        ))
        val scopes = mutableMapOf<String, ChartLaunchScope<Int, Refresh, Output>>()
        val events = mutableMapOf("left" to mutableListOf<Output>(), "right" to mutableListOf())
        fun tab(id: String) = StateChartStore<Int, Refresh, Output>(definition, 0, backgroundScope.coroutineContext) {
            effect("increment") { count, _ -> count + 1 }
            activity(active) { scopes[id] = this }
            onEnter(done) { event(Output(context)) }
            store { plugin(Plugin(onEvent = { _, event -> events.getValue(id) += event })) }
        }
        val left = tab("left")
        val right = tab("right")
        try {
            left.startAndAwait()
            right.startAndAwait()
            runCurrent()
            advanceTimeBy(50)
            left.dispatchAndAwait(Refresh)
            runCurrent()
            scopes.getValue("left").updateContext { it + 10 }
            scopes.getValue("left").event(Output(11))
            assertEquals(11, left.currentState.context)
            assertEquals(0, right.currentState.context)
            assertEquals(listOf(Output(11)), events.getValue("left"))
            assertEquals(emptyList(), events.getValue("right"))
            left.close()
            scopes.getValue("left").event(Output(-1))
            scopes.getValue("right").updateContext { it + 2 }
            advanceTimeBy(50)
            runCurrent()
            assertEquals(setOf(active), left.currentState.configuration.active)
            assertEquals(setOf(done), right.currentState.configuration.active)
            assertEquals(2, right.currentState.context)
            assertEquals(listOf(Output(11)), events.getValue("left"))
            assertEquals(listOf(Output(2)), events.getValue("right"))
        } finally {
            left.close()
            right.close()
        }
    }
}

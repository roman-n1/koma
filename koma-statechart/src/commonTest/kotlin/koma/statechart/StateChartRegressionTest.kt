package koma.statechart

import koma.core.Action
import koma.core.Event
import koma.core.ExperimentalKomaApi
import koma.core.StateSaver
import koma.test.dispatchAndAwait
import koma.test.startAndAwait
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Regression tests for issues found in review of the statechart module.
 *
 * ```
 * [*] --> A
 * A --Go--> B
 * ```
 */
@OptIn(ExperimentalKomaApi::class, ExperimentalCoroutinesApi::class)
class StateChartRegressionTest {

    sealed interface ChartAction : Action {
        data object Go : ChartAction
    }

    sealed interface ChartEvent : Event

    private val a = StateId("A")
    private val b = StateId("B")
    private val go = ActionMatcher.of<ChartAction.Go>("Go")

    private val chart = StateChartDefinition(
        initial = a,
        states = listOf(AtomicState(a), AtomicState(b)),
        transitions = listOf(Transition(a, b, go)),
    )

    /**
     * Work launched from `onEnter` is sent to the work loop only after every hook of the step has
     * succeeded, so it never starts for a failed step, even when the hook suspends before failing.
     */
    @Test
    fun workLaunchedByAFailedStepNeverStartsEvenIfTheHookSuspends() = runTest {
        var workRan = false
        val store = StateChartStore<Unit, ChartAction, ChartEvent>(chart, Unit, backgroundScope.coroutineContext) {
            onEnter(b) {
                launch { workRan = true }
                delay(1.seconds)
                error("boom")
            }
            store { state<ChartState<Unit>> { recover<IllegalStateException> { } } }
        }

        store.dispatchAndAwait(ChartAction.Go)
        advanceTimeBy(5.seconds)
        runCurrent()

        assertEquals(listOf(a), store.currentState.activeLeaves(chart))
        assertFalse(workRan)
        store.close()
    }

    @Test
    fun workLaunchedByASuccessfulStepStartsAfterTheStep() = runTest {
        val log = mutableListOf<String>()
        val store = StateChartStore<Unit, ChartAction, ChartEvent>(chart, Unit, backgroundScope.coroutineContext) {
            onEnter(b) {
                launch { log += "work" }
                delay(1.seconds)
                log += "hook done"
            }
        }

        store.dispatchAndAwait(ChartAction.Go)
        advanceTimeBy(5.seconds)
        runCurrent()

        assertEquals(listOf("hook done", "work"), log)
        store.close()
    }

    /**
     * A transition to an undeclared state (a typo) would exit the whole configuration and leave the
     * chart in a state no transition leaves, so building a Store rejects it, like an undeclared
     * source, an undeclared initial state or a duplicate id. The pure runtime still steps such
     * charts, as its property tests require.
     */
    @Test
    fun chartsReferringToUndeclaredOrDuplicateStatesAreRejected() {
        val typo = chart.copy(transitions = listOf(Transition(a, StateId("Typo"), go)))
        val unknownSource = chart.copy(transitions = listOf(Transition(StateId("Nowhere"), b, go)))
        val unknownInitial = chart.copy(initial = StateId("Nowhere"))
        val duplicate = chart.copy(states = chart.states + AtomicState(b))

        for (broken in listOf(typo, unknownSource, unknownInitial, duplicate)) {
            assertFailsWith<IllegalArgumentException> { StateChartStore<Unit, ChartAction, ChartEvent>(broken, Unit) }
        }
        val error = assertFailsWith<IllegalArgumentException> { StateChartStore<Unit, ChartAction, ChartEvent>(typo, Unit) }
        assertTrue("Typo" in error.message.orEmpty(), error.message)
    }

    /**
     * Timers without a positive delay that restart themselves, directly or through each other,
     * would fire forever without time passing, so building rejects them. A single zero-delay
     * timer that does not loop is still allowed.
     */
    @Test
    fun zeroDelayTimerLoopsAreRejected() {
        val selfLoop = chart.copy(transitions = listOf(Transition(a, a, Trigger.After(Duration.ZERO))))
        val cycle = chart.copy(
            transitions = listOf(
                Transition(a, b, Trigger.After(Duration.ZERO)),
                Transition(b, a, Trigger.After((-1).milliseconds)),
            ),
        )
        val oneWay = chart.copy(transitions = listOf(Transition(a, b, Trigger.After(Duration.ZERO))))
        val slowLoop = chart.copy(transitions = listOf(Transition(a, a, Trigger.After(1.seconds))))

        val error = assertFailsWith<IllegalArgumentException> { StateChartStore<Unit, ChartAction, ChartEvent>(selfLoop, Unit) }
        assertTrue("A --after 0s--> A" in error.message.orEmpty(), error.message)
        assertFailsWith<IllegalArgumentException> { StateChartStore<Unit, ChartAction, ChartEvent>(cycle, Unit) }
        StateChartStore<Unit, ChartAction, ChartEvent>(oneWay, Unit).close()
        StateChartStore<Unit, ChartAction, ChartEvent>(slowLoop, Unit).close()
    }

    /**
     * A configuration restored by a [StateSaver] that the chart cannot produce, for example one
     * saved by an older app version naming a state that no longer exists, starts over from the
     * initial configuration and keeps the restored context.
     */
    @Test
    fun aRestoredConfigurationWithAnUndeclaredStateFallsBackToTheInitialOne() = runTest {
        val entered = mutableListOf<StateId>()
        val saved = ChartState(StateConfiguration(active = setOf(StateId("Removed"))), context = 42)
        val store = StateChartStore<Int, ChartAction, ChartEvent>(chart, 0, backgroundScope.coroutineContext) {
            onEnter(a) { entered += node }
            store { stateSaver(StateSaver(save = {}, restore = { saved })) }
        }

        store.startAndAwait()
        runCurrent()
        assertEquals(listOf(a), store.currentState.activeLeaves(chart))
        assertEquals(42, store.currentState.context)
        assertEquals(listOf(a), entered, "the initial configuration is entered as on a fresh start")

        store.dispatchAndAwait(ChartAction.Go)
        runCurrent()
        assertEquals(listOf(b), store.currentState.activeLeaves(chart))
        store.close()
    }

    @Test
    fun aConsistentRestoredConfigurationIsKept() = runTest {
        val entered = mutableListOf<StateId>()
        val saved = ChartState(StateConfiguration(active = setOf(b)), context = 7)
        val store = StateChartStore<Int, ChartAction, ChartEvent>(chart, 0, backgroundScope.coroutineContext) {
            onEnter(a) { entered += node }
            onEnter(b) { entered += node }
            store { stateSaver(StateSaver(save = {}, restore = { saved })) }
        }

        store.startAndAwait()
        runCurrent()

        assertEquals(saved, store.currentState)
        assertEquals(emptyList(), entered, "a restored configuration is not entered again")
        store.close()
    }

    /**
     * Line breaks and `;` in labels would split a Mermaid statement, so they are written as a
     * space and `,`.
     */
    @Test
    fun mermaidLabelsStayOnOneLine() {
        val guarded = chart.copy(
            transitions = listOf(Transition(a, b, go, guard = "online\nand ready", effect = "log; count")),
        )

        val diagram = guarded.toMermaid()

        assertTrue(diagram.lines().drop(1).all { it.startsWith("    ") }, diagram)
        assertTrue("A --> B : Go [online and ready] / log, count" in diagram, diagram)
    }
}

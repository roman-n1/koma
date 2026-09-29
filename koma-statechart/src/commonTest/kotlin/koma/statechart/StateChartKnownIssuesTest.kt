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
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Reproductions of known issues in the statechart module. Each test describes the expected
 * behavior and is ignored until the issue is fixed; remove `@Ignore` together with the fix.
 *
 * ```
 * [*] --> A
 * A --Go--> B
 * ```
 */
@OptIn(ExperimentalKomaApi::class, ExperimentalCoroutinesApi::class)
class StateChartKnownIssuesTest {

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
     * Known issue: `launch {}` from `onEnter` sends the work to the work loop right away. When the
     * hook suspends before it fails, the loop starts the work while the activation is still
     * active, although [ChartEnterScope.launch] promises that work of a failed step never starts.
     */
    @Ignore
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

    /**
     * Known issue: the runtime rejects only hierarchy and history issues. A transition to an
     * undeclared state (a typo) builds fine, and taking it exits the whole configuration and
     * leaves the chart in a state no transition leaves.
     */
    @Ignore
    @Test
    fun aTransitionToAnUndeclaredStateIsRejected() {
        val typo = StateChartDefinition(
            initial = a,
            states = listOf(AtomicState(a)),
            transitions = listOf(Transition(a, StateId("Typo"), go)),
        )

        assertFailsWith<IllegalArgumentException> { StateChartStore<Unit, ChartAction, ChartEvent>(typo, Unit) }
    }

    /**
     * Known issue: a timer with a zero delay that re-enters its own source only produces a
     * validation warning; in the Store it fires, commits and re-arms itself forever.
     */
    @Ignore
    @Test
    fun aZeroDelaySelfLoopTimerIsRejected() {
        val loop = StateChartDefinition(
            initial = a,
            states = listOf(AtomicState(a)),
            transitions = listOf(Transition(a, a, Trigger.After(Duration.ZERO))),
        )

        assertFailsWith<IllegalArgumentException> { StateChartStore<Unit, ChartAction, ChartEvent>(loop, Unit) }
    }

    /**
     * Known issue: a configuration restored by a [StateSaver] is not checked against the chart.
     * A snapshot saved by an older app version that names a removed state is taken as is, and no
     * transition ever leaves it.
     */
    @Ignore
    @Test
    fun aRestoredConfigurationWithAnUndeclaredStateFallsBackToTheInitialOne() = runTest {
        val saved = ChartState(StateConfiguration(active = setOf(StateId("Removed"))), Unit)
        val store = StateChartStore<Unit, ChartAction, ChartEvent>(chart, Unit, backgroundScope.coroutineContext) {
            store { stateSaver(StateSaver(save = {}, restore = { saved })) }
        }

        store.startAndAwait()
        runCurrent()
        store.dispatchAndAwait(ChartAction.Go)
        runCurrent()

        assertEquals(listOf(b), store.currentState.activeLeaves(chart))
        store.close()
    }

    /**
     * Known issue: action names, guards, effects and ids are written into Mermaid labels as is,
     * so a line break splits the diagram.
     */
    @Ignore
    @Test
    fun mermaidLabelsStayOnOneLine() {
        val guarded = chart.copy(transitions = listOf(Transition(a, b, go, guard = "online\nand ready")))

        val diagram = guarded.toMermaid()

        assertTrue(diagram.lines().drop(1).all { it.isBlank() || it.startsWith("    ") }, diagram)
    }
}

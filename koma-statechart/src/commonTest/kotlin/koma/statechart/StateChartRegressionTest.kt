package koma.statechart

import koma.core.Action
import koma.core.Event
import koma.core.ExperimentalKomaApi
import koma.core.StateSaver
import koma.test.dispatchAndAwait
import koma.test.startAndAwait
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
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

    /**
     * A zero-delay timer restarts itself not only through a plain self-loop: a transition from one
     * region of a parallel state into another re-enters the whole parallel state (so the timer's
     * source again), and a transition into a history state restores its source. Both must be
     * rejected, or the Store would fire the timer forever without time passing.
     *
     * ```
     * P (parallel) { R1 { [*] --> a; a; b }  R2 { [*] --> c; c; d } }   a --after 0--> d
     * C { [*] --> c1; c1; c2; H }                                        c1 --after 0--> H
     * ```
     */
    @Test
    fun zeroDelayTimerLoopsThroughParallelRegionsAndHistoryAreRejected() {
        val p = StateId("P")
        val r1 = StateId("R1")
        val r2 = StateId("R2")
        val c = StateId("c")
        val d = StateId("d")
        val throughParallel = StateChartDefinition(
            initial = p,
            states = listOf(
                ParallelState(p),
                CompoundState(r1, initial = a, parent = p), AtomicState(a, r1), AtomicState(b, r1),
                CompoundState(r2, initial = c, parent = p), AtomicState(c, r2), AtomicState(d, r2),
            ),
            transitions = listOf(Transition(a, d, Trigger.After(Duration.ZERO))),
        )
        val container = StateId("C")
        val c1 = StateId("c1")
        val c2 = StateId("c2")
        val h = StateId("H")
        val throughHistory = StateChartDefinition(
            initial = container,
            states = listOf(CompoundState(container, initial = c1), AtomicState(c1, container), AtomicState(c2, container), HistoryState(h, parent = container)),
            transitions = listOf(Transition(c1, h, Trigger.After(Duration.ZERO))),
        )

        assertEquals(listOf(throughParallel.transitions), throughParallel.instantTimerCycles())
        assertEquals(listOf(throughHistory.transitions), throughHistory.instantTimerCycles())
        assertFailsWith<IllegalArgumentException> { StateChartStore<Unit, ChartAction, ChartEvent>(throughParallel, Unit) }
        assertFailsWith<IllegalArgumentException> { StateChartStore<Unit, ChartAction, ChartEvent>(throughHistory, Unit) }
    }

    /**
     * Entering a compound state enters only its initial child, so a zero-delay timer of another
     * child is not started by it; such a chart is valid.
     *
     * ```
     * [*] --> x;  x --after 0--> C;  C { [*] --> c1; c1 --Go--> c2 };  c2 --after 0--> x
     * ```
     */
    @Test
    fun aZeroDelayTimerIntoACompoundStateDoesNotStartTheTimersOfItsOtherChildren() {
        val x = StateId("x")
        val container = StateId("C")
        val c1 = StateId("c1")
        val c2 = StateId("c2")
        val chart = StateChartDefinition(
            initial = x,
            states = listOf(AtomicState(x), CompoundState(container, initial = c1), AtomicState(c1, container), AtomicState(c2, container)),
            transitions = listOf(
                Transition(x, container, Trigger.After(Duration.ZERO)),
                Transition(c1, c2, go),
                Transition(c2, x, Trigger.After(Duration.ZERO)),
            ),
        )

        assertEquals(emptyList(), chart.instantTimerCycles())
        StateChartStore<Unit, ChartAction, ChartEvent>(chart, Unit).close()
    }

    /**
     * When the step a timer fires fails (here `onEnter` of its target throws), the chart stays
     * where it is and the error reaches `recover {}`. The timer is spent, so it is removed from
     * the running timers instead of being listed as running while it never fires again.
     *
     * ```
     * A --after 1s--> B (enter throws) --recover--> A, no running timer
     * ```
     */
    @Test
    fun aTimerWhoseStepFailsIsDroppedAndTheErrorIsRecovered() = runTest {
        var attempts = 0
        var recovered = 0
        val timed = chart.copy(transitions = chart.transitions + Transition(a, b, Trigger.After(1.seconds)))
        val store = StateChartStore<Unit, ChartAction, ChartEvent>(timed, Unit, backgroundScope.coroutineContext) {
            onEnter(b) { if (++attempts == 1) error("boom") }
            store { state<ChartState<Unit>> { recover<IllegalStateException> { recovered++ } } }
        }

        store.startAndAwait()
        advanceTimeBy(1.5.seconds)
        runCurrent()

        assertEquals(listOf(a), store.currentState.activeLeaves(timed))
        assertEquals(1, attempts)
        assertEquals(1, recovered)
        assertEquals(emptyMap(), store.currentState.timers.running)

        // The chart still works: the action transition enters B.
        store.dispatchAndAwait(ChartAction.Go)
        assertEquals(listOf(b), store.currentState.activeLeaves(timed))
        store.close()
    }

    /**
     * When an enter hook fails during a fresh start there is no earlier configuration to stay in:
     * the Store is in the initial one, so its activities run and its timers fire; only the failed
     * hooks' context changes and launches are dropped. The error still reaches `recover {}`.
     *
     * ```
     * Root { [*] --> A (enter throws); A --Go--> B }  Root --after 5s--> Other
     * ```
     */
    @Test
    fun aFailedFreshStartStillRunsTheActivitiesAndTimersOfTheInitialConfiguration() = runTest {
        val root = StateId("Root")
        val other = StateId("Other")
        val chart = StateChartDefinition(
            initial = root,
            states = listOf(CompoundState(root, initial = a), AtomicState(a, root), AtomicState(b, root), AtomicState(other)),
            transitions = listOf(Transition(a, b, go), Transition(root, other, Trigger.After(5.seconds))),
        )
        var rootActivityRan = false
        var recovered = 0
        val store = StateChartStore<Int, ChartAction, ChartEvent>(chart, 0, backgroundScope.coroutineContext) {
            activity(root) { rootActivityRan = true }
            onEnter(a) {
                context = 42
                error("boom")
            }
            store { state<ChartState<Int>> { recover<IllegalStateException> { recovered++ } } }
        }

        store.startAndAwait()
        runCurrent()
        assertEquals(1, recovered)
        assertEquals(0, store.currentState.context)
        assertTrue(rootActivityRan)

        store.dispatchAndAwait(ChartAction.Go)
        assertEquals(listOf(b), store.currentState.activeLeaves(chart))

        advanceTimeBy(10.seconds)
        runCurrent()
        assertEquals(listOf(other), store.currentState.activeLeaves(chart))
        store.close()
    }

    /**
     * A failing enter hook of an OUTER node on a fresh start must not leave the inner nodes
     * without activation: their activities run and their timers fire, and the hook's own error
     * (not a bookkeeping error) reaches `recover {}`.
     *
     * ```
     * Root (enter throws) { [*] --> Leaf }   Leaf --after 5s--> Other
     * ```
     */
    @Test
    fun aFailedOuterEnterHookOnAFreshStart_keepsTheInnerNodesRunning() = runTest {
        val root = StateId("Root")
        val leaf = StateId("Leaf")
        val other = StateId("Other")
        val chart = StateChartDefinition(
            initial = root,
            states = listOf(CompoundState(root, initial = leaf), AtomicState(leaf, root), AtomicState(other)),
            transitions = listOf(Transition(leaf, other, Trigger.After(5.seconds))),
        )
        var leafActivityRuns = 0
        val recovered = mutableListOf<String>()
        val store = StateChartStore<Unit, ChartAction, ChartEvent>(chart, Unit, backgroundScope.coroutineContext) {
            onEnter(root) { error("boom") }
            activity(leaf) { leafActivityRuns++ }
            store { state<ChartState<Unit>> { recover<Exception> { recovered += error.message.orEmpty() } } }
        }

        store.startAndAwait()
        runCurrent()
        assertEquals(listOf("boom"), recovered)
        assertEquals(1, leafActivityRuns)

        advanceTimeBy(6.seconds)
        runCurrent()
        assertEquals(listOf(other), store.currentState.activeLeaves(chart))
        assertEquals(emptyMap(), store.currentState.timers.running)
        store.close()
    }

    /**
     * An expired `withTimeout {}` in a hook run by a timer firing is a failed step like any other
     * exception: the timer is spent and removed, the error reaches `recover {}`. In an activity it
     * is reported too, instead of ending the activity silently.
     */
    @Test
    fun anExpiredWithTimeout_inATimerStepOrAnActivity_isAFailure() = runTest {
        val timed = chart.copy(transitions = chart.transitions + Transition(a, b, Trigger.After(1.seconds)))
        val recovered = mutableListOf<String>()
        val store = StateChartStore<Unit, ChartAction, ChartEvent>(timed, Unit, backgroundScope.coroutineContext) {
            onEnter(b) { withTimeout(1.milliseconds) { delay(1.seconds) } }
            activity(a) { withTimeout(1.milliseconds) { delay(1.seconds) } }
            store { state<ChartState<Unit>> { recover<Exception> { recovered += error::class.simpleName.orEmpty() } } }
        }

        store.startAndAwait()
        advanceTimeBy(1.5.seconds)
        runCurrent()

        assertEquals(listOf(a), store.currentState.activeLeaves(timed))
        assertEquals(emptyMap(), store.currentState.timers.running)
        assertEquals(listOf("TimeoutCancellationException", "TimeoutCancellationException"), recovered)
        store.close()
    }

    /**
     * `updateContext` returns `false` when its transform throws (the error reaches `recover {}`),
     * `isActive` turns `false` once the Store is closed, and `launch {}` called after the enter
     * hook returned is an error rather than silently dropped work.
     */
    @Test
    fun launchScope_reportsWhatReallyHappened() = runTest {
        val results = mutableListOf<Boolean>()
        val recovered = mutableListOf<String>()
        var activeAfterClose: Boolean? = null
        lateinit var leakedScope: ChartEnterScope<Int, ChartAction, ChartEvent>
        val closed = kotlinx.coroutines.CompletableDeferred<Unit>()
        val store = StateChartStore<Int, ChartAction, ChartEvent>(chart, 0, backgroundScope.coroutineContext) {
            onEnter(a) { leakedScope = this }
            activity(a) {
                results += updateContext { throw IllegalStateException("transform failed") }
                results += updateContext { it + 1 }
                try {
                    leakedScope.launch { }
                } catch (e: IllegalStateException) {
                    recovered += "late launch: " + (e.message?.contains("after the onEnter hook") ?: false)
                }
                // close() cancels the activity; a non-suspending loop would keep reading isActive.
                try {
                    closed.await()
                } finally {
                    activeAfterClose = isActive
                }
            }
            store { state<ChartState<Int>> { recover<IllegalStateException> { recovered += error.message.orEmpty() } } }
        }

        store.startAndAwait()
        runCurrent()
        assertEquals(listOf(false, true), results)
        assertEquals(1, store.currentState.context)
        assertEquals(listOf("transform failed", "late launch: true"), recovered)

        store.close()
        closed.complete(Unit)
        runCurrent()
        assertEquals(false, activeAfterClose)
    }

    /**
     * An expired `withTimeout {}` in an initial enter hook is a failed hook, not the Store being
     * cancelled: the initial configuration keeps its activities and timers, as for any other
     * exception.
     */
    @Test
    fun anExpiredWithTimeout_inAnInitialEnterHook_keepsActivitiesAndTimersRunning() = runTest {
        val timed = chart.copy(transitions = listOf(Transition(a, b, Trigger.After(1.seconds))))
        var activityRan = false
        val recovered = mutableListOf<String>()
        val store = StateChartStore<Unit, ChartAction, ChartEvent>(timed, Unit, backgroundScope.coroutineContext) {
            onEnter(a) { withTimeout(10.milliseconds) { delay(1.seconds) } }
            activity(a) { activityRan = true }
            store { state<ChartState<Unit>> { recover<Exception> { recovered += error::class.simpleName.orEmpty() } } }
        }

        store.startAndAwait()
        runCurrent()
        assertTrue(activityRan)
        assertEquals(listOf("TimeoutCancellationException"), recovered)

        advanceTimeBy(1.5.seconds)
        runCurrent()
        assertEquals(listOf(b), store.currentState.activeLeaves(timed))
        store.close()
    }

    /**
     * Chart hook and launch scopes are Store scopes, so extensions declared on `StoreScope`, such
     * as `koma-message`'s `message()`, work from them.
     */
    @Test
    fun chartScopes_areStoreScopes() = runTest {
        val scopes = mutableListOf<Any>()
        val store = StateChartStore<Unit, ChartAction, ChartEvent>(chart, Unit, backgroundScope.coroutineContext) {
            onEnter(a) { scopes += this }
            onExit(a) { scopes += this }
            activity(a) { scopes += this }
        }

        store.startAndAwait()
        runCurrent() // the activity of A runs
        store.dispatchAndAwait(ChartAction.Go)
        runCurrent()

        assertEquals(3, scopes.size)
        assertTrue(scopes.all { it is koma.core.StoreScope })
        store.close()
    }
}

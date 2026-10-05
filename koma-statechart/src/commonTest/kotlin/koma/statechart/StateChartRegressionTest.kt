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
import kotlin.test.assertIs
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
            store { recover<IllegalStateException> { } }
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
            store { recover<IllegalStateException> { recovered++ } }
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
            store { recover<IllegalStateException> { recovered++ } }
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
            store { recover<Exception> { recovered += error.message.orEmpty() } }
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
            store { recover<Exception> { recovered += error::class.simpleName.orEmpty() } }
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
            store { recover<IllegalStateException> { recovered += error.message.orEmpty() } }
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
            store { recover<Exception> { recovered += error::class.simpleName.orEmpty() } }
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
     * Chart scopes delegate Store extensions to the actual handler or launch scope.
     */
    @Test
    fun chartScopes_delegateToUnderlyingStoreScopes() = runTest {
        val scopes = mutableMapOf<String, koma.core.StoreScope>()
        val store = StateChartStore<Unit, ChartAction, ChartEvent>(chart, Unit, backgroundScope.coroutineContext) {
            onEnter(a) { scopes["enter"] = store; launch { scopes["launch"] = store } }
            onExit(a) { scopes["exit"] = store }
            activity(a) { scopes["activity"] = store }
        }
        store.startAndAwait()
        runCurrent()
        store.dispatchAndAwait(ChartAction.Go)
        assertIs<koma.core.EnterScope<*, *, *>>(scopes.getValue("enter"))
        assertIs<koma.core.ActionScope<*, *, *, *>>(scopes.getValue("exit"))
        assertIs<koma.core.EnterLaunchScope<*, *, *>>(scopes.getValue("launch"))
        assertIs<koma.core.EnterLaunchScope<*, *, *>>(scopes.getValue("activity"))
        store.close()
    }

    @Test
    fun timerHooksDelegateToTransactionScope() = runTest {
        val timed = chart.copy(transitions = listOf(Transition(a, b, Trigger.After(1.seconds))))
        var underlying: koma.core.StoreScope? = null
        val store = StateChartStore<Unit, ChartAction, ChartEvent>(timed, Unit, backgroundScope.coroutineContext) {
            onEnter(b) { underlying = store }
        }
        store.startAndAwait()
        advanceTimeBy(1.seconds)
        runCurrent()
        assertIs<koma.core.EnterTransactionScope<*, *, *>>(underlying)
        assertTrue(store.currentState.isActive(b))
        store.close()
    }

    private data object Ping : ChartAction
    private data object Pong : ChartAction
    private data object Reset : ChartAction

    /**
     * In a parallel chart, one transition of an outer region can exit the whole parallel state.
     * The conformance plugin used to explain the change leaf by leaf, pick a region-local
     * transition for the first changed leaf and then had nothing left for the other region: a
     * violation for a Store that followed the chart exactly. The step is now explained by the
     * runtime's own selection first.
     *
     * ```
     * P { R1 { [*] --> A; A; B }  R2 { [*] --> C; C; D }  R3 { [*] --> E; E; F } }   initial F
     * A --Go--> D,  C --Go--> D
     * ```
     */
    @Test
    fun conformance_explainsAWholeParallelStepByTheRuntimesSelection() = runTest {
        val p = StateId("P")
        val r1 = StateId("R1"); val r2 = StateId("R2"); val r3 = StateId("R3")
        val c = StateId("C"); val d = StateId("D"); val e = StateId("E"); val f = StateId("F")
        val parallel = StateChartDefinition(
            initial = f,
            states = listOf(
                ParallelState(p),
                CompoundState(r1, initial = a, parent = p), AtomicState(a, r1), AtomicState(b, r1),
                CompoundState(r2, initial = c, parent = p), AtomicState(c, r2), AtomicState(d, r2),
                CompoundState(r3, initial = e, parent = p), AtomicState(e, r3), AtomicState(f, r3),
            ),
            transitions = listOf(Transition(a, d, go), Transition(c, d, go)),
        )
        val conformance = StateChartConformance.withActiveLeaves<ChartState<Unit>, ChartAction, ChartEvent>(parallel) { it.activeLeaves(parallel).toSet() }
        val store = StateChartStore<Unit, ChartAction, ChartEvent>(parallel, Unit, backgroundScope.coroutineContext) { store { plugin(conformance) } }

        store.dispatchAndAwait(ChartAction.Go)

        assertEquals(listOf(a, d, e), store.currentState.activeLeaves(parallel))
        assertEquals(emptyList(), conformance.violations)
        assertEquals(setOf(Transition(a, d, go)), conformance.coveredTransitions)
        store.close()
    }

    /**
     * A self-loop that keeps the leaves is not shown to plugins, but the runtime records history
     * on it. The plugin keeps that history as a possibility, so a later transition into the
     * history state that restores what the self-loop recorded is not a violation.
     *
     * ```
     * Q;  P { [*] --> B; A; B; H }   Q --Ping--> A, A --Pong--> Q, Q --Reset--> B, P --Go--> P, B --Pong--> A, A --Ping--> H
     * ```
     */
    @Test
    fun conformance_keepsHistoryRecordedByAnUnseenSelfLoopAsAPossibility() = runTest {
        val q = StateId("Q"); val p = StateId("P"); val h = StateId("H")
        val ping = ActionMatcher.of<Ping>("Ping"); val pong = ActionMatcher.of<Pong>("Pong"); val reset = ActionMatcher.of<Reset>("Reset")
        val chart = StateChartDefinition(
            initial = q,
            states = listOf(AtomicState(q), CompoundState(p, initial = b), AtomicState(a, p), AtomicState(b, p), HistoryState(h, parent = p)),
            transitions = listOf(Transition(q, a, ping), Transition(a, q, pong), Transition(q, b, reset), Transition(p, p, go), Transition(b, a, pong), Transition(a, h, ping)),
        )
        val conformance = StateChartConformance<ChartState<Unit>, ChartAction, ChartEvent>(chart) { it.activeLeaves(chart).first() }
        val store = StateChartStore<Unit, ChartAction, ChartEvent>(chart, Unit, backgroundScope.coroutineContext) { store { plugin(conformance) } }

        for (action in listOf(Ping, Pong, Reset, ChartAction.Go, Pong, Ping)) store.dispatchAndAwait(action)

        assertEquals(listOf(b), store.currentState.activeLeaves(chart))
        assertEquals(emptyList(), conformance.violations)
        store.close()
    }

    /**
     * Configurations of states named by a pattern used to share a handful of hash codes (a Set's
     * hash is the sum of its elements'), which made the configuration graph quadratic.
     */
    @Test
    fun configurationHashesOfPatternNamedStatesStayApart() {
        val configurations = (0 until 5).flatMap { r -> (0 until 5).map { s -> r to s } }.let { pairs ->
            (0 until 200).map { i ->
                StateConfiguration(active = pairs.filter { (r, s) -> (i shr r) and 1 == s % 2 }.map { (r, s) -> StateId("R${r}_S$s") }.toSet())
            }.distinct()
        }
        val hashes = configurations.map { it.hashCode() }.toSet()
        assertTrue(hashes.size >= configurations.size * 9 / 10, "${hashes.size} hashes for ${configurations.size} configurations")
    }

    private sealed interface Leaf : koma.core.State {
        data object A : Leaf
        data object B : Leaf
    }

    /**
     * Without a trigger (a chained `enter {}` at startup), the first transition that leads there
     * in priority order is covered whatever its trigger: an action transition declared before a
     * timer wins, as the class documentation says.
     */
    @Test
    fun conformance_withoutATrigger_coversTheFirstTransitionInPriorityOrder() = runTest {
        val byAction = Transition(a, b, go)
        val byTimer = Transition(a, b, Trigger.After(1.seconds))
        val timed = chart.copy(transitions = listOf(byAction, byTimer))
        val conformance = StateChartConformance<Leaf, ChartAction, ChartEvent>(timed) { if (it is Leaf.A) a else b }
        val store = koma.core.Store<Leaf, ChartAction, ChartEvent>(Leaf.A) {
            coroutineContext(kotlinx.coroutines.Dispatchers.Unconfined)
            plugin(conformance)
            state<Leaf.A> { enter { nextState { Leaf.B } } }
            state<Leaf.B> { }
        }

        store.startAndAwait()

        assertEquals(listOf(byAction), conformance.coveredTransitions.toList())
        assertEquals(emptyList(), conformance.violations)
        store.close()
    }

    /**
     * A self-loop of one region taken together with another region's transition is covered too:
     * the runtime's selection with every guard true explains the step as a whole.
     *
     * ```
     * P { R1 { [*] --> A; A --Go--> B }  R2 { [*] --> C; C --Go--> C } }
     * ```
     */
    @Test
    fun conformance_coversARegionSelfLoopTakenWithAnotherRegionsTransition() = runTest {
        val p = StateId("P"); val r1 = StateId("R1"); val r2 = StateId("R2"); val c = StateId("C")
        val move = Transition(a, b, go)
        val loop = Transition(c, c, go)
        val parallel = StateChartDefinition(
            initial = p,
            states = listOf(ParallelState(p), CompoundState(r1, initial = a, parent = p), AtomicState(a, r1), AtomicState(b, r1), CompoundState(r2, initial = c, parent = p), AtomicState(c, r2)),
            transitions = listOf(move, loop),
        )
        val conformance = StateChartConformance.withActiveLeaves<ChartState<Unit>, ChartAction, ChartEvent>(parallel) { it.activeLeaves(parallel).toSet() }
        val store = StateChartStore<Unit, ChartAction, ChartEvent>(parallel, Unit, backgroundScope.coroutineContext) { store { plugin(conformance) } }

        store.dispatchAndAwait(ChartAction.Go)

        assertEquals(listOf(b, c), store.currentState.activeLeaves(parallel))
        assertEquals(setOf(move, loop), conformance.coveredTransitions)
        assertEquals(emptyList(), conformance.violations)
        store.close()
    }

    /**
     * A snapshot whose active nodes are valid but whose history record was left by an earlier
     * version of the chart (here `Old` was a leaf of `P` that no longer exists) keeps its active
     * nodes; only the record is dropped, so the next transition into the history state takes the
     * default target. Falling back to the initial configuration would sign the user out of the
     * screen they were on.
     *
     * ```
     * [*] --> I
     * I --Go--> S
     * S --Go--> P.H
     * P: [*] --> X
     * ```
     */
    @Test
    fun aRestoredSnapshotWithAStaleHistoryRecord_keepsItsActiveNodes() = runTest {
        val i = StateId("I")
        val s = StateId("S")
        val p = StateId("P")
        val x = StateId("X")
        val h = StateId("H")
        val versioned = StateChartDefinition(
            initial = i,
            states = listOf(AtomicState(i), AtomicState(s), CompoundState(p, x), AtomicState(x, p), HistoryState(h, p)),
            transitions = listOf(Transition(i, s, go), Transition(s, h, go)),
        )
        val saved = ChartState(StateConfiguration(active = setOf(s), history = mapOf(h to setOf(StateId("Old")))), context = 42)
        val entered = mutableListOf<StateId>()
        val store = StateChartStore<Int, ChartAction, ChartEvent>(versioned, 0, backgroundScope.coroutineContext) {
            onEnter(i) { entered += node }
            onEnter(s) { entered += node }
            store { stateSaver(StateSaver(save = {}, restore = { saved })) }
        }

        store.startAndAwait()
        runCurrent()
        assertEquals(listOf(s), store.currentState.activeLeaves(versioned))
        assertEquals(emptyMap(), store.currentState.configuration.history)
        assertEquals(42, store.currentState.context)
        assertEquals(emptyList(), entered, "a restored configuration is not entered again")

        store.dispatchAndAwait(ChartAction.Go)
        assertEquals(listOf(x), store.currentState.activeLeaves(versioned))
        store.close()
    }

    /**
     * An activity that fails while a step out of its node is suspended in an enter hook is still
     * reported: the step commits first and cancels the activity, but the report is requested from
     * the chart's own scope, so it is not skipped with the cancelled task.
     */
    @Test
    fun anActivityFailureThatRacesTheNodesExit_isStillReported() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val recovered = mutableListOf<String>()
        val store = StateChartStore<Unit, ChartAction, ChartEvent>(chart, Unit, backgroundScope.coroutineContext) {
            activity(a) {
                gate.await()
                error("activity failed")
            }
            onEnter(b) { delay(1.seconds) }
            store { recover<IllegalStateException> { recovered += error.message.orEmpty() } }
        }

        store.startAndAwait()
        runCurrent()
        store.dispatch(ChartAction.Go)
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        advanceTimeBy(2.seconds)
        runCurrent()

        assertEquals(listOf(b), store.currentState.activeLeaves(chart))
        assertEquals(listOf("activity failed"), recovered)
        store.close()
    }

    /**
     * A timer step whose enter hook fails commits the spent timer first; an action queued behind
     * that commit may exit the timer's source before the failure is reported. The report still
     * reaches `recover {}`, with the state the queued action produced.
     *
     * ```
     * [*] --> A
     * A --after 1s--> B
     * A --Go--> C
     * ```
     */
    @Test
    fun aTimerStepFailureThatRacesAQueuedAction_isStillReported() = runTest {
        val c = StateId("C")
        val timed = StateChartDefinition(
            initial = a,
            states = listOf(AtomicState(a), AtomicState(b), AtomicState(c)),
            transitions = listOf(Transition(a, b, Trigger.After(1.seconds)), Transition(a, c, go)),
        )
        val recovered = mutableListOf<String>()
        val store = StateChartStore<Unit, ChartAction, ChartEvent>(timed, Unit, backgroundScope.coroutineContext) {
            onEnter(b) {
                delay(1.seconds)
                error("enter failed")
            }
            store { recover<IllegalStateException> { recovered += error.message.orEmpty() } }
        }

        store.startAndAwait()
        advanceTimeBy(1.seconds)
        runCurrent()
        store.dispatch(ChartAction.Go)
        runCurrent()
        advanceTimeBy(2.seconds)
        runCurrent()

        assertEquals(listOf(c), store.currentState.activeLeaves(timed))
        assertEquals(emptyMap(), store.currentState.timers.running)
        assertEquals(listOf("enter failed"), recovered)
        store.close()
    }

    /**
     * Documented behaviour to change deliberately, if ever: an action an activity dispatched is
     * tied to that activation of its node, so a self-loop timer that restarts the node between
     * the dispatch and its processing discards the action. A heartbeat timer therefore belongs in
     * a region of its own, not on the node whose activities report.
     *
     * ```
     * [*] --> A
     * A --after 1s--> A
     * A --Go--> B
     * ```
     */
    @Test
    fun anActionQueuedByAnActivityBeforeASelfLoopTimerFires_isDiscarded() = runTest {
        val looped = chart.copy(transitions = listOf(Transition(a, a, Trigger.After(1.seconds))) + chart.transitions)
        var activations = 0
        val store = StateChartStore<Unit, ChartAction, ChartEvent>(looped, Unit, backgroundScope.coroutineContext) {
            // Entering A takes a second, so the first activity's dispatch (half a second into the
            // timer's step) is queued behind the step that restarts A.
            onEnter(a) { delay(1.seconds) }
            activity(a) {
                if (activations++ == 0) {
                    delay(1.5.seconds)
                    dispatch(ChartAction.Go)
                }
            }
        }

        store.startAndAwait()
        advanceTimeBy(2.5.seconds)
        runCurrent()

        assertEquals(2, activations, "the self-loop restarted the node's activity")
        assertEquals(listOf(a), store.currentState.activeLeaves(looped))
        store.dispatchAndAwait(ChartAction.Go)
        assertEquals(listOf(b), store.currentState.activeLeaves(looped))
        store.close()
    }
}

package actron.statechart

import actron.core.Action
import actron.core.Event
import actron.core.ExperimentalActronApi
import actron.core.StateSaver
import actron.core.Store
import actron.test.dispatchAndAwait
import actron.test.startAndAwait
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Property-based tests for [StateChartStore] over [RandomCharts.forEachTimerChart]: random
 * hierarchical and parallel charts with history states and timers, with random effect labels on
 * some action transitions, driven by random schedules of actions and virtual-time advances.
 *
 * The reference is the pure [StateChartRuntime] fed the same events in the same order, with a
 * naive scheduler: every timer started gets a deadline (start time plus delay) and a sequence
 * number, and the due timer with the smallest (deadline, sequence) fires first, as coroutines
 * with equal deadlines resume in the order their delays began. After every action or advance the
 * Store's configuration, running timers, hook calls (exit, effect, enter, in the runtime's order)
 * and running activities must match the reference.
 *
 * ```
 * [*] --> A
 * A --after 1s / effect 0--> B     timer: fires in a transaction, effect gets TimerFired
 * A --Ping [even]--> A             self-loop: exits and re-enters A, restarting its timers
 * state B { [*] --> B1 }           onEnter/onExit hooks logged, an activity per node
 * ```
 */
@OptIn(ExperimentalActronApi::class, ExperimentalCoroutinesApi::class)
class StateChartStorePropertyTest {

    // Well above what the walks need on JVM; browsers on CI are many times slower.
    private val testTimeout = 5.minutes

    data object NoEvent : Event

    /** A random truth value per guard label and action, and per guard label for timers; independent of the state. */
    private class GuardTable(random: Random) {
        private val table = RandomCharts.guards.associateWith { RandomCharts.actions.associateWith { random.nextBoolean() } }
        private val timers = RandomCharts.guards.associateWith { random.nextBoolean() }

        fun holds(label: String, action: Action): Boolean =
            if (action is TimerFired) timers.getValue(label) else table.getValue(label).getValue(action as RandomAction)
    }

    private val effectLabels = listOf("effect 0", "effect 1", "effect 2")

    /** Adds random effect labels to some action transitions; timers already have some. */
    private fun withEffects(random: Random, chart: StateChartDefinition) = chart.copy(
        transitions = chart.transitions.map { if (it.effect == null && random.nextInt(3) == 0) it.copy(effect = effectLabels.random(random)) else it },
    )

    private class RunningTimer(val index: Int, val deadline: Duration, val sequence: Long)

    /** The pure runtime plus a naive scheduler, fed what the Store is fed. */
    private class Reference(val chart: StateChartDefinition, guards: GuardTable) {
        val runtime = StateChartRuntime<ChartState<Int>>(chart, { chart.activeLeaves(it.configuration).first() }, RandomCharts.guards.associateWith { label -> { _, action -> guards.holds(label, action) } })
        private val timersBySource = chart.transitions.withIndex().filter { it.value.isTimer }.groupBy({ it.value.source }, { it.index })
        var configuration = runtime.initialConfiguration()
        var effects = 0
        val log = mutableListOf<String>()
        val running = mutableListOf<RunningTimer>()
        private var now = Duration.ZERO
        private var sequence = 0L

        init {
            val initial = chart.inEntryOrder(configuration.active)
            initial.forEach { log += "enter $it" }
            start(initial)
        }

        private fun start(states: List<StateId>) {
            for (index in states.flatMap { timersBySource[it].orEmpty() }) {
                running += RunningTimer(index, now + chart.transitions[index].after!!, sequence++)
            }
        }

        /** After a restore every running timer starts over, outermost first. */
        fun restart() {
            running.clear()
            start(chart.inEntryOrder(configuration.active))
        }

        private fun take(result: StepResult.Transitioned) {
            result.exited.forEach { log += "exit $it" }
            result.transitions.mapNotNull { it.effect }.forEach { log += it; effects++ }
            result.entered.forEach { log += "enter $it" }
            val cancelled = result.exited.flatMap { timersBySource[it].orEmpty() }.toSet()
            assertEquals(result.timersToCancel, result.exited.flatMap { timersBySource[it].orEmpty() }.map { chart.transitions[it] })
            running.removeAll { it.index in cancelled }
            start(result.entered)
            assertEquals(result.timersToStart, result.entered.flatMap { timersBySource[it].orEmpty() }.map { chart.transitions[it] })
            configuration = result.configuration
        }

        private fun state() = ChartState(configuration, effects)

        fun dispatch(action: Action) {
            (runtime.step(configuration, state(), action) as? StepResult.Transitioned)?.let(::take)
        }

        fun advance(duration: Duration) {
            val end = now + duration
            while (true) {
                val next = running.filter { it.deadline <= end }.minWithOrNull(compareBy<RunningTimer> { it.deadline }.thenBy { it.sequence }) ?: break
                running -= next
                now = next.deadline
                (runtime.fire(configuration, state(), chart.transitions[next.index]) as? StepResult.Transitioned)?.let(::take)
            }
            now = end
        }
    }

    /** A Store over [chart] whose hooks, effects and activities record into [log] and [liveActivities]. */
    private class Harness(
        chart: StateChartDefinition,
        guards: GuardTable,
        effectLabels: List<String>,
        cancelTimers: Boolean,
        scope: TestScope,
        saver: StateSaver<ChartState<Int>>? = null,
    ) {
        val log = mutableListOf<String>()
        val liveActivities = mutableSetOf<StateId>()
        val host = ChartStoreHost(
            chart,
            0,
            StateChartStoreBuilder<Int, RandomAction, NoEvent>().apply {
                RandomCharts.guards.forEach { label -> guard(label) { _, action -> guards.holds(label, action) } }
                effectLabels.forEach { label -> effect(label) { context, _ -> log += label; context + 1 } }
                for (declared in chart.states.filter { it !is HistoryState }) {
                    onEnter(declared.id) { log += "enter $node" }
                    onExit(declared.id) { log += "exit $node" }
                    activity(declared.id) {
                        assertTrue(liveActivities.add(node), "activity of $node started twice")
                        try {
                            awaitCancellation()
                        } finally {
                            liveActivities -= node
                        }
                    }
                }
                if (saver != null) store { stateSaver(saver) }
            },
            cancelTimers = cancelTimers,
        )
        val store: Store<ChartState<Int>, RandomAction, NoEvent> = host.build(scope.backgroundScope.coroutineContext)
    }

    private class MemorySaver : StateSaver<ChartState<Int>> {
        var saved: ChartState<Int>? = null

        override fun save(state: ChartState<Int>) {
            saved = state
        }

        override fun restore(): ChartState<Int>? = saved
    }

    private val advances = listOf(300.milliseconds, 500.milliseconds, 1.seconds, 2.seconds, 3.seconds, 5.seconds, 10.seconds)

    /**
     * Lets the platform event loop run between charts. Virtual time never leaves the thread, so
     * on JS a whole property test would otherwise block the browser long enough for Karma to
     * lose it (ping timeout). Real dispatch, so it does not touch the TestScope's virtual clock.
     */
    private suspend fun yieldToEventLoop() = withContext(Dispatchers.Default) { yield() }

    private fun assertAgrees(seed: Int, step: Int, reference: Reference, harness: Harness) {
        val state = harness.store.currentState
        val at = "seed $seed, step $step"
        assertEquals(reference.configuration, state.configuration, "$at: configuration")
        assertEquals(reference.running.map { it.index }.toSet(), state.timers.running.keys, "$at: running timers")
        assertEquals(reference.log, harness.log, "$at: hook order")
        assertEquals(reference.effects, state.context, "$at: effects applied")
        assertEquals(state.configuration.active, harness.liveActivities, "$at: running activities")
    }

    /** Walks [harness] and [reference] through [steps] random actions and advances. */
    private suspend fun TestScope.walk(seed: Int, random: Random, reference: Reference, harness: Harness, steps: Int) {
        repeat(steps) { step ->
            if (random.nextInt(10) < 6) {
                val action = RandomCharts.actions.random(random)
                harness.store.dispatchAndAwait(action)
                runCurrent()
                reference.dispatch(action)
            } else {
                val duration = advances.random(random)
                advanceTimeBy(duration)
                runCurrent()
                reference.advance(duration)
            }
            assertAgrees(seed, step, reference, harness)
        }
    }

    // WHY: a TestScope extension, not a function that calls runTest itself. On JS runTest returns
    // a Promise that the @Test function must return, or the assertions after it run before the walk.
    private suspend fun TestScope.storeAgreesWithTheRuntime(cancelTimers: Boolean): Int {
        var stale = 0
        var timerSteps = 0
        RandomCharts.forEachTimerChart(count = 80) { seed, random, base ->
            val chart = withEffects(random, base)
            val guards = GuardTable(random)
            val reference = Reference(chart, guards)
            val harness = Harness(chart, guards, effectLabels, cancelTimers, this)
            harness.store.startAndAwait()
            runCurrent()
            assertAgrees(seed, -1, reference, harness)
            walk(seed, random, reference, harness, steps = 30)
            stale += harness.host.staleFirings
            timerSteps += reference.log.count { it.startsWith("effect") }
            harness.store.close()
            yieldToEventLoop()
        }
        assertTrue(timerSteps > 100, "only $timerSteps effects")
        return stale
    }

    @Test
    fun storeFollowsTheRuntimeStepForStepWithTimersCancelledOnExit() = runTest(timeout = testTimeout) {
        storeAgreesWithTheRuntime(cancelTimers = true)
    }

    @Test
    fun staleTimersNeverFireEvenWhenCancellationComesTooLate() = runTest(timeout = testTimeout) {
        // Timer coroutines are never cancelled: only the tokens in the state stop stale firings.
        val stale = storeAgreesWithTheRuntime(cancelTimers = false)
        assertTrue(stale > 100, "only $stale stale firings exercised")
    }

    @Test
    fun restoredStoreContinuesLikeTheRuntimeWithRestartedTimers() = runTest(timeout = testTimeout) {
        var restores = 0
        RandomCharts.forEachTimerChart(count = 60) { seed, random, base ->
            val chart = withEffects(random, base)
            val guards = GuardTable(random)
            val reference = Reference(chart, guards)
            val saver = MemorySaver()
            val first = Harness(chart, guards, effectLabels, cancelTimers = true, scope = this, saver = saver)
            first.store.startAndAwait()
            runCurrent()
            walk(seed, random, reference, first, steps = 12)
            first.store.close()
            runCurrent()

            // Without any commit nothing was saved, and the second Store starts fresh.
            val fresh = saver.saved == null
            val second = Harness(chart, guards, effectLabels, cancelTimers = true, scope = this, saver = saver)
            second.store.startAndAwait()
            runCurrent()
            reference.restart()
            reference.log.clear() // no enter hooks on restore
            if (fresh) reference.log += chart.inEntryOrder(reference.configuration.active).map { "enter $it" } else restores++
            assertAgrees(seed, 100, reference, second)
            walk(seed, random, reference, second, steps = 12)
            second.store.close()
            yieldToEventLoop()
        }
        assertTrue(restores > 30, "only $restores restores")
    }
}

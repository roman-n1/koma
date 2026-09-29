package koma.statechart

import koma.core.Action
import koma.core.ExceptionHandler
import koma.core.ExperimentalKomaApi
import koma.core.State
import koma.core.StateSaver
import koma.test.dispatchAndAwait
import koma.test.startAndAwait
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalKomaApi::class, ExperimentalCoroutinesApi::class)
class StateChartRecoveryRegressionTest {
    private data object Go : Action
    private data object Box : State
    private val p = StateId("P")
    private val a = StateId("A")
    private val b = StateId("B")
    private val outside = StateId("Outside")
    private val history = StateId("H")
    private val go = ActionMatcher.of<Go>("Go")

    @Test
    fun savedParallelHistoryIsRejectedWhenItsParentBecomesExclusive() = runTest {
        val r1 = StateId("R1")
        val r2 = StateId("R2")
        val old = StateChartDefinition(
            p,
            listOf(ParallelState(p), CompoundState(r1, a, p), CompoundState(r2, b, p), AtomicState(a, r1), AtomicState(b, r2), HistoryState(history, p, deep = true), AtomicState(outside)),
            listOf(Transition(p, outside, go)),
        )
        val runtime = StateChartRuntime<Box>(old, { a })
        val saved = (runtime.step(runtime.initialConfiguration(), Box, Go) as StepResult.Transitioned).configuration
        val updated = StateChartDefinition(
            outside,
            listOf(CompoundState(p, a), AtomicState(a, p), AtomicState(b, p), HistoryState(history, p, deep = true), AtomicState(outside)),
            listOf(Transition(outside, history, go)),
        )
        val store = StateChartStore<Int, Go, Nothing>(updated, 0, backgroundScope.coroutineContext) {
            store { stateSaver(StateSaver(save = {}, restore = { ChartState(saved, 42) })) }
        }
        try {
            store.startAndAwait()
            assertEquals(42, store.currentState.context)
            assertEquals(emptyMap(), store.currentState.configuration.history)
            store.dispatchAndAwait(Go)
            assertEquals(listOf(a), store.currentState.activeLeaves(updated))
        } finally {
            store.close()
        }
    }

    @Test
    fun restoredShallowHistoryMustStillNameAnImmediateChild() {
        val child = StateId("Child")
        val chart = StateChartDefinition(
            outside,
            listOf(CompoundState(p, child), CompoundState(child, a, p), AtomicState(a, child), HistoryState(history, p), AtomicState(outside)),
            emptyList(),
        )
        assertEquals(false, chart.isConsistent(StateConfiguration(setOf(outside), mapOf(history to setOf(a)))))
        assertTrue(chart.isConsistent(StateConfiguration(setOf(outside), mapOf(history to setOf(child)))))
    }

    @Test
    fun deepHistoryInsideAParallelRegionDoesNotRequireItsSiblingRegions() {
        val region = StateId("Region")
        val chart = StateChartDefinition(
            outside,
            listOf(ParallelState(p), CompoundState(region, a, p), AtomicState(a, region), AtomicState(b, p), HistoryState(history, region, deep = true), AtomicState(outside)),
            emptyList(),
        )
        assertTrue(chart.isConsistent(StateConfiguration(setOf(outside), mapOf(history to setOf(a)))))
    }

    @Test
    fun recoveryCannotChangeConfigurationWithoutManagingItsActivities() = runTest {
        val chart = StateChartDefinition(a, listOf(AtomicState(a), AtomicState(b)), listOf(Transition(a, b, go, effect = "fail")))
        val errors = mutableListOf<Throwable>()
        var active = false
        val store = StateChartStore<Int, Go, Nothing>(chart, 0, backgroundScope.coroutineContext) {
            effect("fail") { _, _ -> error("operation failed") }
            activity(a) { active = true; try { awaitCancellation() } finally { active = false } }
            store {
                exceptionHandler(ExceptionHandler { errors += it })
                state<ChartState<Int>> {
                    recover<IllegalStateException> { nextState { state.copy(configuration = chart.configurationOf(b)) } }
                }
            }
        }
        try {
            store.dispatchAndAwait(Go)
            runCurrent()
            assertEquals(listOf(a), store.currentState.activeLeaves(chart))
            assertTrue(active)
            assertTrue(errors.single().message.orEmpty().contains("recover"))
            assertEquals("operation failed", errors.single().suppressedExceptions.single().message)
        } finally {
            store.close()
        }
    }

    @Test
    fun recoveryCannotReplaceTimerBookkeepingButCanUpdateContext() = runTest {
        val chart = StateChartDefinition(a, listOf(AtomicState(a), AtomicState(b)), listOf(Transition(a, b, go, effect = "fail"), Transition(a, b, Trigger.After(1.seconds))))
        for (changeTimers in listOf(true, false)) {
            val errors = mutableListOf<Throwable>()
            val store = StateChartStore<Int, Go, Nothing>(chart, 0, backgroundScope.coroutineContext) {
                effect("fail") { _, _ -> error("operation failed") }
                store {
                    exceptionHandler(ExceptionHandler { errors += it })
                    state<ChartState<Int>> {
                        recover<IllegalStateException> {
                            nextState { if (changeTimers) state.copy(timers = ChartTimers()) else state.copy(context = 1) }
                        }
                    }
                }
            }
            try {
                store.dispatchAndAwait(Go)
                assertEquals(if (changeTimers) 0 else 1, store.currentState.context)
                assertEquals(setOf(1), store.currentState.timers.running.keys)
                assertEquals(if (changeTimers) 1 else 0, errors.size)
            } finally {
                store.close()
            }
        }
    }
}

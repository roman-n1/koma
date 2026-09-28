package koma.statechart

import koma.core.Action
import koma.core.Event
import koma.core.ExperimentalKomaApi
import koma.core.State
import koma.core.Store
import koma.test.dispatchAndAwait
import koma.test.patch
import koma.test.startAndAwait
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Chart:
 * ```
 * [*] --> Idle
 * Idle    --Submit--> Loading
 * Loading --Loaded--> Ready
 * Loading --Failed--> Error
 * Error   --Retry--> Loading
 * ```
 * The Store below follows it, except that `Reset` jumps from Ready to Idle, which the chart does
 * not declare.
 */
@OptIn(ExperimentalKomaApi::class)
class StateChartConformanceTest {

    sealed interface FormState : State {
        data object Idle : FormState
        data class Loading(val attempt: Int) : FormState
        data object Ready : FormState
        data object Error : FormState
    }

    sealed interface FormAction : Action {
        data object Submit : FormAction
        data object Loaded : FormAction
        data object Failed : FormAction
        data object Retry : FormAction
        data object Reset : FormAction
        data object Bump : FormAction
    }

    sealed interface FormEvent : Event

    private val idle = StateId("Idle")
    private val loading = StateId("Loading")
    private val ready = StateId("Ready")
    private val error = StateId("Error")

    private val chart = StateChartDefinition(
        initial = idle,
        states = listOf(AtomicState(idle), AtomicState(loading), AtomicState(ready), AtomicState(error)),
        transitions = listOf(
            Transition(idle, loading, ActionMatcher.of<FormAction.Submit>("Submit")),
            Transition(loading, ready, ActionMatcher.of<FormAction.Loaded>("Loaded")),
            Transition(loading, error, ActionMatcher.of<FormAction.Failed>("Failed")),
            Transition(error, loading, ActionMatcher.of<FormAction.Retry>("Retry")),
        ),
    )

    private fun createStore(initial: FormState = FormState.Idle): Store<FormState, FormAction, FormEvent> {
        return Store(initial) {
            coroutineContext(Dispatchers.Unconfined)
            state<FormState.Idle> {
                action<FormAction.Submit> { nextState { FormState.Loading(attempt = 1) } }
            }
            state<FormState.Loading> {
                action<FormAction.Loaded> { nextState { FormState.Ready } }
                action<FormAction.Failed> { nextState { FormState.Error } }
                action<FormAction.Bump> { nextState { state.copy(attempt = state.attempt + 1) } }
            }
            state<FormState.Error> {
                action<FormAction.Retry> { nextState { FormState.Loading(attempt = 2) } }
            }
            state<FormState.Ready> {
                action<FormAction.Reset> { nextState { FormState.Idle } }
            }
        }
    }

    private fun conformingStore(initial: FormState = FormState.Idle) =
        StateChartConformance<FormState, FormAction, FormEvent>(chart).let { conformance ->
            createStore(initial).patch { plugin(conformance) } to conformance
        }

    @Test
    fun followingTheChartHasNoViolationsAndCoversTakenTransitions() = runTest {
        val (store, conformance) = conformingStore()

        store.dispatchAndAwait(FormAction.Submit)
        store.dispatchAndAwait(FormAction.Failed)
        store.dispatchAndAwait(FormAction.Retry)

        assertTrue(conformance.violations.isEmpty())
        assertEquals(listOf(chart.transitions[0], chart.transitions[2], chart.transitions[3]), conformance.coveredTransitions.toList())
        assertEquals(listOf(chart.transitions[1]), conformance.uncoveredTransitions)
    }

    @Test
    fun dataChangeInsideOneVariantIsNotATransition() = runTest {
        val (store, conformance) = conformingStore()

        store.dispatchAndAwait(FormAction.Submit)
        store.dispatchAndAwait(FormAction.Bump)

        assertEquals(FormState.Loading(attempt = 2), store.currentState)
        assertTrue(conformance.violations.isEmpty())
        assertEquals(listOf(chart.transitions[0]), conformance.coveredTransitions.toList())
    }

    @Test
    fun undeclaredTransitionIsReportedWithLastAction() = runTest {
        val (store, conformance) = conformingStore()

        store.dispatchAndAwait(FormAction.Submit)
        store.dispatchAndAwait(FormAction.Loaded)
        store.dispatchAndAwait(FormAction.Reset)

        assertEquals(
            listOf(ConformanceViolation.UndeclaredTransition(from = ready, to = idle, lastAction = FormAction.Reset)),
            conformance.violations,
        )
    }

    @Test
    fun unexpectedInitialStateIsReported() = runTest {
        val (store, conformance) = conformingStore(initial = FormState.Error)

        store.startAndAwait()

        assertEquals(
            listOf(ConformanceViolation.UnexpectedInitialState(expected = idle, actual = error)),
            conformance.violations,
        )
    }

    @Test
    fun stateMissingFromChartIsReported() = runTest {
        val smallChart = chart.copy(
            states = chart.states.filter { it.id != error },
            transitions = chart.transitions.filter { it.source != error && it.target != error },
        )
        val conformance = StateChartConformance<FormState, FormAction, FormEvent>(smallChart)
        val store = createStore().patch { plugin(conformance) }

        store.dispatchAndAwait(FormAction.Submit)
        store.dispatchAndAwait(FormAction.Failed)

        assertEquals(
            listOf(
                ConformanceViolation.UndeclaredState(error),
                ConformanceViolation.UndeclaredTransition(from = loading, to = error, lastAction = FormAction.Failed),
            ),
            conformance.violations,
        )
    }

    @Test
    fun defaultStateIdIsTheSimpleClassName() {
        assertEquals(loading, defaultStateId(FormState.Loading(attempt = 3)))
    }
}

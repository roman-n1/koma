package actron.statechart

import actron.core.Action
import actron.core.Event
import actron.core.ExperimentalActronApi
import actron.core.State
import actron.core.StateSaver
import actron.core.Store
import actron.test.dispatchAndAwait
import actron.test.patch
import actron.test.startAndAwait
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
 * not declare, and `Bump` changes data inside Loading.
 */
@OptIn(ExperimentalActronApi::class)
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

    private val stateIdOf: (FormState) -> StateId = { state ->
        when (state) {
            FormState.Idle -> idle
            is FormState.Loading -> loading
            FormState.Ready -> ready
            FormState.Error -> error
        }
    }

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

    private fun conformingStore(initial: FormState = FormState.Idle, chart: StateChartDefinition = this.chart) =
        StateChartConformance<FormState, FormAction, FormEvent>(chart, stateIdOf).let { conformance ->
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
    fun changeWithATriggerTheChartDoesNotDeclareIsReportedAndNotCovered() = runTest {
        // The chart moves Ready to Idle on Retry, but the Store does it on Reset.
        val backOnRetry = Transition(ready, idle, ActionMatcher.of<FormAction.Retry>("Retry"))
        val (store, conformance) = conformingStore(chart = chart.copy(transitions = chart.transitions + backOnRetry))

        store.dispatchAndAwait(FormAction.Submit)
        store.dispatchAndAwait(FormAction.Loaded)
        store.dispatchAndAwait(FormAction.Reset)

        assertEquals(listOf(ConformanceViolation.UnexpectedTrigger(from = ready, to = idle, action = FormAction.Reset)), conformance.violations)
        assertEquals(listOf(chart.transitions[0], chart.transitions[1]), conformance.coveredTransitions.toList())
    }

    @Test
    fun selfLoopIsCoveredByItsActionEvenWithoutAStateChange() = runTest {
        // Bump only changes data inside Loading; Retry in Loading has no handler at all.
        val bump = Transition(loading, loading, ActionMatcher.of<FormAction.Bump>("Bump"))
        val retry = Transition(loading, loading, ActionMatcher.of<FormAction.Retry>("Retry"))
        val (store, conformance) = conformingStore(chart = chart.copy(transitions = chart.transitions + bump + retry))

        store.dispatchAndAwait(FormAction.Submit)
        store.dispatchAndAwait(FormAction.Bump)
        assertEquals(listOf(chart.transitions[0], bump), conformance.coveredTransitions.toList())

        store.dispatchAndAwait(FormAction.Retry)
        store.dispatchAndAwait(FormAction.Loaded)

        assertEquals(FormState.Ready, store.currentState)
        assertTrue(conformance.violations.isEmpty())
        assertEquals(listOf(chart.transitions[0], bump, retry, chart.transitions[1]), conformance.coveredTransitions.toList())
    }

    @Test
    fun startingInADeclaredNonInitialStateIsAccepted() = runTest {
        val (store, conformance) = conformingStore(initial = FormState.Error)
        store.startAndAwait()
        store.dispatchAndAwait(FormAction.Retry)

        val patched = StateChartConformance<FormState, FormAction, FormEvent>(chart, stateIdOf)
        val patchedStore = createStore().patch {
            initialState(FormState.Ready)
            plugin(patched)
        }
        patchedStore.startAndAwait()

        val restored = StateChartConformance<FormState, FormAction, FormEvent>(chart, stateIdOf)
        val restoredStore = createStore().patch {
            stateSaver(StateSaver(save = {}, restore = { FormState.Loading(attempt = 4) }))
            plugin(restored)
        }
        restoredStore.dispatchAndAwait(FormAction.Loaded)

        assertEquals(emptyList(), conformance.violations)
        assertEquals(listOf(chart.transitions[3]), conformance.coveredTransitions.toList())
        assertEquals(FormState.Ready, patchedStore.currentState)
        assertEquals(emptyList(), patched.violations)
        assertEquals(FormState.Ready, restoredStore.currentState)
        assertEquals(emptyList(), restored.violations)
        assertEquals(listOf(chart.transitions[1]), restored.coveredTransitions.toList())
    }

    @Test
    fun startingInAnUndeclaredStateIsReported() = runTest {
        val withoutError = chart.copy(states = chart.states.filter { it.id != error })
        val (store, conformance) = conformingStore(initial = FormState.Error, chart = withoutError)

        store.startAndAwait()

        assertEquals(listOf(ConformanceViolation.UndeclaredState(error)), conformance.violations)
    }

    @Test
    fun stateMissingFromChartIsReported() = runTest {
        val smallChart = chart.copy(
            states = chart.states.filter { it.id != error },
            transitions = chart.transitions.filter { it.source != error && it.target != error },
        )
        val conformance = StateChartConformance<FormState, FormAction, FormEvent>(smallChart, stateIdOf)
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
}

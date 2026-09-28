package koma.statechart

import koma.core.Action
import koma.core.ExperimentalKomaApi
import koma.core.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ```
 * [*] --> Idle
 * Idle    --Submit [isValid]--> Loading
 * Idle    --Submit--> Error          (fallback when isValid is false)
 * Loading --Loaded--> Ready
 * Error   --Retry [canRetry]--> Loading   (canRetry reads Error.attempts < 3)
 * ```
 */
@OptIn(ExperimentalKomaApi::class)
class StateChartRuntimeTest {

    sealed interface FormAction : Action {
        data class Submit(val text: String) : FormAction
        data object Loaded : FormAction
        data object Retry : FormAction
    }

    sealed interface FormState : State {
        data object Idle : FormState
        data object Loading : FormState
        data object Ready : FormState
        data class Error(val attempts: Int) : FormState
    }

    private val idle = StateId("Idle")
    private val loading = StateId("Loading")
    private val ready = StateId("Ready")
    private val error = StateId("Error")

    private val chart = StateChartDefinition(
        initial = idle,
        states = listOf(AtomicState(idle), AtomicState(loading), AtomicState(ready), AtomicState(error)),
        transitions = listOf(
            Transition(idle, loading, ActionMatcher.of<FormAction.Submit>("Submit"), guard = "isValid"),
            Transition(idle, error, ActionMatcher.of<FormAction.Submit>("Submit")),
            Transition(loading, ready, ActionMatcher.of<FormAction.Loaded>("Loaded")),
            Transition(error, loading, ActionMatcher.of<FormAction.Retry>("Retry"), guard = "canRetry"),
        ),
    )

    private val stateIdOf: (FormState) -> StateId = { state ->
        when (state) {
            FormState.Idle -> idle
            FormState.Loading -> loading
            FormState.Ready -> ready
            is FormState.Error -> error
        }
    }

    private val runtime = StateChartRuntime(
        definition = chart,
        stateIdOf = stateIdOf,
        guards = mapOf(
            "isValid" to { _, action -> (action as FormAction.Submit).text.isNotBlank() },
            "canRetry" to { state, _ -> (state as FormState.Error).attempts < 3 },
        ),
    )

    @Test
    fun takesGuardedTransitionWhenGuardHolds() {
        val result = runtime.step(FormState.Idle, FormAction.Submit("hello"))

        assertEquals(StepResult.Transitioned(chart.transitions[0]), result)
        assertEquals(loading, (result as StepResult.Transitioned).target)
    }

    @Test
    fun fallsBackToNextTransitionWhenGuardFails() {
        val result = runtime.step(FormState.Idle, FormAction.Submit(" "))

        assertEquals(StepResult.Transitioned(chart.transitions[1]), result)
    }

    @Test
    fun ignoresActionWithoutTransitionFromCurrentState() {
        assertEquals(StepResult.Ignored, runtime.step(FormState.Idle, FormAction.Loaded))
        assertEquals(StepResult.Ignored, runtime.step(FormState.Ready, FormAction.Retry))
    }

    @Test
    fun guardReadsStateData() {
        assertEquals(StepResult.Transitioned(chart.transitions[3]), runtime.step(FormState.Error(attempts = 2), FormAction.Retry))
        assertEquals(StepResult.Ignored, runtime.step(FormState.Error(attempts = 3), FormAction.Retry))
    }

    @Test
    fun followsAPathThroughTheChart() {
        val actions = listOf(FormAction.Submit(""), FormAction.Retry, FormAction.Loaded)
        val states = mapOf(idle to FormState.Idle, loading to FormState.Loading, ready to FormState.Ready, error to FormState.Error(1))
        val path = actions.runningFold(chart.initial) { id, action ->
            when (val result = runtime.step(states.getValue(id), action)) {
                is StepResult.Transitioned -> result.target
                StepResult.Ignored -> id
            }
        }

        assertEquals(listOf(idle, error, loading, ready), path)
    }

    @Test
    fun missingGuardImplementationFailsFast() {
        val error = assertFailsWith<IllegalArgumentException> { StateChartRuntime(chart, stateIdOf) }

        assertEquals("[Koma] Missing guard implementations: isValid, canRetry", error.message)
    }

    @Test
    fun matcherWithoutTypeMatchesBySimpleName() {
        assertTrue(ActionMatcher("Loaded").matches(FormAction.Loaded))
        assertTrue(!ActionMatcher("Retry").matches(FormAction.Loaded))
    }
}

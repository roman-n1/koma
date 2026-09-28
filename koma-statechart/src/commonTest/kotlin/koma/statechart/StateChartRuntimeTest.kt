package koma.statechart

import koma.core.Action
import koma.core.ExperimentalKomaApi
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
 * Error   --Retry--> Loading
 * ```
 */
@OptIn(ExperimentalKomaApi::class)
class StateChartRuntimeTest {

    sealed interface FormAction : Action {
        data class Submit(val text: String) : FormAction
        data object Loaded : FormAction
        data object Retry : FormAction
    }

    private val idle = StateId("Idle")
    private val loading = StateId("Loading")
    private val ready = StateId("Ready")
    private val error = StateId("Error")

    private val chart = StateChartDefinition(
        initial = idle,
        states = listOf(AtomicState(idle), AtomicState(loading), AtomicState(ready), AtomicState(error)),
        transitions = listOf(
            Transition(idle, loading, ActionMatcher.of<FormAction.Submit>(), guard = "isValid"),
            Transition(idle, error, ActionMatcher.of<FormAction.Submit>()),
            Transition(loading, ready, ActionMatcher.of<FormAction.Loaded>()),
            Transition(error, loading, ActionMatcher.of<FormAction.Retry>()),
        ),
    )

    private val runtime = StateChartRuntime(
        definition = chart,
        guards = mapOf("isValid" to { action -> (action as FormAction.Submit).text.isNotBlank() }),
    )

    @Test
    fun takesGuardedTransitionWhenGuardHolds() {
        val result = runtime.step(idle, FormAction.Submit("hello"))

        assertEquals(StepResult.Transitioned(chart.transitions[0]), result)
        assertEquals(loading, (result as StepResult.Transitioned).target)
    }

    @Test
    fun fallsBackToNextTransitionWhenGuardFails() {
        val result = runtime.step(idle, FormAction.Submit(" "))

        assertEquals(StepResult.Transitioned(chart.transitions[1]), result)
    }

    @Test
    fun ignoresActionWithoutTransitionFromCurrentState() {
        assertEquals(StepResult.Ignored, runtime.step(idle, FormAction.Loaded))
        assertEquals(StepResult.Ignored, runtime.step(ready, FormAction.Retry))
    }

    @Test
    fun followsAPathThroughTheChart() {
        val actions = listOf(FormAction.Submit(""), FormAction.Retry, FormAction.Loaded)
        val path = actions.runningFold(chart.initial) { state, action ->
            when (val result = runtime.step(state, action)) {
                is StepResult.Transitioned -> result.target
                StepResult.Ignored -> state
            }
        }

        assertEquals(listOf(idle, error, loading, ready), path)
    }

    @Test
    fun missingGuardImplementationFailsFast() {
        val error = assertFailsWith<IllegalArgumentException> { StateChartRuntime(chart) }

        assertTrue(error.message!!.contains("isValid"))
    }

    @Test
    fun matcherWithoutTypeMatchesBySimpleName() {
        assertTrue(ActionMatcher("Loaded").matches(FormAction.Loaded))
        assertTrue(!ActionMatcher("Retry").matches(FormAction.Loaded))
    }
}

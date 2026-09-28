package koma.statechart

import koma.core.Action
import koma.core.ExperimentalKomaApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * ```
 * [*] --> Idle
 * Idle    --Submit [isValid]--> Loading
 * Idle    --Submit--> Error
 * Loading --Loaded--> Ready
 * Error   --Retry--> Loading
 * Ready   --Refresh--> Ready
 * Orphan  --Retry--> Idle          (Orphan is unreachable)
 * ```
 */
@OptIn(ExperimentalKomaApi::class)
class StateChartPathTest {

    sealed interface FormAction : Action {
        data object Submit : FormAction
        data object Loaded : FormAction
        data object Retry : FormAction
        data object Refresh : FormAction
    }

    private val idle = StateId("Idle")
    private val loading = StateId("Loading")
    private val ready = StateId("Ready")
    private val error = StateId("Error")
    private val orphan = StateId("Orphan")

    private val submitValid = Transition(idle, loading, ActionMatcher.of<FormAction.Submit>("Submit"), guard = "isValid")
    private val submitInvalid = Transition(idle, error, ActionMatcher.of<FormAction.Submit>("Submit"))
    private val loaded = Transition(loading, ready, ActionMatcher.of<FormAction.Loaded>("Loaded"))
    private val retry = Transition(error, loading, ActionMatcher.of<FormAction.Retry>("Retry"))
    private val refresh = Transition(ready, ready, ActionMatcher.of<FormAction.Refresh>("Refresh"))
    private val orphanRetry = Transition(orphan, idle, ActionMatcher.of<FormAction.Retry>("Retry"))

    private val chart = StateChartDefinition(
        initial = idle,
        states = listOf(idle, loading, ready, error, orphan).map(::AtomicState),
        transitions = listOf(submitValid, submitInvalid, loaded, retry, refresh, orphanRetry),
    )

    @Test
    fun shortestPathToInitialStateIsEmpty() {
        val path = chart.shortestPathTo(idle)

        assertEquals(StateChartPath(idle, emptyList()), path)
        assertEquals(idle, path?.end)
    }

    @Test
    fun shortestPathPrefersDeclarationOrderAmongEqualLengths() {
        // Ready is two steps away through Loading; Error -> Loading is longer.
        val path = chart.shortestPathTo(ready)

        assertEquals(listOf(submitValid, loaded), path?.transitions)
        assertEquals(listOf("Submit", "Loaded"), path?.actions?.map { it.name })
        assertEquals(ready, path?.end)
    }

    @Test
    fun shortestPathToUnreachableStateIsNull() {
        assertNull(chart.shortestPathTo(orphan))
        assertNull(chart.shortestPathTo(StateId("Unknown")))
    }

    @Test
    fun coveragePathsTakeEveryReachableTransition() {
        val paths = chart.transitionCoveragePaths()

        assertEquals(
            listOf(
                listOf(submitInvalid, retry),
                listOf(submitValid, loaded, refresh),
            ),
            paths.map { it.transitions },
        )
        assertEquals(
            chart.transitions - orphanRetry,
            chart.transitions.filter { t -> paths.any { t in it.transitions } },
        )
    }

    @Test
    fun coveragePathsOfChartWithoutTransitionsAreEmpty() {
        val single = StateChartDefinition(initial = idle, states = listOf(AtomicState(idle)), transitions = emptyList())

        assertEquals(emptyList(), single.transitionCoveragePaths())
    }

    @Test
    fun pathMustBeConnected() {
        assertFailsWith<IllegalArgumentException> {
            StateChartPath(idle, listOf(loaded))
        }
    }
}

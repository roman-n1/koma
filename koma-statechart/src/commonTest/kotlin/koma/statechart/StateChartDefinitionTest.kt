package koma.statechart

import koma.core.Action
import koma.core.ExperimentalKomaApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Sample chart used by these tests:
 *
 * ```
 * [*] --> Idle
 * Idle    --Submit [isValid]--> Loading
 * Idle    --Submit--> Error
 * Loading --Loaded--> Ready
 * Loading --Failed--> Error
 * Error   --Retry--> Loading
 * ```
 */
@OptIn(ExperimentalKomaApi::class)
class StateChartDefinitionTest {

    sealed interface FormAction : Action {
        data object Submit : FormAction
        data object Loaded : FormAction
        data object Failed : FormAction
        data object Retry : FormAction
    }

    private val idle = StateId("Idle")
    private val loading = StateId("Loading")
    private val ready = StateId("Ready")
    private val error = StateId("Error")

    private val submit = ActionMatcher.of<FormAction.Submit>()
    private val loaded = ActionMatcher.of<FormAction.Loaded>()
    private val failed = ActionMatcher.of<FormAction.Failed>()
    private val retry = ActionMatcher.of<FormAction.Retry>()

    private val chart = StateChartDefinition(
        initial = idle,
        states = listOf(AtomicState(idle), AtomicState(loading), AtomicState(ready), AtomicState(error)),
        transitions = listOf(
            Transition(idle, loading, submit, guard = "isValid"),
            Transition(idle, error, submit),
            Transition(loading, ready, loaded),
            Transition(loading, error, failed),
            Transition(error, loading, retry),
        ),
    )

    @Test
    fun actionMatcherIsNamedAfterActionType() {
        assertEquals(ActionMatcher("Submit", FormAction.Submit::class), submit)
    }

    @Test
    fun blankIdsAreRejected() {
        assertFailsWith<IllegalArgumentException> { StateId(" ") }
        assertFailsWith<IllegalArgumentException> { ActionMatcher("") }
    }

    @Test
    fun transitionsFromKeepsDeclarationOrder() {
        assertEquals(
            listOf(Transition(idle, loading, submit, guard = "isValid"), Transition(idle, error, submit)),
            chart.transitionsFrom(idle),
        )
    }

    @Test
    fun validChartHasNoIssues() {
        assertTrue(chart.validate().isEmpty())
    }

    @Test
    fun allStatesAreReachable() {
        assertEquals(setOf(idle, loading, error, ready), chart.reachableStates())
    }

    @Test
    fun unreachableStateIsReported() {
        val orphan = StateId("Orphan")
        val withOrphan = chart.copy(states = chart.states + AtomicState(orphan))

        assertEquals(listOf(ValidationIssue.UnreachableState(orphan)), withOrphan.validate())
    }

    @Test
    fun duplicateAndUnknownIdsAreReported() {
        val missing = StateId("Missing")
        val broken = StateChartDefinition(
            initial = missing,
            states = listOf(AtomicState(idle), AtomicState(idle)),
            transitions = listOf(Transition(idle, ready, submit), Transition(missing, idle, retry)),
        )

        assertEquals(
            listOf(
                ValidationIssue.DuplicateStateId(idle),
                ValidationIssue.UnknownInitialState(missing),
                ValidationIssue.UnknownTransitionTarget(Transition(idle, ready, submit)),
                ValidationIssue.UnknownTransitionSource(Transition(missing, idle, retry)),
            ),
            broken.validate(),
        )
    }

    @Test
    fun unguardedTransitionsOnSameActionAreAmbiguous() {
        val ambiguous = chart.copy(transitions = chart.transitions + Transition(idle, ready, submit))

        assertEquals(
            listOf(
                ValidationIssue.AmbiguousTransitions(
                    source = idle,
                    on = submit,
                    transitions = listOf(Transition(idle, error, submit), Transition(idle, ready, submit)),
                ),
            ),
            ambiguous.validate(),
        )
    }

    @Test
    fun rendersMermaidStateDiagram() {
        assertEquals(
            """
            stateDiagram-v2
                [*] --> Idle
                Idle --> Loading : Submit [isValid]
                Idle --> Error : Submit
                Loading --> Ready : Loaded
                Loading --> Error : Failed
                Error --> Loading : Retry
            """.trimIndent(),
            chart.toMermaid(),
        )
    }

    @Test
    fun mermaidAliasesIdsThatAreNotIdentifiers() {
        val start = StateId("Not signed in")
        val done = StateId("Signed-in")
        val chart = StateChartDefinition(
            initial = start,
            states = listOf(AtomicState(start), AtomicState(done)),
            transitions = listOf(Transition(start, done, submit)),
        )

        assertEquals(
            """
            stateDiagram-v2
                state "Not signed in" as koma_state_0
                state "Signed-in" as koma_state_1
                [*] --> koma_state_0
                koma_state_0 --> koma_state_1 : Submit
            """.trimIndent(),
            chart.toMermaid(),
        )
    }
}

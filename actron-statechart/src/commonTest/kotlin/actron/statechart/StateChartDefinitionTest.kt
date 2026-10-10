package actron.statechart

import actron.core.Action
import actron.core.ExperimentalActronApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
@OptIn(ExperimentalActronApi::class)
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

    private val submit = ActionMatcher.of<FormAction.Submit>("Submit")
    private val loaded = ActionMatcher.of<FormAction.Loaded>("Loaded")
    private val failed = ActionMatcher.of<FormAction.Failed>("Failed")
    private val retry = ActionMatcher.of<FormAction.Retry>("Retry")

    private val chart = StateChartDefinition(
        initial = idle,
        states = listOf(AtomicState(idle), AtomicState(loading), AtomicState(ready), AtomicState(error)),
        transitions = listOf(
            Transition(idle, loading, submit, guard = actron.statechart.GuardKey("isValid")),
            Transition(idle, error, submit),
            Transition(loading, ready, loaded),
            Transition(loading, error, failed),
            Transition(error, loading, retry),
        ),
    )

    sealed interface ChatAction : Action {
        data class Send(val text: String) : ChatAction
        data object Clear : ChatAction
    }

    @Test
    fun actionMatcherOfUsesTheGivenNameAndTheType() {
        assertEquals(ActionMatcher("Submit", FormAction.Submit::class), submit)
        assertEquals(ActionMatcher("Send message", ChatAction.Send::class), ActionMatcher.of<ChatAction.Send>("Send message"))
        assertFailsWith<IllegalArgumentException> { ActionMatcher.of<ChatAction.Send>(" ") }
    }

    @Test
    fun matchesByTypeIncludingSubtypesOrBySimpleName() {
        val any = ActionMatcher.of<ChatAction>("Chat")
        assertTrue(any.matches(ChatAction.Send("hi")))
        assertTrue(any.matches(ChatAction.Clear))
        assertFalse(any.matches(FormAction.Submit))
        assertTrue(ActionMatcher.of<ChatAction.Send>("Renamed").matches(ChatAction.Send("hi")))
        assertTrue(ActionMatcher("Send").matches(ChatAction.Send("hi")))
        assertFalse(ActionMatcher("Renamed").matches(ChatAction.Send("hi")))
    }

    @Test
    fun blankIdsAreRejected() {
        assertFailsWith<IllegalArgumentException> { StateId(" ") }
        assertFailsWith<IllegalArgumentException> { ActionMatcher("") }
    }

    @Test
    fun transitionsFromKeepsDeclarationOrder() {
        assertEquals(
            listOf(Transition(idle, loading, submit, guard = actron.statechart.GuardKey("isValid")), Transition(idle, error, submit)),
            chart.transitionsFrom(idle),
        )
    }

    @Test
    fun validChartHasNoIssues() {
        assertTrue(chart.validate().isEmpty())
        assertTrue(chart.validate(listOf(FormAction.Submit, FormAction.Loaded, FormAction.Failed, FormAction.Retry)).isEmpty())
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

    private val chatIdle = StateId("ChatIdle")
    private val sending = StateId("Sending")
    private val cleared = StateId("Cleared")
    private val anyChat = ActionMatcher.of<ChatAction>("Chat")
    private val send = ActionMatcher.of<ChatAction.Send>("Send")
    private val chatSamples = listOf(ChatAction.Send("hi"), ChatAction.Clear)

    /**
     * ```
     * [*] --> ChatIdle
     * ChatIdle --Chat--> Cleared      (matches every ChatAction)
     * ChatIdle --Send--> Sending      (never fires: Chat is declared first)
     * ```
     */
    private val shadowing = StateChartDefinition(
        initial = chatIdle,
        states = listOf(AtomicState(chatIdle), AtomicState(sending), AtomicState(cleared)),
        transitions = listOf(Transition(chatIdle, cleared, anyChat), Transition(chatIdle, sending, send)),
    )

    @Test
    fun supertypeMatcherShadowingSubtypeIsReportedOnlyWithSamples() {
        assertEquals(emptyList(), shadowing.validate())
        assertEquals(
            listOf(
                ValidationIssue.ShadowedTransitions(
                    source = chatIdle,
                    sample = ChatAction.Send("hi"),
                    transitions = listOf(Transition(chatIdle, cleared, anyChat), Transition(chatIdle, sending, send)),
                ),
            ),
            shadowing.validate(chatSamples),
        )
    }

    @Test
    fun guardedOverlapIsNotShadowing() {
        val guarded = shadowing.copy(
            transitions = listOf(Transition(chatIdle, cleared, anyChat, guard = actron.statechart.GuardKey("isEmpty")), Transition(chatIdle, sending, send)),
        )

        assertEquals(emptyList(), guarded.validate(chatSamples))
    }

    @Test
    fun equalMatchersAreReportedAsAmbiguityNotShadowing() {
        val sameMatcher = shadowing.copy(
            transitions = listOf(Transition(chatIdle, cleared, send), Transition(chatIdle, sending, send)),
        )

        assertEquals(
            listOf(ValidationIssue.AmbiguousTransitions(chatIdle, send, sameMatcher.transitions)),
            sameMatcher.validate(chatSamples),
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
    fun mermaidHighlightsTheActiveStates_byTheirReference_andIgnoresUnknownIds() {
        val plain = chart.toMermaid()

        val highlighted = chart.toMermaid(setOf(StateId("Loading"), StateId("Nowhere")))

        assertEquals(
            plain + "\n    classDef actron_active fill:#ffe0b2,stroke:#ef6c00,stroke-width:2px\n    class Loading actron_active",
            highlighted,
        )
        assertEquals(plain, chart.toMermaid(emptySet()))
        val start = StateId("Not signed in")
        val done = StateId("Signed-in")
        val aliased = StateChartDefinition(initial = start, states = listOf(AtomicState(start), AtomicState(done)), transitions = listOf(Transition(start, done, submit)))
        assertTrue(aliased.toMermaid(setOf(start, done)).endsWith("class actron_state_0,actron_state_1 actron_active"), aliased.toMermaid(setOf(start, done)))
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
                state "Not signed in" as actron_state_0
                state "Signed-in" as actron_state_1
                [*] --> actron_state_0
                actron_state_0 --> actron_state_1 : Submit
            """.trimIndent(),
            chart.toMermaid(),
        )
    }
}

package actron.statechart

import actron.core.Action
import actron.core.ExperimentalActronApi
import actron.core.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Example tests for compound states: model helpers, runtime exit/entry order, validation, Mermaid
 * and paths.
 *
 * ```
 * [*] --> Online
 * state Online {
 *     [*] --> Idle
 *     Idle --Open--> Chat                     enters Chat, then its initial Reading
 *     state Chat {
 *         [*] --> Reading
 *         Reading --Type--> Typing
 *         Typing --Send--> Reading
 *         Typing --Close [hasDraft]--> Reading   inner: wins over Chat --Close--> Idle
 *         Reading --Home--> Online              to an ancestor: exits and re-enters Online
 *     }
 *     Chat --Close--> Idle
 *     Chat --Refresh--> Chat                  external self-loop: exits and re-enters Chat
 * }
 * Online --Disconnect--> Offline             from any leaf of Online, innermost exited first
 * Online --DeepLink--> Typing                to a descendant: exits and re-enters Online
 * Offline --Reconnect--> Online
 * ```
 */
@OptIn(ExperimentalActronApi::class)
class StateChartHierarchyTest {

    sealed interface ChatAction : Action {
        data object Open : ChatAction
        data object Close : ChatAction
        data object Type : ChatAction
        data object Send : ChatAction
        data object Disconnect : ChatAction
        data object Reconnect : ChatAction
        data object Refresh : ChatAction
        data object DeepLink : ChatAction
        data object Home : ChatAction
    }

    sealed interface ChatState : State {
        data object Idle : ChatState
        data object Reading : ChatState
        data class Typing(val draft: String) : ChatState
        data object Offline : ChatState
    }

    private val online = StateId("Online")
    private val idle = StateId("Idle")
    private val chat = StateId("Chat")
    private val reading = StateId("Reading")
    private val typing = StateId("Typing")
    private val offline = StateId("Offline")

    private val open = Transition(idle, chat, ActionMatcher.of<ChatAction.Open>("Open"))
    private val type = Transition(reading, typing, ActionMatcher.of<ChatAction.Type>("Type"))
    private val send = Transition(typing, reading, ActionMatcher.of<ChatAction.Send>("Send"))
    private val keepDraft = Transition(typing, reading, ActionMatcher.of<ChatAction.Close>("Close"), guard = actron.statechart.GuardKey("hasDraft"))
    private val home = Transition(reading, online, ActionMatcher.of<ChatAction.Home>("Home"))
    private val close = Transition(chat, idle, ActionMatcher.of<ChatAction.Close>("Close"))
    private val refresh = Transition(chat, chat, ActionMatcher.of<ChatAction.Refresh>("Refresh"))
    private val disconnect = Transition(online, offline, ActionMatcher.of<ChatAction.Disconnect>("Disconnect"))
    private val deepLink = Transition(online, typing, ActionMatcher.of<ChatAction.DeepLink>("DeepLink"))
    private val reconnect = Transition(offline, online, ActionMatcher.of<ChatAction.Reconnect>("Reconnect"))

    private val chart = StateChartDefinition(
        initial = online,
        states = listOf(
            CompoundState(online, initial = idle),
            AtomicState(idle, parent = online),
            CompoundState(chat, initial = reading, parent = online),
            AtomicState(reading, parent = chat),
            AtomicState(typing, parent = chat),
            AtomicState(offline),
        ),
        transitions = listOf(open, type, send, keepDraft, home, close, refresh, disconnect, deepLink, reconnect),
    )

    private val stateIdOf: (ChatState) -> StateId = { state ->
        when (state) {
            ChatState.Idle -> idle
            ChatState.Reading -> reading
            is ChatState.Typing -> typing
            ChatState.Offline -> offline
        }
    }

    private val runtime = StateChartRuntime(
        definition = chart,
        stateIdOf = stateIdOf,
        guards = mapOf("hasDraft" to { state, _ -> (state as ChatState.Typing).draft.isNotEmpty() }),
    )

    private fun configuration(vararg ids: String) = StateConfiguration(ids.map(::StateId).toSet())

    private fun transitioned(transition: Transition, exited: List<StateId>, entered: List<StateId>, vararg active: String) =
        StepResult.Transitioned(listOf(transition), exited, entered, configuration(*active))

    // region model

    @Test
    fun hierarchyHelpersFollowParentLinks() {
        assertEquals(CompoundState(chat, initial = reading, parent = online), chart.node(chat))
        assertFalse(chart.hasNode(StateId("Nowhere")))
        assertFalse(chart.nodeSatisfies(StateId("Nowhere")) { true })
        assertFailsWith<NoSuchElementException> { chart.node(StateId("Nowhere")) }
        assertEquals(listOf(online, offline), chart.childrenOf(StateParent.Root).map { it.id })
        assertEquals(listOf(idle, chat), chart.childrenOf(online).map { it.id })
        assertEquals(emptyList(), chart.childrenOf(typing))
        assertEquals(listOf(chat, online), chart.ancestorsOf(typing))
        assertEquals(emptyList(), chart.ancestorsOf(offline))
        assertEquals(emptyList(), chart.ancestorsOf(StateId("Nowhere")))
        assertTrue(chart.isDescendant(typing, online))
        assertFalse(chart.isDescendant(online, online))
        assertFalse(chart.isDescendant(online, typing))
        assertEquals(configuration("Online", "Chat", "Typing"), chart.configurationOf(typing))
        assertEquals(listOf(online, chat, typing), chart.configurationOf(typing).active.toList())
        assertEquals(emptyMap(), chart.configurationOf(typing).history)
    }

    @Test
    fun atomicStateKeepsItsOneArgumentConstructor() {
        assertEquals(AtomicState(idle, parent = StateParent.Root), AtomicState(idle))
        assertEquals(StateParent.Root, AtomicState(idle).parent)
    }

    @Test
    fun ancestorsStopAtAParentCycleAndAtAnUndeclaredParent() {
        val (a, b, c) = listOf("A", "B", "C").map(::StateId)
        val cyclic = StateChartDefinition(a, listOf(CompoundState(a, b, parent = b), CompoundState(b, a, parent = a), AtomicState(c, parent = c)), emptyList())
        assertEquals(listOf(b), cyclic.ancestorsOf(a))
        assertEquals(emptyList(), cyclic.ancestorsOf(c))
        val orphan = StateChartDefinition(a, listOf(AtomicState(a, parent = StateId("ghost"))), emptyList())
        assertEquals(emptyList(), orphan.ancestorsOf(a))
    }

    // endregion

    // region runtime

    @Test
    fun initialConfigurationEntersTheInitialChildOfTheInitialState() {
        val initial = runtime.initialConfiguration()

        assertEquals(listOf(online, idle), initial.active.toList())
        assertEquals(listOf(idle), chart.activeLeaves(initial))
    }

    @Test
    fun nestedInitialStateIsEnteredWithItsAncestors() {
        assertEquals(listOf(online, chat, reading), StateChartRuntime(chart.copy(initial = chat), stateIdOf, mapOf("hasDraft" to { _, _ -> true })).initialConfiguration().active.toList())
    }

    @Test
    fun enteringACompoundStateEntersItsInitialChild() {
        val result = runtime.step(runtime.initialConfiguration(), ChatState.Idle, ChatAction.Open)

        assertEquals(transitioned(open, exited = listOf(idle), entered = listOf(chat, reading), "Online", "Chat", "Reading"), result)
        result as StepResult.Transitioned
        assertEquals(open, result.transition)
        assertEquals(chat, result.target)
        assertEquals(listOf(online, chat, reading), result.configuration.active.toList())
    }

    @Test
    fun siblingTransitionOnlyExitsAndEntersBelowTheParent() {
        assertEquals(
            transitioned(type, exited = listOf(reading), entered = listOf(typing), "Online", "Chat", "Typing"),
            runtime.step(chart.configurationOf(reading), ChatState.Reading, ChatAction.Type),
        )
    }

    @Test
    fun ancestorTransitionExitsInnermostFirst() {
        assertEquals(
            transitioned(disconnect, exited = listOf(typing, chat, online), entered = listOf(offline), "Offline"),
            runtime.step(chart.configurationOf(typing), ChatState.Typing("hi"), ChatAction.Disconnect),
        )
        assertEquals(
            transitioned(reconnect, exited = listOf(offline), entered = listOf(online, idle), "Online", "Idle"),
            runtime.step(chart.configurationOf(offline), ChatState.Offline, ChatAction.Reconnect),
        )
    }

    @Test
    fun innerTransitionWinsOverOuterOneWhenItsGuardHolds() {
        assertEquals(
            transitioned(keepDraft, exited = listOf(typing), entered = listOf(reading), "Online", "Chat", "Reading"),
            runtime.step(chart.configurationOf(typing), ChatState.Typing("draft"), ChatAction.Close),
        )
        assertEquals(
            transitioned(close, exited = listOf(typing, chat), entered = listOf(idle), "Online", "Idle"),
            runtime.step(chart.configurationOf(typing), ChatState.Typing(""), ChatAction.Close),
        )
    }

    @Test
    fun selfLoopOnACompoundStateIsExternal() {
        assertEquals(
            transitioned(refresh, exited = listOf(typing, chat), entered = listOf(chat, reading), "Online", "Chat", "Reading"),
            runtime.step(chart.configurationOf(typing), ChatState.Typing(""), ChatAction.Refresh),
        )
    }

    @Test
    fun transitionToADescendantExitsAndReentersTheSource() {
        assertEquals(
            transitioned(deepLink, exited = listOf(idle, online), entered = listOf(online, chat, typing), "Online", "Chat", "Typing"),
            runtime.step(chart.configurationOf(idle), ChatState.Idle, ChatAction.DeepLink),
        )
    }

    @Test
    fun transitionToAnAncestorExitsAndReentersTheAncestor() {
        assertEquals(
            transitioned(home, exited = listOf(reading, chat, online), entered = listOf(online, idle), "Online", "Idle"),
            runtime.step(chart.configurationOf(reading), ChatState.Reading, ChatAction.Home),
        )
    }

    @Test
    fun actionWithoutTransitionOnTheLeafOrItsAncestorsIsIgnored() {
        assertEquals(StepResult.Ignored, runtime.step(chart.configurationOf(idle), ChatState.Idle, ChatAction.Reconnect))
        assertEquals(StepResult.Ignored, runtime.step(chart.configurationOf(offline), ChatState.Offline, ChatAction.Close))
    }

    @Test
    fun stepWithoutConfigurationDerivesItFromTheLeaf() {
        for (state in listOf(ChatState.Idle, ChatState.Reading, ChatState.Typing(""), ChatState.Typing("x"), ChatState.Offline)) {
            for (action in listOf(ChatAction.Open, ChatAction.Close, ChatAction.Disconnect, ChatAction.Refresh, ChatAction.Home)) {
                assertEquals(runtime.step(chart.configurationOf(stateIdOf(state)), state, action), runtime.step(state, action), "$state on $action")
            }
        }
    }

    @Test
    fun severalActiveLeavesPickOneTransitionEachUnlessTheirExitSetsMeet() {
        // Only parallel states will produce such configurations; the selection rule is already the
        // documented one.
        val (p, a, a2) = listOf("P", "A", "A2").map(::StateId)
        val (q, b, b2) = listOf("Q", "B", "B2").map(::StateId)
        val ping = ActionMatcher.of<RandomAction.Ping>("Ping")
        val pong = ActionMatcher.of<RandomAction.Pong>("Pong")
        val insideP = Transition(a, a2, ping)
        val insideQ = Transition(b, b2, ping)
        val leaveP = Transition(p, p, pong)
        val leaveQ = Transition(q, q, pong)
        val twoRoots = StateChartDefinition(
            initial = p,
            states = listOf(CompoundState(p, a), AtomicState(a, p), AtomicState(a2, p), CompoundState(q, b), AtomicState(b, q), AtomicState(b2, q)),
            transitions = listOf(insideP, insideQ, leaveP, leaveQ),
        )
        val both = StateChartRuntime(twoRoots, { s: RandomState -> s.id })
        val start = StateConfiguration(setOf(p, a, q, b))

        assertEquals(listOf(a, b), twoRoots.activeLeaves(start))
        assertEquals(
            // Same depth: exited in reverse declaration order, entered in declaration order.
            StepResult.Transitioned(listOf(insideP, insideQ), listOf(b, a), listOf(a2, b2), StateConfiguration(setOf(p, q, a2, b2))),
            both.step(start, RandomState(a), RandomAction.Ping),
        )
        // A top-level self-loop has the implicit root as its domain and exits everything, so Q's
        // self-loop conflicts with it and is dropped.
        assertEquals(
            StepResult.Transitioned(listOf(leaveP), listOf(b, a, q, p), listOf(p, a), StateConfiguration(setOf(p, a))),
            both.step(start, RandomState(a), RandomAction.Pong),
        )
    }

    @Test
    fun activeLeavesComeInDeclarationOrderWithUndeclaredIdsLast() {
        val ghost = StateId("Ghost")

        assertEquals(listOf(idle, offline, ghost), chart.activeLeaves(StateConfiguration(setOf(ghost, offline, online, idle))))
        assertEquals(emptyList(), chart.activeLeaves(StateConfiguration(emptySet())))
    }

    @Test
    fun flatTransitionedConstructorDescribesOneFlatStep() {
        val result = StepResult.Transitioned(open)

        assertEquals(listOf(open), result.transitions)
        assertEquals(listOf(idle), result.exited)
        assertEquals(listOf(chat), result.entered)
        assertEquals(StateConfiguration(setOf(chat)), result.configuration)
        assertFailsWith<IllegalArgumentException> { StepResult.Transitioned(emptyList(), emptyList(), emptyList(), StateConfiguration(emptySet())) }
    }

    @Test
    fun malformedHierarchyIsRejectedByTheRuntime() {
        val broken = chart.copy(states = chart.states + AtomicState(StateId("Stray"), parent = idle))
        val error = assertFailsWith<IllegalArgumentException> { StateChartRuntime(broken, stateIdOf, mapOf("hasDraft" to { _, _ -> true })) }

        assertEquals("[Actron] Malformed state hierarchy: AtomicParent(id=Stray, parent=Idle)", error.message)
    }

    // endregion

    // region validation

    @Test
    fun wellFormedHierarchyHasNoIssues() {
        assertEquals(emptyList(), chart.validate())
        assertEquals(setOf(online, idle, chat, reading, typing, offline), chart.reachableStates())
    }

    @Test
    fun initialChildIsReachableAndAStateNobodyEntersIsNot() {
        val muted = StateId("Muted")
        val lonely = chart.copy(
            states = chart.states + AtomicState(muted, parent = chat),
            transitions = chart.transitions + Transition(muted, offline, ActionMatcher("Go")),
        )
        assertEquals(listOf(ValidationIssue.UnreachableState(muted)), lonely.validate())
        // Reachable only as the initial child of a compound target.
        val noTyping = chart.copy(transitions = listOf(open))
        assertEquals(listOf(online, idle, chat, reading), noTyping.reachableStates().toList())
        assertEquals(listOf(ValidationIssue.UnreachableState(typing), ValidationIssue.UnreachableState(offline)), noTyping.validate())
    }

    @Test
    fun malformedHierarchyIssuesAreReportedAfterTheInitialStateAndBeforeEndpoints() {
        val (a, b, c) = listOf("A", "B", "C").map(::StateId)
        val (ghost, empty, stray) = listOf("Ghost", "Empty", "Stray").map(::StateId)
        val broken = StateChartDefinition(
            initial = StateId("Nowhere"),
            states = listOf(
                AtomicState(stray, parent = idle),
                AtomicState(ghost, parent = StateId("Missing")),
                CompoundState(b, initial = c, parent = a),
                CompoundState(a, initial = c, parent = b),
                AtomicState(c, parent = a),
                AtomicState(idle, parent = c),
                CompoundState(empty, initial = ghost),
                AtomicState(stray),
            ),
            transitions = listOf(Transition(a, StateId("Void"), ActionMatcher("Go"))),
        )

        assertEquals(
            listOf(
                ValidationIssue.DuplicateStateId(stray),
                ValidationIssue.UnknownInitialState(StateId("Nowhere")),
                ValidationIssue.AtomicParent(stray, idle),
                ValidationIssue.UnknownParent(ghost, StateId("Missing")),
                ValidationIssue.AtomicParent(idle, c),
                ValidationIssue.ParentCycle(listOf(b, a)),
                ValidationIssue.InitialNotChild(b, c),
                ValidationIssue.InitialNotChild(empty, ghost),
                ValidationIssue.EmptyCompoundState(empty),
                ValidationIssue.UnknownTransitionTarget(broken.transitions[0]),
            ),
            broken.validate(),
        )
    }

    @Test
    fun selfParentIsACycleOfOne() {
        val a = StateId("A")
        assertEquals(listOf(ValidationIssue.ParentCycle(listOf(a))), StateChartDefinition(a, listOf(AtomicState(a, parent = a)), emptyList()).validate().filterIsInstance<ValidationIssue.ParentCycle>())
    }

    // endregion

    // region mermaid

    @Test
    fun mermaidNestsCompoundStates() {
        assertEquals(
            """
            stateDiagram-v2
                [*] --> Online
                state Online {
                    [*] --> Idle
                    Idle
                    state Chat {
                        [*] --> Reading
                        Reading
                        Typing
                        Reading --> Typing : Type
                        Typing --> Reading : Send
                        Typing --> Reading : Close [hasDraft]
                    }
                    Idle --> Chat : Open
                    Chat --> Idle : Close
                    Chat --> Chat : Refresh
                }
                Reading --> Online : Home
                Online --> Offline : Disconnect
                Online --> Typing : DeepLink
                Offline --> Online : Reconnect
            """.trimIndent(),
            chart.toMermaid(),
        )
    }

    @Test
    fun mermaidAliasesNestedIdsInTheirBlock() {
        val signedIn = StateId("Signed in")
        val state = StateId("state")
        val ghost = StateId("not declared")
        val chart = StateChartDefinition(
            initial = signedIn,
            states = listOf(CompoundState(signedIn, initial = state), AtomicState(state, parent = signedIn), AtomicState(StateId("Plain"), parent = signedIn)),
            transitions = listOf(Transition(state, ghost, ActionMatcher("Go"))),
        )

        assertEquals(
            """
            stateDiagram-v2
                state "not declared" as actron_state_2
                [*] --> actron_state_0
                state "Signed in" as actron_state_0 {
                    [*] --> actron_state_1
                    state "state" as actron_state_1
                    Plain
                }
                actron_state_1 --> actron_state_2 : Go
            """.trimIndent(),
            chart.toMermaid(),
        )
    }

    @Test
    fun mermaidDrawsStatesWithABrokenParentAtTheTopLevel() {
        val (a, b, c) = listOf("A", "B", "C").map(::StateId)
        val chart = StateChartDefinition(
            initial = a,
            states = listOf(CompoundState(a, initial = b, parent = b), CompoundState(b, initial = a, parent = a), AtomicState(c, parent = StateId("Ghost"))),
            transitions = listOf(Transition(a, c, ActionMatcher("Go"))),
        )

        assertEquals(
            """
            stateDiagram-v2
                [*] --> A
                state A {
                    [*] --> B
                    state B {
                        [*] --> A
                    }
                }
                A --> C : Go
            """.trimIndent(),
            chart.toMermaid(),
        )
    }

    // endregion

    // region paths

    @Test
    fun shortestPathEndsInTheFirstConfigurationWhereTheStateIsActive() {
        // DeepLink reaches Typing in one step, before Open and Type do in two.
        assertEquals(StateChartPath(idle, listOf(deepLink), listOf(typing)), chart.shortestPathTo(typing))
        assertEquals(StateChartPath(idle, listOf(open), listOf(reading)), chart.shortestPathTo(chat))
        assertEquals(StateChartPath(idle, emptyList(), emptyList()), chart.shortestPathTo(online))
        assertEquals(reading, chart.shortestPathTo(chat)?.end)
        assertNull(chart.shortestPathTo(StateId("Nowhere")))
    }

    @Test
    fun coveragePathsTakeTransitionsFromAncestorsOfTheCurrentLeaf() {
        assertEquals(
            listOf(
                StateChartPath(idle, listOf(open, type), listOf(reading, typing)),
                StateChartPath(idle, listOf(deepLink, send), listOf(typing, reading)),
                StateChartPath(idle, listOf(deepLink, keepDraft), listOf(typing, reading)),
                StateChartPath(idle, listOf(open, home), listOf(reading, idle)),
                StateChartPath(idle, listOf(open, close), listOf(reading, idle)),
                StateChartPath(idle, listOf(open, refresh), listOf(reading, reading)),
                StateChartPath(idle, listOf(disconnect, reconnect), listOf(offline, idle)),
            ),
            chart.transitionCoveragePaths(),
        )
    }

    @Test
    fun explicitLeavesMustMatchTheTransitions() {
        val error = assertFailsWith<IllegalArgumentException> { StateChartPath(idle, listOf(open), emptyList()) }
        assertEquals("[Actron] Path has 1 transitions but 0 leaves", error.message)
        // The explicit form does not know the hierarchy, so it does not check connections.
        assertEquals(typing, StateChartPath(idle, listOf(disconnect), listOf(typing)).end)
    }

    // endregion
}

package koma.statechart

import koma.core.Action
import koma.core.Event
import koma.core.ExperimentalKomaApi
import koma.core.State
import koma.core.Store
import koma.test.dispatchAndAwait
import koma.test.patch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Example tests for history states: recording on exit, restoring on entry, defaults, validation,
 * Mermaid, paths and conformance, on a small messenger.
 *
 * ```
 * [*] --> Splash
 * Splash --Start--> MainHistory             nothing recorded: the deep default, Composing
 * state Main {
 *     [*] --> Inbox
 *     state "[H*]" as MainHistory            deep, default Composing (two levels down)
 *     MainHistory --> Composing
 *     Inbox --Open--> ChatHistory            first time: Chat's initial, Composing
 *     state Chat {
 *         [*] --> Composing
 *         state "[H]" as ChatHistory         shallow, no default
 *         Composing --Send--> Sending
 *         Sending --Sent--> Composing
 *         Sending --Retry--> ChatHistory     Chat is not exited: restores the older record
 *     }
 *     Chat --Settings--> Settings            records ChatHistory = Sending
 *     Settings --Back--> ChatHistory         restores Sending
 *     Chat --Close--> Inbox
 * }
 * Main --Lock--> Locked                      records MainHistory = Sending (deep) or Chat (shallow)
 * Locked --Unlock--> MainHistory             restores Main, Chat, Sending
 * ```
 */
@OptIn(ExperimentalKomaApi::class)
class StateChartHistoryTest {

    sealed interface ChatAction : Action {
        data object Start : ChatAction
        data object Open : ChatAction
        data object Send : ChatAction
        data object Sent : ChatAction
        data object Retry : ChatAction
        data object Settings : ChatAction
        data object Back : ChatAction
        data object Close : ChatAction
        data object Lock : ChatAction
        data object Unlock : ChatAction
    }

    sealed interface ChatState : State {
        data object Splash : ChatState
        data object Inbox : ChatState
        data object Composing : ChatState
        data object Sending : ChatState
        data class Settings(val returnTo: ChatState) : ChatState
        data class Locked(val returnTo: ChatState) : ChatState
    }

    sealed interface ChatEvent : Event

    private val splash = StateId("Splash")
    private val main = StateId("Main")
    private val mainHistory = StateId("MainHistory")
    private val inbox = StateId("Inbox")
    private val chat = StateId("Chat")
    private val chatHistory = StateId("ChatHistory")
    private val composing = StateId("Composing")
    private val sending = StateId("Sending")
    private val settings = StateId("Settings")
    private val locked = StateId("Locked")

    private val start = Transition(splash, mainHistory, ActionMatcher.of<ChatAction.Start>("Start"))
    private val open = Transition(inbox, chatHistory, ActionMatcher.of<ChatAction.Open>("Open"))
    private val send = Transition(composing, sending, ActionMatcher.of<ChatAction.Send>("Send"))
    private val sent = Transition(sending, composing, ActionMatcher.of<ChatAction.Sent>("Sent"))
    private val retry = Transition(sending, chatHistory, ActionMatcher.of<ChatAction.Retry>("Retry"))
    private val toSettings = Transition(chat, settings, ActionMatcher.of<ChatAction.Settings>("Settings"))
    private val back = Transition(settings, chatHistory, ActionMatcher.of<ChatAction.Back>("Back"))
    private val close = Transition(chat, inbox, ActionMatcher.of<ChatAction.Close>("Close"))
    private val lock = Transition(main, locked, ActionMatcher.of<ChatAction.Lock>("Lock"))
    private val unlock = Transition(locked, mainHistory, ActionMatcher.of<ChatAction.Unlock>("Unlock"))

    private val chart = StateChartDefinition(
        initial = splash,
        states = listOf(
            AtomicState(splash),
            CompoundState(main, initial = inbox),
            HistoryState(mainHistory, parent = main, deep = true, default = composing),
            AtomicState(inbox, parent = main),
            CompoundState(chat, initial = composing, parent = main),
            HistoryState(chatHistory, parent = chat),
            AtomicState(composing, parent = chat),
            AtomicState(sending, parent = chat),
            AtomicState(settings, parent = main),
            AtomicState(locked),
        ),
        transitions = listOf(start, open, send, sent, retry, toSettings, back, close, lock, unlock),
    )

    private val stateIdOf: (ChatState) -> StateId = { state ->
        when (state) {
            ChatState.Splash -> splash
            ChatState.Inbox -> inbox
            ChatState.Composing -> composing
            ChatState.Sending -> sending
            is ChatState.Settings -> settings
            is ChatState.Locked -> locked
        }
    }

    private val runtime = StateChartRuntime(chart, stateIdOf)

    /** Steps from [configuration] with [actions]; returns every result. The chart has no guards, so the Koma state is not read. */
    private fun walk(configuration: StateConfiguration, vararg actions: ChatAction): List<StepResult.Transitioned> {
        var current = configuration
        return actions.map { action ->
            (runtime.step(current, ChatState.Splash, action) as StepResult.Transitioned).also { current = it.configuration }
        }
    }

    private fun afterStart() = walk(runtime.initialConfiguration(), ChatAction.Start).single().configuration

    // region runtime

    @Test
    fun firstEntryThroughADeepHistoryEntersItsDefaultWithEveryAncestor() {
        assertEquals(StateConfiguration(setOf(splash)), runtime.initialConfiguration())
        val result = walk(runtime.initialConfiguration(), ChatAction.Start).single()
        assertEquals(listOf(splash), result.exited)
        assertEquals(listOf(main, chat, composing), result.entered)
        assertEquals(StateConfiguration(setOf(main, chat, composing)), result.configuration)
        assertEquals(mainHistory, result.target)
        assertEquals(composing, result.entered.last())
    }

    @Test
    fun firstEntryThroughAShallowHistoryWithoutDefaultEntersTheParentsInitialChild() {
        val result = runtime.step(chart.configurationOf(inbox), ChatState.Inbox, ChatAction.Open) as StepResult.Transitioned
        assertEquals(listOf(inbox), result.exited)
        assertEquals(listOf(chat, composing), result.entered)
        assertEquals(StateConfiguration(setOf(main, chat, composing)), result.configuration)
    }

    @Test
    fun leavingToSettingsAndComingBackRestoresSending() {
        val (toSending, toSettingsStep, backStep) = walk(afterStart(), ChatAction.Send, ChatAction.Settings, ChatAction.Back)
        assertEquals(listOf(sending), toSending.entered)
        assertEquals(listOf(sending, chat), toSettingsStep.exited)
        assertEquals(mapOf(chatHistory to setOf(sending)), toSettingsStep.configuration.history)
        assertEquals(listOf(settings), backStep.exited)
        assertEquals(listOf(chat, sending), backStep.entered)
        assertEquals(StateConfiguration(setOf(main, chat, sending), mapOf(chatHistory to setOf(sending))), backStep.configuration)
    }

    @Test
    fun everyExitOverwritesTheRecord() {
        val steps = walk(afterStart(), ChatAction.Send, ChatAction.Settings, ChatAction.Back, ChatAction.Sent, ChatAction.Settings, ChatAction.Back)
        assertEquals(listOf(chat, composing), steps.last().entered)
        assertEquals(mapOf(chatHistory to setOf(composing)), steps.last().configuration.history)
    }

    @Test
    fun deepHistoryRestoresTheLeafTwoLevelsDownAndShallowOnlyTheChild() {
        val (_, lockStep, unlockStep) = walk(afterStart(), ChatAction.Send, ChatAction.Lock, ChatAction.Unlock)
        assertEquals(listOf(sending, chat, main), lockStep.exited)
        assertEquals(mapOf(chatHistory to setOf(sending), mainHistory to setOf(sending)), lockStep.configuration.history)
        assertEquals(listOf(main, chat, sending), unlockStep.entered)

        // The same walk with a shallow history of Main restores Chat, which enters its initial child.
        val shallow = chart.copy(states = chart.states.map { if (it.id == mainHistory) HistoryState(mainHistory, parent = main) else it })
        val shallowRuntime = StateChartRuntime(shallow, stateIdOf)
        var configuration = shallowRuntime.initialConfiguration()
        val results = listOf(ChatAction.Start, ChatAction.Open, ChatAction.Send, ChatAction.Lock, ChatAction.Unlock).map { action ->
            (shallowRuntime.step(configuration, ChatState.Splash, action) as StepResult.Transitioned).also { configuration = it.configuration }
        }
        assertEquals(listOf(main, inbox), results[0].entered) // no default: Main's initial child
        assertEquals(mapOf(chatHistory to setOf(sending), mainHistory to setOf(chat)), results[3].configuration.history)
        assertEquals(listOf(main, chat, composing), results[4].entered)
    }

    @Test
    fun transitionInsideTheParentRestoresTheOlderRecordBecauseTheParentStaysActive() {
        val steps = walk(afterStart(), ChatAction.Settings, ChatAction.Back, ChatAction.Send, ChatAction.Retry)
        // Chat was last exited in Composing; Retry leaves Sending but not Chat, so nothing new is recorded.
        assertEquals(listOf(sending), steps.last().exited)
        assertEquals(listOf(composing), steps.last().entered)
        assertEquals(mapOf(chatHistory to setOf(composing)), steps.last().configuration.history)
    }

    @Test
    fun leafOnlyStepStartsWithNothingRecorded() {
        val result = runtime.step(ChatState.Settings(ChatState.Sending), ChatAction.Back) as StepResult.Transitioned
        assertEquals(listOf(chat, composing), result.entered)
    }

    @Test
    fun historyStatesAreChildrenButNeverActive() {
        assertEquals(listOf(mainHistory, inbox, chat, settings), chart.childrenOf(main).map { it.id })
        assertEquals(listOf(main), chart.ancestorsOf(mainHistory))
        assertEquals(listOf(composing), chart.activeLeaves(afterStart()))
    }

    // endregion

    // region validation

    @Test
    fun wellFormedHistoryHasNoIssuesAndIsNeverUnreachable() {
        assertEquals(emptyList(), chart.validate())
        assertEquals(setOf(splash, main, chat, composing, sending, settings, inbox, locked), chart.reachableStates())
        // Nothing targets ChatHistory any more: still not reported, a history state is never active.
        assertEquals(emptyList(), chart.copy(transitions = listOf(start, send, toSettings, close, lock, unlock)).validate())
    }

    @Test
    fun malformedHistoryIsReportedInOrder() {
        val broken = StateChartDefinition(
            initial = chatHistory,
            states = listOf(
                CompoundState(main, initial = mainHistory),
                HistoryState(mainHistory, parent = main, deep = true, default = main),
                AtomicState(inbox, parent = main),
                CompoundState(chat, initial = composing, parent = main),
                HistoryState(chatHistory, parent = chat, default = sending),
                AtomicState(composing, parent = chat),
                AtomicState(sending, parent = chatHistory),
                HistoryState(StateId("OnAtomic"), parent = inbox, default = StateId("Nowhere")),
                HistoryState(StateId("Orphan"), parent = StateId("Ghost")),
                HistoryState(StateId("Shallow"), parent = main, default = composing),
                HistoryState(StateId("ToHistory"), parent = main, deep = true, default = chatHistory),
            ),
            transitions = listOf(Transition(chatHistory, inbox, ActionMatcher("Go"))),
        )
        assertEquals(
            listOf(
                ValidationIssue.HistoryParent(sending, chatHistory),
                ValidationIssue.AtomicParent(StateId("OnAtomic"), inbox),
                ValidationIssue.UnknownParent(StateId("Orphan"), StateId("Ghost")),
                ValidationIssue.HistoryAsInitial(null, chatHistory),
                ValidationIssue.HistoryAsInitial(main, mainHistory),
                ValidationIssue.InvalidHistoryDefault(mainHistory, main),
                ValidationIssue.InvalidHistoryDefault(chatHistory, sending),
                ValidationIssue.InvalidHistoryDefault(StateId("Shallow"), composing),
                ValidationIssue.InvalidHistoryDefault(StateId("ToHistory"), chatHistory),
                ValidationIssue.TransitionFromHistory(Transition(chatHistory, inbox, ActionMatcher("Go"))),
                ValidationIssue.UnreachableState(composing),
            ),
            broken.validate(),
        )
        val error = assertFailsWith<IllegalArgumentException> { StateChartRuntime(broken, stateIdOf) }
        assertTrue(error.message!!.startsWith("[Koma] Malformed state hierarchy: HistoryParent"), error.message)
        // Tools keep working on it.
        assertEquals(listOf(Transition(chatHistory, inbox, ActionMatcher("Go"))), broken.transitionCoveragePaths().single().transitions)
    }

    @Test
    fun historyIssuesAloneAreRejectedByTheRuntime() {
        val deepDefaultAsChild = chart.copy(states = chart.states.map { if (it.id == chatHistory) HistoryState(chatHistory, parent = chat, default = main) else it })
        val error = assertFailsWith<IllegalArgumentException> { StateChartRuntime(deepDefaultAsChild, stateIdOf) }
        assertEquals("[Koma] Malformed state hierarchy: ${ValidationIssue.InvalidHistoryDefault(chatHistory, main)}", error.message)
        // A deep history may default to any descendant, a shallow one only to a child.
        val shallowChildDefault = chart.copy(states = chart.states.map { if (it.id == chatHistory) HistoryState(chatHistory, parent = chat, default = sending) else it })
        assertEquals(emptyList(), shallowChildDefault.validate())
        // Several history states per parent are allowed and remember independently.
        val two = chart.copy(states = chart.states + HistoryState(StateId("MainShallow"), parent = main))
        assertEquals(emptyList(), two.validate())
        val twoRuntime = StateChartRuntime(two, stateIdOf)
        var configuration = twoRuntime.initialConfiguration()
        for (action in listOf(ChatAction.Start, ChatAction.Send, ChatAction.Lock)) {
            configuration = (twoRuntime.step(configuration, ChatState.Splash, action) as StepResult.Transitioned).configuration
        }
        assertEquals(mapOf(chatHistory to setOf(sending), mainHistory to setOf(sending), StateId("MainShallow") to setOf(chat)), configuration.history)
    }

    @Test
    fun historyWithANonCompoundParentOrUnusableDefaultIsEnteredLikeAnAtomicState() {
        // Invalid charts: the runtime rejects them, but paths and reachability still terminate.
        val orphan = StateChartDefinition(splash, listOf(AtomicState(splash), HistoryState(StateId("Orphan"), parent = StateId("Ghost"))), listOf(Transition(splash, StateId("Orphan"), ActionMatcher("Go"))))
        assertEquals(setOf(splash, StateId("Orphan")), orphan.reachableStates())
        val badDefault = chart.copy(states = chart.states.map { if (it.id == chatHistory) HistoryState(chatHistory, parent = chat, default = locked) else it })
        // Retry leaves Sending but not Chat, which has recorded nothing yet, and the default is unusable.
        assertEquals(StateChartPath(splash, listOf(start, send, retry), listOf(composing, sending, chatHistory)), badDefault.shortestPathTo(chatHistory))
        val historyInitial = chart.copy(states = chart.states.map { if (it.id == chat) CompoundState(chat, initial = chatHistory, parent = main) else it })
        assertEquals(StateChartPath(splash, listOf(start), listOf(composing)), historyInitial.shortestPathTo(composing))
        assertNull(historyInitial.shortestPathTo(StateId("Nowhere")))
    }

    // endregion

    // region mermaid

    @Test
    fun mermaidDrawsHistoryAsLabelledStatesWithDefaultEdges() {
        val expected = """
            stateDiagram-v2
                [*] --> Splash
                state Main {
                    [*] --> Inbox
                    state "[H*]" as MainHistory
                    Inbox
                    state Chat {
                        [*] --> Composing
                        state "[H]" as ChatHistory
                        Composing
                        Sending
                        Composing --> Sending : Send
                        Sending --> Composing : Sent
                        Sending --> ChatHistory : Retry
                    }
                    Settings
                    MainHistory --> Composing
                    Inbox --> ChatHistory : Open
                    Chat --> Settings : Settings
                    Settings --> ChatHistory : Back
                    Chat --> Inbox : Close
                }
                Splash --> MainHistory : Start
                Main --> Locked : Lock
                Locked --> MainHistory : Unlock
        """.trimIndent()
        assertEquals(expected, chart.toMermaid())
    }

    @Test
    fun mermaidAliasesHistoryIdsAndDrawsBrokenOnesAtTheTop() {
        val odd = StateChartDefinition(
            initial = StateId("a b"),
            states = listOf(
                CompoundState(StateId("a b"), initial = StateId("in")),
                AtomicState(StateId("in"), parent = StateId("a b")),
                HistoryState(StateId("state"), parent = StateId("a b"), default = StateId("in")),
                HistoryState(StateId("lost"), parent = StateId("in"), deep = true, default = StateId("gone")),
            ),
            transitions = listOf(Transition(StateId("in"), StateId("state"), ActionMatcher("Go"))),
        )
        val expected = """
            stateDiagram-v2
                state "[H*]" as lost
                [*] --> koma_state_0
                state "a b" as koma_state_0 {
                    [*] --> in
                    in
                    state "[H]" as koma_state_1
                    koma_state_1 --> in
                    in --> koma_state_1 : Go
                }
                lost --> gone
        """.trimIndent()
        assertEquals(expected, odd.toMermaid())
    }

    // endregion

    // region paths

    @Test
    fun pathsFollowWhatTheHistoryRemembers() {
        assertEquals(StateChartPath(splash, listOf(start, send), listOf(composing, sending)), chart.shortestPathTo(sending))
        val coverage = chart.transitionCoveragePaths()
        assertEquals(chart.transitions.toSet(), coverage.flatMap { it.transitions }.toSet())
        // Back after Settings from Composing restores Composing.
        assertTrue(StateChartPath(splash, listOf(start, toSettings, back), listOf(composing, settings, composing)) in coverage, "$coverage")
    }

    // endregion

    // region conformance

    /** Keeps the screen to return to in the state, like a real app would. */
    private fun createStore(forgetful: Boolean): Store<ChatState, ChatAction, ChatEvent> = Store(ChatState.Splash) {
        coroutineContext(Dispatchers.Unconfined)
        state<ChatState.Splash> { action<ChatAction.Start> { nextState { ChatState.Composing } } }
        state<ChatState.Composing> {
            action<ChatAction.Send> { nextState { ChatState.Sending } }
            action<ChatAction.Settings> { nextState { ChatState.Settings(state) } }
            action<ChatAction.Lock> { nextState { ChatState.Locked(state) } }
        }
        state<ChatState.Sending> {
            action<ChatAction.Sent> { nextState { ChatState.Composing } }
            action<ChatAction.Settings> { nextState { ChatState.Settings(state) } }
            action<ChatAction.Lock> { nextState { ChatState.Locked(state) } }
        }
        state<ChatState.Settings> {
            action<ChatAction.Back> { nextState { if (forgetful) ChatState.Composing else state.returnTo } }
        }
        state<ChatState.Locked> {
            action<ChatAction.Unlock> { nextState { state.returnTo } }
        }
    }

    @Test
    fun storeThatRestoresConformsAndOneThatForgetsIsReported() = runTest {
        val actions = listOf(ChatAction.Start, ChatAction.Send, ChatAction.Settings, ChatAction.Back, ChatAction.Lock, ChatAction.Unlock)
        val good = StateChartConformance<ChatState, ChatAction, ChatEvent>(chart, stateIdOf)
        val store = createStore(forgetful = false).patch { plugin(good) }
        actions.forEach { store.dispatchAndAwait(it) }
        assertEquals(ChatState.Sending, store.currentState)
        assertEquals(emptyList(), good.violations)
        assertEquals(listOf(start, send, toSettings, back, lock, unlock), good.coveredTransitions.toList())

        val bad = StateChartConformance<ChatState, ChatAction, ChatEvent>(chart, stateIdOf)
        val forgetful = createStore(forgetful = true).patch { plugin(bad) }
        actions.forEach { forgetful.dispatchAndAwait(it) }
        assertEquals(listOf(ConformanceViolation.UndeclaredTransition(settings, composing, ChatAction.Back)), bad.violations)
        // The plugin then follows the Store's Composing: Lock records it, and Unlock restores it.
        assertEquals(listOf(start, send, toSettings, lock, unlock), bad.coveredTransitions.toList())
    }

    @Test
    fun storeStartedFromASavedStateMayRestoreAnythingTheHistoryCouldHold() = runTest {
        // The plugin never saw Chat exited, so Back to Sending is accepted.
        val conformance = StateChartConformance<ChatState, ChatAction, ChatEvent>(chart, stateIdOf)
        val store = createStore(forgetful = false).patch {
            initialState(ChatState.Settings(ChatState.Sending))
            plugin(conformance)
        }
        store.dispatchAndAwait(ChatAction.Back)
        assertEquals(ChatState.Sending, store.currentState)
        assertEquals(emptyList(), conformance.violations)
        assertEquals(listOf(back), conformance.coveredTransitions.toList())
    }

    // endregion
}

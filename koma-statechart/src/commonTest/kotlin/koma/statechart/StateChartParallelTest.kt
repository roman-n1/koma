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
import kotlin.test.assertTrue

/**
 * Example tests for parallel states on a messenger that is online in three regions at once:
 * entry and exit of every region, one action firing in two regions, a transition of the parallel
 * state that exits all of them, conflicts, a transition between regions, history, validation,
 * Mermaid, paths and conformance.
 *
 * ```
 * [*] --> Offline
 * Offline --Connect--> Online
 * Offline --Resume--> OnlineHistory         deep: restores every region's leaf
 * Offline --Reopen--> ChatHistory           enters Online; Chat through its history, the rest initial
 * state Online {                            parallel
 *     state "[H*]" as OnlineHistory
 *     state Connection {
 *         [*] --> Connected
 *         Connected --Drop--> Reconnecting
 *         Reconnecting --Restore--> Connected
 *     }
 *     --
 *     state Sync {
 *         [*] --> Idle
 *         Idle --Sync--> Syncing
 *         Syncing --Synced--> Idle
 *         Syncing --Drop--> Paused           Drop fires here and in Connection together
 *         Paused --Restore--> Syncing
 *         Paused --GiveUp--> Idle            loses to Reconnecting --GiveUp--> Offline, chosen first
 *     }
 *     --
 *     state Chat {
 *         [*] --> Inbox
 *         state "[H]" as ChatHistory
 *         Inbox --Open--> Conversation
 *         Conversation --Escape--> Inbox     beats Online --Escape--> Offline: its source is inside
 *     }
 * }
 * Reconnecting --GiveUp--> Offline
 * Conversation --Resync--> Syncing          between regions: exits and re-enters Online
 * Online --Logout--> Offline                exits every region, once
 * Online --Escape--> Offline
 * ```
 */
@OptIn(ExperimentalKomaApi::class)
class StateChartParallelTest {

    sealed interface MessengerAction : Action {
        data object Connect : MessengerAction
        data object Resume : MessengerAction
        data object Reopen : MessengerAction
        data object Drop : MessengerAction
        data object Restore : MessengerAction
        data object GiveUp : MessengerAction
        data object Sync : MessengerAction
        data object Synced : MessengerAction
        data object Open : MessengerAction
        data object Escape : MessengerAction
        data object Resync : MessengerAction
        data object Logout : MessengerAction
    }

    enum class Link { Connected, Reconnecting }

    enum class Syncing { Idle, Syncing, Paused }

    enum class Screen { Inbox, Conversation }

    sealed interface MessengerState : State {
        data object Offline : MessengerState
        data class Online(val link: Link, val sync: Syncing, val screen: Screen) : MessengerState
    }

    sealed interface MessengerEvent : Event

    private val offline = StateId("Offline")
    private val online = StateId("Online")
    private val onlineHistory = StateId("OnlineHistory")
    private val connection = StateId("Connection")
    private val connected = StateId("Connected")
    private val reconnecting = StateId("Reconnecting")
    private val sync = StateId("Sync")
    private val idle = StateId("Idle")
    private val syncing = StateId("Syncing")
    private val paused = StateId("Paused")
    private val chat = StateId("Chat")
    private val chatHistory = StateId("ChatHistory")
    private val inbox = StateId("Inbox")
    private val conversation = StateId("Conversation")

    private inline fun <reified A : MessengerAction> on(name: String) = ActionMatcher.of<A>(name)

    private val connect = Transition(offline, online, on<MessengerAction.Connect>("Connect"))
    private val resume = Transition(offline, onlineHistory, on<MessengerAction.Resume>("Resume"))
    private val reopen = Transition(offline, chatHistory, on<MessengerAction.Reopen>("Reopen"))
    private val drop = Transition(connected, reconnecting, on<MessengerAction.Drop>("Drop"))
    private val restore = Transition(reconnecting, connected, on<MessengerAction.Restore>("Restore"))
    private val giveUp = Transition(reconnecting, offline, on<MessengerAction.GiveUp>("GiveUp"))
    private val startSync = Transition(idle, syncing, on<MessengerAction.Sync>("Sync"))
    private val synced = Transition(syncing, idle, on<MessengerAction.Synced>("Synced"))
    private val pause = Transition(syncing, paused, on<MessengerAction.Drop>("Drop"))
    private val unpause = Transition(paused, syncing, on<MessengerAction.Restore>("Restore"))
    private val abandon = Transition(paused, idle, on<MessengerAction.GiveUp>("GiveUp"))
    private val open = Transition(inbox, conversation, on<MessengerAction.Open>("Open"))
    private val close = Transition(conversation, inbox, on<MessengerAction.Escape>("Escape"))
    private val resync = Transition(conversation, syncing, on<MessengerAction.Resync>("Resync"))
    private val logout = Transition(online, offline, on<MessengerAction.Logout>("Logout"))
    private val escape = Transition(online, offline, on<MessengerAction.Escape>("Escape"))

    private val chart = StateChartDefinition(
        initial = offline,
        states = listOf(
            AtomicState(offline),
            ParallelState(online),
            HistoryState(onlineHistory, parent = online, deep = true),
            CompoundState(connection, initial = connected, parent = online),
            AtomicState(connected, parent = connection),
            AtomicState(reconnecting, parent = connection),
            CompoundState(sync, initial = idle, parent = online),
            AtomicState(idle, parent = sync),
            AtomicState(syncing, parent = sync),
            AtomicState(paused, parent = sync),
            CompoundState(chat, initial = inbox, parent = online),
            HistoryState(chatHistory, parent = chat),
            AtomicState(inbox, parent = chat),
            AtomicState(conversation, parent = chat),
        ),
        transitions = listOf(
            connect, resume, reopen, drop, restore, giveUp, startSync, synced, pause, unpause, abandon, open, close, resync, logout, escape,
        ),
    )

    private val activeLeavesOf: (MessengerState) -> Set<StateId> = { state ->
        when (state) {
            MessengerState.Offline -> setOf(offline)
            is MessengerState.Online -> setOf(
                when (state.link) {
                    Link.Connected -> connected
                    Link.Reconnecting -> reconnecting
                },
                when (state.sync) {
                    Syncing.Idle -> idle
                    Syncing.Syncing -> syncing
                    Syncing.Paused -> paused
                },
                when (state.screen) {
                    Screen.Inbox -> inbox
                    Screen.Conversation -> conversation
                },
            )
        }
    }

    // The configuration overload is used throughout: one leaf cannot describe three regions.
    private val runtime = StateChartRuntime(chart, { s: MessengerState -> activeLeavesOf(s).first() })

    @Test
    fun recordThatNamesAStateAndItsInitialChildEntersEachOnce() {
        // A caller-made record: Chat and Inbox, Chat's initial child. Entering Chat already enters Inbox.
        val result = runtime.step(StateConfiguration(setOf(offline), mapOf(onlineHistory to setOf(chat, inbox))), MessengerState.Offline, MessengerAction.Resume)
        assertEquals(listOf(online, connection, sync, chat, connected, idle, inbox), (result as StepResult.Transitioned).entered)
    }

    /** Steps from [configuration] with [actions]; returns every result. The chart has no guards. */
    private fun walk(configuration: StateConfiguration, vararg actions: MessengerAction): List<StepResult.Transitioned> {
        var current = configuration
        return actions.map { action ->
            (runtime.step(current, MessengerState.Offline, action) as StepResult.Transitioned).also { current = it.configuration }
        }
    }

    private fun online(vararg actions: MessengerAction) = walk(runtime.initialConfiguration(), MessengerAction.Connect, *actions).last().configuration

    // region runtime

    @Test
    fun enteringTheParallelStateEntersEveryRegionOutermostFirst() {
        val result = walk(runtime.initialConfiguration(), MessengerAction.Connect).single()
        assertEquals(listOf(offline), result.exited)
        assertEquals(listOf(online, connection, sync, chat, connected, idle, inbox), result.entered)
        assertEquals(StateConfiguration(setOf(online, connection, sync, chat, connected, idle, inbox)), result.configuration)
        assertEquals(listOf(connected, idle, inbox), chart.activeLeaves(result.configuration))
    }

    @Test
    fun oneActionFiresInTwoRegionsAtOnce() {
        val result = walk(online(MessengerAction.Sync), MessengerAction.Drop).single()
        assertEquals(listOf(drop, pause), result.transitions)
        // Same depth: reverse declaration order out, declaration order in.
        assertEquals(listOf(syncing, connected), result.exited)
        assertEquals(listOf(reconnecting, paused), result.entered)
        assertEquals(listOf(reconnecting, paused, inbox), chart.activeLeaves(result.configuration))
        // Without Syncing, Drop only fires in Connection.
        assertEquals(listOf(drop), walk(online(), MessengerAction.Drop).single().transitions)
    }

    @Test
    fun transitionOfTheParallelStateExitsEveryRegionOnce() {
        val result = walk(online(MessengerAction.Sync, MessengerAction.Open), MessengerAction.Logout).single()
        // Three leaves chose Logout; it is taken once.
        assertEquals(listOf(logout), result.transitions)
        assertEquals(listOf(conversation, syncing, connected, chat, sync, connection, online), result.exited)
        assertEquals(listOf(offline), result.entered)
        assertEquals(setOf(offline), result.configuration.active)
    }

    @Test
    fun innerTransitionBeatsTheParallelStatesOwnWhicheverLeafChoseFirst() {
        // Connected and Idle choose Online --Escape--> Offline before Conversation chooses its own
        // Escape; Conversation's source lies inside Online, so it replaces the outer one.
        val inConversation = online(MessengerAction.Open)
        val result = walk(inConversation, MessengerAction.Escape).single()
        assertEquals(listOf(close), result.transitions)
        assertEquals(listOf(conversation), result.exited)
        assertEquals(listOf(inbox), result.entered)
        // From the inbox nothing inner handles Escape, so the whole parallel state is left.
        assertEquals(listOf(escape), walk(result.configuration, MessengerAction.Escape).single().transitions)
    }

    @Test
    fun conflictBetweenUnrelatedSourcesKeepsTheFirstChosen() {
        // Reconnecting --GiveUp--> Offline exits everything, so Paused --GiveUp--> Idle conflicts
        // with it; neither source contains the other, and Connection's leaf chose first.
        val result = walk(online(MessengerAction.Sync, MessengerAction.Drop), MessengerAction.GiveUp).single()
        assertEquals(listOf(giveUp), result.transitions)
        assertEquals(setOf(offline), result.configuration.active)
    }

    @Test
    fun transitionBetweenRegionsExitsAndReentersTheParallelState() {
        // As in SCXML, the domain is a compound state, never a parallel one: here the root.
        val before = online(MessengerAction.Drop, MessengerAction.Open)
        val result = walk(before, MessengerAction.Resync).single()
        assertEquals(listOf(resync), result.transitions)
        assertEquals(listOf(conversation, idle, reconnecting, chat, sync, connection, online), result.exited)
        assertEquals(listOf(online, connection, sync, chat, connected, syncing, inbox), result.entered)
        // Connection and Chat were reset to their initial states.
        assertEquals(listOf(connected, syncing, inbox), chart.activeLeaves(result.configuration))
    }

    @Test
    fun deepHistoryOfTheParallelStateRestoresEveryRegion() {
        val before = online(MessengerAction.Sync, MessengerAction.Drop, MessengerAction.Open)
        val (logoutStep, resumeStep) = walk(before, MessengerAction.Logout, MessengerAction.Resume)
        assertEquals(
            mapOf(onlineHistory to setOf(reconnecting, paused, conversation), chatHistory to setOf(conversation)),
            logoutStep.configuration.history,
        )
        assertEquals(listOf(online, connection, sync, chat, reconnecting, paused, conversation), resumeStep.entered)
        assertEquals(onlineHistory, resumeStep.target)
        // Nothing recorded yet: the deep history without a default enters every region's initial state.
        assertEquals(listOf(online, connection, sync, chat, connected, idle, inbox), walk(runtime.initialConfiguration(), MessengerAction.Resume).single().entered)
    }

    @Test
    fun historyOfARegionEntersTheParallelStateAndTheOtherRegionsThroughTheirInitialStates() {
        val before = online(MessengerAction.Drop, MessengerAction.Open)
        val (_, reopenStep) = walk(before, MessengerAction.Logout, MessengerAction.Reopen)
        assertEquals(listOf(online, connection, sync, chat, connected, idle, conversation), reopenStep.entered)
    }

    @Test
    fun shallowHistoryOfAParallelStateRestoresItsRegionsWithTheirInitialStates() {
        val p = StateId("P")
        val a = StateId("A")
        val b = StateId("B")
        val (a1, a2, b1, b2) = listOf("a1", "a2", "b1", "b2").map(::StateId)
        val out = StateId("Out")
        val h = StateId("H")
        val d = StateId("D")
        val go = ActionMatcher.of<Small.Go>("Go")
        val back = ActionMatcher.of<Small.Back>("Back")
        val deepBack = ActionMatcher.of<Small.DeepBack>("DeepBack")
        val leave = ActionMatcher.of<Small.Leave>("Leave")
        val twoRegions = StateChartDefinition(
            initial = p,
            states = listOf(
                ParallelState(p), HistoryState(h, parent = p, default = b), HistoryState(d, parent = p, deep = true),
                CompoundState(a, initial = a1, parent = p), AtomicState(a1, a), AtomicState(a2, a),
                CompoundState(b, initial = b1, parent = p), AtomicState(b1, b), AtomicState(b2, b),
                AtomicState(out),
            ),
            transitions = listOf(Transition(a1, a2, go), Transition(b1, b2, go), Transition(p, out, leave), Transition(out, h, back), Transition(out, d, deepBack)),
        )
        val runtime = StateChartRuntime(twoRegions, { _: MessengerState -> p })
        fun step(configuration: StateConfiguration, action: Small) =
            runtime.step(configuration, MessengerState.Offline, action) as StepResult.Transitioned
        val start = runtime.initialConfiguration()
        assertEquals(setOf(p, a, b, a1, b1), start.active)
        // A shallow history with a default region that nothing has recorded yet: B, and A initially.
        assertEquals(listOf(p, a, b, a1, b1), step(StateConfiguration(setOf(out)), Small.Back).entered)
        val moved = step(start, Small.Go)
        assertEquals(2, moved.transitions.size)
        val left = step(moved.configuration, Small.Leave)
        assertEquals(mapOf(h to setOf(a, b), d to setOf(a2, b2)), left.configuration.history)
        // Shallow: both regions, each through its initial state. Deep: both leaves.
        assertEquals(listOf(p, a, b, a1, b1), step(left.configuration, Small.Back).entered)
        assertEquals(listOf(p, a, b, a2, b2), step(left.configuration, Small.DeepBack).entered)
    }

    sealed interface Small : Action {
        data object Go : Small
        data object Back : Small
        data object DeepBack : Small
        data object Leave : Small
    }

    // endregion

    // region validation

    @Test
    fun messengerIsValid() {
        assertEquals(emptyList(), chart.validate(sampleActions = listOf(MessengerAction.Escape, MessengerAction.Drop, MessengerAction.GiveUp)))
    }

    @Test
    fun parallelStateWithFewerThanTwoRegionsIsAWarningTheRuntimeAccepts() {
        val lonely = chart.copy(
            states = chart.states.filter { n -> chart.ancestorsOf(n.id).none { it == connection || it == sync } && n.id != connection && n.id != sync } +
                ParallelState(StateId("Empty"), parent = chat) +
                HistoryState(StateId("BadDefault"), parent = online, default = inbox),
        )
        val issues = lonely.validate()
        assertEquals(
            listOf(
                ValidationIssue.InvalidHistoryDefault(StateId("BadDefault"), inbox),
                ValidationIssue.TooFewRegions(online, listOf(chat)),
                ValidationIssue.TooFewRegions(StateId("Empty"), emptyList()),
            ),
            issues.filter { it !is ValidationIssue.UnknownTransitionSource && it !is ValidationIssue.UnknownTransitionTarget && it !is ValidationIssue.UnreachableState },
        )
        // A shallow history of a parallel state takes a region as default; a deep one any descendant.
        assertEquals(emptyList(), chart.copy(states = chart.states + HistoryState(StateId("Ok"), parent = online, default = sync)).validate())
        assertEquals(emptyList(), chart.copy(states = chart.states.map { if (it.id == onlineHistory) HistoryState(onlineHistory, online, deep = true, default = paused) else it }).validate())
        // Without the invalid default, the runtime accepts too few regions: one region behaves like a compound state.
        val accepted = lonely.copy(states = lonely.states.filter { it.id != StateId("BadDefault") }, transitions = listOf(connect, logout))
        val runtime = StateChartRuntime(accepted, { _: MessengerState -> offline })
        val entered = (runtime.step(runtime.initialConfiguration(), MessengerState.Offline, MessengerAction.Connect) as StepResult.Transitioned).entered
        assertEquals(listOf(online, chat, inbox), entered)
    }

    // endregion

    // region mermaid

    @Test
    fun mermaidDrawsRegionsInSectionsAndTransitionsBetweenThemOutside() {
        val expected = """
            stateDiagram-v2
                [*] --> Offline
                state Online {
                    state Connection {
                        [*] --> Connected
                        Connected
                        Reconnecting
                        Connected --> Reconnecting : Drop
                        Reconnecting --> Connected : Restore
                    }
                    state "[H*]" as OnlineHistory
                    --
                    state Sync {
                        [*] --> Idle
                        Idle
                        Syncing
                        Paused
                        Idle --> Syncing : Sync
                        Syncing --> Idle : Synced
                        Syncing --> Paused : Drop
                        Paused --> Syncing : Restore
                        Paused --> Idle : GiveUp
                    }
                    --
                    state Chat {
                        [*] --> Inbox
                        state "[H]" as ChatHistory
                        Inbox
                        Conversation
                        Inbox --> Conversation : Open
                        Conversation --> Inbox : Escape
                    }
                }
                Offline --> Online : Connect
                Offline --> OnlineHistory : Resume
                Offline --> ChatHistory : Reopen
                Reconnecting --> Offline : GiveUp
                Conversation --> Syncing : Resync
                Online --> Offline : Logout
                Online --> Offline : Escape
        """.trimIndent()
        assertEquals(expected, chart.toMermaid())
    }

    @Test
    fun mermaidDrawsAtomicAndNestedParallelRegionsAndAParallelStateWithoutChildren() {
        val odd = StateChartDefinition(
            initial = StateId("P"),
            states = listOf(
                ParallelState(StateId("P")),
                AtomicState(StateId("Beacon"), parent = StateId("P")),
                ParallelState(StateId("inner p"), parent = StateId("P")),
                AtomicState(StateId("x"), parent = StateId("inner p")),
                AtomicState(StateId("y"), parent = StateId("inner p")),
                ParallelState(StateId("Bare")),
            ),
            transitions = listOf(Transition(StateId("x"), StateId("y"), ActionMatcher("Go")), Transition(StateId("P"), StateId("Bare"), ActionMatcher("Stop"))),
        )
        val expected = """
            stateDiagram-v2
                [*] --> P
                state P {
                    Beacon
                    --
                    state "inner p" as koma_state_0 {
                        x
                        --
                        y
                    }
                }
                x --> y : Go
                P --> Bare : Stop
        """.trimIndent()
        assertEquals(expected, odd.toMermaid())
    }

    // endregion

    // region paths

    @Test
    fun pathStepsFireEveryRegionTheActionFiresIn() {
        // The Drop step is Connected --Drop--> Reconnecting; Syncing --Drop--> Paused fires with it.
        val path = chart.shortestPathTo(paused)
        assertEquals(
            StateChartPath(
                start = offline,
                transitions = listOf(connect, startSync, drop),
                leaves = listOf(connected, connected, reconnecting),
                startLeaves = listOf(offline),
                activeLeaves = listOf(listOf(connected, idle, inbox), listOf(connected, syncing, inbox), listOf(reconnecting, paused, inbox)),
            ),
            path,
        )
        // Replayed on the runtime, one action per transition, it ends in the same leaves.
        var configuration = runtime.initialConfiguration()
        for (action in listOf(MessengerAction.Connect, MessengerAction.Sync, MessengerAction.Drop)) configuration = walk(configuration, action).single().configuration
        assertEquals(path!!.activeLeaves.last(), chart.activeLeaves(configuration))
        val coverage = chart.transitionCoveragePaths()
        assertEquals(chart.transitions.toSet(), coverage.flatMap { it.transitions }.toSet())
        assertTrue(coverage.all { it.activeLeaves.size == it.transitions.size && it.leaves == it.activeLeaves.map { l -> l.first() } })
    }

    @Test
    fun pathsKeepTheirOneLeafShapeWithoutParallelStates() {
        val flat = StateChartPath(offline, listOf(connect))
        assertEquals(listOf(offline), flat.startLeaves)
        assertEquals(listOf(listOf(online)), flat.activeLeaves)
        assertEquals(flat, StateChartPath(offline, listOf(connect), listOf(online)))
        val error = kotlin.runCatching { StateChartPath(offline, listOf(connect), listOf(online), activeLeaves = emptyList()) }.exceptionOrNull()
        assertEquals("[Koma] Path has 1 transitions but 0 sets of active leaves", error?.message)
    }

    // endregion

    // region conformance

    /**
     * A Store written by hand; with [forgetful], Resync moves Connection and Sync but leaves the chat
     * open; Connect opens [firstScreen].
     */
    private fun createStore(forgetful: Boolean, firstScreen: Screen = Screen.Inbox): Store<MessengerState, MessengerAction, MessengerEvent> = Store(MessengerState.Offline) {
        coroutineContext(Dispatchers.Unconfined)
        state<MessengerState.Offline> {
            action<MessengerAction.Connect> { nextState { MessengerState.Online(Link.Connected, Syncing.Idle, firstScreen) } }
        }
        state<MessengerState.Online> {
            action<MessengerAction.Drop> {
                nextState { state.copy(link = Link.Reconnecting, sync = if (state.sync == Syncing.Syncing) Syncing.Paused else state.sync) }
            }
            action<MessengerAction.Sync> { if (state.sync == Syncing.Idle) nextState { state.copy(sync = Syncing.Syncing) } }
            action<MessengerAction.Open> { nextState { state.copy(screen = Screen.Conversation) } }
            action<MessengerAction.Escape> {
                nextState { if (state.screen == Screen.Conversation) state.copy(screen = Screen.Inbox) else MessengerState.Offline }
            }
            action<MessengerAction.Resync> {
                nextState { MessengerState.Online(Link.Connected, Syncing.Syncing, if (forgetful) state.screen else Screen.Inbox) }
            }
            action<MessengerAction.Logout> { nextState { MessengerState.Offline } }
        }
    }

    private val script = listOf(
        MessengerAction.Connect, MessengerAction.Sync, MessengerAction.Drop, MessengerAction.Open, MessengerAction.Escape,
        MessengerAction.Open, MessengerAction.Resync, MessengerAction.Logout,
    )

    @Test
    fun storeThatMovesEveryRegionAsTheChartSaysConforms() = runTest {
        val conformance = StateChartConformance.withActiveLeaves<MessengerState, MessengerAction, MessengerEvent>(chart, activeLeavesOf)
        val store = createStore(forgetful = false).patch { plugin(conformance) }
        script.forEach { store.dispatchAndAwait(it) }
        assertEquals(MessengerState.Offline, store.currentState)
        assertEquals(emptyList(), conformance.violations)
        // Drop changed two regions: both transitions are covered, in declaration order of the old leaves.
        assertEquals(listOf(connect, startSync, drop, pause, open, close, resync, logout), conformance.coveredTransitions.toList())
    }

    @Test
    fun storeThatKeepsARegionTheChartResetsIsReported() = runTest {
        val conformance = StateChartConformance.withActiveLeaves<MessengerState, MessengerAction, MessengerEvent>(chart, activeLeavesOf)
        val store = createStore(forgetful = true).patch { plugin(conformance) }
        script.forEach { store.dispatchAndAwait(it) }
        // Resync exits and re-enters Online, so the chat must be back in the inbox. The two regions
        // that did move look like Restore, not Resync.
        assertEquals(
            listOf(
                ConformanceViolation.UnexpectedTrigger(reconnecting, connected, MessengerAction.Resync),
                ConformanceViolation.UnexpectedTrigger(paused, syncing, MessengerAction.Resync),
            ),
            conformance.violations,
        )
    }

    @Test
    fun newLeafThatNothingExplainsIsReportedFromTheClosestOldLeaf() = runTest {
        // The Store reports Online with only two regions' leaves, then adds the third one.
        val two = StateChartConformance.withActiveLeaves<MessengerState, MessengerAction, MessengerEvent>(chart) { s ->
            activeLeavesOf(s).let { leaves -> if (s is MessengerState.Online && s.screen == Screen.Conversation) leaves else leaves - inbox }
        }
        val store = createStore(forgetful = false).patch { plugin(two) }
        listOf(MessengerAction.Connect, MessengerAction.Open, MessengerAction.Escape).forEach { store.dispatchAndAwait(it) }
        // Connected and Idle are equally close (both share Online with it); the first declared is named.
        // Then a leaf disappears without a new one: it is reported with the closest remaining leaf.
        assertEquals(
            listOf(
                ConformanceViolation.UndeclaredTransition(connected, conversation, MessengerAction.Open),
                ConformanceViolation.UndeclaredTransition(conversation, connected, MessengerAction.Escape),
            ),
            two.violations,
        )
    }

    @Test
    fun changeOnlyAnotherTransitionExplainsIsAnUnexpectedTrigger() = runTest {
        // Connect must enter the inbox; only Resume, through the deep history the plugin has not
        // seen record anything, could enter the conversation.
        val conformance = StateChartConformance.withActiveLeaves<MessengerState, MessengerAction, MessengerEvent>(chart, activeLeavesOf)
        val store = createStore(forgetful = false, firstScreen = Screen.Conversation).patch { plugin(conformance) }
        store.dispatchAndAwait(MessengerAction.Connect)
        // Offline shares no ancestor with any new leaf, so the first new leaf is named.
        assertEquals(listOf(ConformanceViolation.UnexpectedTrigger(offline, connected, MessengerAction.Connect)), conformance.violations)
    }

    // endregion
}

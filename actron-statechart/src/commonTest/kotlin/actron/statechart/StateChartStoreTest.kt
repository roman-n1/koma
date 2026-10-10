package actron.statechart

import actron.core.Action
import actron.core.Event
import actron.core.ExceptionHandler
import actron.core.ExperimentalActronApi
import actron.core.Plugin
import actron.core.StateSaver
import actron.core.Store
import actron.test.createRecorder
import actron.test.dispatchAndAwait
import actron.test.patch
import actron.test.startAndAwait
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Example tests for [StateChartStore] on a messenger: a connection region that retries a lost
 * connection every 5 seconds up to three times and then gives up, and a chat region with a typing
 * indicator timer and a deep history that brings the conversation back after the settings screen.
 * Entering Connected launches a fake sync job that is cancelled when the connection drops; an
 * activity pings every 30 seconds while connected.
 *
 * ```
 * [*] --> Main
 * state Main {                                                 parallel
 *     state Connection {
 *         [*] --> Connected                                    onEnter: launch sync (10s); activity: ping every 30s
 *         Connected --Drop--> Reconnecting
 *         Reconnecting --after 5s [canRetry] / countAttempt--> Reconnecting
 *         Reconnecting --after 5s [outOfRetries]--> Offline
 *         Reconnecting --Restore / resetAttempts--> Connected
 *         Offline --Connect / resetAttempts--> Connected       onEnter Offline: event ConnectionLost
 *     }
 *     --
 *     state ChatRegion {
 *         [*] --> Chat
 *         state Chat {
 *             [*] --> Inbox
 *             [H*] ChatHistory
 *             Inbox --Open--> Conversation
 *             state Conversation {
 *                 [*] --> Reading
 *                 Reading --KeyPress--> Typing
 *                 Typing --KeyPress--> Typing                  restarts the typing timer
 *                 Typing --after 3s--> Reading
 *                 Typing --Send / countSent--> Reading
 *             }
 *             Conversation --Back--> Inbox
 *         }
 *         Chat --OpenSettings--> Settings
 *         Settings --Close--> ChatHistory
 *     }
 * }
 * ```
 */
@OptIn(ExperimentalActronApi::class, ExperimentalCoroutinesApi::class)
class StateChartStoreTest {

    sealed interface MessengerAction : Action {
        data object Drop : MessengerAction
        data object Restore : MessengerAction
        data object Connect : MessengerAction
        data object Open : MessengerAction
        data object Back : MessengerAction
        data object KeyPress : MessengerAction
        data object Send : MessengerAction
        data object OpenSettings : MessengerAction
        data object Close : MessengerAction
    }

    sealed interface MessengerEvent : Event {
        data object ConnectionLost : MessengerEvent
        data object Synced : MessengerEvent
        data object Ping : MessengerEvent
        data class Note(val text: String) : MessengerEvent
    }

    data class Messenger(val attempts: Int = 0, val sent: Int = 0, val synced: Int = 0)

    private val main = StateId("Main")
    private val connection = StateId("Connection")
    private val connected = StateId("Connected")
    private val reconnecting = StateId("Reconnecting")
    private val offline = StateId("Offline")
    private val chatRegion = StateId("ChatRegion")
    private val chat = StateId("Chat")
    private val chatHistory = StateId("ChatHistory")
    private val inbox = StateId("Inbox")
    private val conversation = StateId("Conversation")
    private val reading = StateId("Reading")
    private val typing = StateId("Typing")
    private val settings = StateId("Settings")

    private val drop = Transition(connected, reconnecting, ActionMatcher.of<MessengerAction.Drop>("Drop"))
    private val retry = Transition(reconnecting, reconnecting, Trigger.After(5.seconds), guard = "canRetry", effect = "countAttempt")
    private val giveUp = Transition(reconnecting, offline, Trigger.After(5.seconds), guard = "outOfRetries")
    private val restore = Transition(reconnecting, connected, ActionMatcher.of<MessengerAction.Restore>("Restore"), effect = "resetAttempts")
    private val connect = Transition(offline, connected, ActionMatcher.of<MessengerAction.Connect>("Connect"), effect = "resetAttempts")
    private val open = Transition(inbox, conversation, ActionMatcher.of<MessengerAction.Open>("Open"))
    private val back = Transition(conversation, inbox, ActionMatcher.of<MessengerAction.Back>("Back"))
    private val keyPress = Transition(reading, typing, ActionMatcher.of<MessengerAction.KeyPress>("KeyPress"))
    private val keepTyping = Transition(typing, typing, ActionMatcher.of<MessengerAction.KeyPress>("KeyPress"))
    private val typingTimeout = Transition(typing, reading, Trigger.After(3.seconds))
    private val send = Transition(typing, reading, ActionMatcher.of<MessengerAction.Send>("Send"), effect = "countSent")
    private val openSettings = Transition(chat, settings, ActionMatcher.of<MessengerAction.OpenSettings>("OpenSettings"))
    private val close = Transition(settings, chatHistory, ActionMatcher.of<MessengerAction.Close>("Close"))

    private val chart = StateChartDefinition(
        initial = main,
        states = listOf(
            ParallelState(main),
            CompoundState(connection, initial = connected, parent = main),
            AtomicState(connected, connection),
            AtomicState(reconnecting, connection),
            AtomicState(offline, connection),
            CompoundState(chatRegion, initial = chat, parent = main),
            CompoundState(chat, initial = inbox, parent = chatRegion),
            HistoryState(chatHistory, parent = chat, deep = true),
            AtomicState(inbox, chat),
            CompoundState(conversation, initial = reading, parent = chat),
            AtomicState(reading, conversation),
            AtomicState(typing, conversation),
            AtomicState(settings, chatRegion),
        ),
        transitions = listOf(drop, retry, giveUp, restore, connect, open, back, keyPress, keepTyping, typingTimeout, send, openSettings, close),
    )

    /** Hook calls in order: `exit X`, `effect label`, `enter X`. */
    private val log = mutableListOf<String>()

    /** What the guards were asked with. */
    private val guardActions = mutableListOf<Action>()

    private fun TestScope.messenger(
        extra: StateChartStoreBuilder<Messenger, MessengerAction, MessengerEvent>.() -> Unit = {},
    ): Store<ChartState<Messenger>, MessengerAction, MessengerEvent> =
        StateChartStore<Messenger, MessengerAction, MessengerEvent>(chart, Messenger(), backgroundScope.coroutineContext) {
            guard("canRetry") { state, action -> guardActions += action; state.context.attempts < 3 }
            guard("outOfRetries") { state, action -> guardActions += action; state.context.attempts >= 3 }
            effect("countAttempt") { context, action -> log += "effect countAttempt ${action is TimerFired}"; context.copy(attempts = context.attempts + 1) }
            effect("resetAttempts") { context, _ -> log += "effect resetAttempts"; context.copy(attempts = 0) }
            effect("countSent") { context, _ -> log += "effect countSent"; context.copy(sent = context.sent + 1) }
            for (declared in chart.states.filter { it !is HistoryState }) {
                onEnter(declared.id) { log += "enter $node" }
                onExit(declared.id) { log += "exit $node" }
            }
            onEnter(offline) { event(MessengerEvent.ConnectionLost) }
            onEnter(connected) {
                launch {
                    delay(10.seconds)
                    updateContext { it.copy(synced = it.synced + 1) }
                    event(MessengerEvent.Synced)
                }
            }
            activity(connected) {
                while (true) {
                    delay(30.seconds)
                    event(MessengerEvent.Ping)
                }
            }
            extra()
        }

    private val Store<ChartState<Messenger>, *, *>.leaves get() = currentState.activeLeaves(chart)

    private fun TestScope.advance(duration: Duration) {
        advanceTimeBy(duration)
        runCurrent()
    }

    private suspend fun TestScope.send(store: Store<ChartState<Messenger>, MessengerAction, MessengerEvent>, vararg actions: MessengerAction) {
        for (action in actions) store.dispatchAndAwait(action)
        runCurrent()
    }

    // region start and steps

    @Test
    fun startEntersTheInitialConfigurationOutermostFirst() = runTest {
        val store = messenger()
        val recorder = store.createRecorder()
        store.startAndAwait()
        runCurrent()
        assertEquals(listOf(connected, inbox), store.leaves)
        assertTrue(store.currentState.isActive(main) && store.currentState.isActive(chat) && !store.currentState.isActive(offline))
        assertEquals(listOf("enter Main", "enter Connection", "enter ChatRegion", "enter Connected", "enter Chat", "enter Inbox"), log)
        // Hooks changed nothing and no timer runs: nothing is committed on start.
        assertEquals(listOf(store.currentState), recorder.states)
        assertEquals(ChartTimers(), store.currentState.timers)
        store.close()
    }

    @Test
    fun aStepRunsExitHooksThenEffectsThenEnterHooksAndCommitsOnce() = runTest {
        val store = messenger()
        val recorder = store.createRecorder()
        send(store, MessengerAction.Open, MessengerAction.KeyPress)
        log.clear()
        recorder.clear()
        send(store, MessengerAction.Send)
        assertEquals(listOf("exit Typing", "effect countSent", "enter Reading"), log)
        assertEquals(1, recorder.states.size)
        assertEquals(Messenger(sent = 1), store.currentState.context)
        store.close()
    }

    @Test
    fun anActionNoTransitionTakesChangesNothing() = runTest {
        val store = messenger()
        val recorder = store.createRecorder()
        send(store, MessengerAction.Back, MessengerAction.Send, MessengerAction.Close)
        assertEquals(1, recorder.states.size, "only the start state")
        assertEquals(listOf(connected, inbox), store.leaves)
        assertEquals(6, log.size, "only the initial entry")
        store.close()
    }

    // endregion

    // region timers

    @Test
    fun reconnectRetriesEveryFiveSecondsThreeTimesThenGivesUp() = runTest {
        val store = messenger()
        val recorder = store.createRecorder()
        send(store, MessengerAction.Drop)
        assertEquals(listOf(reconnecting, inbox), store.leaves)
        assertEquals(setOf(1, 2), store.currentState.timers.running.keys, "retry and giveUp, by their index")
        advance(4.9.seconds)
        assertEquals(0, store.currentState.context.attempts)
        advance(0.1.seconds)
        assertEquals(1, store.currentState.context.attempts)
        assertEquals(reconnecting, store.leaves.first())
        advance(10.seconds)
        assertEquals(3, store.currentState.context.attempts)
        assertEquals(emptyList(), recorder.events)
        advance(5.seconds)
        assertEquals(listOf(offline, inbox), store.leaves)
        assertEquals(listOf(MessengerEvent.ConnectionLost), recorder.events)
        assertEquals(emptyMap(), store.currentState.timers.running)
        // Guards were asked with TimerFired; effects of timers get it too.
        assertTrue(guardActions.isNotEmpty() && guardActions.all { it is TimerFired })
        assertTrue("effect countAttempt true" in log)
        advance(60.seconds)
        assertEquals(listOf(offline, inbox), store.leaves)
        store.close()
    }

    @Test
    fun leavingTheSourceCancelsItsTimers() = runTest {
        val store = messenger()
        send(store, MessengerAction.Drop)
        advance(3.seconds)
        send(store, MessengerAction.Restore)
        assertEquals(listOf(connected, inbox), store.leaves)
        assertEquals(emptyMap(), store.currentState.timers.running)
        advance(60.seconds)
        assertEquals(listOf(connected, inbox), store.leaves)
        assertEquals(0, store.currentState.context.attempts)
        store.close()
    }

    @Test
    fun typingIndicatorClearsAfterThreeSecondsOfSilence() = runTest {
        val store = messenger()
        send(store, MessengerAction.Open, MessengerAction.KeyPress)
        assertTrue(store.currentState.isActive(typing))
        advance(2.seconds)
        send(store, MessengerAction.KeyPress) // re-enters Typing: the timer restarts
        advance(2.9.seconds)
        assertTrue(store.currentState.isActive(typing))
        advance(0.1.seconds)
        assertEquals(listOf(connected, reading), store.leaves)
        store.close()
    }

    @Test
    fun historyBringsTheConversationBackAndRestartsItsTimer() = runTest {
        val store = messenger()
        send(store, MessengerAction.Open, MessengerAction.KeyPress, MessengerAction.OpenSettings)
        assertEquals(listOf(connected, settings), store.leaves)
        advance(10.seconds) // Typing is not active: its timer does not fire
        log.clear()
        send(store, MessengerAction.Close)
        assertEquals(listOf(connected, typing), store.leaves)
        assertEquals(listOf("exit Settings", "enter Chat", "enter Conversation", "enter Typing"), log)
        advance(3.seconds)
        assertEquals(listOf(connected, reading), store.leaves)
        store.close()
    }

    // endregion

    // region node-scoped work

    @Test
    fun syncJobLaunchedOnEnterRunsWhileConnected() = runTest {
        val store = messenger()
        val recorder = store.createRecorder()
        store.startAndAwait()
        advance(10.seconds)
        assertEquals(1, store.currentState.context.synced)
        assertEquals(listOf(MessengerEvent.Synced), recorder.events)
        advance(20.seconds)
        assertEquals(listOf(MessengerEvent.Synced, MessengerEvent.Ping), recorder.events)
        store.close()
    }

    @Test
    fun syncJobIsCancelledWhenConnectedIsExited() = runTest {
        val store = messenger()
        val recorder = store.createRecorder()
        store.startAndAwait()
        advance(4.seconds)
        send(store, MessengerAction.Drop)
        advance(2.seconds)
        send(store, MessengerAction.Restore) // a new sync starts at 6s
        advance(9.seconds)
        assertEquals(0, store.currentState.context.synced, "the first sync would have finished at 10s")
        advance(1.seconds)
        assertEquals(1, store.currentState.context.synced)
        assertEquals(listOf(MessengerEvent.Synced), recorder.events)
        send(store, MessengerAction.Drop)
        advance(120.seconds)
        assertEquals(listOf(MessengerEvent.Synced, MessengerEvent.ConnectionLost), recorder.events, "no ping while not connected")
        store.close()
    }

    @Test
    fun workThatOutlivesItsNodeCannotUpdateTheContext() = runTest {
        var updated: Boolean? = null
        var activeAtTheEnd: Boolean? = null
        val store = messenger {
            onEnter(reconnecting) {
                launch {
                    withContext(NonCancellable) {
                        delay(4.seconds)
                        activeAtTheEnd = isActive
                        updated = updateContext { it.copy(attempts = 100) }
                    }
                }
            }
        }
        send(store, MessengerAction.Drop)
        advance(1.seconds)
        send(store, MessengerAction.Restore)
        advance(5.seconds)
        assertEquals(false, updated)
        assertEquals(false, activeAtTheEnd)
        assertEquals(0, store.currentState.context.attempts)
        store.close()
    }

    @Test
    fun workCanDispatchActions() = runTest {
        val store = messenger {
            activity(typing) {
                assertEquals(typing, node)
                delay(1.seconds)
                dispatch(MessengerAction.Send)
            }
        }
        send(store, MessengerAction.Open, MessengerAction.KeyPress)
        advance(1.seconds)
        assertEquals(listOf(connected, reading), store.leaves)
        assertEquals(1, store.currentState.context.sent)
        store.close()
    }

    @Test
    fun closingTheStoreCancelsWorkAndTimers() = runTest {
        val store = messenger()
        val recorder = store.createRecorder()
        send(store, MessengerAction.Drop, MessengerAction.Restore)
        store.close()
        advance(120.seconds)
        assertEquals(emptyList(), recorder.events)
        assertEquals(0, store.currentState.context.synced)
    }

    // endregion

    // region restore

    private class MemorySaver<S : actron.core.State>(var saved: S? = null) : StateSaver<S> {
        override fun save(state: S) {
            saved = state
        }

        override fun restore(initialState: S): S = saved ?: initialState
    }

    @Test
    fun restoredStoreResumesTimersAndActivitiesWithoutEnteringAgain() = runTest {
        val saver = MemorySaver<ChartState<Messenger>>()
        val first = messenger { store { stateSaver(saver) } }
        send(first, MessengerAction.Open, MessengerAction.Drop)
        advance(7.seconds)
        assertEquals(1, first.currentState.context.attempts)
        first.close()

        log.clear()
        val second = messenger { store { stateSaver(saver) } }
        val recorder = second.createRecorder()
        second.startAndAwait()
        runCurrent()
        assertEquals(emptyList(), log, "no enter hooks for a restored configuration")
        assertEquals(listOf(reconnecting, reading), second.leaves)
        // The retry timer starts over: 5 seconds from the restore, not 3.
        advance(4.seconds)
        assertEquals(1, second.currentState.context.attempts)
        advance(1.seconds)
        assertEquals(2, second.currentState.context.attempts)
        advance(10.seconds)
        assertEquals(offline, second.leaves.first())
        assertEquals(listOf(MessengerEvent.ConnectionLost), recorder.events)
        second.close()
    }

    @Test
    fun restoredConnectedStateRestartsItsActivityButNotTheSyncLaunchedOnEnter() = runTest {
        val saver = MemorySaver<ChartState<Messenger>>()
        val first = messenger { store { stateSaver(saver) } }
        send(first, MessengerAction.Open)
        first.close()

        val second = messenger { store { stateSaver(saver) } }
        val recorder = second.createRecorder()
        second.startAndAwait()
        advance(30.seconds)
        assertEquals(listOf(MessengerEvent.Ping), recorder.events)
        assertEquals(0, second.currentState.context.synced)
        second.close()
    }

    @Test
    fun restoredStateWhoseTimersDoNotMatchGetsFreshTokens() = runTest {
        val configuration = StateConfiguration(active = setOf(main, connection, reconnecting, chatRegion, chat, inbox))
        val saved = ChartState(configuration, Messenger(attempts = 3), ChartTimers(running = mapOf(9 to 4L), issued = 7))
        val store = messenger { store { stateSaver(MemorySaver(saved)) } }
        store.startAndAwait()
        runCurrent()
        assertEquals(ChartTimers(running = mapOf(1 to 8L, 2 to 9L), issued = 9), store.currentState.timers)
        advance(5.seconds)
        assertEquals(offline, store.leaves.first())
        store.close()
    }

    // endregion

    // region Actron interoperability

    @Test
    fun pluginsActronTestAndConformanceWorkOnTheStore() = runTest {
        val conformance = StateChartConformance.withActiveLeaves<ChartState<Messenger>, MessengerAction, MessengerEvent>(chart) {
            it.activeLeaves(chart).toSet()
        }
        val seen = mutableListOf<MessengerAction>()
        val store = messenger().patch {
            plugin(conformance)
            plugin(Plugin(onAction = { _, action -> seen += action }))
        }
        val recorder = store.createRecorder()
        send(store, MessengerAction.Open, MessengerAction.KeyPress)
        advance(3.seconds)
        send(store, MessengerAction.KeyPress, MessengerAction.KeyPress, MessengerAction.Send, MessengerAction.OpenSettings, MessengerAction.Close, MessengerAction.Back)
        send(store, MessengerAction.Drop)
        advance(20.seconds)
        send(store, MessengerAction.Connect, MessengerAction.Drop, MessengerAction.Restore)
        assertEquals(emptyList(), conformance.violations)
        // A self-loop timer does not change the leaves, so the plugin never sees it.
        assertEquals(listOf(retry), conformance.uncoveredTransitions)
        assertEquals(12, seen.size)
        assertEquals(listOf(MessengerEvent.ConnectionLost), recorder.events)
        assertEquals(store.currentState, recorder.states.last())
        store.close()
    }

    // endregion

    // region errors

    @Test
    fun aFailingHookIsRecoveredAndTheStepIsNotCommitted() = runTest {
        val errors = mutableListOf<Exception>()
        var workRan = false
        val store = messenger {
            onEnter(typing) {
                launch { workRan = true }
                error("keyboard broke")
            }
            store {
                recover<IllegalStateException> { errors += error }
            }
        }
        send(store, MessengerAction.Open, MessengerAction.KeyPress)
        advance(5.seconds)
        assertEquals(listOf("keyboard broke"), errors.map { it.message })
        assertEquals(listOf(connected, reading), store.leaves)
        assertFalse(workRan, "work launched by the failed step never starts")
        store.close()
    }

    @Test
    fun failingWorkReachesTheExceptionHandler() = runTest {
        val errors = mutableListOf<Throwable>()
        val store = messenger {
            activity(conversation) {
                delay(1.seconds)
                throw IllegalArgumentException("sync failed")
            }
            store { exceptionHandler(ExceptionHandler { errors += it }) }
        }
        send(store, MessengerAction.Open)
        advance(1.seconds)
        assertEquals(listOf("sync failed"), errors.map { it.message })
        // The Store keeps working, timers included.
        send(store, MessengerAction.KeyPress)
        advance(3.seconds)
        assertEquals(listOf(connected, reading), store.leaves)
        store.close()
    }

    @Test
    fun aFailingStartIsRecoveredAndLaterStepsStillWork() = runTest {
        val errors = mutableListOf<Exception>()
        var starting = true
        val store = messenger {
            onEnter(inbox) { if (starting) error("no inbox yet") }
            store {
                recover<IllegalStateException> { errors += error }
            }
        }
        store.startAndAwait()
        runCurrent()
        starting = false
        assertEquals(listOf("no inbox yet"), errors.map { it.message })
        assertEquals(listOf(connected, inbox), store.leaves)
        // The nodes entered on the failed start have no work to cancel; steps and timers still run.
        send(store, MessengerAction.Drop, MessengerAction.Open)
        advance(20.seconds)
        assertEquals(listOf(offline, reading), store.leaves)
        store.close()
    }

    @Test
    fun hooksEmitEventsOnStartAndOnSteps() = runTest {
        val store = messenger {
            onEnter(main) { event(MessengerEvent.Note("started ${action == null}")) }
            onExit(connected) { event(MessengerEvent.Note("left $node on $action")) }
        }
        val recorder = store.createRecorder()
        send(store, MessengerAction.Drop)
        assertEquals(listOf(MessengerEvent.Note("started true"), MessengerEvent.Note("left Connected on Drop")), recorder.events)
        store.close()
    }

    @Test
    fun buildingFailsFastOnMissingImplementationsAndBadCharts() {
        fun build(chart: StateChartDefinition = this.chart, block: StateChartStoreBuilder<Messenger, MessengerAction, MessengerEvent>.() -> Unit) =
            StateChartStore(chart, Messenger(), builder = block)
        val guards: StateChartStoreBuilder<Messenger, MessengerAction, MessengerEvent>.() -> Unit = {
            guard("canRetry") { _, _ -> true }
            guard("outOfRetries") { _, _ -> true }
        }
        val effects: StateChartStoreBuilder<Messenger, MessengerAction, MessengerEvent>.() -> Unit = {
            listOf("countAttempt", "resetAttempts", "countSent").forEach { label -> effect(label) { c, _ -> c } }
        }
        assertFailsWith<IllegalArgumentException> { build { effects() } }.also { assertTrue("canRetry" in it.message!!) }
        assertFailsWith<IllegalArgumentException> { build { guards(); effect("countSent") { c, _ -> c } } }.also {
            assertEquals("[Actron] Missing effect implementations: countAttempt, resetAttempts", it.message)
        }
        assertFailsWith<IllegalArgumentException> { build { guards(); effects(); onEnter(StateId("Nowhere")) {} } }
        assertFailsWith<IllegalArgumentException> { build { guards(); effects(); onExit(chatHistory) {} } }
        assertFailsWith<IllegalArgumentException> { build { guards(); effects(); activity(StateId("Nowhere")) {} } }
        assertFailsWith<IllegalArgumentException> { build { guards(); guard("canRetry") { _, _ -> false } } }
        assertFailsWith<IllegalArgumentException> { build { effects(); effect("countSent") { c, _ -> c } } }
        val broken = chart.copy(states = chart.states + AtomicState(StateId("Orphan"), parent = StateId("Ghost")))
        assertFailsWith<IllegalArgumentException> { build(broken) { guards(); effects() } }
        build { guards(); effects() }.close()
    }

    // endregion

    @Test
    fun timersWithTheSameDelayFireInDeclarationOrderAndTheLoserIsStale() = runTest {
        // retry and giveUp both run 5 seconds in Reconnecting; retry is declared first and, while
        // its guard holds, re-enters Reconnecting, which restarts both.
        val store = messenger()
        send(store, MessengerAction.Drop)
        val before = store.currentState.timers
        advance(5.seconds)
        val after = store.currentState.timers
        assertEquals(before.running.keys, after.running.keys)
        assertTrue(after.running.values.all { it > before.issued }, "both got new tokens")
        store.close()
    }

    @Test
    fun zeroDelayTimerFiresRightAway() = runTest {
        val quick = StateChartDefinition(
            initial = StateId("A"),
            states = listOf(AtomicState(StateId("A")), AtomicState(StateId("B"))),
            transitions = listOf(Transition(StateId("A"), StateId("B"), Trigger.After(0.milliseconds))),
        )
        val store = StateChartStore<Unit, MessengerAction, MessengerEvent>(quick, Unit, backgroundScope.coroutineContext)
        store.startAndAwait()
        runCurrent()
        assertEquals(listOf(StateId("B")), store.currentState.activeLeaves(quick))
        store.close()
    }
}

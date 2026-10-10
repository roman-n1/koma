package actron.statechart

import actron.core.Action
import actron.core.Event
import actron.core.ExperimentalActronApi
import actron.core.PendingActionPolicy
import actron.core.State
import actron.core.Store
import actron.test.dispatchAndAwait
import actron.test.patch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Example tests for timers ([Trigger.After]) on a messenger: a reconnect that gives up after 5
 * seconds, a typing indicator that clears after 3 seconds of silence, a heartbeat self-loop, a
 * session timeout of the parallel state; which timers each step starts and cancels, firing,
 * stale timers, guards, validation, Mermaid, paths and conformance.
 *
 * ```
 * [*] --> Offline
 * Offline --Connect--> Online
 * state Online {                                      parallel
 *     state Connection {
 *         [*] --> Connected
 *         Connected --after 30s / sendPing--> Connected   heartbeat: restarts itself
 *         Connected --Drop--> Reconnecting
 *         Reconnecting --Restore--> Connected
 *         Reconnecting --Retry--> Connected
 *     }
 *     --
 *     state Composer {
 *         [*] --> Idle
 *         Idle --KeyPress--> Typing
 *         Typing --KeyPress--> Typing                   re-enters Typing: its timer restarts
 *         Typing --after 3s--> Idle
 *         Typing --Send--> Idle
 *     }
 * }
 * Reconnecting --after 5s [outOfRetries]--> Offline
 * Online --after 30m--> Offline                        session timeout, cancelled only by leaving Online
 * Online --Logout--> Offline
 * ```
 */
@OptIn(ExperimentalActronApi::class, ExperimentalCoroutinesApi::class)
class StateChartTimerTest {

    sealed interface MessengerAction : Action {
        data object Connect : MessengerAction
        data object Drop : MessengerAction
        data object Restore : MessengerAction
        data object Retry : MessengerAction
        data object KeyPress : MessengerAction
        data object Send : MessengerAction
        data object Logout : MessengerAction
    }

    enum class Link { Connected, Reconnecting }

    sealed interface MessengerState : State {
        data object Offline : MessengerState
        data class Online(val link: Link, val typing: Boolean = false, val retriesLeft: Int = 0) : MessengerState
    }

    sealed interface MessengerEvent : Event

    private val offline = StateId("Offline")
    private val online = StateId("Online")
    private val connection = StateId("Connection")
    private val connected = StateId("Connected")
    private val reconnecting = StateId("Reconnecting")
    private val composer = StateId("Composer")
    private val idle = StateId("Idle")
    private val typing = StateId("Typing")

    private inline fun <reified A : MessengerAction> on(name: String) = ActionMatcher.of<A>(name)

    private val connect = Transition(offline, online, on<MessengerAction.Connect>("Connect"))
    private val heartbeat = Transition(connected, connected, Trigger.After(30.seconds), effect = "sendPing")
    private val drop = Transition(connected, reconnecting, on<MessengerAction.Drop>("Drop"))
    private val restore = Transition(reconnecting, connected, on<MessengerAction.Restore>("Restore"))
    private val retry = Transition(reconnecting, connected, on<MessengerAction.Retry>("Retry"))
    private val keyPress = Transition(idle, typing, on<MessengerAction.KeyPress>("KeyPress"))
    private val keepTyping = Transition(typing, typing, on<MessengerAction.KeyPress>("KeyPress"))
    private val typingTimeout = Transition(typing, idle, Trigger.After(3.seconds))
    private val send = Transition(typing, idle, on<MessengerAction.Send>("Send"))
    private val giveUp = Transition(reconnecting, offline, Trigger.After(5.seconds), guard = "outOfRetries")
    private val sessionTimeout = Transition(online, offline, Trigger.After(30.minutes))
    private val logout = Transition(online, offline, on<MessengerAction.Logout>("Logout"))

    private val chart = StateChartDefinition(
        initial = offline,
        states = listOf(
            AtomicState(offline),
            ParallelState(online),
            CompoundState(connection, initial = connected, parent = online),
            AtomicState(connected, parent = connection),
            AtomicState(reconnecting, parent = connection),
            CompoundState(composer, initial = idle, parent = online),
            AtomicState(idle, parent = composer),
            AtomicState(typing, parent = composer),
        ),
        transitions = listOf(
            connect, heartbeat, drop, restore, retry, keyPress, keepTyping, typingTimeout, send, giveUp, sessionTimeout, logout,
        ),
    )

    private val activeLeavesOf: (MessengerState) -> Set<StateId> = { state ->
        when (state) {
            MessengerState.Offline -> setOf(offline)
            is MessengerState.Online -> setOf(if (state.link == Link.Connected) connected else reconnecting, if (state.typing) typing else idle)
        }
    }

    /** What the guard was asked with, in order. */
    private val guardCalls = mutableListOf<Pair<MessengerState, Action>>()

    private val runtime = StateChartRuntime(
        chart,
        { s: MessengerState -> activeLeavesOf(s).first() },
        guards = mapOf(
            "outOfRetries" to { state, action ->
                guardCalls += state to action
                state is MessengerState.Online && state.retriesLeft == 0
            },
        ),
    )

    private val noRetries = MessengerState.Online(Link.Reconnecting)

    private fun step(configuration: StateConfiguration, action: MessengerAction) =
        runtime.step(configuration, noRetries, action) as StepResult.Transitioned

    private fun fire(configuration: StateConfiguration, timer: Transition, state: MessengerState = noRetries) =
        runtime.fire(configuration, state, timer)

    private fun online(vararg actions: MessengerAction): StateConfiguration =
        (listOf(MessengerAction.Connect) + actions).fold(runtime.initialConfiguration()) { c, a -> step(c, a).configuration }

    // region runtime

    @Test
    fun enteringStartsTheTimersOfEveryEnteredStateOutermostFirst() {
        assertEquals(emptyList(), runtime.initialTimers())
        val result = step(runtime.initialConfiguration(), MessengerAction.Connect)
        // Entered: Online, Connection, Composer, Connected, Idle.
        assertEquals(listOf(sessionTimeout, heartbeat), result.timersToStart)
        assertEquals(emptyList(), result.timersToCancel)
        assertEquals(listOf(sessionTimeout, heartbeat), runtime.activeTimers(result.configuration))
    }

    @Test
    fun initialTimersComeFromTheInitialConfiguration() {
        val startOnline = StateChartRuntime(chart.copy(initial = online), { s: MessengerState -> activeLeavesOf(s).first() }, mapOf("outOfRetries" to { _, _ -> true }))
        assertEquals(listOf(sessionTimeout, heartbeat), startOnline.initialTimers())
    }

    @Test
    fun leavingAStateCancelsItsTimersAndEnteringTheNextStartsItsOwn() {
        val result = step(online(), MessengerAction.Drop)
        assertEquals(listOf(heartbeat), result.timersToCancel)
        assertEquals(listOf(giveUp), result.timersToStart)
    }

    @Test
    fun reconnectingGivesUpAfterFiveSecondsAndLeavesEverything() {
        val reconnectingAndTyping = online(MessengerAction.Drop, MessengerAction.KeyPress)
        val result = fire(reconnectingAndTyping, giveUp) as StepResult.Transitioned
        assertEquals(listOf(giveUp), result.transitions)
        assertEquals(listOf(typing, reconnecting, composer, connection, online), result.exited)
        assertEquals(listOf(offline), result.entered)
        assertEquals(StateConfiguration(setOf(offline)), result.configuration)
        // The fired timer's own source is exited, so it is cancelled with the rest.
        assertEquals(listOf(typingTimeout, giveUp, sessionTimeout), result.timersToCancel)
        assertEquals(emptyList(), result.timersToStart)
    }

    @Test
    fun timerCancelledByLeavingItsStateIsIgnoredWhenItFiresLate() {
        val restored = step(online(MessengerAction.Drop), MessengerAction.Restore)
        assertEquals(listOf(giveUp), restored.timersToCancel)
        assertEquals(listOf(heartbeat), restored.timersToStart)
        // A caller that did not cancel it in time: the source is no longer active.
        assertEquals(StepResult.Ignored, fire(restored.configuration, giveUp))
        assertEquals(emptyList(), guardCalls)
    }

    @Test
    fun timerInOneRegionFiresWhileTheOtherRegionHandlesActions() {
        val typingOnline = online(MessengerAction.KeyPress)
        // Drop moves Connection; the typing timer keeps running.
        val dropped = step(typingOnline, MessengerAction.Drop)
        assertEquals(listOf(drop), dropped.transitions)
        assertEquals(listOf(heartbeat), dropped.timersToCancel)
        assertEquals(listOf(giveUp), dropped.timersToStart)
        // The typing timer fires in Composer only; Connection stays reconnecting.
        val cleared = fire(dropped.configuration, typingTimeout) as StepResult.Transitioned
        assertEquals(listOf(typing), cleared.exited)
        assertEquals(listOf(idle), cleared.entered)
        assertEquals(listOf(reconnecting, idle), chart.activeLeaves(cleared.configuration))
        assertEquals(listOf(typingTimeout), cleared.timersToCancel)
        assertEquals(emptyList(), cleared.timersToStart)
    }

    @Test
    fun reenteringAStateRestartsItsTimers() {
        // An action self-loop: Typing is exited and entered again.
        val again = step(online(MessengerAction.KeyPress), MessengerAction.KeyPress)
        assertEquals(listOf(typingTimeout), again.timersToCancel)
        assertEquals(listOf(typingTimeout), again.timersToStart)
    }

    @Test
    fun selfLoopTimerRestartsItself() {
        val result = fire(online(), heartbeat) as StepResult.Transitioned
        assertEquals(listOf(connected), result.exited)
        assertEquals(listOf(connected), result.entered)
        assertEquals(listOf(heartbeat), result.timersToCancel)
        assertEquals(listOf(heartbeat), result.timersToStart)
        assertEquals("sendPing", result.transition.effect)
    }

    @Test
    fun guardOfATimerGetsTheStateAndTimerFired() {
        val reconnectingOnline = online(MessengerAction.Drop)
        val withRetries = MessengerState.Online(Link.Reconnecting, retriesLeft = 2)
        assertEquals(StepResult.Ignored, fire(reconnectingOnline, giveUp, withRetries))
        assertEquals(listOf<Pair<MessengerState, Action>>(withRetries to TimerFired(giveUp)), guardCalls)
        assertEquals(listOf(offline), chart.activeLeaves((fire(reconnectingOnline, giveUp) as StepResult.Transitioned).configuration))
    }

    @Test
    fun actionsNeverFireTimers() {
        // TimerFired passed as an action is just an action: no matcher here matches it.
        assertEquals(StepResult.Ignored, runtime.step(online(MessengerAction.Drop), noRetries, TimerFired(giveUp)))
        assertEquals(StepResult.Ignored, runtime.step(online(MessengerAction.Drop), noRetries, MessengerAction.Send))
    }

    @Test
    fun fireRejectsActionTransitionsAndUndeclaredTimers() {
        val notATimer = assertFailsWith<IllegalArgumentException> { fire(online(), drop) }
        assertEquals("[Actron] Not a timer: $drop", notATimer.message)
        val stranger = Transition(connected, connected, Trigger.After(31.seconds))
        val undeclared = assertFailsWith<IllegalArgumentException> { fire(online(), stranger) }
        assertEquals("[Actron] Timer is not declared in the chart: $stranger", undeclared.message)
    }

    @Test
    fun transitionKeepsItsActionShapeAndTellsTimersApart() {
        assertEquals(Transition(offline, online, Trigger.OnAction(connect.on!!)), connect)
        assertEquals(on<MessengerAction.Connect>("Connect"), connect.on)
        assertNull(connect.after)
        assertEquals(false, connect.isTimer)
        assertNull(giveUp.on)
        assertEquals(5.seconds, giveUp.after)
        assertEquals(true, giveUp.isTimer)
        assertEquals(Transition(offline, online, connect.on!!, "g", "e"), Transition(offline, online, Trigger.OnAction(connect.on!!), "g", "e"))
        // The flat shortcut result starts and cancels nothing.
        assertEquals(StepResult.Transitioned(listOf(connect), listOf(offline), listOf(online), StateConfiguration(setOf(online))), StepResult.Transitioned(connect))
    }

    // endregion

    // region validation

    @Test
    fun messengerIsValid() {
        assertEquals(emptyList(), chart.validate(listOf(MessengerAction.Connect, MessengerAction.KeyPress, TimerFired(giveUp))))
    }

    @Test
    fun timerIssuesAreReportedWithTheOtherTransitionIssues() {
        val history = StateId("ComposerHistory")
        val zero = Transition(typing, idle, Trigger.After(Duration.ZERO))
        val negative = Transition(idle, typing, Trigger.After((-1).seconds))
        val fromHistory = Transition(history, idle, Trigger.After(1.seconds))
        val twin = Transition(typing, offline, Trigger.After(3.seconds))
        val broken = chart.copy(
            states = chart.states + HistoryState(history, parent = composer),
            transitions = chart.transitions + zero + negative + fromHistory + twin,
        )
        assertEquals(
            listOf(
                ValidationIssue.NonPositiveDelay(zero),
                ValidationIssue.NonPositiveDelay(negative),
                ValidationIssue.TransitionFromHistory(fromHistory),
                ValidationIssue.AmbiguousTimers(typing, 3.seconds, listOf(typingTimeout, twin)),
            ),
            broken.validate(),
        )
    }

    // endregion

    // region mermaid

    @Test
    fun mermaidLabelsTimersWithTheirDelayGuardAndEffect() {
        assertEquals(
            """
            stateDiagram-v2
                [*] --> Offline
                state Online {
                    state Connection {
                        [*] --> Connected
                        Connected
                        Reconnecting
                        Connected --> Connected : after 30s / sendPing
                        Connected --> Reconnecting : Drop
                        Reconnecting --> Connected : Restore
                        Reconnecting --> Connected : Retry
                    }
                    --
                    state Composer {
                        [*] --> Idle
                        Idle
                        Typing
                        Idle --> Typing : KeyPress
                        Typing --> Typing : KeyPress
                        Typing --> Idle : after 3s
                        Typing --> Idle : Send
                    }
                }
                Offline --> Online : Connect
                Reconnecting --> Offline : after 5s [outOfRetries]
                Online --> Offline : after 30m
                Online --> Offline : Logout
            """.trimIndent(),
            chart.toMermaid(),
        )
        val odd = StateChartDefinition(offline, listOf(AtomicState(offline)), listOf(Transition(offline, offline, Trigger.After(1500.milliseconds + 1.minutes), "g", "e")))
        assertEquals("    Offline --> Offline : after 1m 1.5s [g] / e", odd.toMermaid().lines().last())
    }

    // endregion

    // region paths

    @Test
    fun pathStepsIncludeTimersAndReplayWithFire() {
        val path = chart.shortestPathTo(reconnecting)!!
        assertEquals(listOf(connect, drop), path.transitions)
        // A splash screen that moves on by itself: the path to Details starts with the timer.
        val splash = StateId("Splash")
        val home = StateId("Home")
        val details = StateId("Details")
        val showHome = Transition(splash, home, Trigger.After(2.seconds))
        val open = Transition(home, details, on<MessengerAction.Send>("Open"))
        val app = StateChartDefinition(splash, listOf(AtomicState(splash), AtomicState(home), AtomicState(details)), listOf(showHome, open))
        val toDetails = app.shortestPathTo(details)!!
        assertEquals(StateChartPath(splash, listOf(showHome, open)), toDetails)
        assertEquals(listOf(Trigger.After(2.seconds), Trigger.OnAction(open.on!!)), toDetails.triggers)
        assertEquals(listOf(open.on), toDetails.actions)
        assertEquals(listOf(toDetails), app.transitionCoveragePaths())
        // Replayed on the runtime: fire for the timer step, step for the action step.
        val appRuntime = StateChartRuntime(app, { _: MessengerState -> splash })
        val afterSplash = appRuntime.fire(appRuntime.initialConfiguration(), MessengerState.Offline, showHome) as StepResult.Transitioned
        assertEquals(listOf(showHome), appRuntime.initialTimers())
        val end = appRuntime.step(afterSplash.configuration, MessengerState.Offline, MessengerAction.Send) as StepResult.Transitioned
        assertEquals(StateConfiguration(setOf(details)), end.configuration)
        // A state only a timer reaches is reachable, and the timer is covered.
        assertEquals(setOf(offline, online, connection, composer, connected, idle, reconnecting, typing), chart.reachableStates())
        assertEquals(chart.transitions.toSet(), chart.transitionCoveragePaths().flatMap { it.transitions }.toSet())
    }

    // endregion

    // region conformance

    /**
     * A Store written by hand, with its timers as launched delays: Drop gives up after 5 seconds
     * (or, with [restoresItself], restores the link instead, which the chart does not declare),
     * Retry reconnects after 4 seconds, a key press clears the typing indicator after 3 seconds.
     */
    private fun TestScope.createStore(restoresItself: Boolean = false): Store<MessengerState, MessengerAction, MessengerEvent> =
        Store<MessengerState, MessengerAction, MessengerEvent>(MessengerState.Offline) {
            coroutineContext(StandardTestDispatcher(testScheduler))
            pendingActionPolicy(PendingActionPolicy.Keep)
            state<MessengerState.Offline> {
                action<MessengerAction.Connect> { nextState { MessengerState.Online(Link.Connected) } }
            }
            state<MessengerState.Online> {
                action<MessengerAction.Drop> {
                    nextState { state.copy(link = Link.Reconnecting) }
                    launch {
                        delay(5.seconds)
                        transaction {
                            if (state.link == Link.Reconnecting) {
                                nextState { if (restoresItself) state.copy(link = Link.Connected) else MessengerState.Offline }
                            }
                        }
                    }
                }
                action<MessengerAction.Restore> { nextState { state.copy(link = Link.Connected) } }
                action<MessengerAction.Retry> {
                    launch {
                        delay(4.seconds)
                        transaction { nextState { state.copy(link = Link.Connected) } }
                    }
                }
                action<MessengerAction.KeyPress> {
                    nextState { state.copy(typing = true) }
                    launch {
                        delay(3.seconds)
                        transaction { nextState { state.copy(typing = false) } }
                    }
                }
                action<MessengerAction.Send> { if (state.typing) nextState { state.copy(typing = false) } }
                action<MessengerAction.Logout> { nextState { MessengerState.Offline } }
            }
        }

    private fun conformance() = StateChartConformance.withActiveLeaves<MessengerState, MessengerAction, MessengerEvent>(chart, activeLeavesOf)

    private fun TestScope.advance(duration: Duration) {
        advanceTimeBy(duration)
        runCurrent()
    }

    @Test
    fun changeNoActionExplainsIsCreditedToATimer() = runTest {
        val conformance = conformance()
        val store = createStore().patch { plugin(conformance) }
        store.dispatchAndAwait(MessengerAction.Connect)
        store.dispatchAndAwait(MessengerAction.Drop)
        store.dispatchAndAwait(MessengerAction.Send) // not typing: no change, but it is the latest action
        advance(5.seconds)
        assertEquals(MessengerState.Offline, store.currentState)
        assertEquals(emptyList(), conformance.violations)
        assertEquals(listOf(connect, drop, giveUp), conformance.coveredTransitions.toList())
        store.close()
    }

    @Test
    fun changeATimerExplainsLeavesTheActionForTheNextChange() = runTest {
        val conformance = conformance()
        val store = createStore().patch { plugin(conformance) }
        listOf(MessengerAction.Connect, MessengerAction.Drop, MessengerAction.KeyPress, MessengerAction.Retry).forEach { store.dispatchAndAwait(it) }
        advance(3.seconds) // the typing timer: Retry does not explain it, the timer does
        advance(1.seconds) // Retry's reconnect: still attributed to Retry, not to Restore
        assertEquals(MessengerState.Online(Link.Connected), store.currentState)
        assertEquals(emptyList(), conformance.violations)
        assertEquals(listOf(connect, drop, keyPress, typingTimeout, retry), conformance.coveredTransitions.toList())
        store.close()
    }

    @Test
    fun changeNeitherAnActionNorATimerExplainsIsReported() = runTest {
        val conformance = conformance()
        val store = createStore(restoresItself = true).patch { plugin(conformance) }
        listOf(MessengerAction.Connect, MessengerAction.Drop, MessengerAction.Send).forEach { store.dispatchAndAwait(it) }
        advance(5.seconds)
        // The chart gives up after 5 seconds; restoring the link needs Restore or Retry.
        assertEquals(listOf(ConformanceViolation.UnexpectedTrigger(reconnecting, connected, MessengerAction.Send)), conformance.violations)
        store.close()
    }

    // endregion
}

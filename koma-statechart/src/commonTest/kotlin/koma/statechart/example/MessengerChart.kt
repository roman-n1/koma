package koma.statechart.example

import koma.core.Action
import koma.core.Event
import koma.core.ExperimentalKomaApi
import koma.core.Store
import koma.statechart.ActionMatcher
import koma.statechart.AtomicState
import koma.statechart.ChartState
import koma.statechart.CompoundState
import koma.statechart.HistoryState
import koma.statechart.ParallelState
import koma.statechart.StateChartDefinition
import koma.statechart.StateChartStore
import koma.statechart.StateId
import koma.statechart.Transition
import koma.statechart.Trigger
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds

/*
 * A messenger screen as one statechart, run by a StateChartStore. This file is the example to
 * copy from; MessengerChartTest drives it scenario by scenario and replays every generated test
 * path against it.
 *
 * Layout of this file, in the order an app would write it:
 * 1. actions, events and the context (the data that goes with the chart);
 * 2. the services the chart talks to (sign-in, connection, sending), as an interface;
 * 3. the chart: state ids, transitions, the definition;
 * 4. the Store factory: guards, effects, hooks and activities.
 *
 * Everything that names a state or an action is an explicit string, never a class name, so the
 * chart survives R8 and ProGuard: StateId("...") and ActionMatcher.of<A>("...").
 */

/** What the UI and the services send to the messenger. */
sealed interface MessengerAction : Action {
    data class SubmitCredentials(val login: String, val password: String) : MessengerAction
    data class SignInSucceeded(val user: String) : MessengerAction
    data class SignInFailed(val reason: String) : MessengerAction
    data object SignOut : MessengerAction

    data object ConnectionOpened : MessengerAction
    data object ConnectFailed : MessengerAction
    data object ConnectionLost : MessengerAction

    data class OpenChat(val chatId: String) : MessengerAction
    data object CloseChat : MessengerAction
    data class SendMessage(val text: String) : MessengerAction
    data object MessageDelivered : MessengerAction
    data object SendFailed : MessengerAction
    data object RetryMessage : MessengerAction
    data object DiscardMessage : MessengerAction

    data object OpenSettings : MessengerAction
    data object CloseSettings : MessengerAction
}

/** One-off signals for the UI (snackbars, toasts). */
sealed interface MessengerEvent : Event {
    data class SignInError(val reason: String) : MessengerEvent
    data class MessageNotSent(val text: String) : MessengerEvent
}

/**
 * How often to retry before slowing down or giving up. Part of the context so that a test (or a
 * remote config) can change it; the guards read it.
 *
 * @property quickReconnects Reconnect attempts made after [MessengerChart.QUICK_BACKOFF]; later
 * attempts wait [MessengerChart.SLOW_BACKOFF]
 * @property resends Automatic resends of a failed message before it is marked as failed
 */
data class RetryPolicy(val quickReconnects: Int = 3, val resends: Int = 2)

/**
 * The data that goes with the chart. Where the chart is lives in [ChartState.configuration]; this
 * is only what the states do not say.
 */
data class MessengerContext(
    val user: String? = null,
    val chatId: String? = null,
    val connectAttempts: Int = 0,
    val outgoing: String? = null,
    val sendFailures: Int = 0,
    val delivered: List<String> = emptyList(),
    val policy: RetryPolicy = RetryPolicy(),
)

/**
 * The outside world. Every call suspends, and the chart cancels a call when the state that made it
 * is exited (sign-out cancels a pending connect, opening settings cancels a pending send).
 */
interface MessengerServices {
    /** Returns the user name, or throws when the credentials are rejected. */
    suspend fun signIn(login: String, password: String): String

    /** Returns when the connection is open, or throws when it cannot be opened. */
    suspend fun connect()

    /** Returns when an open connection closes. */
    suspend fun awaitDisconnect()

    /** Returns when [text] is delivered to [chatId], or throws. */
    suspend fun send(chatId: String, text: String)
}

/**
 * The messenger statechart.
 *
 * - **Auth → session.** `SignedOut` → `SigningIn` → `Session`; signing out, from anywhere in the
 *   session, exits every region of it at once.
 * - **Parallel regions.** `Session` runs `Connection` and `Ui` side by side: the connection
 *   reconnects on its own while the user reads, writes or opens settings.
 * - **Backoff with timers.** A failed or lost connection waits in `Backoff`: 2 s while the attempt
 *   count is within [RetryPolicy.quickReconnects], 30 s after that. Both are timers with guards,
 *   so exactly one of them moves the chart; the other is ignored when it fires.
 * - **Send with retry and failure.** `Sending` → `SendFailed` → `RetryWait`, which resends after
 *   3 s up to [RetryPolicy.resends] times, then gives up in `Failed`; the user may retry or discard.
 * - **History.** `Settings` is a sibling of `Chats`; closing it goes to the deep history of `Chats`,
 *   so the user is back in the same chat and even in the same send attempt.
 *
 * The same chart as [StateChartDefinition.toMermaid][koma.statechart.toMermaid] draws it (the test
 * `mermaidDiagramShowsTheWholeChart` checks this text):
 *
 * ```mermaid
 * stateDiagram-v2
 *     [*] --> App
 *     state App {
 *         [*] --> SignedOut
 *         SignedOut
 *         SigningIn
 *         state Session {
 *             state Connection {
 *                 [*] --> Connecting
 *                 Connecting
 *                 Online
 *                 Backoff
 *                 Connecting --> Online : ConnectionOpened / resetAttempts
 *                 Connecting --> Backoff : ConnectFailed / countAttempt
 *                 Online --> Backoff : ConnectionLost / countAttempt
 *                 Backoff --> Connecting : after 2s [quickReconnect]
 *                 Backoff --> Connecting : after 30s [slowReconnect]
 *             }
 *             --
 *             state Ui {
 *                 [*] --> Chats
 *                 state Chats {
 *                     [*] --> ChatList
 *                     state "[H*]" as ChatsHistory
 *                     ChatList
 *                     state Chat {
 *                         [*] --> Composing
 *                         Composing
 *                         Sending
 *                         RetryWait
 *                         Failed
 *                         Composing --> Sending : SendMessage / queueMessage
 *                         Sending --> Composing : MessageDelivered / markDelivered
 *                         Sending --> RetryWait : SendFailed / countSendFailure
 *                         RetryWait --> Sending : after 3s [canResend]
 *                         RetryWait --> Failed : after 3s [resendsExhausted]
 *                         Failed --> Sending : RetryMessage / resetSendFailures
 *                         Failed --> Composing : DiscardMessage / dropMessage
 *                     }
 *                     ChatList --> Chat : OpenChat / rememberChat
 *                     Chat --> ChatList : CloseChat
 *                 }
 *                 Settings
 *                 Chats --> Settings : OpenSettings
 *                 Settings --> ChatsHistory : CloseSettings
 *             }
 *         }
 *         SignedOut --> SigningIn : SubmitCredentials
 *         SigningIn --> Session : SignInSucceeded / rememberUser
 *         SigningIn --> SignedOut : SignInFailed
 *         Session --> SignedOut : SignOut / forgetUser
 *     }
 * ```
 */
@OptIn(ExperimentalKomaApi::class)
object MessengerChart {
    val QUICK_BACKOFF = 2.seconds
    val SLOW_BACKOFF = 30.seconds
    val RESEND_DELAY = 3.seconds

    // region states

    val app = StateId("App")
    val signedOut = StateId("SignedOut")
    val signingIn = StateId("SigningIn")
    val session = StateId("Session")

    val connection = StateId("Connection")
    val connecting = StateId("Connecting")
    val online = StateId("Online")
    val backoff = StateId("Backoff")

    val ui = StateId("Ui")
    val chats = StateId("Chats")
    val chatsHistory = StateId("ChatsHistory")
    val chatList = StateId("ChatList")
    val chat = StateId("Chat")
    val composing = StateId("Composing")
    val sending = StateId("Sending")
    val retryWait = StateId("RetryWait")
    val failed = StateId("Failed")
    val settings = StateId("Settings")

    // endregion

    // region transitions

    val submitCredentials = Transition(signedOut, signingIn, ActionMatcher.of<MessengerAction.SubmitCredentials>("SubmitCredentials"))
    val signInSucceeded = Transition(signingIn, session, ActionMatcher.of<MessengerAction.SignInSucceeded>("SignInSucceeded"), effect = "rememberUser")
    val signInFailed = Transition(signingIn, signedOut, ActionMatcher.of<MessengerAction.SignInFailed>("SignInFailed"))
    val signOut = Transition(session, signedOut, ActionMatcher.of<MessengerAction.SignOut>("SignOut"), effect = "forgetUser")

    val connectionOpened = Transition(connecting, online, ActionMatcher.of<MessengerAction.ConnectionOpened>("ConnectionOpened"), effect = "resetAttempts")
    val connectFailed = Transition(connecting, backoff, ActionMatcher.of<MessengerAction.ConnectFailed>("ConnectFailed"), effect = "countAttempt")
    val connectionLost = Transition(online, backoff, ActionMatcher.of<MessengerAction.ConnectionLost>("ConnectionLost"), effect = "countAttempt")
    val quickReconnect = Transition(backoff, connecting, Trigger.After(QUICK_BACKOFF), guard = "quickReconnect")
    val slowReconnect = Transition(backoff, connecting, Trigger.After(SLOW_BACKOFF), guard = "slowReconnect")

    val openChat = Transition(chatList, chat, ActionMatcher.of<MessengerAction.OpenChat>("OpenChat"), effect = "rememberChat")
    val closeChat = Transition(chat, chatList, ActionMatcher.of<MessengerAction.CloseChat>("CloseChat"))
    val sendMessage = Transition(composing, sending, ActionMatcher.of<MessengerAction.SendMessage>("SendMessage"), effect = "queueMessage")
    val messageDelivered = Transition(sending, composing, ActionMatcher.of<MessengerAction.MessageDelivered>("MessageDelivered"), effect = "markDelivered")
    val sendFailed = Transition(sending, retryWait, ActionMatcher.of<MessengerAction.SendFailed>("SendFailed"), effect = "countSendFailure")
    val resend = Transition(retryWait, sending, Trigger.After(RESEND_DELAY), guard = "canResend")
    val giveUpSending = Transition(retryWait, failed, Trigger.After(RESEND_DELAY), guard = "resendsExhausted")
    val retryMessage = Transition(failed, sending, ActionMatcher.of<MessengerAction.RetryMessage>("RetryMessage"), effect = "resetSendFailures")
    val discardMessage = Transition(failed, composing, ActionMatcher.of<MessengerAction.DiscardMessage>("DiscardMessage"), effect = "dropMessage")

    val openSettings = Transition(chats, settings, ActionMatcher.of<MessengerAction.OpenSettings>("OpenSettings"))
    val closeSettings = Transition(settings, chatsHistory, ActionMatcher.of<MessengerAction.CloseSettings>("CloseSettings"))

    // endregion

    val definition = StateChartDefinition(
        initial = app,
        states = listOf(
            CompoundState(app, initial = signedOut),
            AtomicState(signedOut, parent = app),
            AtomicState(signingIn, parent = app),
            ParallelState(session, parent = app),

            CompoundState(connection, initial = connecting, parent = session),
            AtomicState(connecting, parent = connection),
            AtomicState(online, parent = connection),
            AtomicState(backoff, parent = connection),

            CompoundState(ui, initial = chats, parent = session),
            CompoundState(chats, initial = chatList, parent = ui),
            HistoryState(chatsHistory, parent = chats, deep = true),
            AtomicState(chatList, parent = chats),
            CompoundState(chat, initial = composing, parent = chats),
            AtomicState(composing, parent = chat),
            AtomicState(sending, parent = chat),
            AtomicState(retryWait, parent = chat),
            AtomicState(failed, parent = chat),
            AtomicState(settings, parent = ui),
        ),
        transitions = listOf(
            submitCredentials, signInSucceeded, signInFailed, signOut,
            connectionOpened, connectFailed, connectionLost, quickReconnect, slowReconnect,
            openChat, closeChat, sendMessage, messageDelivered, sendFailed, resend, giveUpSending, retryMessage, discardMessage,
            openSettings, closeSettings,
        ),
    )
}

/** The Store type the UI collects: a plain Koma Store. */
@OptIn(ExperimentalKomaApi::class)
typealias MessengerStore = Store<ChartState<MessengerContext>, MessengerAction, MessengerEvent>

/**
 * Creates the messenger Store: the chart's guards and effects, and the work each state does.
 *
 * Guards and effects are pure functions of the context and the action. Work that talks to
 * [services] lives in `onEnter { launch {} }` (when it needs the action that entered the state) or
 * in `activity` (when it must also run after a restore); both are cancelled when their state is
 * exited, and both report back by dispatching an action.
 */
@OptIn(ExperimentalKomaApi::class)
fun MessengerStore(
    services: MessengerServices,
    initial: MessengerContext = MessengerContext(),
    coroutineContext: CoroutineContext? = null,
): MessengerStore = StateChartStore(MessengerChart.definition, initial, coroutineContext) {
    // The parameter is not called `context`: inside the hooks below, `context` must be the hook
    // scope's context (the value as updated by this step), and a parameter of that name would
    // shadow it.
    // Guards: read the state before the step; a timer's guard gets TimerFired as the action.
    guard("quickReconnect") { state, _ -> state.context.connectAttempts <= state.context.policy.quickReconnects }
    guard("slowReconnect") { state, _ -> state.context.connectAttempts > state.context.policy.quickReconnects }
    guard("canResend") { state, _ -> state.context.sendFailures <= state.context.policy.resends }
    guard("resendsExhausted") { state, _ -> state.context.sendFailures > state.context.policy.resends }

    // Effects: pure context updates, run between the exit and the enter hooks of a step.
    effect("rememberUser") { c, action -> c.copy(user = (action as MessengerAction.SignInSucceeded).user) }
    effect("forgetUser") { c, _ -> MessengerContext(policy = c.policy) }
    effect("resetAttempts") { c, _ -> c.copy(connectAttempts = 0) }
    effect("countAttempt") { c, _ -> c.copy(connectAttempts = c.connectAttempts + 1) }
    effect("rememberChat") { c, action -> c.copy(chatId = (action as MessengerAction.OpenChat).chatId) }
    effect("queueMessage") { c, action -> c.copy(outgoing = (action as MessengerAction.SendMessage).text, sendFailures = 0) }
    effect("markDelivered") { c, _ -> c.copy(delivered = c.delivered + listOfNotNull(c.outgoing), outgoing = null) }
    effect("countSendFailure") { c, _ -> c.copy(sendFailures = c.sendFailures + 1) }
    effect("resetSendFailures") { c, _ -> c.copy(sendFailures = 0) }
    effect("dropMessage") { c, _ -> c.copy(outgoing = null, sendFailures = 0) }

    // Work that needs the action that entered the state: onEnter + launch.
    onEnter(MessengerChart.signingIn) {
        val submit = action as? MessengerAction.SubmitCredentials ?: return@onEnter
        launch {
            val result = attempt { services.signIn(submit.login, submit.password) }
            dispatch(result.fold({ MessengerAction.SignInSucceeded(it) }, { MessengerAction.SignInFailed(it.message ?: "unknown") }))
        }
    }
    onEnter(MessengerChart.signedOut) {
        (action as? MessengerAction.SignInFailed)?.let { event(MessengerEvent.SignInError(it.reason)) }
    }
    onEnter(MessengerChart.sending) {
        val chatId = context.chatId ?: return@onEnter
        val text = context.outgoing ?: return@onEnter
        launch {
            val sent = attempt { services.send(chatId, text) }.isSuccess
            dispatch(if (sent) MessengerAction.MessageDelivered else MessengerAction.SendFailed)
        }
    }
    onEnter(MessengerChart.failed) {
        context.outgoing?.let { event(MessengerEvent.MessageNotSent(it)) }
    }

    // Work that only needs to know it is in the state, and must restart after a restore: activity.
    activity(MessengerChart.connecting) {
        val opened = attempt { services.connect() }.isSuccess
        dispatch(if (opened) MessengerAction.ConnectionOpened else MessengerAction.ConnectFailed)
    }
    activity(MessengerChart.online) {
        services.awaitDisconnect()
        dispatch(MessengerAction.ConnectionLost)
    }
}

/**
 * Runs a service call and captures its failure, but lets cancellation through: when the state that
 * started the call is exited, the call must stop, not report a failure. (`runCatching` would catch
 * the cancellation too.)
 */
private suspend fun <T> attempt(call: suspend () -> T): Result<T> = try {
    Result.success(call())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

package actron.statechart.example

import actron.core.ExperimentalActronApi
import actron.statechart.ChartState
import actron.statechart.StateChartConformance
import actron.statechart.StateChartPath
import actron.statechart.StateId
import actron.statechart.Transition
import actron.statechart.example.MessengerChart.backoff
import actron.statechart.example.MessengerChart.chat
import actron.statechart.example.MessengerChart.chatList
import actron.statechart.example.MessengerChart.composing
import actron.statechart.example.MessengerChart.connecting
import actron.statechart.example.MessengerChart.definition
import actron.statechart.example.MessengerChart.failed
import actron.statechart.example.MessengerChart.online
import actron.statechart.example.MessengerChart.retryWait
import actron.statechart.example.MessengerChart.sending
import actron.statechart.example.MessengerChart.settings
import actron.statechart.example.MessengerChart.signedOut
import actron.statechart.example.MessengerChart.signingIn
import actron.statechart.toMermaid
import actron.statechart.transitionCoveragePaths
import actron.statechart.validate
import actron.test.createRecorder
import actron.test.dispatchAndAwait
import actron.test.patch
import actron.test.startAndAwait
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end tests of the messenger example ([MessengerChart], [MessengerStore]): each scenario
 * drives the real Store with fake services in virtual time, and the model-based test replays every
 * path [transitionCoveragePaths] generates.
 *
 * The chart, as a diagram, is in the KDoc of [MessengerChart]; `mermaidDiagramShowsTheWholeChart`
 * checks it. In short:
 *
 * ```
 * App: SignedOut --SubmitCredentials--> SigningIn --SignInSucceeded--> Session   (--SignInFailed--> SignedOut)
 * Session (parallel) --SignOut--> SignedOut
 *   Connection: Connecting --ConnectionOpened--> Online --ConnectionLost--> Backoff
 *               Connecting --ConnectFailed--> Backoff --after 2s [quick] / after 30s [slow]--> Connecting
 *   Ui: Chats { ChatList --OpenChat--> Chat { Composing -> Sending -> RetryWait -> Sending | Failed } }
 *       Chats --OpenSettings--> Settings --CloseSettings--> Chats [H*]
 * ```
 *
 * Adapter details that are the same for every chart are tested once, in
 * [actron.statechart.StateChartStoreTest], and not repeated here: the order of exit hooks, effects
 * and enter hooks in one step; restoring from a `StateSaver`; stale timer firings; failing hooks
 * and failing work; and why a timer self-loop is never covered by the conformance plugin.
 */
@OptIn(ExperimentalActronApi::class, ExperimentalCoroutinesApi::class)
class MessengerChartTest {

    /**
     * Services that answer in virtual time: sign-in takes 1 s and accepts the password "secret",
     * connecting takes 0.5 s, sending takes 1 s. Failures are scripted per test.
     */
    private class FakeServices : MessengerServices {
        var connectFailures = 0
        var sendFailures = 0
        var connectCalls = 0
        var sendCalls = 0
        val sent = mutableListOf<Pair<String, String>>()
        private val disconnects = Channel<Unit>(Channel.CONFLATED)

        fun dropConnection() {
            disconnects.trySend(Unit)
        }

        override suspend fun signIn(login: String, password: String): String {
            delay(1.seconds)
            if (password != "secret") throw IllegalArgumentException("wrong password")
            return login
        }

        override suspend fun connect() {
            connectCalls++
            delay(500.milliseconds)
            if (connectFailures > 0) {
                connectFailures--
                throw IllegalStateException("no network")
            }
        }

        override suspend fun awaitDisconnect() {
            disconnects.receive()
        }

        override suspend fun send(chatId: String, text: String) {
            sendCalls++
            delay(1.seconds)
            if (sendFailures > 0) {
                sendFailures--
                throw IllegalStateException("send failed")
            }
            sent += chatId to text
        }
    }

    /** Services that never answer, so only the test moves the chart. */
    private object SilentServices : MessengerServices {
        override suspend fun signIn(login: String, password: String): String = awaitCancellation()
        override suspend fun connect(): Unit = awaitCancellation()
        override suspend fun awaitDisconnect(): Unit = awaitCancellation()
        override suspend fun send(chatId: String, text: String): Unit = awaitCancellation()
    }

    private val MessengerStore.leaves: List<StateId> get() = currentState.activeLeaves(definition)
    private val MessengerStore.context: MessengerContext get() = currentState.context

    private fun TestScope.advance(duration: Duration) {
        advanceTimeBy(duration)
        runCurrent()
    }

    private suspend fun TestScope.send(store: MessengerStore, vararg actions: MessengerAction) {
        for (action in actions) store.dispatchAndAwait(action)
        runCurrent()
    }

    private fun TestScope.messenger(services: MessengerServices, policy: RetryPolicy = RetryPolicy()): MessengerStore =
        MessengerStore(services, MessengerContext(policy = policy), backgroundScope.coroutineContext)

    /** Signs in with the fake's 1 s delay: the Store is then in Session, Connecting and ChatList. */
    private suspend fun TestScope.signIn(store: MessengerStore): MessengerStore {
        send(store, MessengerAction.SubmitCredentials("roman", "secret"))
        advance(1.seconds)
        return store
    }

    // region scenarios

    @Test
    fun signInFailsWithAnEventThenSucceedsAndStartsBothRegions() = runTest {
        val services = FakeServices()
        val store = messenger(services)
        val recorder = store.createRecorder()
        send(store, MessengerAction.SubmitCredentials("roman", "wrong"))
        assertEquals(listOf(signingIn), store.leaves)
        advance(1.seconds)
        assertEquals(listOf(signedOut), store.leaves)
        assertEquals(listOf(MessengerEvent.SignInError("wrong password")), recorder.events)

        send(store, MessengerAction.SubmitCredentials("roman", "secret"))
        advance(1.seconds)
        assertEquals(listOf(connecting, chatList), store.leaves, "one leaf per region")
        assertEquals("roman", store.context.user)
        advance(500.milliseconds)
        assertEquals(listOf(online, chatList), store.leaves)
        store.close()
    }

    @Test
    fun reconnectBacksOffQuicklyThenSlowlyWhileTheChatKeepsWorking() = runTest {
        val services = FakeServices().apply { connectFailures = Int.MAX_VALUE }
        val store = signIn(messenger(services, RetryPolicy(quickReconnects = 2)))
        advance(500.milliseconds)
        assertEquals(backoff, store.leaves.first())
        assertEquals(1, store.context.connectAttempts)
        // The other region does not care: the user opens a chat while offline.
        send(store, MessengerAction.OpenChat("family"))
        assertEquals(listOf(backoff, composing), store.leaves)

        advance(2.seconds) // quick backoff: attempt 2
        assertEquals(listOf(connecting, composing), store.leaves)
        advance(500.milliseconds + 2.seconds + 500.milliseconds) // attempt 2 fails, attempt 3 fails
        assertEquals(3, services.connectCalls)
        assertEquals(3, store.context.connectAttempts)
        advance(29.seconds) // past the quick backoff: now it waits 30 s
        assertEquals(3, services.connectCalls)
        assertEquals(backoff, store.leaves.first())

        services.connectFailures = 0
        advance(1.seconds + 500.milliseconds)
        assertEquals(listOf(online, composing), store.leaves)
        assertEquals(0, store.context.connectAttempts)

        services.dropConnection() // the Online activity reports it
        runCurrent()
        assertEquals(listOf(backoff, composing), store.leaves)
        advance(2.seconds + 500.milliseconds)
        assertEquals(online, store.leaves.first())
        store.close()
    }

    @Test
    fun aFailedMessageIsResentThenMarkedFailedAndCanBeRetried() = runTest {
        val services = FakeServices().apply { sendFailures = 2 }
        val store = messenger(services, RetryPolicy(resends = 1))
        val recorder = store.createRecorder()
        signIn(store)
        send(store, MessengerAction.OpenChat("family"), MessengerAction.SendMessage("hi"))
        assertEquals(sending, store.leaves.last())
        advance(1.seconds)
        assertEquals(retryWait, store.leaves.last())
        advance(3.seconds) // one resend allowed
        assertEquals(sending, store.leaves.last())
        advance(1.seconds + 3.seconds) // fails again: no resend left
        assertEquals(failed, store.leaves.last())
        assertEquals(listOf(MessengerEvent.MessageNotSent("hi")), recorder.events)
        assertEquals(2, services.sendCalls)

        send(store, MessengerAction.RetryMessage)
        advance(1.seconds)
        assertEquals(composing, store.leaves.last())
        assertEquals(listOf("family" to "hi"), services.sent)
        assertEquals(listOf("hi"), store.context.delivered)
        assertEquals(null, store.context.outgoing)
        store.close()
    }

    @Test
    fun closingSettingsReturnsToTheSameChatAndResumesTheSend() = runTest {
        val services = FakeServices()
        val store = signIn(messenger(services))
        send(store, MessengerAction.OpenChat("family"), MessengerAction.SendMessage("hi"))
        advance(500.milliseconds)
        send(store, MessengerAction.OpenSettings)
        assertEquals(listOf(online, settings), store.leaves)
        advance(10.seconds)
        assertEquals(emptyList(), services.sent, "leaving Sending cancelled the call")

        send(store, MessengerAction.CloseSettings) // deep history: back in Chat > Sending
        assertEquals(listOf(online, sending), store.leaves)
        assertTrue(store.currentState.isActive(chat))
        advance(1.seconds)
        assertEquals(listOf("family" to "hi"), services.sent)
        assertEquals(2, services.sendCalls, "entering Sending again sends again")
        assertEquals(listOf(online, composing), store.leaves)
        store.close()
    }

    @Test
    fun signingOutLeavesEveryRegionAndStopsTheirWork() = runTest {
        val services = FakeServices().apply { connectFailures = Int.MAX_VALUE }
        val store = signIn(messenger(services))
        send(store, MessengerAction.OpenChat("family"), MessengerAction.SendMessage("hi"))
        advance(500.milliseconds) // connect failed; the send is still running
        send(store, MessengerAction.SignOut)
        assertEquals(listOf(signedOut), store.leaves)
        assertEquals(MessengerContext(), store.context)
        val calls = services.connectCalls
        advance(60.seconds)
        assertEquals(calls, services.connectCalls, "no backoff timer survives sign-out")
        assertEquals(emptyList(), services.sent)
        assertEquals(listOf(signedOut), store.leaves)
        store.close()
    }

    // endregion

    // region model

    /** One action per action matcher of the chart, for validation and for replaying paths. */
    private val sampleActions = listOf(
        MessengerAction.SubmitCredentials("roman", "secret"),
        MessengerAction.SignInSucceeded("roman"),
        MessengerAction.SignInFailed("wrong password"),
        MessengerAction.SignOut,
        MessengerAction.ConnectionOpened,
        MessengerAction.ConnectFailed,
        MessengerAction.ConnectionLost,
        MessengerAction.OpenChat("family"),
        MessengerAction.CloseChat,
        MessengerAction.SendMessage("hi"),
        MessengerAction.MessageDelivered,
        MessengerAction.SendFailed,
        MessengerAction.RetryMessage,
        MessengerAction.DiscardMessage,
        MessengerAction.OpenSettings,
        MessengerAction.CloseSettings,
    )

    @Test
    fun chartIsValid() {
        assertEquals(emptyList(), definition.validate(sampleActions))
    }

    @Test
    fun mermaidDiagramShowsTheWholeChart() {
        assertEquals(MERMAID, definition.toMermaid())
    }

    /**
     * Paths ignore guards, so each path gets a retry policy under which the guards it passes are
     * true: a path through a "slow" or "give up" timer needs a policy with no quick retries left.
     */
    private fun policyFor(path: StateChartPath) = RetryPolicy(
        quickReconnects = if (MessengerChart.slowReconnect in path.transitions) 0 else 3,
        resends = if (MessengerChart.giveUpSending in path.transitions) 0 else 2,
    )

    /**
     * Replays [path] on a fresh Store with [SilentServices]: dispatches a sample action for each
     * action step and lets virtual time pass for each timer step, and checks the active leaves
     * after every step and the conformance plugin at the end. Returns what the plugin covered.
     */
    private suspend fun TestScope.replay(index: Int, path: StateChartPath): Set<Transition> {
        val conformance = StateChartConformance.withActiveLeaves<ChartState<MessengerContext>, MessengerAction, MessengerEvent>(definition) {
            it.activeLeaves(definition).toSet()
        }
        val store = messenger(SilentServices, policyFor(path)).patch { plugin(conformance) }
        store.startAndAwait()
        runCurrent()
        assertEquals(path.startLeaves, store.leaves, "path $index start")
        path.transitions.forEachIndexed { step, transition ->
            val delay = transition.after
            if (delay != null) {
                advance(delay)
            } else {
                send(store, sampleActions.first { transition.on!!.matches(it) })
            }
            assertEquals(path.activeLeaves[step], store.leaves, "path $index step $step: $transition")
        }
        assertEquals(emptyList(), conformance.violations, "path $index")
        store.close()
        return conformance.coveredTransitions
    }

    /** Lets a browser test runner breathe between iterations of a long loop inside runTest. */
    private suspend fun yieldToEventLoop() = withContext(Dispatchers.Default) { yield() }

    @Test
    fun everyGeneratedPathReplaysOnTheStoreAndTogetherTheyCoverTheChart() = runTest {
        val paths = definition.transitionCoveragePaths()
        val covered = mutableSetOf<Transition>()
        paths.forEachIndexed { index, path ->
            covered += replay(index, path)
            yieldToEventLoop()
        }
        assertEquals(definition.transitions.toSet(), paths.flatMap { it.transitions }.toSet(), "paths take every transition")
        // The conformance plugin sees leaves, not guards or clocks: Backoff -> Connecting is explained
        // by the first timer that leads there, quickReconnect, even on the path where slowReconnect
        // fired. The replay itself proved slowReconnect: the leaves changed only after 30 s.
        assertEquals(listOf(MessengerChart.slowReconnect), definition.transitions.filter { it !in covered }, "the Store took every transition")
    }

    // endregion

    private companion object {
        /** Exactly the diagram in the KDoc of [MessengerChart]. */
        val MERMAID = """
            stateDiagram-v2
                [*] --> App
                state App {
                    [*] --> SignedOut
                    SignedOut
                    SigningIn
                    state Session {
                        state Connection {
                            [*] --> Connecting
                            Connecting
                            Online
                            Backoff
                            Connecting --> Online : ConnectionOpened / resetAttempts
                            Connecting --> Backoff : ConnectFailed / countAttempt
                            Online --> Backoff : ConnectionLost / countAttempt
                            Backoff --> Connecting : after 2s [quickReconnect]
                            Backoff --> Connecting : after 30s [slowReconnect]
                        }
                        --
                        state Ui {
                            [*] --> Chats
                            state Chats {
                                [*] --> ChatList
                                state "[H*]" as ChatsHistory
                                ChatList
                                state Chat {
                                    [*] --> Composing
                                    Composing
                                    Sending
                                    RetryWait
                                    Failed
                                    Composing --> Sending : SendMessage / queueMessage
                                    Sending --> Composing : MessageDelivered / markDelivered
                                    Sending --> RetryWait : SendFailed / countSendFailure
                                    RetryWait --> Sending : after 3s [canResend]
                                    RetryWait --> Failed : after 3s [resendsExhausted]
                                    Failed --> Sending : RetryMessage / resetSendFailures
                                    Failed --> Composing : DiscardMessage / dropMessage
                                }
                                ChatList --> Chat : OpenChat / rememberChat
                                Chat --> ChatList : CloseChat
                            }
                            Settings
                            Chats --> Settings : OpenSettings
                            Settings --> ChatsHistory : CloseSettings
                        }
                    }
                    SignedOut --> SigningIn : SubmitCredentials
                    SigningIn --> Session : SignInSucceeded / rememberUser
                    SigningIn --> SignedOut : SignInFailed
                    Session --> SignedOut : SignOut / forgetUser
                }
        """.trimIndent()
    }
}

package koma.message

import koma.core.Action
import koma.core.ExceptionHandler
import koma.core.Plugin
import koma.core.PluginExecutionPolicy
import koma.core.State
import koma.core.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MessageDeliveryTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private data class ChatState(val received: List<String> = emptyList()) : State

    private sealed interface ChatAction : Action {
        data class Send(val text: String) : ChatAction
        data class Received(val text: String) : ChatAction
    }

    private data class ChatMessage(val text: String) : Message

    @Test
    fun anotherPluginsStartupFailureDoesNotDuplicateMessageSubscriptions() = runTest(testDispatcher) {
        for (policy in PluginExecutionPolicy.entries) {
            var attempts = 0
            val seen = mutableListOf<String>()
            val receiver = Store<ChatState, ChatAction, Nothing>(ChatState(), Dispatchers.Unconfined) {
                exceptionHandler(ExceptionHandler.Ignore)
                pluginExecutionPolicy(policy)
                plugin(receiveMessages { if (it is ChatMessage) seen += it.text })
                plugin(Plugin(onStart = { if (++attempts == 1) error("transient startup failure") }))
            }
            val sender = senderStore()
            try {
                receiver.start()
                receiver.start()
                sender.dispatch(ChatAction.Send("once"))

                assertEquals(2, attempts)
                assertEquals(listOf("once"), seen, "policy=$policy")
            } finally {
                receiver.close()
                sender.close()
            }
        }
    }

    private fun senderStore(): Store<ChatState, ChatAction, Nothing> = Store(ChatState()) {
        coroutineContext(Dispatchers.Unconfined)
        state<ChatState> {
            action<ChatAction.Send> { message(ChatMessage(action.text)) }
        }
    }

    private fun receiverStore(
        exceptionHandler: ExceptionHandler = ExceptionHandler.Ignore,
        onMessage: (ChatMessage) -> Unit = {},
    ): Store<ChatState, ChatAction, Nothing> = Store(ChatState()) {
        coroutineContext(Dispatchers.Unconfined)
        exceptionHandler(exceptionHandler)
        plugin(
            receiveMessages { message ->
                if (message is ChatMessage) {
                    onMessage(message)
                    dispatch(ChatAction.Received(message.text))
                }
            },
        )
        state<ChatState> {
            action<ChatAction.Received> { nextState { state.copy(received = state.received + action.text) } }
        }
    }

    @Test
    fun message_sentFromOneStore_isDispatchedIntoAnotherStartedStore() = runTest(testDispatcher) {
        val sender = senderStore()
        val receiver = receiverStore()
        receiver.start()

        sender.dispatch(ChatAction.Send("hello"))
        sender.dispatch(ChatAction.Send("world"))

        assertEquals(listOf("hello", "world"), receiver.currentState.received)
        sender.close()
        receiver.close()
    }

    @Test
    fun message_isNotDeliveredToAStoreThatHasNotStarted() = runTest(testDispatcher) {
        val sender = senderStore()
        val receiver = receiverStore()

        sender.dispatch(ChatAction.Send("early"))
        receiver.start()

        assertEquals(emptyList(), receiver.currentState.received)
        sender.close()
        receiver.close()
    }

    @Test
    fun message_isNotDeliveredAfterTheReceiverIsClosed() = runTest(testDispatcher) {
        val sender = senderStore()
        val seen = mutableListOf<String>()
        val receiver = receiverStore(onMessage = { seen += it.text })
        receiver.start()

        sender.dispatch(ChatAction.Send("before"))
        receiver.close()
        sender.dispatch(ChatAction.Send("after"))

        assertEquals(listOf("before"), seen)
        sender.close()
    }

    @Test
    fun message_receiverKeepsItsSubscriptionAfterItsBlockThrows() = runTest(testDispatcher) {
        val sender = senderStore()
        val seen = mutableListOf<String>()
        val handled = mutableListOf<Throwable>()
        val receiver = receiverStore(
            exceptionHandler = ExceptionHandler { handled += it },
            onMessage = {
                seen += it.text
                if (it.text == "bad") throw IllegalStateException("bad message")
            },
        )
        receiver.start()

        sender.dispatch(ChatAction.Send("bad"))
        sender.dispatch(ChatAction.Send("good"))

        assertEquals(listOf("bad", "good"), seen)
        assertEquals(listOf("bad message"), handled.map { it.message })
        sender.close()
        receiver.close()
    }

    @Test
    fun message_sentFromTheReceiversOwnStartup_isReceivedOnAMultiThreadedDispatcher() = runTest {
        withContext(Dispatchers.Default) {
            repeat(50) { attempt ->
                val received = CompletableDeferred<Message>()
                val store: Store<ChatState, ChatAction, Nothing> = Store(ChatState()) {
                    coroutineContext(Dispatchers.Default)
                    plugin(receiveMessages { if (it == ChatMessage("startup $attempt")) received.complete(it) })
                    state<ChatState> {
                        enter { message(ChatMessage("startup $attempt")) }
                    }
                }
                store.start()

                assertEquals(ChatMessage("startup $attempt"), kotlinx.coroutines.withTimeoutOrNull(5_000) { received.await() }, "attempt $attempt")
                store.close()
            }
        }
    }

    /**
     * A Store whose initial `enter {}` fails still counts as started, with exactly one
     * subscription: messages are handled once, not once per dispatch that arrived meanwhile.
     */
    @Test
    fun failedStartup_leavesExactlyOneSubscription() = runTest(testDispatcher) {
        var enterCalls = 0
        val seen = mutableListOf<String>()
        val receiver: Store<ChatState, ChatAction, Nothing> = Store(ChatState()) {
            coroutineContext(Dispatchers.Unconfined)
            exceptionHandler(ExceptionHandler.Ignore)
            plugin(
                receiveMessages { message ->
                    if (message is ChatMessage) {
                        seen += message.text
                        dispatch(ChatAction.Received(message.text))
                    }
                },
            )
            state<ChatState> {
                enter {
                    enterCalls++
                    throw IllegalStateException("boot failed")
                }
                action<ChatAction.Received> { nextState { state.copy(received = state.received + action.text) } }
                action<ChatAction.Send> { }
            }
        }
        receiver.dispatch(ChatAction.Send("ignored")) // startup fails once
        receiver.dispatch(ChatAction.Send("ignored"))
        val sender = senderStore()

        sender.dispatch(ChatAction.Send("hello"))

        assertEquals(1, enterCalls)
        assertEquals(listOf("hello"), seen)
        assertEquals(listOf("hello"), receiver.currentState.received)
        sender.close()
        receiver.close()
    }

    /**
     * An expired `withTimeout {}` inside a `receiveMessages {}` block is a failure of that block,
     * reported to the exception handler; the subscription keeps receiving.
     */
    @Test
    fun expiredWithTimeoutInAReceiveBlock_isReportedAndTheSubscriptionContinues() = runTest {
        val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        val handled = mutableListOf<Throwable>()
        val seen = mutableListOf<String>()
        val receiver: Store<ChatState, ChatAction, Nothing> = Store(ChatState()) {
            coroutineContext(dispatcher)
            exceptionHandler(ExceptionHandler { handled += it })
            plugin(
                receiveMessages { message ->
                    if (message is ChatMessage) {
                        seen += message.text
                        if (seen.size == 1) kotlinx.coroutines.withTimeout(10) { kotlinx.coroutines.delay(1_000) }
                    }
                },
            )
            state<ChatState> { action<ChatAction.Received> { } }
        }
        val sender: Store<ChatState, ChatAction, Nothing> = Store(ChatState()) {
            coroutineContext(dispatcher)
            state<ChatState> { action<ChatAction.Send> { message(ChatMessage(action.text)) } }
        }
        receiver.start()
        advanceUntilIdle()

        sender.dispatch(ChatAction.Send("first"))
        advanceUntilIdle()
        sender.dispatch(ChatAction.Send("second"))
        advanceUntilIdle()

        assertEquals(listOf("first", "second"), seen)
        assertEquals(listOf("TimeoutCancellationException"), handled.map { it.cause!!::class.simpleName })
        sender.close()
        receiver.close()
    }
}

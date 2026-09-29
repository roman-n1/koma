package koma.message

import koma.core.Action
import koma.core.ExceptionHandler
import koma.core.State
import koma.core.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
     * A startup whose initial `enter {}` fails is retried on the next dispatch. The plugin must
     * not subscribe a second time then, or every message would be handled twice.
     */
    @Test
    fun retriedStartup_doesNotSubscribeTwice() = runTest(testDispatcher) {
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
                enter { if (++enterCalls == 1) throw IllegalStateException("transient") }
                action<ChatAction.Received> { nextState { state.copy(received = state.received + action.text) } }
                action<ChatAction.Send> { }
            }
        }
        receiver.dispatch(ChatAction.Send("ignored")) // startup fails
        receiver.dispatch(ChatAction.Send("ignored")) // startup is retried and succeeds
        val sender = senderStore()

        sender.dispatch(ChatAction.Send("hello"))

        assertEquals(2, enterCalls)
        assertEquals(listOf("hello"), seen)
        assertEquals(listOf("hello"), receiver.currentState.received)
        sender.close()
        receiver.close()
    }
}

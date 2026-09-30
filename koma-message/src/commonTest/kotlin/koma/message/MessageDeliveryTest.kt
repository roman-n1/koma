package koma.message

import koma.core.Action
import koma.core.ExceptionHandler
import koma.core.State
import koma.core.Store
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What `receiveMessages {}` promises about delivery: a message sent from the receiver's own
 * startup is received, on a multi-threaded dispatcher too, and a block that throws does not end
 * the subscription.
 */
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
        // The stores stay open until the end: a send waits for every subscriber of the hub, and
        // a subscriber leaving (a closed store) would make the wait about the hub, not the startup.
        val stores = mutableListOf<Store<ChatState, ChatAction, Nothing>>()
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
                stores += store
                store.start()

                assertEquals(ChatMessage("startup $attempt"), withTimeoutOrNull(5_000) { received.await() }, "attempt $attempt")
            }
        }
        for (store in stores) store.close()
    }
}

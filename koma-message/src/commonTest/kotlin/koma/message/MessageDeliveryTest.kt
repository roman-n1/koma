package koma.message

import koma.core.Action
import koma.core.ExceptionHandler
import koma.core.State
import koma.core.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Ignore
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
        onMessage: (ChatMessage) -> Unit = {},
    ): Store<ChatState, ChatAction, Nothing> = Store(ChatState()) {
        coroutineContext(Dispatchers.Unconfined)
        exceptionHandler(ExceptionHandler.Ignore)
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

    /**
     * Known issue: an exception thrown by the `receiveMessages {}` block ends that Store's
     * subscription for good, so later messages are silently lost.
     */
    @Ignore
    @Test
    fun message_receiverKeepsItsSubscriptionAfterItsBlockThrows() = runTest(testDispatcher) {
        val sender = senderStore()
        val seen = mutableListOf<String>()
        val receiver = receiverStore(
            onMessage = {
                seen += it.text
                if (it.text == "bad") throw IllegalStateException("bad message")
            },
        )
        receiver.start()

        sender.dispatch(ChatAction.Send("bad"))
        sender.dispatch(ChatAction.Send("good"))

        assertEquals(listOf("bad", "good"), seen)
        sender.close()
        receiver.close()
    }
}

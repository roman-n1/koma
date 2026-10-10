package actron.message

import actron.core.StoreScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Marker interface for process-wide messages sent through Actron's shared message bus.
 */
interface Message

internal object MessageHub {
    // The buffer lets a sender continue while receivers are still handling earlier messages; a
    // sender suspends only when a receiver falls this far behind.
    private val _messages = MutableSharedFlow<Message>(extraBufferCapacity = 64)
    val messages: SharedFlow<Message> get() = _messages

    suspend fun send(message: Message) {
        _messages.emit(message)
    }
}

/**
 * Sends a [Message] to Actron's process-wide shared message bus.
 *
 * Any DSL scope that implements [StoreScope] can call this, including enter, action, exit, recover,
 * launch, and transaction scopes.
 * Messages are not replayed, so receivers that are not actively collecting when a message is sent
 * will not receive that past message.
 * Sending returns once the message is buffered for every active receiver; it suspends only when a
 * receiver has fallen 64 messages behind, so keep `receiveMessages {}` blocks short and move slow
 * work into a dispatched action.
 *
 * @param message The message to send
 */
suspend fun StoreScope.message(message: Message) {
    MessageHub.send(message)
}

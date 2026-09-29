package koma.message

import koma.core.Action
import koma.core.Event
import koma.core.Plugin
import koma.core.PluginLaunchScope
import koma.core.PluginScope
import koma.core.State
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.onSubscription

/**
 * Creates a plugin that subscribes to Koma's shared message bus when the Store starts.
 *
 * The subscription is registered before Store startup continues, so messages sent from the
 * Store's own initial `enter {}` or right after startup are received.
 * It stays active until the Store closes and invokes [block] for each received [Message], one
 * message at a time.
 * An exception thrown by [block] is reported to the Store's exception handler (an expired
 * `withTimeout {}` wrapped in an [IllegalStateException] with it as the cause); the subscription
 * keeps receiving later messages.
 * The underlying bus is process-wide and shared across all Stores using this plugin.
 * Messages are delivered only to active subscribers and are not replayed to Stores that start
 * later.
 *
 * @param block Function to process received messages with [PluginLaunchScope] as receiver
 * @return Plugin that processes shared messages
 */
fun <S : State, A : Action, E : Event> receiveMessages(block: suspend PluginLaunchScope<S, A>.(message: Message) -> Unit): Plugin<S, A, E> {
    return object : Plugin<S, A, E> {
        override suspend fun onStart(scope: PluginScope<S, A>, state: S) {
            val subscribed = CompletableDeferred<Unit>()
            scope.launch {
                MessageHub.messages
                    .onSubscription { subscribed.complete(Unit) }
                    .collect { message ->
                        try {
                            block(message)
                        } catch (e: Exception) {
                            // The subscription's own cancellation (the Store closing) ends it; any
                            // other exception, including an expired withTimeout, is reported through
                            // the Store's exception handler and the subscription continues.
                            if (e is CancellationException && !currentCoroutineContext().isActive) throw e
                            // A CancellationException thrown in a launch would only cancel it, so an
                            // expired withTimeout is reported wrapped, with the original as its cause.
                            scope.launch { throw if (e is CancellationException) IllegalStateException("[Koma] receiveMessages {} block was cancelled: ${e.message}", e) else e }
                        }
                    }
            }
            subscribed.await()
        }
    }
}

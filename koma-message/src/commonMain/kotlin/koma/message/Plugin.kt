package koma.message

import koma.core.Action
import koma.core.Event
import koma.core.Plugin
import koma.core.PluginLaunchScope
import koma.core.PluginScope
import koma.core.State
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.onSubscription

/**
 * Creates a plugin that subscribes to Koma's shared message bus when the Store starts.
 *
 * The subscription is registered before Store startup continues, so messages sent from the
 * Store's own initial `enter {}` or right after startup are received.
 * It stays active until the Store closes and invokes [block] for each received [Message], one
 * message at a time.
 * An exception thrown by [block] is reported to the Store's exception handler; the subscription
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
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Report through the Store's exception handler without ending the subscription.
                            scope.launch { throw e }
                        }
                    }
            }
            subscribed.await()
        }
    }
}

package actron.statechart.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import actron.core.Event
import actron.core.ExperimentalActronApi
import actron.statechart.machine.Delivery
import actron.statechart.machine.EffectMailbox

/**
 * Subscribes this composition to a [MachineStore][actron.statechart.machine.MachineStore]'s
 * retained [mailbox]: [block] runs for each [Delivery], in the mailbox's order, one at a time,
 * and acknowledges it once the UI did what the effect asked. The subscription lives as long as
 * the composition: what was queued before it entered is delivered when it enters; what it was
 * handling when it left, unacknowledged, waits for the next subscriber, which gets it again with
 * `attempt + 1`, up to the policy's budget. That is the mailbox's contract (handoff §10); this
 * composable only ties a subscription to the composition's lifetime.
 *
 * One `MailboxEffect` per mailbox at a time, at the screen that owns the store: the mailbox
 * hands each effect to one subscriber, whichever asks first, so the block handles every kind of
 * effect the policy keeps, with a `when`, and acknowledges each. There is no filter by type for
 * that reason. The latest [block] is used; a block that throws fails the composition, as a
 * `LaunchedEffect` does, and the effect waits for the next subscriber.
 *
 * ```kotlin
 * MailboxEffect(store.mailbox) { delivery ->
 *     when (val effect = delivery.event) {
 *         is Ev.Navigate -> navigator.go(effect.route)
 *         is Ev.ShowDialog -> dialogs.show(effect)          // suspends until the dialog is shown
 *     }
 *     delivery.acknowledge()
 * }
 * ```
 *
 * @param mailbox The mailbox to subscribe to; a new instance restarts the subscription
 * @param block Handles one delivery and acknowledges it
 */
@ExperimentalActronApi
@Composable
fun <E : Event> MailboxEffect(mailbox: EffectMailbox<E>, block: suspend (Delivery<E>) -> Unit) {
    val currentBlock = rememberUpdatedState(block)
    LaunchedEffect(mailbox) {
        mailbox.subscribe().collect { delivery -> currentBlock.value(delivery) }
    }
}

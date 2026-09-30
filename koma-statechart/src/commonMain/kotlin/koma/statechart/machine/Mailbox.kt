package koma.statechart.machine

import koma.core.Event
import koma.core.ExperimentalKomaApi
import koma.core.InputId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * How a Store's mailbox treats one effect (handoff §10): what happens when no subscriber is
 * there, when the one handling it goes away, and when a newer effect of the same kind arrives.
 */
@ExperimentalKomaApi
sealed interface EffectPolicy {
    /**
     * Delivered to whoever collects `Store.event` at that moment, or lost: a toast. Never kept,
     * never acknowledged, not in a checkpoint.
     */
    data object Transient : EffectPolicy

    /**
     * Kept in the mailbox until a subscriber acknowledges it; when the subscriber handling it goes
     * away without acknowledging, the next subscriber gets it again. A navigation, a one-time
     * dialog that must not be lost across a recreation of the UI.
     */
    data object Retained : EffectPolicy

    /**
     * Kept like [Retained], but only the newest effect with [key] waits: an older one still
     * waiting is discarded as superseded when a newer arrives. A badge, a scroll target.
     */
    data class Latest(val key: String) : EffectPolicy

    /** The policy's name, the journal's vocabulary. */
    val name: String
        get() = when (this) {
            Transient -> "Transient"
            Retained -> "Retained"
            is Latest -> "Latest($key)"
        }
}

/**
 * Why the mailbox gave up an effect without an acknowledgement.
 */
@ExperimentalKomaApi
enum class EffectDiscardReason {
    /** A newer effect with the same [EffectPolicy.Latest] key arrived while this one waited. */
    Superseded,

    /** The mailbox held its maximum; the oldest waiting effect made room. */
    Overflow,

    /** The Store closed with the effect still waiting or being handled. */
    StoreClosed,
}

/**
 * An effect the mailbox holds: waiting for a subscriber, or being handled by one who has not
 * acknowledged it yet. What a checkpoint keeps of the mailbox.
 *
 * @property attempts How many times a subscriber took it
 * @property handling Whether a subscriber has it now
 */
@ExperimentalKomaApi
data class PendingEffect<out E : Event>(val id: EffectId, val event: E, val policy: EffectPolicy, val attempts: Int, val handling: Boolean)

/**
 * An effect handed to a subscriber of the mailbox. [acknowledge] it once the UI did what it
 * asked; until then it is pending, and a subscriber that goes away without acknowledging hands
 * it to the next one. An acknowledgement does not prove the external effect happened once
 * across a process death; it says the UI took responsibility for it.
 */
@ExperimentalKomaApi
class Delivery<out E : Event> internal constructor(
    val id: EffectId,
    val event: E,
    val attempt: Int,
    private val acknowledgeIt: () -> Boolean,
) {
    /** Removes the effect from the mailbox; `false` when it was not pending any more. */
    fun acknowledge(): Boolean = acknowledgeIt()

    override fun toString(): String = "Delivery($id attempt=$attempt $event)"
}

/**
 * Sees what the mailbox does with effects, for a journal. The callbacks are short and run on
 * the executor's or a subscriber's coroutine; a throwing listener is reported and the mailbox
 * continues.
 */
@ExperimentalKomaApi
interface EffectListener<in E : Event> {
    fun onQueued(input: InputId?, effect: PendingEffect<E>) {}

    fun onHandlingStarted(effect: PendingEffect<E>) {}

    fun onAcknowledged(id: EffectId) {}

    fun onDiscarded(id: EffectId, reason: EffectDiscardReason) {}
}

/**
 * The retained mailbox of a [MachineStore]'s effects (handoff §10): the effects whose policy is
 * not [EffectPolicy.Transient] wait here, with their [EffectId], until a subscriber acknowledges
 * them. The mailbox lives with the Store, so it survives the UI: a subscriber that collects
 * [subscribe] after a recreation gets what the previous one never acknowledged. The effects
 * still pending are part of the Store's checkpoint.
 */
@ExperimentalKomaApi
interface EffectMailbox<E : Event> {
    /** The effects waiting or being handled, oldest first. */
    val pending: List<PendingEffect<E>>

    /**
     * The deliveries of the mailbox to this subscriber: each pending effect once, oldest first,
     * then every new one as it is queued. Several subscribers share the pending effects, each
     * effect going to one of them. When the collector is cancelled, the effects it was handling
     * and had not acknowledged wait for the next subscriber.
     */
    fun subscribe(): Flow<Delivery<E>>

    /** Removes the effect [id] from the mailbox; `false` when it was not pending. */
    fun acknowledge(id: EffectId): Boolean
}

/**
 * Configuration of a [MachineStore]'s mailbox.
 *
 * @property policy The [EffectPolicy] of each effect; the default keeps nothing, as `Store.event` did
 * @property maxRetained How many effects may wait or be handled; beyond it the oldest waiting one
 * is discarded as [EffectDiscardReason.Overflow], or the new one when every one is being handled
 * @property listeners See what the mailbox does; `session.effectsOf(store)` journals it
 */
@ExperimentalKomaApi
class MailboxConfig<E : Event>(
    val policy: (E) -> EffectPolicy = { EffectPolicy.Transient },
    val maxRetained: Int = 64,
    val listeners: List<EffectListener<E>> = emptyList(),
) {
    init {
        require(maxRetained >= 1) { "[Koma] maxRetained must be at least 1" }
    }
}

/**
 * The mailbox: a queue under a short lock, a doorbell for subscribers, and the transient effects
 * pumped to the Store's event flow by one coroutine.
 */
@OptIn(ExperimentalKomaApi::class)
internal class MailboxImpl<E : Event>(
    private val config: MailboxConfig<E>,
    scope: CoroutineScope,
    private val report: (Throwable) -> Unit,
) : EffectMailbox<E> {
    private val lock = Mutex()
    private val entries = ArrayDeque<Entry<E>>()
    private val doorbell = MutableStateFlow(0L)
    private var closed = false

    private val transientChannel = Channel<E>(Channel.UNLIMITED)
    private val transientFlow = MutableSharedFlow<E>()

    init {
        scope.launch { for (event in transientChannel) transientFlow.emit(event) }
    }

    /** The transient effects, in decision order: what `Store.event` is. */
    val transient: Flow<E> get() = transientFlow

    private class Entry<E : Event>(val id: EffectId, val event: E, val policy: EffectPolicy) {
        var attempts = 0
        var handler: Any? = null

        fun pending(): PendingEffect<E> = PendingEffect(id, event, policy, attempts, handler != null)
    }

    override val pending: List<PendingEffect<E>>
        get() = locked { entries.map { it.pending() } }

    /** Queues the effects of a decision made while processing [input]. */
    fun enqueue(input: InputId?, effects: List<EffectEnvelope<E>>) {
        for (effect in effects) {
            val policy = try {
                config.policy(effect.event)
            } catch (e: Exception) {
                report(e)
                EffectPolicy.Transient
            }
            if (policy == EffectPolicy.Transient) {
                transientChannel.trySend(effect.event)
                continue
            }
            val discarded = mutableListOf<Pair<EffectId, EffectDiscardReason>>()
            val queued = locked {
                if (closed) {
                    discarded += effect.id to EffectDiscardReason.StoreClosed
                    return@locked null
                }
                if (policy is EffectPolicy.Latest) {
                    val superseded = entries.filter { it.handler == null && (it.policy as? EffectPolicy.Latest)?.key == policy.key }
                    entries.removeAll(superseded)
                    for (old in superseded) discarded += old.id to EffectDiscardReason.Superseded
                }
                while (entries.size >= config.maxRetained) {
                    val oldest = entries.firstOrNull { it.handler == null } ?: break
                    entries.remove(oldest)
                    discarded += oldest.id to EffectDiscardReason.Overflow
                }
                if (entries.size >= config.maxRetained) {
                    // Every held effect is being handled: the new one has no room.
                    discarded += effect.id to EffectDiscardReason.Overflow
                    return@locked null
                }
                Entry(effect.id, effect.event, policy).also { entries += it }.pending()
            }
            for ((id, reason) in discarded) notify { it.onDiscarded(id, reason) }
            if (queued != null) {
                notify { it.onQueued(input, queued) }
                ring()
            }
        }
    }

    override fun subscribe(): Flow<Delivery<E>> = flow {
        val subscriber = Any()
        try {
            while (true) {
                // The doorbell is read before the queue: a ring between the two is not missed.
                val seen = doorbell.value
                val taken = locked {
                    if (closed) return@flow
                    entries.firstOrNull { it.handler == null }?.also {
                        it.handler = subscriber
                        it.attempts++
                    }?.pending()
                }
                if (taken == null) {
                    doorbell.first { it != seen }
                    continue
                }
                notify { it.onHandlingStarted(taken) }
                emit(Delivery(taken.id, taken.event, taken.attempts) { acknowledge(taken.id) })
            }
        } finally {
            // The subscriber is gone: what it was handling waits for the next one.
            val released = locked { entries.filter { it.handler === subscriber }.onEach { it.handler = null }.isNotEmpty() }
            if (released) ring()
        }
    }

    override fun acknowledge(id: EffectId): Boolean {
        val removed = locked {
            val index = entries.indexOfFirst { it.id == id }
            if (index >= 0) entries.removeAt(index)
            index >= 0
        }
        if (removed) notify { it.onAcknowledged(id) }
        return removed
    }

    /** What a checkpoint keeps: the effects pending now. */
    fun snapshot(): List<PendingEffect<E>> = pending

    /** Discards every pending effect as [EffectDiscardReason.StoreClosed] and ends the subscribers. */
    fun close() {
        val remaining = locked {
            closed = true
            val all = entries.toList()
            entries.clear()
            all
        }
        for (entry in remaining) notify { it.onDiscarded(entry.id, EffectDiscardReason.StoreClosed) }
        transientChannel.close()
        ring()
    }

    private fun ring() = doorbell.update { it + 1 }

    private inline fun notify(call: (EffectListener<E>) -> Unit) {
        for (listener in config.listeners) {
            try {
                call(listener)
            } catch (e: Exception) {
                report(e)
            }
        }
    }

    private inline fun <T> locked(block: () -> T): T {
        while (!lock.tryLock()) {
            // Spin: the holder touches the queue.
        }
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}

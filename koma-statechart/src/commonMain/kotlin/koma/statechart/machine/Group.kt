package koma.statechart.machine

import koma.core.Action
import koma.core.Event
import koma.core.ExperimentalKomaApi
import koma.core.InputId
import koma.observability.FailureDescriptor
import koma.observability.GroupSeq
import koma.observability.JournalEntry
import koma.observability.RecordingSession
import koma.observability.StoreInstanceId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A bridge message on its way: sent to [to] and not yet decided by it.
 */
@ExperimentalKomaApi
data class BridgeMessage(val id: MessageId, val to: StoreInstanceId)

/**
 * A consistent cut of a [MachineGroup] (handoff §8.1): every member's [ExecutorCheckpoint],
 * taken while no member processed anything, the bridge messages in flight at that moment,
 * how many inputs each member's controlled queue held, and the journal boundary.
 *
 * @property members The checkpoint of each member, by id
 * @property inFlight The messages sent before the cut and not yet decided by their receiver
 * @property held Inputs accepted into each member's controlled queue during the cut; they were
 * let in after it, in order, and are the steps after the cut
 * @property boundary The last record the session had published at the cut, when a session is
 * attached; the records after it are after the cut
 * @property sources The state of every attached [ExternalSource] at the cut, as the members had
 * decided it
 */
@ExperimentalKomaApi
data class GroupCheckpoint(
    val members: Map<StoreInstanceId, ExecutorCheckpoint<*, *>>,
    val inFlight: List<BridgeMessage>,
    val held: Map<StoreInstanceId, Int>,
    val boundary: GroupSeq?,
    val sources: Map<SourceId, SourceSnapshot> = emptyMap(),
)

/**
 * Sees every cut a [MachineGroup] makes, from inside it: the sources are paused and the members
 * frozen and idle, so what a member's observer has seen so far is exactly what the cut holds,
 * and nothing enters until the listener returns. A recorder uses this to mark the cut's place
 * in what it records. A listener that throws fails the cut for its caller; the members are
 * thawed and the sources resumed all the same.
 */
@ExperimentalKomaApi
fun interface CutListener {
    fun onCut(checkpoint: GroupCheckpoint)
}

/**
 * A group of [MachineStore]s that talk to each other through a bridge and can be cut
 * consistently (handoff §8.1, §10).
 *
 * **Bridge.** A [route] turns an effect of one member into an action of another: when the
 * sender commits a decision, each of its effects that a route maps is delivered to the
 * receiver as a [MachineInput.BridgeReceived] carrying a [MessageId] of the sender and the
 * effect, so a replay meets the same ids. A message to a member that is not attached goes
 * nowhere and is journaled as undelivered: the group's record is then partial. Messages are
 * [inFlight] from delivery until the receiver decided them.
 *
 * **Cut.** [checkpoint] pauses every attached [ExternalSource], freezes every member's
 * controlled queue (new inputs wait, whatever their source), waits until each member has
 * finished what it had already accepted, takes every executor's checkpoint, the messages in
 * flight and every source's snapshot, then thaws the queues in order and resumes the sources.
 * No Store lock is held while waiting, and no member waits on another's user code; a member
 * that does not settle or a source that does not pause within the timeout aborts the cut and
 * the group resumes. A result of a command that arrives during the cut waits in the queue and
 * is applied once, after it. Never call [checkpoint] from a handler or a plugin of a member:
 * it would wait for its own processing to end. A successful cut is journaled as
 * [JournalEntry.CheckpointCreated]. A [CutListener] registered with [onCut] sees the cut before
 * the thaw.
 *
 * Members are registered with [member] (the observer to give the store) and [Member.attach]
 * (the store itself, once built); sources with [source].
 *
 * @param session The journal that gets [JournalEntry.BridgeSent], [JournalEntry.BridgeReceived]
 * and [JournalEntry.CheckpointCreated]
 */
@ExperimentalKomaApi
class MachineGroup(private val session: RecordingSession? = null) {
    private val lock = Mutex()
    private val coordinating = Mutex()
    private val members = linkedMapOf<StoreInstanceId, Member<*, *, *, *>>()
    private val routeList = mutableListOf<Route>()
    private val inFlightMessages = linkedMapOf<MessageId, BridgeMessage>()
    private val sourceList = mutableListOf<ExternalSource>()
    private val cutListeners = mutableListOf<CutListener>()

    /** A route of the bridge: effects of [from] that [map] turns into actions of [to]. */
    class Route internal constructor(val from: StoreInstanceId, val to: StoreInstanceId, internal val map: (Event) -> Action?) {
        /** The action [event] becomes for [to], or `null` when the route does not carry it. */
        fun mapEvent(event: Event): Action? = map(event)

        override fun toString(): String = "$from -> $to"
    }

    /** The routes, in registration order. */
    val routes: List<Route> get() = locked { routeList.toList() }

    /** The members, in registration order. */
    val memberIds: List<StoreInstanceId> get() = locked { members.keys.toList() }

    /** The messages sent and not yet decided by their receiver. */
    val inFlight: List<BridgeMessage> get() = locked { inFlightMessages.values.toList() }

    /** The attached sources, in registration order. */
    val sourceIds: List<SourceId> get() = locked { sourceList.map { it.id } }

    /**
     * Attaches [source]: it is paused and snapshotted in every cut from now on.
     *
     * @throws IllegalArgumentException if a source with the same id is attached
     */
    fun source(source: ExternalSource) {
        locked {
            require(sourceList.none { it.id == source.id }) { "[Koma] ${source.id} is already attached to this group" }
            sourceList += source
        }
    }

    /** Calls [listener] inside every cut from now on; see [CutListener]. */
    fun onCut(listener: CutListener) {
        locked { cutListeners += listener }
    }

    /** Stops calling [listener]; nothing happens when it is not registered. */
    fun removeCutListener(listener: CutListener) {
        locked { cutListeners -= listener }
    }

    /**
     * Routes the effects of [from] to [to]: each effect [map] returns an action for is delivered;
     * `null` means the effect is not for [to].
     */
    fun <E : Event, A : Action> route(from: StoreInstanceId, to: StoreInstanceId, map: (E) -> A?): Route {
        require(from != to) { "[Koma] A route goes to another member; $from -> $from" }
        @Suppress("UNCHECKED_CAST")
        val route = Route(from, to, map as (Event) -> Action?)
        locked { routeList += route }
        return route
    }

    /**
     * Registers [id] as a member and returns the observer to give its store; [Member.attach] the
     * store once it is built.
     *
     * @throws IllegalArgumentException if [id] is already a member
     */
    fun <C, A : Action, CMD, E : Event> member(id: StoreInstanceId): Member<C, A, CMD, E> {
        val member = Member<C, A, CMD, E>(id)
        locked { require(members.put(id, member) == null) { "[Koma] $id is already a member of this group" } }
        return member
    }

    /**
     * A consistent cut of the group, or `null` when a member did not settle within [timeout] or
     * has closed; see the class documentation.
     */
    suspend fun checkpoint(timeout: Duration = 2.seconds): GroupCheckpoint? = coordinating.withLock {
        val attached = locked { members.values.mapNotNull { member -> member.store?.let { member.id to it } } }
        val sources = locked { sourceList.toList() }
        val started = TimeSource.Monotonic.markNow()
        val paused = mutableListOf<ExternalSource>()
        var frozen = false
        try {
            // Sources first: once none feeds, what the members hold is all there is.
            for (source in sources) {
                val remaining = timeout - started.elapsedNow()
                if (remaining <= Duration.ZERO || withTimeoutOrNull(remaining) { source.pause() } == null) return null
                paused += source
            }
            for ((_, store) in attached) store.freeze()
            frozen = true
            for ((_, store) in attached) {
                val remaining = timeout - started.elapsedNow()
                if (remaining <= Duration.ZERO || !store.awaitIdle(remaining).isIdle) return null
            }
            val cuts = attached.associate { (id, store) ->
                id to try {
                    store.checkpoint()
                } catch (e: IllegalStateException) {
                    return null
                }
            }
            val held = attached.associate { (id, store) -> id to store.heldInputs }
            val messages = locked { inFlightMessages.values.toList() }
            val snapshots = sources.associate { it.id to it.snapshot() }
            val boundary = session?.stats?.published?.takeIf { it > 0 }?.let(::GroupSeq)
            session?.publish(JournalEntry.CheckpointCreated(attached.map { it.first }, sources.map { it.id.value }, messages.size))
            val checkpoint = GroupCheckpoint(cuts, messages, held, boundary, snapshots)
            // Still frozen: a listener sees the cut where the members' observers stand.
            for (listener in locked { cutListeners.toList() }) listener.onCut(checkpoint)
            checkpoint
        } finally {
            if (frozen) for ((_, store) in attached) store.thaw()
            for (source in paused) source.resume()
        }
    }

    /**
     * One member: the [DecisionObserver] its store runs with. It sends the store's effects along
     * the routes and books the bridge messages the store decided.
     */
    inner class Member<C, A : Action, CMD, E : Event> internal constructor(val id: StoreInstanceId) : DecisionObserver<C, A, CMD, E> {
        internal var store: MachineStoreImpl<C, A, CMD, E>? = null
            private set

        /**
         * Attaches the store built with this member as an observer, so messages can reach it.
         *
         * @throws IllegalArgumentException if [store] is not a store of this library
         */
        fun attach(store: MachineStore<C, A, CMD, E>) {
            require(store is MachineStoreImpl<C, A, CMD, E>) { "[Koma] Only a MachineStore built by MachineStore(...) can join a group" }
            locked { this.store = store }
        }

        override fun onCommitted(input: InputId?, machineInput: MachineInput<A>, decision: Decision<C, CMD, E>) {
            decided(input, machineInput)
            send(input, decision)
        }

        override fun onIgnored(input: InputId?, machineInput: MachineInput<A>, reason: IgnoreReason) = decided(input, machineInput)

        override fun onFailed(input: InputId?, machineInput: MachineInput<A>, failure: FailureDescriptor) = decided(input, machineInput)

        private fun decided(input: InputId?, machineInput: MachineInput<A>) {
            if (machineInput !is MachineInput.BridgeReceived) return
            locked { inFlightMessages.remove(machineInput.message) }
            session?.publish(id, JournalEntry.BridgeReceived(input, machineInput.message.toRef()))
        }

        private fun send(input: InputId?, decision: Decision<C, CMD, E>) {
            if (decision.effects.isEmpty()) return
            val routes = locked { routeList.filter { it.from == id } }
            if (routes.isEmpty()) return
            for (effect in decision.effects) {
                for (route in routes) {
                    val action = route.map(effect.event) ?: continue
                    val message = MessageId(id, effect.id)
                    val target = locked { members[route.to]?.store }
                    if (target != null) {
                        // Booked before delivery, so the receiver's decision finds it in flight.
                        locked { inFlightMessages[message] = BridgeMessage(message, route.to) }
                        target.deliverUnchecked(message, action)
                    }
                    session?.publish(id, JournalEntry.BridgeSent(input, message.toRef(), route.to, delivered = target != null))
                }
            }
        }
    }

    private inline fun <T> locked(block: () -> T): T {
        while (!lock.tryLock()) {
            // Spin: the holder touches a map.
        }
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}

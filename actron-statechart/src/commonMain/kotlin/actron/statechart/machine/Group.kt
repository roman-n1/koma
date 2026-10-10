package actron.statechart.machine

import actron.core.InputAttribution

import actron.core.Action
import actron.core.Event
import actron.core.ExperimentalActronApi
import actron.core.InputId
import actron.observability.FailureDescriptor
import actron.observability.GroupSeq
import actron.observability.JournalBoundary
import actron.observability.JournalPublisher
import actron.observability.JournalEntry
import actron.observability.RecordingSession
import actron.observability.StoreInstanceId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A bridge message on its way: sent to [to] and not yet decided by it.
 */
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
data class GroupCheckpoint(
    val members: Map<StoreInstanceId, ExecutorCheckpoint<*, *>>,
    val inFlight: List<BridgeMessage>,
    val held: Map<StoreInstanceId, Int>,
    val boundary: JournalBoundary,
    val sources: Map<SourceId, SourceSnapshot> = emptyMap(),
)

/** The operational result of attempting a consistent group cut. */
sealed interface GroupCut {
    data class Ready(val checkpoint: GroupCheckpoint) : GroupCut
    data class TimedOut(val stage: CutStage) : GroupCut
    data class MemberUnavailable(val member: StoreInstanceId) : GroupCut
}

sealed interface CutStage {
    data class PausingSource(val source: SourceId) : CutStage
    data class SettlingMember(val member: StoreInstanceId) : CutStage
}

/** The role of a route in a request/reply pair; see [MachineGroup.requestReply]. */
enum class PairRole { Request, Reply }

/** A route's place in the request/reply pair named [name]; see [MachineGroup.requestReply]. */
sealed interface RouteProtocol {
    val suffix: String

    data object OneWay : RouteProtocol {
        override val suffix: String = ""
    }
}

data class RoutePair(val name: String, val role: PairRole) : RouteProtocol {
    override val suffix: String get() = " ($name $role)"
}

/** The two routes of a request/reply pair named [name]; see [MachineGroup.requestReply]. */
class RequestReply(val name: String, val request: MachineGroup.Route, val reply: MachineGroup.Route)

/**
 * Sees every cut a [MachineGroup] makes, from inside it: the sources are paused and the members
 * frozen and idle, so what a member's observer has seen so far is exactly what the cut holds,
 * and nothing enters until the listener returns. A recorder uses this to mark the cut's place
 * in what it records. A listener that throws fails the cut for its caller; the members are
 * thawed and the sources resumed all the same.
 */
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
 * effect, so a replay meets the same ids. A message to a member that is not attached, has
 * closed or has left goes nowhere and is journaled as undelivered: the group's record is then
 * partial. Messages are [inFlight] from delivery until the store they were delivered to decided
 * them or closed; a store that closes with messages in flight to it will never decide them:
 * they are dropped and journaled as [JournalEntry.BridgeDropped]. A member that leaves with
 * [Member.detach] gets no more messages; what its store holds is still in flight until the
 * store decides it or closes. A route can be removed with [removeRoute]; [routeHistory] keeps
 * every route the bridge had, for a recording. [requestReply] registers a request route and a
 * reply route as a named pair: a reply decided in the same step as the request names the
 * request in the journal (`BridgeSent.cause`), and a replay checks that a reply follows a
 * request of the pair.
 *
 * **Cut.** [checkpoint] pauses every attached [ExternalSource], freezes every member's
 * controlled queue (new inputs wait, whatever their source), waits until each member has
 * finished what it had already accepted, takes every executor's checkpoint, the messages in
 * flight and every source's snapshot, then thaws the queues in order and resumes the sources.
 * No Store lock is held while waiting, and no member waits on another's user code; a member
 * that does not settle or a source that does not pause within the timeout aborts the cut and
 * the group resumes. A member whose store closed, or that left, is not part of the cut. A result of a
 * command that arrives during the cut waits in the queue and is applied once, after it. Never
 * call [checkpoint] from a handler or a plugin of a member:
 * it would wait for its own processing to end. A successful cut is journaled as
 * [JournalEntry.CheckpointCreated]. A [CutListener] registered with [onCut] sees the cut before
 * the thaw.
 *
 * Members are registered with [member] (the observer to give the store) and [Member.attach]
 * (the store itself, once built); sources with [source].
 *
 * @param session The journal that gets [JournalEntry.BridgeSent], [JournalEntry.BridgeReceived],
 * [JournalEntry.BridgeDropped] and [JournalEntry.CheckpointCreated]
 */
// The sources are the experimental part of the group (their contract and [source]); the group
// itself is stable and opts in without propagating.
@OptIn(ExperimentalActronApi::class)
class MachineGroup(private val session: JournalPublisher = JournalPublisher.Disabled) {
    private val lock = Mutex()
    private val coordinating = Mutex()
    private val members = linkedMapOf<StoreInstanceId, Member<*, *, *, *>>()
    private val routeList = mutableListOf<Route>()
    private val routeHistoryList = mutableListOf<Route>()
    // A routed effect can reach several members: each destination has its own delivery.
    // Track the actual store too, so reattachment cannot move an old delivery to a new store.
    private val inFlightTargets = linkedMapOf<BridgeMessage, MachineStoreImpl<*, *, *, *>>()
    private val sourceList = mutableListOf<ExternalSource>()
    private val cutListeners = mutableListOf<CutListener>()

    /**
     * A route of the bridge: effects of [from] that [map] turns into actions of [to]; [pair] is
     * its place in a request/reply pair, when it has one.
     */
    class Route internal constructor(val from: StoreInstanceId, val to: StoreInstanceId, map: (Event, (Action) -> Unit) -> Unit, val pair: RouteProtocol = RouteProtocol.OneWay) {
        private val mapping = EventRoute(map)
        /** Prepares one synchronous action for [to], or filters [event] without calling [carry]. */
        fun mapEvent(event: Event, carry: (Action) -> Unit): Boolean = mapping.mapEvent(event, carry)

        override fun toString(): String = "$from -> $to" + pair.suffix
    }

    /** The routes the bridge carries now, in registration order. */
    val routes: List<Route> get() = locked { routeList.toList() }

    /**
     * Every route registered since the group was created, in registration order, the removed
     * ones included: what a recording keeps, so a replay tells a delivery that never had a route
     * (instances mixed) from one whose route was removed later.
     */
    val routeHistory: List<Route> get() = locked { routeHistoryList.toList() }

    /** The members, in registration order. */
    val memberIds: List<StoreInstanceId> get() = locked { members.keys.toList() }

    /** The messages sent and not yet decided by their receiver. */
    val inFlight: List<BridgeMessage> get() = locked { inFlightTargets.keys.toList() }

    /** The attached sources, in registration order. */
    val sourceIds: List<SourceId> get() = locked { sourceList.map { it.id } }

    /**
     * Attaches [source]: it is paused and snapshotted in every cut from now on.
     *
     * @throws IllegalArgumentException if a source with the same id is attached
     */
    @ExperimentalActronApi
    fun source(source: ExternalSource) {
        locked {
            require(sourceList.none { it.id == source.id }) { "[Actron] ${source.id} is already attached to this group" }
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
     * Omitting the carry callback leaves the effect unrouted.
     */
    fun <E : Event, A : Action> route(from: StoreInstanceId, to: StoreInstanceId, map: (E, (A) -> Unit) -> Unit): Route = register(from, to, map, pair = RouteProtocol.OneWay)

    /**
     * A request and its reply as a pair of routes named [name]: the effects of [requester] that
     * [request] maps go to [responder], the effects of [responder] that [reply] maps go back to
     * [requester]. Asynchronous by construction: the requester waits in a state of its own, with
     * a timer if it must, and correlates by what the actions carry. The pair adds the causality:
     * a reply the responder decides in the same step as the request names the request in the
     * journal ([JournalEntry.BridgeSent.cause]), and a replay checks that every reply follows a
     * request of the pair. Between two members, a pair's direction should carry the pair alone:
     * a replay tells a reply by its direction.
     *
     * @throws IllegalArgumentException if [requester] and [responder] are the same member
     */
    fun <RQ : Event, RA : Action, RP : Event, PA : Action> requestReply(
        requester: Member<*, PA, *, RQ>,
        responder: Member<*, RA, *, RP>,
        name: String,
        request: (RQ, (RA) -> Unit) -> Unit,
        reply: (RP, (PA) -> Unit) -> Unit,
    ): RequestReply {
        val requestRoute = register(requester.id, responder.id, request, RoutePair(name, PairRole.Request))
        val replyRoute = register(responder.id, requester.id, reply, RoutePair(name, PairRole.Reply))
        return RequestReply(name, requestRoute, replyRoute)
    }

    private fun <E : Event, A : Action> register(from: StoreInstanceId, to: StoreInstanceId, map: (E, (A) -> Unit) -> Unit, pair: RouteProtocol): Route {
        require(from != to) { "[Actron] A route goes to another member; $from -> $from" }
        @Suppress("UNCHECKED_CAST")
        val route = Route(from, to, map as (Event, (Action) -> Unit) -> Unit, pair)
        locked {
            routeList += route
            routeHistoryList += route
        }
        return route
    }

    /**
     * Routes the effects of [from] to [to], the receiver's action type checked at compile time:
     * [map] synchronously carries an action [to] accepts, or carries none for an unrelated effect.
     */
    fun <E : Event, A : Action> route(from: Member<*, *, *, E>, to: Member<*, A, *, *>, map: (E, (A) -> Unit) -> Unit): Route = route(from.id, to.id, map)

    /**
     * Stops routing along [route]: the effects decided from now on are not carried by it; the
     * messages it carried before are in flight until decided. The route stays in [routeHistory].
     *
     * @return `false` when [route] is not registered
     */
    fun removeRoute(route: Route): Boolean = locked { routeList.remove(route) }

    /**
     * Registers [id] as a member and returns the observer to give its store; [Member.attach] the
     * store once it is built.
     *
     * @throws IllegalArgumentException if [id] is already a member
     */
    fun <C : Any, A : Action, CMD : Any, E : Event> member(id: StoreInstanceId): Member<C, A, CMD, E> {
        val member = Member<C, A, CMD, E>(id)
        locked {
            require(id !in members) { "[Actron] $id is already a member of this group" }
            members[id] = member
        }
        return member
    }

    /**
     * A [GroupCut.Ready] checkpoint, a [GroupCut.TimedOut] stage or a [GroupCut.MemberUnavailable]
     * result when a member has closed; see the class documentation.
     */
    suspend fun checkpoint(timeout: Duration = 2.seconds): GroupCut = coordinating.withLock {
        val attached = locked {
            buildList<Pair<StoreInstanceId, MachineStoreImpl<*, *, *, *>>> {
                for (member in members.values) member.withReachableStore { add(member.id to it) }
            }
        }
        val sources = locked { sourceList.toList() }
        val started = TimeSource.Monotonic.markNow()
        val paused = mutableListOf<ExternalSource>()
        var frozen = false
        var failure: Result<Unit> = Result.success(Unit)
        var primaryFailed = false
        try {
            // Sources first: once none feeds, what the members hold is all there is.
            for (source in sources) {
                val remaining = timeout - started.elapsedNow()
                if (remaining <= Duration.ZERO) return@withLock GroupCut.TimedOut(CutStage.PausingSource(source.id))
                val completed = withTimeoutOrNull(remaining) {
                    source.pause()
                    // Transfer ownership before leaving the timeout scope: prompt cancellation
                    // can discard its successful result even after pause has returned.
                    paused += source
                    true
                } ?: false
                if (!completed) return@withLock GroupCut.TimedOut(CutStage.PausingSource(source.id))
            }
            for ((_, store) in attached) store.freeze()
            frozen = true
            for ((id, store) in attached) {
                val remaining = timeout - started.elapsedNow()
                if (remaining <= Duration.ZERO || !store.awaitIdle(remaining).isIdle) return@withLock GroupCut.TimedOut(CutStage.SettlingMember(id))
            }
            val cuts = attached.associate { (id, store) ->
                id to try {
                    store.checkpoint()
                } catch (e: IllegalStateException) {
                    return@withLock GroupCut.MemberUnavailable(id)
                }
            }
            val held = attached.associate { (id, store) -> id to store.heldInputs }
            val messages = locked { inFlightTargets.keys.toList() }
            val snapshots = sources.associate { it.id to it.snapshot() }
            val boundary = session.boundary
            session.publish(JournalEntry.CheckpointCreated(attached.map { it.first }, sources.map { it.id.value }, messages.size))
            val checkpoint = GroupCheckpoint(cuts, messages, held, boundary, snapshots)
            // Still frozen: a listener sees the cut where the members' observers stand.
            for (listener in locked { cutListeners.toList() }) listener.onCut(checkpoint)
            GroupCut.Ready(checkpoint)
        } catch (t: Throwable) {
            failure = Result.failure(t)
            primaryFailed = true
            throw t
        } finally {
            fun release(block: () -> Unit) {
                try {
                    block()
                } catch (t: Throwable) {
                    if (failure.isSuccess) failure = Result.failure(t) else failure.onFailure { previous ->
                        if (previous !== t) previous.addSuppressed(t)
                    }
                }
            }
            if (frozen) for ((_, store) in attached) release { store.thaw() }
            for (source in paused) release { source.resume() }
            if (!primaryFailed) failure.onFailure { throw it }
        }
    }

    /**
     * One member: the [DecisionObserver] its store runs with. It sends the store's effects along
     * the routes and books the bridge messages the store decided. It takes part from [attach]
     * until it [detach]es or its store closes; a message delivered to its store is in flight
     * until that store decides it or closes, and is dropped then.
     */
    inner class Member<C : Any, A : Action, CMD : Any, E : Event> internal constructor(val id: StoreInstanceId) : DecisionObserver<C, A, CMD, E> {
        private var storeOpen: () -> Boolean = { false }
        private var visitStore: ((MachineStoreImpl<C, A, CMD, E>) -> Unit) -> Unit = {}

        // Under the group's lock: the member left with [detach].
        private var detached = false

        // Under the lock: a store is attached and open, and the member has not left.
        internal val isReachable: Boolean get() = storeOpen() && !detached

        // Called under the group lock: visits the exact attached instance, never a later replacement.
        internal fun withReachableStore(visit: (MachineStoreImpl<C, A, CMD, E>) -> Unit): Boolean {
            if (!isReachable) return false
            visitStore(visit)
            return true
        }

        /** Whether a message can reach this member now: a store is attached that has not closed, and the member has not left. */
        val isAttached: Boolean get() = locked { isReachable }

        /**
         * Attaches the store built with this member as an observer, so messages can reach it; a
         * member that detached, or whose store closed, takes part again with a new store.
         *
         * @throws IllegalArgumentException if [store] is not a store of this library
         */
        fun attach(store: MachineStore<C, A, CMD, E>) {
            require(store is MachineStoreImpl<C, A, CMD, E>) { "[Actron] Only a MachineStore built by MachineStore(...) can join a group" }
            locked {
                storeOpen = { !store.isClosed }
                visitStore = { visit -> visit(store) }
                detached = false
            }
            // The store says when it closed, after nothing can be decided any more: what was
            // delivered to it and not decided never will be, whatever the member attached since.
            store.onClose {
                val dropped = locked { takeInFlight(store) }
                for (message in dropped) session.publish(id, JournalEntry.BridgeDropped(message.id.toRef(), id, "StoreClosed"))
            }
        }

        /**
         * The member leaves the group: its effects are not routed any more, a message sent to it
         * goes nowhere, and a cut leaves it out. What was delivered to its store before is in
         * flight until the store decides it or closes; close the store to end that. [attach] a
         * store to take part again.
         */
        fun detach() {
            locked { detached = true }
        }

        override fun onCommitted(input: InputAttribution, machineInput: MachineInput<A>, decision: Decision<C, CMD, E>) {
            decided(input, machineInput)
        }

        override fun onIgnored(input: InputAttribution, machineInput: MachineInput<A>, reason: IgnoreReason) = decided(input, machineInput)

        override fun onFailed(input: InputAttribution, machineInput: MachineInput<A>, failure: FailureDescriptor) = decided(input, machineInput)

        private fun decided(input: InputAttribution, machineInput: MachineInput<A>) {
            if (machineInput !is MachineInput.BridgeReceived) return
            val (wasInFlight, gone) = locked {
                val target = BridgeMessage(machineInput.message, id)
                val booked = target in inFlightTargets
                inFlightTargets.remove(target)
                booked to !isReachable
            }
            // Dropped already (a closing store decided it all the same) or a member that left: not the group's story.
            if (!wasInFlight && gone) return
            session.publish(id, JournalEntry.BridgeReceived(input, machineInput.message.toRef()))
        }

        // MachineStore routes only after every observer has recorded the sending decision.
        internal fun send(input: InputAttribution, machineInput: MachineInput<A>, decision: Decision<C, CMD, E>) {
            if (decision.effects.isEmpty()) return
            val routes = locked { if (detached) emptyList() else routeList.filter { it.from == id } }
            if (routes.isEmpty()) return
            // The bridge message this step decided, if it was one: what a reply replies to.
            val cause: actron.observability.MessageCause = if (machineInput is MachineInput.BridgeReceived) machineInput.message.toRef() else actron.observability.MessageCause.Unprompted
            for (effect in decision.effects) {
                for (route in routes) {
                    route.mapEvent(effect.event) { action ->
                    val message = MessageId(id, effect.id)
                    // Booked under the lock together with the receiver's reachability: a store
                    // that closes meanwhile finds the message in flight and drops it, one that
                    // closed or left already gets nothing, and the receiver's decision finds the
                    // message in flight.
                    val deliver: () -> Unit = locked {
                        var delivery: () -> Unit = {}
                        val booked = route.to in members && members.getValue(route.to).withReachableStore { store ->
                            inFlightTargets[BridgeMessage(message, route.to)] = store
                            session.publish(id, JournalEntry.BridgeSent(input, message.toRef(), route.to, delivered = true, cause = cause))
                            delivery = { store.deliverUnchecked(message, action) }
                        }
                        if (!booked) {
                            session.publish(id, JournalEntry.BridgeSent(input, message.toRef(), route.to, delivered = false, cause = cause))
                        }
                        delivery
                    }
                    deliver()
                    }
                }
            }
        }
    }

    // Under the lock: removes the messages in flight that were delivered to [store] and returns them.
    private fun takeInFlight(store: MachineStoreImpl<*, *, *, *>): List<BridgeMessage> {
        val mine = inFlightTargets.filterValues { it === store }.keys.toList()
        for (id in mine) inFlightTargets.remove(id)
        return mine
    }

    private inline fun <T : Any> locked(block: () -> T): T {
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

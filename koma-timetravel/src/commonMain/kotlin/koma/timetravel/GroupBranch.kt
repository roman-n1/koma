package koma.timetravel

import koma.core.Action
import koma.core.Event
import koma.core.ExperimentalKomaApi
import koma.observability.FailureDescriptor
import koma.observability.StoreInstanceId
import koma.statechart.machine.BridgeMessage
import koma.statechart.machine.CommandId
import koma.statechart.machine.Decision
import koma.statechart.machine.MachineSnapshot
import koma.statechart.machine.MessageId
import kotlin.time.Duration

/**
 * A branch of a whole group: every member's [Branch], joined by a local bridge that delivers a
 * member's routed effects to the others as they are decided, depth first, in the order the
 * effects were emitted. Nothing of the live group is touched, and no live bridge is used
 * (handoff §9.2, §10).
 *
 * @param members The members' branches, from one group position
 * @param routes What the local bridge routes: effects of one member turned into actions of another
 */
@ExperimentalKomaApi
class GroupBranch(
    val members: Map<StoreInstanceId, Branch<*, *, *, *>>,
    private val routes: List<Route>,
) {
    /** A route of the local bridge: effects of [from] that [map] turns into actions of [to]. */
    class Route(val from: StoreInstanceId, val to: StoreInstanceId, val map: (Event) -> Action?)

    private val deliveries = mutableListOf<BridgeMessage>()

    /** Every delivery the local bridge made, in order. */
    val delivered: List<BridgeMessage> get() = deliveries.toList()

    /** The snapshot of [store] in the branch now. */
    fun snapshot(store: StoreInstanceId): MachineSnapshot<*> = branch(store).snapshot

    /** Decides [action] on [store]; returns every decision it caused, deliveries included. */
    fun dispatch(store: StoreInstanceId, action: Action): List<Decision<*, *, *>> = produce(store) { it.dispatch(action) }

    /** Answers the awaiting [command] of [store] with [result]; see [Branch.answer]. */
    fun answer(store: StoreInstanceId, command: CommandId, result: Action): List<Decision<*, *, *>> = produce(store) { it.answer(command, result) }

    /** Completes the awaiting [command] of [store]; see [Branch.complete]. */
    fun complete(store: StoreInstanceId, command: CommandId): List<Decision<*, *, *>> = produce(store) { it.complete(command) }

    /** Fails the awaiting [command] of [store]; see [Branch.fail]. */
    fun fail(store: StoreInstanceId, command: CommandId, failure: FailureDescriptor): List<Decision<*, *, *>> = produce(store) { it.fail(command, failure) }

    /**
     * Moves every member's clock forward by [duration], firing the timers that come due, member
     * by member in registration order; returns every decision, deliveries included.
     */
    fun advance(duration: Duration): List<Decision<*, *, *>> =
        members.keys.flatMap { store -> produce(store) { it.advance(duration) } }

    private fun produce(store: StoreInstanceId, act: (Branch<Any?, Action, Any?, Event>) -> Unit): List<Decision<*, *, *>> {
        val branch = branch(store)
        val before = branch.history.size
        act(branch)
        val produced = branch.history.drop(before)
        return produced + route(store, produced)
    }

    // Delivers the routed effects of [decisions], each delivery's own decisions routed in turn.
    private fun route(from: StoreInstanceId, decisions: List<Decision<*, *, *>>): List<Decision<*, *, *>> {
        val caused = mutableListOf<Decision<*, *, *>>()
        for (decision in decisions) {
            if (!decision.isHandled) continue
            for (effect in decision.effects) {
                for (route in routes) {
                    if (route.from != from) continue
                    val action = route.map(effect.event) ?: continue
                    val target = members[route.to] ?: continue
                    val message = MessageId(from, effect.id)
                    deliveries += BridgeMessage(message, route.to)
                    caused += produce(route.to) { it.deliver(message, action) }
                }
            }
        }
        return caused
    }

    @Suppress("UNCHECKED_CAST")
    private fun branch(store: StoreInstanceId): Branch<Any?, Action, Any?, Event> =
        requireNotNull(members[store]) { "[Koma] $store is not a member of this branch" } as Branch<Any?, Action, Any?, Event>
}

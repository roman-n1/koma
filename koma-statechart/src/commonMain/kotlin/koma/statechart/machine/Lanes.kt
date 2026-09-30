package koma.statechart.machine

import koma.core.ExperimentalKomaApi

/**
 * The executor's bookkeeping of commands as data: which commands run and which wait in which
 * lane, and what admitting a command, finishing one or exiting an activation does to them under
 * the [ConcurrencyPolicy] of each lane. Pure and immutable: every operation returns a
 * [LaneChange] with the next [Lanes] and the effects for whoever runs the commands. The live
 * scheduler starts and cancels jobs and feeds the abandonments; a replay branch moves commands
 * between awaiting and queued; a checkpoint of the executor is these lanes.
 *
 * A command stays in [running] until [finished] is called for it, also after it was cancelled
 * by [exited]: a lane frees its place when the job has ended, not when it was told to end. A
 * command superseded under [ConcurrencyPolicy.Latest] leaves at once, as that lane never queues.
 *
 * @property running The commands started and not yet finished, in start order
 * @property queued The commands waiting in their lanes, in registration order; a lane with
 * nothing waiting is absent
 */
data class Lanes<CMD>(
    val running: Map<CommandId, CommandRegistration<CMD>> = emptyMap(),
    val queued: Map<LaneId, List<CommandRegistration<CMD>>> = emptyMap(),
) {
    init {
        require(queued.values.none { it.isEmpty() }) { "[Koma] A lane with nothing queued is absent, not empty" }
    }

    /** The registered commands of every category, by id. */
    val all: Map<CommandId, CommandRegistration<CMD>>
        get() = running + queued.values.flatten().associateBy { it.id }

    /**
     * Admits [registration] under its lane's policy: started at once, queued behind the lane's
     * running commands, or abandoned. Under [ConcurrencyPolicy.Latest] the lane's running and
     * queued commands are abandoned as [AbandonReason.Superseded] and the new one starts.
     */
    fun admit(registration: CommandRegistration<CMD>): LaneChange<CMD> {
        val lane = registration.lane ?: return start(registration)
        return when (val policy = registration.policy) {
            ConcurrencyPolicy.Latest -> {
                val superseded = runningIn(lane) + (queued[lane] ?: emptyList())
                val next = copy(running = running - superseded.map { it.id }.toSet(), queued = queued - lane)
                val started = next.start(registration)
                started.copy(abandoned = superseded.map { LaneChange.Abandoned(it, AbandonReason.Superseded) })
            }
            ConcurrencyPolicy.Sequential -> if (runningIn(lane).isEmpty()) start(registration) else enqueue(registration, lane)
            ConcurrencyPolicy.DropIfRunning ->
                if (runningIn(lane).isEmpty()) start(registration) else LaneChange(this, abandoned = listOf(LaneChange.Abandoned(registration, AbandonReason.Dropped)))
            is ConcurrencyPolicy.Parallel -> if (runningIn(lane).size < policy.limit) start(registration) else enqueue(registration, lane)
            null -> start(registration)
        }
    }

    /**
     * The command [id] has ended, whatever the reason: it leaves [running] and the commands
     * waiting in its lane start while the lane's policy lets them. A command not running is a
     * no-op.
     */
    fun finished(id: CommandId): LaneChange<CMD> {
        val registration = running[id] ?: return LaneChange(this)
        var next = copy(running = running - id)
        val lane = registration.lane ?: return LaneChange(next)
        val started = mutableListOf<CommandRegistration<CMD>>()
        while (true) {
            val waiting = next.queued[lane] ?: break
            val candidate = waiting.first()
            val fits = when (val policy = candidate.policy) {
                ConcurrencyPolicy.Sequential -> next.runningIn(lane).isEmpty()
                is ConcurrencyPolicy.Parallel -> next.runningIn(lane).size < policy.limit
                else -> true
            }
            if (!fits) break
            next = next.copy(running = next.running + (candidate.id to candidate), queued = next.without(lane, candidate))
            started += candidate
        }
        return LaneChange(next, started = started)
    }

    /**
     * The activations [scopes] have exited: their running commands are to be cancelled (they
     * stay in [running] until [finished]), their queued commands are dropped without a word,
     * as the decision that exited them already deregistered them.
     */
    fun exited(scopes: Collection<ActivationId>): LaneChange<CMD> {
        if (scopes.isEmpty()) return LaneChange(this)
        val exited = scopes.toSet()
        val cancelled = running.values.filter { it.scope in exited }.map { it.id }
        val remaining = queued.mapValues { (_, waiting) -> waiting.filter { it.scope !in exited } }.filterValues { it.isNotEmpty() }
        return LaneChange(copy(queued = remaining), cancelled = cancelled)
    }

    /** The running commands of [lane], in start order. */
    fun runningIn(lane: LaneId): List<CommandRegistration<CMD>> = running.values.filter { it.lane == lane }

    private fun start(registration: CommandRegistration<CMD>): LaneChange<CMD> =
        LaneChange(copy(running = running + (registration.id to registration)), started = listOf(registration))

    private fun enqueue(registration: CommandRegistration<CMD>, lane: LaneId): LaneChange<CMD> =
        LaneChange(copy(queued = queued + (lane to (queued[lane] ?: emptyList()) + registration)))

    private fun without(lane: LaneId, registration: CommandRegistration<CMD>): Map<LaneId, List<CommandRegistration<CMD>>> {
        val rest = (queued[lane] ?: emptyList()) - registration
        return if (rest.isEmpty()) queued - lane else queued + (lane to rest)
    }
}

/**
 * What one operation on [Lanes] asks the executor to do, with the lanes after it.
 *
 * @property lanes The lanes after the operation
 * @property started The commands to start, in order; they are in [Lanes.running] now
 * @property cancelled The running commands to cancel because their activation exited; they
 * stay in [Lanes.running] until [Lanes.finished] and are not reported to the machine
 * @property abandoned The commands the executor gives up on, to cancel if they run and to
 * report to the machine as [MachineInput.CommandAbandoned]; they are out of the lanes
 */
data class LaneChange<CMD>(
    val lanes: Lanes<CMD>,
    val started: List<CommandRegistration<CMD>> = emptyList(),
    val cancelled: List<CommandId> = emptyList(),
    val abandoned: List<Abandoned<CMD>> = emptyList(),
) {
    /** A command the executor gave up on, and why. */
    data class Abandoned<CMD>(val registration: CommandRegistration<CMD>, val reason: AbandonReason)
}

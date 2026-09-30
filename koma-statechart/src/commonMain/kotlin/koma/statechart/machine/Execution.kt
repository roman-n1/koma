package koma.statechart.machine

import koma.core.Action
import koma.core.ExperimentalKomaApi
import kotlinx.coroutines.delay
import kotlin.time.TimeSource

/**
 * The clock of a [MachineStore]: where every [MachineInput.now] comes from and what timers wait
 * on. Live, it is monotonic elapsed time since the store was created; a replay gives the machine
 * a virtual one.
 */
@ExperimentalKomaApi
interface MachineClock {
    /** The current machine time. */
    fun now(): MachineTime

    /** Suspends until [now] is at or past [deadline]; returns at once when it already is. */
    suspend fun delayUntil(deadline: MachineTime)

    companion object {
        /**
         * A live clock: [MachineTime.Zero] when created, then [timeSource]'s elapsed time; waits
         * with [delay].
         */
        fun monotonic(timeSource: TimeSource = TimeSource.Monotonic): MachineClock = object : MachineClock {
            private val start = timeSource.markNow()

            override fun now(): MachineTime = MachineTime(start.elapsedNow())

            override suspend fun delayUntil(deadline: MachineTime) {
                val remaining = deadline - now()
                if (remaining.isPositive()) delay(remaining)
            }
        }
    }
}

/**
 * A registered command as the executor hands it to a [CommandHandler].
 *
 * @property id The command's identity; results the handler sends are checked against it
 * @property command The command as the enter rule registered it
 * @property scope The activation the command belongs to; the handler is cancelled when it exits
 * @property lane The lane, when the command runs under a [ConcurrencyPolicy]
 */
@ExperimentalKomaApi
data class CommandEnvelope<out CMD>(val id: CommandId, val command: CMD, val scope: ActivationId, val lane: LaneId?)

/**
 * Where a [CommandHandler] sends what it produced. Each result becomes a
 * [MachineInput.CommandResult] in the store's input queue, in the order sent; a result of a
 * command the machine no longer holds is ignored as stale.
 */
@ExperimentalKomaApi
fun interface ResultSink<in A : Action> {
    fun result(action: A)
}

/**
 * Executes the commands a [Machine] registers. This is the only place I/O happens: the network,
 * the repository, a subscription, a platform API.
 *
 * The handler runs in the store's execution scope, outside the store's lock, after the decision
 * that registered the command was committed. It sends results through [ResultSink]; when it
 * returns, the command is [MachineInput.CommandCompleted]. An exception it throws is
 * [MachineInput.CommandFailed] and is reported to the store's exception handler; an expected
 * failure of a service (a 404, an offline error) should instead be sent as a typed result. The
 * handler is cancelled when the command's activation exits, when a newer command supersedes it in
 * its lane, or when the store closes; clean up in `finally` and never send a result from
 * `NonCancellable` cleanup as if the command had succeeded.
 *
 * The handler never sets a state: it returns inputs, and the machine decides.
 */
@ExperimentalKomaApi
fun interface CommandHandler<in CMD, out A : Action> {
    suspend fun execute(command: CommandEnvelope<CMD>, results: ResultSink<A>)
}

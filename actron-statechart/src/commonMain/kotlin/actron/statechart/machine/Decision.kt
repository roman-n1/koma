package actron.statechart.machine

import actron.core.Event
import actron.core.ExperimentalActronApi
import actron.observability.FailureDescriptor

/**
 * What a [Machine] decided for one input: the next snapshot and every intent the executor must
 * carry out after committing it. Nothing here has happened yet; a decision is data.
 *
 * @property outcome Whether the input was handled, ignored or failed
 * @property snapshot The snapshot to commit; the given one, unchanged, when the outcome is not
 * [DecisionOutcome.Handled]
 * @property transitions The transitions taken, in selection order
 * @property exited The activations left, innermost first
 * @property entered The activations created, outermost first
 * @property commands The commands to register, in the order the hooks asked for them
 * @property cancelledScopes The activations whose commands the executor cancels: the exited ones
 * @property timersScheduled The timers to schedule
 * @property timersCancelled The timers to cancel, including a fired one
 * @property effects The events to deliver, in the order the hooks emitted them
 */
data class Decision<C, out CMD, out E : Event>(
    val outcome: DecisionOutcome,
    val snapshot: MachineSnapshot<C>,
    val transitions: List<TransitionId> = emptyList(),
    val exited: List<Activation> = emptyList(),
    val entered: List<Activation> = emptyList(),
    val commands: List<CommandRegistration<CMD>> = emptyList(),
    val cancelledScopes: List<ActivationId> = emptyList(),
    val timersScheduled: List<TimerSchedule> = emptyList(),
    val timersCancelled: List<TimerId> = emptyList(),
    val effects: List<EffectEnvelope<E>> = emptyList(),
) {
    /**
     * Whether [snapshot] is a new one to commit.
     */
    val isHandled: Boolean get() = outcome is DecisionOutcome.Handled
}

/**
 * How an input was decided.
 */
sealed interface DecisionOutcome {
    /**
     * The input was accepted: the snapshot advanced by one revision, also when nothing but the
     * revision changed.
     */
    data object Handled : DecisionOutcome

    /**
     * The input changed nothing; the snapshot is the same and nothing is registered.
     */
    data class Ignored(val reason: IgnoreReason) : DecisionOutcome

    /**
     * A guard, reducer or hook threw. Nothing is committed and nothing is registered; the
     * executor reports [cause] to the exception handler and the journal keeps [failure].
     */
    class Failed(val failure: FailureDescriptor, val cause: Throwable) : DecisionOutcome {
        override fun toString(): String = "Failed($failure)"
    }
}

/**
 * Why an input was ignored.
 */
enum class IgnoreReason {
    /** An input other than [MachineInput.Start] before the start. */
    NotStarted,

    /** A second [MachineInput.Start]. */
    AlreadyStarted,

    /** No transition of the active nodes matched the action, or every matching guard was false. */
    NoTransition,

    /** The command is not registered any more: its scope exited, or it already ended. */
    StaleCommand,

    /** The timer is not scheduled any more: its source was exited, or it already fired. */
    UnknownTimer,
}

/**
 * How commands in one [LaneId] relate.
 */
sealed interface ConcurrencyPolicy {
    /** A new command cancels the running one in the lane. */
    data object Latest : ConcurrencyPolicy

    /** Commands in the lane run one after another, in registration order. */
    data object Sequential : ConcurrencyPolicy

    /** A new command is dropped while one runs in the lane. */
    data object DropIfRunning : ConcurrencyPolicy

    /** Up to [limit] commands run at once; further ones wait. */
    data class Parallel(val limit: Int) : ConcurrencyPolicy {
        init {
            require(limit >= 1) { "[Actron] Parallel needs a limit of at least 1" }
        }
    }
}

/**
 * A command to register: what to run, for which activation, and how it relates to other commands
 * of its lane. A command without a lane runs on its own.
 */
data class CommandRegistration<out CMD>(
    val id: CommandId,
    val command: CMD,
    val scope: ActivationId,
    val lane: LaneId? = null,
    val policy: ConcurrencyPolicy? = null,
) {
    init {
        require((lane == null) == (policy == null)) { "[Actron] A lane needs a policy and a policy needs a lane" }
    }
}

/**
 * A timer to schedule: fire [MachineInput.TimerFired] with [id] when the clock reaches
 * [deadline], unless it was cancelled first.
 */
data class TimerSchedule(val id: TimerId, val transition: TransitionId, val activation: ActivationId, val deadline: MachineTime)

/**
 * An event to deliver, with an identity of its own.
 */
data class EffectEnvelope<out E : Event>(val id: EffectId, val event: E)

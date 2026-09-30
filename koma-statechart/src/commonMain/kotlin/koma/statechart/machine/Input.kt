package koma.statechart.machine

import koma.core.Action
import koma.core.ExperimentalKomaApi
import koma.observability.FailureDescriptor

/**
 * What a [Machine] decides on. Every value the decision needs that is not in the snapshot comes
 * here, the clock included, so the same snapshot and input always give the same decision.
 *
 * More kinds of input may be added, so code that matches on this type should expect more cases.
 */
@ExperimentalKomaApi
sealed interface MachineInput<out A : Action> : Action {
    /**
     * The machine's clock when the input was accepted.
     */
    val now: MachineTime

    /**
     * Enters the initial configuration; the first input of every run.
     */
    data class Start(override val now: MachineTime) : MachineInput<Nothing>

    /**
     * An action from the UI, a bridge or any other source outside the machine's own commands.
     */
    data class Dispatch<out A : Action>(val action: A, override val now: MachineTime) : MachineInput<A>

    /**
     * The bridge message [message] from another Store of the group, carrying [action]: decided like
     * a [Dispatch], never refused by admission, and identified so that a replay delivers it once.
     */
    data class BridgeReceived<out A : Action>(val message: MessageId, val action: A, override val now: MachineTime) : MachineInput<A>

    /**
     * The timer [timer] is due. The executor sends it; the machine checks that the timer is still
     * scheduled for the current activation of its source before it fires the transition.
     */
    data class TimerFired(val timer: TimerId, override val now: MachineTime) : MachineInput<Nothing>

    /**
     * The command [command] produced [action]: a loaded page, a delivery receipt, one element of
     * a subscription. The command stays registered; see [CommandCompleted].
     */
    data class CommandResult<out A : Action>(val command: CommandId, val action: A, override val now: MachineTime) : MachineInput<A>

    /**
     * The command [command] finished without further results.
     */
    data class CommandCompleted(val command: CommandId, override val now: MachineTime) : MachineInput<Nothing>

    /**
     * The command [command] failed unexpectedly. An expected failure of a service is not this: the
     * handler turns it into a typed [CommandResult]. The machine steps with a [CommandFailure]
     * action, so a chart may declare a transition for it.
     */
    data class CommandFailed(val command: CommandId, val failure: FailureDescriptor, override val now: MachineTime) : MachineInput<Nothing>

    /**
     * The command [command] never ran or was stopped by the executor for [reason]: superseded in
     * its lane, dropped because the lane was busy. The machine only deregisters it. A command
     * cancelled because its activation exited is not reported: the decision that exited the
     * activation already removed it.
     */
    data class CommandAbandoned(val command: CommandId, val reason: AbandonReason, override val now: MachineTime) : MachineInput<Nothing>
}

/**
 * The action a [Machine] steps with for [MachineInput.CommandFailed]. Declare
 * `ActionMatcher.of<CommandFailure>("CommandFailure")` on a transition to react to it; without
 * one the command is only deregistered.
 */
@ExperimentalKomaApi
data class CommandFailure(val command: CommandId, val failure: FailureDescriptor) : Action

/**
 * Why the executor abandoned a command; see [MachineInput.CommandAbandoned].
 */
@ExperimentalKomaApi
enum class AbandonReason {
    /** A newer command in the same lane under [ConcurrencyPolicy.Latest] cancelled it. */
    Superseded,

    /** The lane was busy under [ConcurrencyPolicy.DropIfRunning], so it never ran. */
    Dropped,
}

package koma.timetravel

import koma.core.Action
import koma.core.Event
import koma.core.ExperimentalKomaApi
import koma.statechart.machine.Decision
import koma.statechart.machine.DecisionOutcome
import koma.statechart.machine.ExecutorCheckpoint
import koma.statechart.machine.Machine
import koma.statechart.machine.MachineInput
import koma.statechart.machine.MachineSnapshot

/**
 * The first point where a replay differs from its recording.
 *
 * @property position The step that differed (0-based)
 * @property input The input of that step
 * @property expected What the recording says happened
 * @property actual What the machine decided now, on the snapshot the replay had at [position]
 * @property differences One line per field that differs, for the inspector
 */
@ExperimentalKomaApi
data class ReplayMismatch<C, A : Action, CMD, E : Event>(
    val position: Int,
    val input: MachineInput<A>,
    val expected: RecordedStep<C, A, CMD, E>,
    val actual: Decision<C, CMD, E>,
    val differences: List<String>,
) {
    fun describe(): String = "Replay diverged at step $position on $input:\n" + differences.joinToString("\n") { "  - $it" }
}

/**
 * The result of one forward step of a [ReplaySession].
 */
@ExperimentalKomaApi
sealed interface ReplayStep<C, A : Action, CMD, E : Event> {
    /** The machine decided the recorded input as recorded; [decision] is what it decided. */
    data class Matched<C, A : Action, CMD, E : Event>(val position: Int, val decision: Decision<C, CMD, E>) : ReplayStep<C, A, CMD, E>

    /** The machine decided differently; the session stays at the position. */
    data class Diverged<C, A : Action, CMD, E : Event>(val mismatch: ReplayMismatch<C, A, CMD, E>) : ReplayStep<C, A, CMD, E>
}

/**
 * Replays a [Recording] with [machine]: a cursor over the recorded steps with the snapshot at
 * each position, forward steps that decide the recorded input again and compare, and a
 * [branch] that continues from any position with new inputs.
 *
 * Nothing here executes: no command handler, no clock, no store. A recorded run is decided
 * again by the pure machine; a branch feeds the machine only what the caller gives it. The
 * recorded ids stay the recording's; a branch continues the counters of the snapshot it starts
 * from, so its ids follow the recorded ones without colliding.
 *
 * Positions are checkpoints: [seek] and [stepBackward] take the recorded snapshot and the
 * executor's state there and cost nothing; [stepForward] and [verify] are what compares.
 *
 * @throws IllegalArgumentException if [recording] is not [Compatibility.Replayable] by [machine]
 */
@ExperimentalKomaApi
class ReplaySession<C, A : Action, CMD, E : Event>(
    val machine: Machine<C, A, CMD, E>,
    val recording: Recording<C, A, CMD, E>,
) {
    init {
        val compatibility = recording.compatibilityWith(machine)
        require(compatibility == Compatibility.Replayable) { "[Koma] The recording cannot be replayed: $compatibility" }
    }

    /** The number of steps. */
    val length: Int get() = recording.length

    /** The current position, 0 (before the first step) to [length]. */
    var position: Int = 0
        private set

    /** The snapshot at [position]. */
    val snapshot: MachineSnapshot<C> get() = recording.snapshotAt(position)

    /** The recorded step [position] will decide next, or `null` at the end. */
    val next: RecordedStep<C, A, CMD, E>? get() = recording.steps.getOrNull(position)

    /**
     * Decides the next recorded input on the current snapshot and compares with the recording.
     * Advances on a match; stays on a divergence, and returns it again until [seek] moves on.
     * `null` at the end.
     */
    fun stepForward(): ReplayStep<C, A, CMD, E>? {
        val step = next ?: return null
        val decision = machine.decide(snapshot, step.input)
        val mismatch = replay(position, snapshot, step, decision)
        return if (mismatch == null) {
            position++
            ReplayStep.Matched(position - 1, decision)
        } else {
            ReplayStep.Diverged(mismatch)
        }
    }

    /**
     * Moves one step back, to the recorded snapshot; `false` at the start.
     */
    fun stepBackward(): Boolean {
        if (position == 0) return false
        position--
        return true
    }

    /**
     * Moves to [position], a recorded checkpoint, without deciding anything.
     *
     * @throws IllegalArgumentException if [position] is outside 0..[length]
     */
    fun seek(position: Int) {
        require(position in 0..length) { "[Koma] Position $position is outside 0..$length" }
        this.position = position
    }

    /**
     * Decides the recorded steps [from] until [to] again, from the recorded snapshot at [from],
     * and returns the first divergence, or `null` when every step matches. Does not move.
     */
    fun verify(from: Int = 0, to: Int = length): ReplayMismatch<C, A, CMD, E>? {
        require(from in 0..to && to <= length) { "[Koma] Range $from..$to is outside 0..$length" }
        var current = recording.snapshotAt(from)
        for (index in from until to) {
            val step = recording.steps[index]
            replay(index, current, step)?.let { return it }
            if (step is RecordedStep.Committed) current = step.decision.snapshot
        }
        return null
    }

    /** Invariant violations at the selected checkpoint, usable by an inspector without replay. */
    fun checkInvariants(): List<koma.statechart.machine.InvariantViolation> = machine.checkInvariants(snapshot)

    /** Explains the next recorded input through one pure decision; does not move the cursor. */
    fun explainNext(): koma.statechart.machine.ExplainedDecision<C, CMD, E>? =
        next?.let { machine.decideExplained(snapshot, it.input) }

    /** The executor's state at [position]; see [Recording.checkpointAt]. */
    val checkpoint: ExecutorCheckpoint<C, CMD> get() = recording.checkpointAt(position)

    /**
     * A new branch from the current position: the checkpoint there (the recorded snapshot, the
     * clock of the last recorded input, the commands running and queued), ready for inputs of
     * the caller's choosing.
     */
    fun branch(): Branch<C, A, CMD, E> = Branch(machine, checkpoint, recording)

    private fun replay(index: Int, base: MachineSnapshot<C>, step: RecordedStep<C, A, CMD, E>, actual: Decision<C, CMD, E> = machine.decide(base, step.input)): ReplayMismatch<C, A, CMD, E>? {
        val differences = mutableListOf<String>()
        val input = step.input
        if (input is MachineInput.TimerFired) {
            val timer = base.timers[input.timer]
            if (timer != null && input.now < timer.deadline) differences += "timer ${input.timer} fired at ${input.now}, before its deadline ${timer.deadline}"
        }
        when (step) {
            is RecordedStep.Committed -> {
                if (actual.outcome != DecisionOutcome.Handled) {
                    differences += "outcome: recorded Handled, now ${actual.outcome}"
                } else {
                    differences += compare(step.decision, actual)
                }
            }
            is RecordedStep.Ignored -> {
                val outcome = actual.outcome
                if (outcome !is DecisionOutcome.Ignored) differences += "outcome: recorded Ignored(${step.reason}), now $outcome"
                else if (outcome.reason != step.reason) differences += "ignore reason: recorded ${step.reason}, now ${outcome.reason}"
            }
            is RecordedStep.Failed -> {
                val outcome = actual.outcome
                if (outcome !is DecisionOutcome.Failed) differences += "outcome: recorded Failed(${step.failure.type}), now $outcome"
                else if (outcome.failure.type != step.failure.type) differences += "failure: recorded ${step.failure.type}, now ${outcome.failure.type}"
            }
        }
        return if (differences.isEmpty()) null else ReplayMismatch(index, input, step, actual, differences)
    }

    private fun compare(expected: Decision<C, CMD, E>, actual: Decision<C, CMD, E>): List<String> = buildList {
        val e = expected.snapshot
        val a = actual.snapshot
        if (e.revision != a.revision) add("revision: recorded ${e.revision}, now ${a.revision}")
        if (e.configuration != a.configuration) add("configuration: recorded ${e.configuration.active}, now ${a.configuration.active}")
        if (e.context != a.context) add("context: recorded ${e.context}, now ${a.context}")
        if (e.activations != a.activations) add("activations: recorded ${e.activations}, now ${a.activations}")
        if (e.commands != a.commands) add("registered commands: recorded ${e.commands.keys}, now ${a.commands.keys}")
        if (e.timers != a.timers) add("scheduled timers: recorded ${e.timers}, now ${a.timers}")
        if (e.counters != a.counters) add("counters: recorded ${e.counters}, now ${a.counters}")
        if (expected.transitions != actual.transitions) add("transitions: recorded ${expected.transitions}, now ${actual.transitions}")
        if (expected.exited != actual.exited) add("exited: recorded ${expected.exited}, now ${actual.exited}")
        if (expected.entered != actual.entered) add("entered: recorded ${expected.entered}, now ${actual.entered}")
        if (expected.commands != actual.commands) add("commands: recorded ${expected.commands}, now ${actual.commands}")
        if (expected.cancelledScopes != actual.cancelledScopes) add("cancelled scopes: recorded ${expected.cancelledScopes}, now ${actual.cancelledScopes}")
        if (expected.timersScheduled != actual.timersScheduled) add("timers scheduled: recorded ${expected.timersScheduled}, now ${actual.timersScheduled}")
        if (expected.timersCancelled != actual.timersCancelled) add("timers cancelled: recorded ${expected.timersCancelled}, now ${actual.timersCancelled}")
        if (expected.effects != actual.effects) add("effects: recorded ${expected.effects}, now ${actual.effects}")
    }
}

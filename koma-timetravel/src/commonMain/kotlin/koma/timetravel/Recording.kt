package koma.timetravel

import koma.core.Action
import koma.core.Event
import koma.core.ExperimentalKomaApi
import koma.core.InputId
import koma.observability.FailureDescriptor
import koma.statechart.machine.Decision
import koma.statechart.machine.DecisionObserver
import koma.statechart.machine.DefinitionId
import koma.statechart.machine.DefinitionVersion
import koma.statechart.machine.IgnoreReason
import koma.statechart.machine.Machine
import koma.statechart.machine.MachineInput
import koma.statechart.machine.MachineSnapshot
import kotlinx.coroutines.sync.Mutex

/**
 * One input a machine decided during a recorded run, with what came of it.
 */
@ExperimentalKomaApi
sealed interface RecordedStep<C, A : Action, CMD, E : Event> {
    val input: MachineInput<A>

    /** The input was handled: [decision] holds the snapshot committed and every intent registered. */
    data class Committed<C, A : Action, CMD, E : Event>(override val input: MachineInput<A>, val decision: Decision<C, CMD, E>) : RecordedStep<C, A, CMD, E>

    /** The input changed nothing. */
    data class Ignored<C, A : Action, CMD, E : Event>(override val input: MachineInput<A>, val reason: IgnoreReason) : RecordedStep<C, A, CMD, E>

    /** A guard, reducer or rule threw; nothing was committed. */
    data class Failed<C, A : Action, CMD, E : Event>(override val input: MachineInput<A>, val failure: FailureDescriptor) : RecordedStep<C, A, CMD, E>
}

/**
 * A run of one machine: the snapshot it started from and every input it decided, in processing
 * order, with the outcome. Immutable; a replay decides the inputs again and compares.
 *
 * Positions run from 0 (before the first step) to [length] (after the last). The snapshot at a
 * position is the one the last committed step before it produced, so every position is a
 * checkpoint and stepping backward costs nothing.
 *
 * @property definition The machine that produced the run
 * @property version Its version; another version cannot replay this run
 * @property initial The snapshot before the first step, revision 0
 * @property steps The inputs in processing order with their outcomes
 */
@ExperimentalKomaApi
class Recording<C, A : Action, CMD, E : Event>(
    val definition: DefinitionId,
    val version: DefinitionVersion,
    val initial: MachineSnapshot<C>,
    val steps: List<RecordedStep<C, A, CMD, E>>,
) {
    init {
        require(initial.definition == definition && initial.version == version) { "[Koma] The initial snapshot belongs to ${initial.definition} ${initial.version}, not $definition $version" }
        require(initial.revision == 0L) { "[Koma] A recording starts before the first decision, at revision 0" }
    }

    /** The number of steps. */
    val length: Int get() = steps.size

    private val snapshots: List<MachineSnapshot<C>> by lazy {
        buildList(steps.size + 1) {
            var current = initial
            add(current)
            for (step in steps) {
                if (step is RecordedStep.Committed) current = step.decision.snapshot
                add(current)
            }
        }
    }

    /**
     * The snapshot after the first [position] steps: [initial] for 0.
     *
     * @throws IllegalArgumentException if [position] is outside 0..[length]
     */
    fun snapshotAt(position: Int): MachineSnapshot<C> {
        require(position in 0..length) { "[Koma] Position $position is outside 0..$length" }
        return snapshots[position]
    }

    /**
     * Whether [machine] can replay this run: [Compatibility.Replayable] for the same definition
     * and version, [Compatibility.InspectableOnly] for the same definition in another version
     * (the snapshots can be shown, not decided again), [Compatibility.Unsupported] otherwise.
     */
    fun compatibilityWith(machine: Machine<C, A, CMD, E>): Compatibility = when {
        machine.id != definition -> Compatibility.Unsupported("a recording of $definition cannot be replayed by ${machine.id}")
        machine.version != version -> Compatibility.InspectableOnly("recorded by version $version, the machine is version ${machine.version}")
        else -> Compatibility.Replayable
    }

    override fun toString(): String = "Recording($definition $version, ${steps.size} steps)"
}

/**
 * What a machine can do with a [Recording].
 */
@ExperimentalKomaApi
sealed interface Compatibility {
    /** The recording can be decided again and compared step by step. */
    data object Replayable : Compatibility

    /** The snapshots can be shown; the machine's behaviour differs, so it must not decide them. */
    data class InspectableOnly(val reason: String) : Compatibility

    /** The recording is of another machine. */
    data class Unsupported(val reason: String) : Compatibility
}

/**
 * Records the run of a `MachineStore` as a [Recording]: register it as one of the store's
 * observers. It keeps the live objects (inputs, snapshots, commands, events); it is a debug
 * recorder, not a production journal, and its memory grows with the run.
 *
 * The callbacks come serialized from the store; [recording] may be read from any thread and
 * returns a snapshot of what was recorded so far. A run that did not start from
 * `machine.initialSnapshot(context)` (a restored snapshot that was started over, for example)
 * is noticed at its first step and reported through [problem]: such a recording cannot be
 * replayed from its initial snapshot.
 *
 * @param machine The machine of the store
 * @param context The context the store was created with
 */
@ExperimentalKomaApi
class MachineRecorder<C, A : Action, CMD, E : Event>(
    private val machine: Machine<C, A, CMD, E>,
    context: C,
) : DecisionObserver<C, A, CMD, E> {
    private val initial: MachineSnapshot<C> = machine.initialSnapshot(context)
    private val lock = Mutex()
    private val steps = ArrayList<RecordedStep<C, A, CMD, E>>()

    /**
     * Why this recording cannot be replayed, or `null`: set when the first committed decision did
     * not start from the initial snapshot the recorder was given.
     */
    var problem: String? = null
        private set

    override fun onCommitted(input: InputId?, machineInput: MachineInput<A>, decision: Decision<C, CMD, E>) {
        locked {
            if (steps.isEmpty() && machineInput is MachineInput.Start) {
                val expected = machine.decide(initial, machineInput)
                if (expected.snapshot != decision.snapshot) {
                    problem = "the run did not start from the recorder's initial snapshot (a restored snapshot started over?)"
                }
            }
            steps += RecordedStep.Committed(machineInput, decision)
        }
    }

    override fun onIgnored(input: InputId?, machineInput: MachineInput<A>, reason: IgnoreReason) {
        locked { steps += RecordedStep.Ignored(machineInput, reason) }
    }

    override fun onFailed(input: InputId?, machineInput: MachineInput<A>, failure: FailureDescriptor) {
        locked { steps += RecordedStep.Failed(machineInput, failure) }
    }

    /**
     * The recording so far.
     */
    fun recording(): Recording<C, A, CMD, E> = locked { Recording(machine.id, machine.version, initial, steps.toList()) }

    private inline fun <T> locked(block: () -> T): T {
        while (!lock.tryLock()) {
            // Spin: the holder appends one step or copies the list.
        }
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}

package koma.timetravel

import koma.core.Action
import koma.core.Event
import koma.core.InputId
import koma.observability.FailureDescriptor
import koma.statechart.machine.Decision
import koma.statechart.machine.DecisionObserver
import koma.statechart.machine.DefinitionId
import koma.statechart.machine.DefinitionVersion
import koma.statechart.machine.ExecutorCheckpoint
import koma.statechart.machine.IgnoreReason
import koma.statechart.machine.Machine
import koma.statechart.machine.MachineInput
import koma.statechart.machine.MachineSnapshot
import kotlinx.coroutines.sync.Mutex

/**
 * One input a machine decided during a recorded run, with what came of it.
 */
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
 * A run of one machine: the checkpoint it starts from and every input decided after it, in
 * processing order, with the outcome. Immutable; a replay decides the inputs again and compares.
 *
 * Positions run from 0 (at [start]) to [length] (after the last step). Every position is a
 * checkpoint: the snapshot at a position is the one the last committed step before it produced,
 * and the executor's state there ([checkpointAt]) is carried forward from [start] by the same
 * lane bookkeeping the live executor uses, so stepping backward and branching cost nothing.
 *
 * @property definition The machine that produced the run
 * @property version Its version; another version cannot replay this run
 * @property start Where the run begins: the initial snapshot of a whole run, or a checkpoint of
 * the live executor for a run recorded from there (see [since])
 * @property steps The inputs in processing order with their outcomes
 */
class Recording<C, A : Action, CMD, E : Event>(
    val definition: DefinitionId,
    val version: DefinitionVersion,
    val start: ExecutorCheckpoint<C, CMD>,
    val steps: List<RecordedStep<C, A, CMD, E>>,
) {
    /**
     * A whole run: from [initial], the snapshot before the first decision, with nothing running.
     */
    constructor(definition: DefinitionId, version: DefinitionVersion, initial: MachineSnapshot<C>, steps: List<RecordedStep<C, A, CMD, E>>) :
        this(definition, version, ExecutorCheckpoint.initial(initial), steps)

    init {
        require(start.snapshot.definition == definition && start.snapshot.version == version) { "[Koma] The start snapshot belongs to ${start.snapshot.definition} ${start.snapshot.version}, not $definition $version" }
    }

    /** The snapshot the run starts from: [start]'s. */
    val initial: MachineSnapshot<C> get() = start.snapshot

    /** The number of steps. */
    val length: Int get() = steps.size

    private val checkpoints: List<ExecutorCheckpoint<C, CMD>> by lazy {
        buildList(steps.size + 1) {
            var current = start
            add(current)
            for (step in steps) {
                current = current.carriedPast(step)
                add(current)
            }
        }
    }

    /**
     * The snapshot after the first [position] steps: [initial] for 0.
     *
     * @throws IllegalArgumentException if [position] is outside 0..[length]
     */
    fun snapshotAt(position: Int): MachineSnapshot<C> = checkpointAt(position).snapshot

    /**
     * The executor's state after the first [position] steps: [start] for 0, then what the lanes
     * come to when each step's decision is carried out and each decided end of a command is
     * booked, with the clock of the last input. A command superseded or dropped is ending until
     * the machine decides its abandonment, as at the live executor.
     *
     * @throws IllegalArgumentException if [position] is outside 0..[length], or the steps are
     * not a consistent run of the machine
     */
    fun checkpointAt(position: Int): ExecutorCheckpoint<C, CMD> {
        require(position in 0..length) { "[Koma] Position $position is outside 0..$length" }
        return checkpoints[position]
    }

    /**
     * This run from [checkpoint] on: the recording whose [start] is [checkpoint] and whose steps
     * are the ones decided after its snapshot. The checkpoint is the live executor's (taken
     * while this run was being recorded) or one of [checkpointAt]. A recording that begins at a
     * checkpoint is what a journal keeps when its ring has dropped the beginning of the run.
     *
     * @throws IllegalArgumentException if no position of this run has the checkpoint's
     * snapshot, or the checkpoint registers commands this run did not
     */
    fun since(checkpoint: ExecutorCheckpoint<C, CMD>): Recording<C, A, CMD, E> {
        val position = (0..length).firstOrNull { snapshotAt(it) == checkpoint.snapshot }
            ?: throw IllegalArgumentException("[Koma] The checkpoint at revision ${checkpoint.snapshot.revision} is not of this run")
        val recorded = checkpointAt(position).registrations
        require(checkpoint.registrations == recorded) {
            "[Koma] The checkpoint's commands ${checkpoint.registrations.keys} are not the run's at revision ${checkpoint.snapshot.revision}: ${recorded.keys}"
        }
        return Recording(definition, version, checkpoint, steps.drop(position))
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

    override fun toString(): String = "Recording($definition $version, from revision ${initial.revision}, ${steps.size} steps)"
}

/**
 * The executor's state after [step], as the live executor books it: an end the machine decided
 * has been booked, commands of exited activations have ended (nothing runs in a recording, so
 * nothing has cleanup to wait for), the decision's commands are admitted under their lanes, and
 * what was abandoned is ending until the machine deregisters it. What [Recording.checkpointAt]
 * carries forward, and what a recording file begins each segment with.
 */
fun <C, A : Action, CMD, E : Event> ExecutorCheckpoint<C, CMD>.carriedPast(step: RecordedStep<C, A, CMD, E>): ExecutorCheckpoint<C, CMD> {
    val now = maxOf(now, step.input.now)
    if (step !is RecordedStep.Committed) return copy(now = now)
    val decision = step.decision
    var lanes = lanes
    val ending = ending.toMutableMap()
    when (val input = step.input) {
        is MachineInput.CommandCompleted -> lanes = lanes.finished(input.command).lanes
        is MachineInput.CommandFailed -> lanes = lanes.finished(input.command).lanes
        is MachineInput.CommandAbandoned -> lanes = lanes.finished(input.command).lanes
        else -> Unit
    }
    val exited = lanes.exited(decision.cancelledScopes)
    lanes = exited.lanes
    for (id in exited.cancelled) lanes = lanes.finished(id).lanes
    for (registration in decision.commands) {
        val change = lanes.admit(registration)
        lanes = change.lanes
        for (abandoned in change.abandoned) ending[abandoned.registration.id] = abandoned.registration
    }
    ending.keys.retainAll(decision.snapshot.commands.keys)
    return ExecutorCheckpoint(decision.snapshot, now, lanes, ending)
}

/**
 * What a machine can do with a [Recording].
 */
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

package actron.timetravel.file

import actron.core.InputAttribution

import actron.core.Action
import actron.core.Event
import actron.core.InputId
import actron.observability.FailureDescriptor
import actron.observability.StoreInstanceId
import actron.observability.file.Framing
import actron.observability.file.SegmentOutput
import actron.observability.file.SegmentStorage
import actron.statechart.machine.Decision
import actron.statechart.machine.DecisionObserver
import actron.statechart.machine.ExecutorCheckpoint
import actron.statechart.machine.IgnoreReason
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineInput
import actron.timetravel.RecordedStep
import actron.timetravel.RecordingCodec
import actron.timetravel.carriedPast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Budgets of a [RecordingFileSink].
 *
 * @property maxSegmentBytes A segment that would grow past this with the next step is finished
 * and a new one begun with a checkpoint
 * @property maxSegments How many segments of the Store are kept; the oldest beyond this are
 * deleted, and the remaining range still begins with a checkpoint
 * @property queueCapacity How many steps may wait for the writer; a step that finds the queue
 * full is dropped, counted, and the next step begins a new segment with a checkpoint that
 * already includes the dropped one, so the file stays replayable after the hole
 * @property flushEveryFrames How many frames are written before the storage is asked to flush
 * @property onFailure Called with a storage failure; the writer stops writing after one and
 * releases its output. An exception thrown by this callback is ignored.
 */
data class RecordingFileConfig(
    val maxSegmentBytes: Int = 512 * 1024,
    val maxSegments: Int = 8,
    val queueCapacity: Int = 1024,
    val flushEveryFrames: Int = 32,
    val onFailure: (Throwable) -> Unit = {},
) {
    init {
        require(maxSegmentBytes >= 1 && maxSegments >= 1 && queueCapacity >= 1 && flushEveryFrames >= 1) { "[Actron] RecordingFileConfig budgets must be at least 1" }
    }
}

/** Counters of a [RecordingFileSink]. */
data class RecordingFileStats(val recorded: Long, val written: Long, val dropped: Long, val segments: Int)

/**
 * Records the run of a `MachineStore` into segments of a [SegmentStorage] in the
 * [RecordingFileFormat], as it happens: a [DecisionObserver] that carries the executor's
 * checkpoint forward on the store's thread (pure, no I/O) and hands each step to one writer
 * coroutine with the checkpoint before it, so any step can begin a segment. Segments rotate by
 * size, the oldest beyond the budget are deleted, and every segment begins with a checkpoint:
 * whatever a ring dropped, what remains replays from its first segment.
 *
 * A step the writer's queue has no room for is dropped and counted, and the next step begins
 * a new segment: the recording file then has a hole, which [RecordingFiles] reports, and a
 * replayable range after it. A step dropped with no step after it, at the end of a run, is
 * simply absent: the files end before the run did, and only [stats] tells. A run that did not
 * begin at the machine's initial snapshot is noticed at its first step and reported through
 * [problem], like `MachineRecorder`.
 * A storage failure or cancellation releases the output without completing a partial segment.
 *
 * @param store The Store this recording is of; names the segments
 * @param machine The machine of the store
 * @param context The context the store was created with
 * @param codec The codec of the machine's types
 * @param storage Where the segments go
 * @param scope Runs the writer; choose a dispatcher fit for the storage's I/O
 */
class RecordingFileSink<C : Any, A : Action, CMD : Any, E : Event>(
    private val store: StoreInstanceId,
    private val machine: Machine<C, A, CMD, E>,
    context: C,
    private val codec: RecordingCodec<C, A, CMD, E>,
    private val storage: SegmentStorage,
    scope: CoroutineScope,
    private val config: RecordingFileConfig = RecordingFileConfig(),
) : DecisionObserver<C, A, CMD, E> {
    private val lock = Mutex()
    private var current: ExecutorCheckpoint<C, CMD> = ExecutorCheckpoint.initial(machine.initialSnapshot(context))
    private var nextStep = 0
    private var beginSegment = true
    private var recorded = 0L
    private var dropped = 0L
    private var closed = false

    /** Whether the sink observed the initial snapshot it was configured to replay from. */
    var origin: actron.timetravel.RecordingOrigin = actron.timetravel.RecordingOrigin.InitialSnapshot
        private set

    private class Item<C : Any, A : Action, CMD : Any, E : Event>(val step: RecordedStep<C, A, CMD, E>, val index: Int, val before: ExecutorCheckpoint<C, CMD>, val beginSegment: Boolean)

    private val queue = Channel<Item<C, A, CMD, E>>(config.queueCapacity)

    // Writer state: touched only by the writer.
    private sealed interface SegmentWriter {
        data object Detached : SegmentWriter
        data class Active(val output: SegmentOutput) : SegmentWriter
    }
    private var segmentWriter: SegmentWriter = SegmentWriter.Detached
    private var segmentIndex = -1
    private var segmentBytes = 0
    private var segmentSteps = 0
    private var unflushed = 0
    private var written = 0L
    private var failed = false

    private val writer: Job = scope.launch {
        var completion: Result<Unit> = Result.success(Unit)
        try {
            for (item in queue) {
                if (failed) continue
                try {
                    write(item)
                } catch (e: Exception) {
                    if (e is CancellationException && !currentCoroutineContext().isActive) throw e
                    failed = true
                    release(Result.failure(e))
                    notifyFailure(e)
                }
            }
            if (!failed) finish()
        } catch (t: Throwable) {
            completion = Result.failure(t)
            if (t is CancellationException || t !is Exception) throw t
            notifyFailure(t)
        } finally {
            // Cancellation leaves an unfinished segment; never append END to a failed frame.
            try {
                release(completion)
            } finally {
                locked { closed = true }
                queue.cancel()
            }
        }
    }

    private fun notifyFailure(failure: Throwable) {
        try {
            config.onFailure(failure)
        } catch (_: Exception) {
            // Reporting a storage failure must not fail the writer's parent or its cleanup.
        }
    }

    private fun release(completion: Result<Unit>) {
        val active = segmentWriter
        if (active !is SegmentWriter.Active) return
        segmentWriter = SegmentWriter.Detached
        closeOutput(active.output, completion)
    }

    private fun closeOutput(output: SegmentOutput, completion: Result<Unit>) {
        try {
            output.close()
        } catch (t: Throwable) {
            completion.fold(
                onSuccess = { throw t },
                onFailure = { primary -> if (primary !== t) primary.addSuppressed(t) },
            )
        }
    }

    /** The counters as of now. */
    val stats: RecordingFileStats
        get() = locked { RecordingFileStats(recorded, written, dropped, segments.size) }

    /** The Store's segments present in the storage, oldest first. */
    val segments: List<String>
        get() = RecordingFileFormat.namedSegments(storage, store)
            .sortedBy { it.first }.map { it.second }

    override fun onCommitted(input: InputAttribution, machineInput: MachineInput<A>, decision: Decision<C, CMD, E>) {
        locked {
            if (nextStep == 0 && machineInput is MachineInput.Start && machine.decide(current.snapshot, machineInput).snapshot != decision.snapshot) {
                origin = actron.timetravel.RecordingOrigin.UnknownBeginning("the run did not start from the sink's initial snapshot (a restored snapshot started over?)")
            }
        }
        record(RecordedStep.Committed(machineInput, decision))
    }

    override fun onIgnored(input: InputAttribution, machineInput: MachineInput<A>, reason: IgnoreReason) = record(RecordedStep.Ignored(machineInput, reason))

    override fun onFailed(input: InputAttribution, machineInput: MachineInput<A>, failure: FailureDescriptor) = record(RecordedStep.Failed(machineInput, failure))

    /** Finishes the active segment with the end frame and stops the writer. Steps after this are dropped. */
    suspend fun close() {
        locked { closed = true }
        queue.close()
        writer.join()
    }

    /** The next step begins a segment: a group's cut is a segment boundary in every file. */
    internal fun cut() {
        locked { beginSegment = true }
    }

    private fun record(step: RecordedStep<C, A, CMD, E>) {
        val item = locked {
            if (closed) return
            val before = current
            current = current.carriedPast(step)
            recorded++
            Item(step, nextStep++, before, beginSegment).also { beginSegment = false }
        }
        if (queue.trySend(item).isFailure) {
            locked {
                dropped++
                // The next step that fits begins a segment with the checkpoint that includes this one.
                beginSegment = true
            }
        }
    }

    private fun write(item: Item<C, A, CMD, E>) {
        val frame = RecordingFileFormat.stepFrame(codec.encodeStep(item.step))
        if (item.beginSegment || (segmentSteps > 0 && segmentBytes + frame.size > config.maxSegmentBytes) || segmentWriter is SegmentWriter.Detached) {
            finish()
            open(item)
            retain()
        }
        val active = segmentWriter
        check(active is SegmentWriter.Active)
        val output = active.output
        output.write(frame)
        segmentBytes += frame.size
        segmentSteps++
        written++
        if (++unflushed >= config.flushEveryFrames) {
            output.flush()
            unflushed = 0
        }
    }

    private fun open(item: Item<C, A, CMD, E>) {
        segmentIndex = if (segmentIndex < 0) nextIndex() else segmentIndex + 1
        val header = RecordingFileFormat.header(machine.id, machine.version, store, segmentIndex, item.index)
        val checkpoint = RecordingFileFormat.checkpointFrame(codec.encodeCheckpoint(item.before))
        val output = storage.append(RecordingFileFormat.segmentName(store, segmentIndex))
        segmentWriter = SegmentWriter.Active(output)
        output.write(header)
        output.write(checkpoint)
        segmentBytes = header.size + checkpoint.size
        segmentSteps = 0
        unflushed = 0
    }

    private fun nextIndex(): Int = RecordingFileFormat.namedSegments(storage, store).maxOfOrNull { it.first }?.plus(1) ?: 0

    private fun finish() {
        val active = segmentWriter
        if (active !is SegmentWriter.Active) return
        val output = active.output
        segmentWriter = SegmentWriter.Detached
        var completion: Result<Unit> = Result.success(Unit)
        try {
            output.write(Framing.END)
        } catch (t: Throwable) {
            completion = Result.failure(t)
            throw t
        } finally {
            closeOutput(output, completion)
        }
    }

    private fun retain() {
        val mine = RecordingFileFormat.namedSegments(storage, store).sortedBy { it.first }
        for ((_, name) in mine.dropLast(config.maxSegments)) storage.delete(name)
    }

    private inline fun <T : Any> locked(block: () -> T): T {
        while (!lock.tryLock()) {
            // Spin: the holder carries one step forward.
        }
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}

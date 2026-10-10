package actron.timetravel.file

import actron.core.Action
import actron.core.Event
import actron.observability.StoreInstanceId
import actron.observability.file.SegmentMark
import actron.observability.file.SegmentStorage
import actron.statechart.machine.ExecutorCheckpoint
import actron.timetravel.RecordedStep
import actron.timetravel.Recording
import actron.timetravel.RecordingCodec
import actron.timetravel.carriedPast

/**
 * What a reader of recording files found instead of, or between, steps. Never thrown.
 */
sealed interface RecordingFileMark {
    /** A segment is damaged, cut short, of a newer format or not a segment; see the mark. */
    data class Damaged(val mark: SegmentMark) : RecordingFileMark

    /** Segments [fromIndex]..[toIndex] of [store] are absent: rotated away, pruned or lost. */
    data class MissingSegments(val store: StoreInstanceId, val fromIndex: Int, val toIndex: Int) : RecordingFileMark

    /** The steps [expected] until [found] of [store] are absent: dropped by the sink or in a segment that could not be read. */
    data class StepsMissing(val store: StoreInstanceId, val expected: Int, val found: Int) : RecordingFileMark

    /** The checkpoint [segment] begins with is not where the previous segment's steps lead; the range starts over there. */
    data class StartMismatch(val store: StoreInstanceId, val segment: String, val reason: String) : RecordingFileMark
}

/**
 * What [RecordingFiles.read] found: the last continuous range of the Store's run, replayable
 * from its first segment's checkpoint, and the marks of what was missing or damaged before or
 * inside the files. A hole is never crossed (handoff §7.1): the range begins after the last one.
 *
 * A [RecordingFileContents.Readable] has an authentic replayable range; [RecordingFileContents.Unreadable] retains diagnostics
 * @property firstStep The index in the whole run of the range's first step
 * @property segments The segments the range was read from
 */
sealed class RecordingFileContents<C : Any, A : Action, CMD : Any, E : Event> {
    abstract val segments: List<String>
    abstract val marks: List<RecordingFileMark>
    /** Whether every present segment was read without a hole or damage. */
    val isComplete: Boolean get() = marks.none { it !is RecordingFileMark.Damaged || it.mark !is SegmentMark.Unfinished }

    data class Unreadable<C : Any, A : Action, CMD : Any, E : Event>(override val marks: List<RecordingFileMark>) : RecordingFileContents<C, A, CMD, E>() {
        override val segments: List<String> get() = emptyList()
    }
    data class Readable<C : Any, A : Action, CMD : Any, E : Event>(
        val recording: Recording<C, A, CMD, E>,
        val firstStep: Int,
        override val segments: List<String>,
        override val marks: List<RecordingFileMark>,
    ) : RecordingFileContents<C, A, CMD, E>()
}

/**
 * Reads the segments a [RecordingFileSink] wrote: the Stores present and, for a Store, the
 * last continuous range of its run with the marks of everything else. Reading never throws on
 * damage. [prune] bounds what every Store's and group's files take together.
 */
class RecordingFiles(private val storage: SegmentStorage) {
    /** The Stores that have segments. */
    fun stores(): List<StoreInstanceId> = buildList { for (info in storage.list()) RecordingFileFormat.parseSegmentName(info.name) { id, _ -> add(id) } }.distinct()

    /**
     * The last continuous range of [store]'s run, decoded with [codec]. Segments are read in
     * index order; a missing segment, a jump in the step index, a checkpoint that does not
     * continue the previous segment, or damage inside a segment ends the range, and the next
     * readable segment begins a new one from its own checkpoint.
     */
    fun <C : Any, A : Action, CMD : Any, E : Event> read(store: StoreInstanceId, codec: RecordingCodec<C, A, CMD, E>): RecordingFileContents<C, A, CMD, E> {
        val marks = mutableListOf<RecordingFileMark>()
        val segments = RecordingFileFormat.namedSegments(storage, store).sortedBy { it.first }
        // Keep the genuine sequence of continuous ranges; damage prevents extending its last run.
        val runs = mutableListOf<Run<C, A, CMD, E>>()
        var canContinue = false
        // A Store's segments begin at index 0: what is absent before the first present one rotated away.
        var expectedIndex = 0
        for ((index, name) in segments) {
            if (index > expectedIndex) {
                marks += RecordingFileMark.MissingSegments(store, expectedIndex, index - 1)
                canContinue = false
            }
            expectedIndex = index + 1
            val decoded = RecordingFileFormat.decodeSegment(name, storage.read(name), codec)
            if (decoded !is DecodedRecordingSegment.Readable) {
                decoded.ending.withIssue { marks += RecordingFileMark.Damaged(it) }
                canContinue = false
                continue
            }
            val header = decoded.header
            val start = decoded.start
            if (canContinue) {
                val current = runs.last()
                if (header.firstStep != current.nextStep) {
                    marks += RecordingFileMark.StepsMissing(store, current.nextStep, header.firstStep)
                    canContinue = false
                } else if (start.snapshot != current.carried.snapshot) {
                    marks += RecordingFileMark.StartMismatch(store, name, "begins at revision ${start.snapshot.revision}, the previous segment led to ${current.carried.snapshot.revision}")
                    canContinue = false
                }
            }
            if (!canContinue) {
                runs += Run(header.firstStep, start, header.definition, header.version, mutableListOf(), start, mutableListOf())
                canContinue = true
            }
            val range = runs.last()
            for (step in decoded.steps) {
                range.steps += step
                range.carried = range.carried.carriedPast(step)
            }
            range.nextStep = header.firstStep + decoded.steps.size
            range.segments += name
            decoded.ending.withIssue { mark ->
                marks += RecordingFileMark.Damaged(mark)
                // Steps after the damage are gone; the next segment cannot continue this range.
                if (mark !is SegmentMark.Unfinished) canContinue = false
            }
        }
        if (runs.isEmpty()) return RecordingFileContents.Unreadable(marks)
        val last = runs.last()
        return RecordingFileContents.Readable(Recording(last.definition, last.version, last.start, last.steps.toList()), last.firstStep, last.segments.toList(), marks)
    }

    /**
     * Deletes the oldest segments of every Store and group in the storage, by modification time,
     * until the total is within [maxTotalBytes]; the newest segment is never deleted. What
     * remains of a Store still begins with a checkpoint, and a reader marks what is missing
     * before it, as after a ring's rotation. Returns the names deleted, oldest first.
     */
    fun prune(maxTotalBytes: Long): List<String> {
        val segments = buildList {
            for (info in storage.list()) {
                val recording = RecordingFileFormat.parseSegmentName(info.name) { _, index -> add(Triple(info, info.modified, index)) }
                if (!recording) GroupRecordingFileFormat.parseSegmentName(info.name) { _, index -> add(Triple(info, info.modified, index)) }
            }
        }.sortedWith(compareBy({ it.second }, { it.third }, { it.first.name }))
        var total = segments.sumOf { it.first.size }
        val deleted = mutableListOf<String>()
        for ((info, _, _) in segments.dropLast(1)) {
            if (total <= maxTotalBytes) break
            storage.delete(info.name)
            total -= info.size
            deleted += info.name
        }
        return deleted
    }

    private class Run<C : Any, A : Action, CMD : Any, E : Event>(
        val firstStep: Int,
        val start: ExecutorCheckpoint<C, CMD>,
        val definition: actron.statechart.machine.DefinitionId,
        val version: actron.statechart.machine.DefinitionVersion,
        val steps: MutableList<RecordedStep<C, A, CMD, E>>,
        var carried: ExecutorCheckpoint<C, CMD>,
        val segments: MutableList<String>,
    ) {
        var nextStep: Int = firstStep
    }
}

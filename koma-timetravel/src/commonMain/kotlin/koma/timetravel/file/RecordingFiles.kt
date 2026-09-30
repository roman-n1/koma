package koma.timetravel.file

import koma.core.Action
import koma.core.Event
import koma.core.ExperimentalKomaApi
import koma.observability.StoreInstanceId
import koma.observability.file.SegmentMark
import koma.observability.file.SegmentStorage
import koma.statechart.machine.ExecutorCheckpoint
import koma.timetravel.RecordedStep
import koma.timetravel.Recording
import koma.timetravel.RecordingCodec
import koma.timetravel.carriedPast

/**
 * What a reader of recording files found instead of, or between, steps. Never thrown.
 */
@ExperimentalKomaApi
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
 * @property recording The replayable range, or `null` when no segment could be read
 * @property firstStep The index in the whole run of the range's first step
 * @property segments The segments the range was read from
 */
@ExperimentalKomaApi
data class RecordingFileContents<C, A : Action, CMD, E : Event>(
    val recording: Recording<C, A, CMD, E>?,
    val firstStep: Int?,
    val segments: List<String>,
    val marks: List<RecordingFileMark>,
) {
    /** Whether the whole run from its first segment present was read, nothing missing or damaged. */
    val isComplete: Boolean get() = marks.none { it !is RecordingFileMark.Damaged || it.mark !is SegmentMark.Unfinished }
}

/**
 * Reads the segments a [RecordingFileSink] wrote: the Stores present and, for a Store, the
 * last continuous range of its run with the marks of everything else. Reading never throws on
 * damage. [prune] bounds what every Store's and group's files take together.
 */
@ExperimentalKomaApi
class RecordingFiles(private val storage: SegmentStorage) {
    /** The Stores that have segments. */
    fun stores(): List<StoreInstanceId> = storage.list().mapNotNull { RecordingFileFormat.parseSegmentName(it.name)?.first }.distinct()

    /**
     * The last continuous range of [store]'s run, decoded with [codec]. Segments are read in
     * index order; a missing segment, a jump in the step index, a checkpoint that does not
     * continue the previous segment, or damage inside a segment ends the range, and the next
     * readable segment begins a new one from its own checkpoint.
     */
    fun <C, A : Action, CMD, E : Event> read(store: StoreInstanceId, codec: RecordingCodec<C, A, CMD, E>): RecordingFileContents<C, A, CMD, E> {
        val marks = mutableListOf<RecordingFileMark>()
        val segments = storage.list().mapNotNull { info -> RecordingFileFormat.parseSegmentName(info.name)?.takeIf { it.first == store }?.let { it.second to info.name } }.sortedBy { it.first }
        // `run` is the range the next segment may continue; `latest` the last range read, which
        // is what remains when damage ends a range at the last segment.
        var run: Run<C, A, CMD, E>? = null
        var latest: Run<C, A, CMD, E>? = null
        // A Store's segments begin at index 0: what is absent before the first present one rotated away.
        var expectedIndex = 0
        for ((index, name) in segments) {
            if (index > expectedIndex) {
                marks += RecordingFileMark.MissingSegments(store, expectedIndex, index - 1)
                run = null
            }
            expectedIndex = index + 1
            val decoded = RecordingFileFormat.decodeSegment(name, storage.read(name), codec)
            val header = decoded.header
            val start = decoded.start
            if (header == null || start == null) {
                decoded.mark?.let { marks += RecordingFileMark.Damaged(it) }
                run = null
                continue
            }
            val current = run
            if (current != null) {
                if (header.firstStep != current.nextStep) {
                    marks += RecordingFileMark.StepsMissing(store, current.nextStep, header.firstStep)
                    run = null
                } else if (start.snapshot != current.carried.snapshot) {
                    marks += RecordingFileMark.StartMismatch(store, name, "begins at revision ${start.snapshot.revision}, the previous segment led to ${current.carried.snapshot.revision}")
                    run = null
                }
            }
            val range = run ?: Run<C, A, CMD, E>(header.firstStep, start, header.definition, header.version, mutableListOf<RecordedStep<C, A, CMD, E>>(), start, mutableListOf<String>()).also {
                run = it
                latest = it
            }
            for (step in decoded.steps) {
                range.steps += step
                range.carried = range.carried.carriedPast(step)
            }
            range.nextStep = header.firstStep + decoded.steps.size
            range.segments += name
            val mark = decoded.mark
            if (mark != null) {
                marks += RecordingFileMark.Damaged(mark)
                // Steps after the damage are gone; the next segment cannot continue this range.
                if (mark !is SegmentMark.Unfinished) run = null
            }
        }
        val last = latest ?: return RecordingFileContents(null, null, emptyList(), marks)
        return RecordingFileContents(Recording(last.definition, last.version, last.start, last.steps.toList()), last.firstStep, last.segments.toList(), marks)
    }

    /**
     * Deletes the oldest segments of every Store and group in the storage, by modification time,
     * until the total is within [maxTotalBytes]; the newest segment is never deleted. What
     * remains of a Store still begins with a checkpoint, and a reader marks what is missing
     * before it, as after a ring's rotation. Returns the names deleted, oldest first.
     */
    fun prune(maxTotalBytes: Long): List<String> {
        val segments = storage.list().mapNotNull { info ->
            val index = RecordingFileFormat.parseSegmentName(info.name)?.second ?: GroupRecordingFileFormat.parseSegmentName(info.name)?.second ?: return@mapNotNull null
            Triple(info, info.modified, index)
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

    private class Run<C, A : Action, CMD, E : Event>(
        val firstStep: Int,
        val start: ExecutorCheckpoint<C, CMD>,
        val definition: koma.statechart.machine.DefinitionId,
        val version: koma.statechart.machine.DefinitionVersion,
        val steps: MutableList<RecordedStep<C, A, CMD, E>>,
        var carried: ExecutorCheckpoint<C, CMD>,
        val segments: MutableList<String>,
    ) {
        var nextStep: Int = firstStep
    }
}

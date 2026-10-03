package koma.timetravel.file

import koma.core.Action
import koma.core.Event
import koma.observability.StoreInstanceId
import koma.observability.file.Framing
import koma.observability.file.SegmentMark
import koma.statechart.machine.DefinitionId
import koma.statechart.machine.DefinitionVersion
import koma.statechart.machine.ExecutorCheckpoint
import koma.timetravel.RECORDING_FORMAT_VERSION
import koma.timetravel.RecordedStep
import koma.timetravel.RecordingCodec
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Version of the segment layout of a recording file. Bumped when the header or the framing
 * changes; the JSON of checkpoints and steps has its own [RECORDING_FORMAT_VERSION], written in
 * every header. A reader that meets a newer version of either refuses the segment.
 */
const val RECORDING_FILE_FORMAT_VERSION: Int = 1

/**
 * The layout of a segment of a recording file (handoff §7.1, §8): `KOMARECD`, a header frame,
 * a checkpoint frame, step frames, and the end frame once finished, framed by [Framing] like
 * the journal's segments. Every segment begins with the executor's checkpoint before its first
 * step, so a ring that dropped the oldest segments still begins at a full checkpoint and the
 * remaining range replays; the header's `firstStep` is the step's index in the whole run, so a
 * reader tells a continuous range from one with a hole. Checkpoints and steps are the
 * [RecordingCodec]'s JSON, one per frame, with a tag byte in front.
 */
object RecordingFileFormat {
    /** The first eight bytes of every segment. */
    val MAGIC: ByteArray = "KOMARECD".encodeToByteArray()

    /** The extension of segment names. */
    const val EXTENSION: String = ".recording"

    private const val TAG_HEADER = 1
    private const val TAG_CHECKPOINT = 2
    private const val TAG_STEP = 3

    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = false
    }

    /** `<store>-<index, six digits>.recording`. */
    fun segmentName(store: StoreInstanceId, index: Int): String {
        require(index >= 0) { "[Koma] A segment index is not negative" }
        return "${store.value}-${index.toString().padStart(6, '0')}$EXTENSION"
    }

    /** The store and index of a segment name, or `null` for a name that is not one. */
    fun parseSegmentName(name: String): Pair<StoreInstanceId, Int>? {
        if (!name.endsWith(EXTENSION)) return null
        val stem = name.removeSuffix(EXTENSION)
        val dash = stem.lastIndexOf('-')
        if (dash <= 0) return null
        val index = stem.substring(dash + 1).takeIf { it.length == 6 }?.toIntOrNull() ?: return null
        return StoreInstanceId(stem.substring(0, dash)) to index
    }

    /** The bytes a segment begins with: the magic and the header frame. */
    fun header(definition: DefinitionId, version: DefinitionVersion, store: StoreInstanceId, index: Int, firstStep: Int): ByteArray =
        header(definition, version, store, index, firstStep, RECORDING_FILE_FORMAT_VERSION, RECORDING_FORMAT_VERSION)

    internal fun header(definition: DefinitionId, version: DefinitionVersion, store: StoreInstanceId, index: Int, firstStep: Int, fileFormatVersion: Int, recordingFormatVersion: Int): ByteArray {
        val wire = RecordingHeaderWire(fileFormatVersion, recordingFormatVersion, definition.value, version.value, store.value, index, firstStep)
        return MAGIC + Framing.frame(tagged(TAG_HEADER, json.encodeToString(RecordingHeaderWire.serializer(), wire)))
    }

    /** The frame of a checkpoint's JSON ([RecordingCodec.encodeCheckpoint]). */
    fun checkpointFrame(text: String): ByteArray = Framing.frame(tagged(TAG_CHECKPOINT, text))

    /** The frame of a step's JSON ([RecordingCodec.encodeStep]). */
    fun stepFrame(text: String): ByteArray = Framing.frame(tagged(TAG_STEP, text))

    /**
     * Reads [bytes] as the segment [name] with [codec]: the header, the checkpoint it begins
     * with, the steps that could be read, and what stopped the reading. Never throws on damage.
     */
    fun <C, A : Action, CMD, E : Event> decodeSegment(name: String, bytes: ByteArray, codec: RecordingCodec<C, A, CMD, E>): DecodedRecordingSegment<C, A, CMD, E> {
        val read = Framing.read(name, bytes, MAGIC)
        val frames = read.frames
        fun stopped(header: RecordingSegmentHeader?, start: ExecutorCheckpoint<C, CMD>?, steps: List<RecordedStep<C, A, CMD, E>>, mark: SegmentMark?) =
            DecodedRecordingSegment(header, start, steps, mark, finished = false)
        if (frames.isEmpty()) {
            val mark = when (val mark = read.mark) {
                is SegmentMark.Unfinished -> SegmentMark.TruncatedTail(name, 0, 0)
                null -> SegmentMark.Corrupt(name, MAGIC.size, 0, "ended before the header")
                else -> mark
            }
            return stopped(null, null, emptyList(), mark)
        }
        val header = try {
            val text = untag(frames[0].payload, TAG_HEADER, "header")
            json.decodeFromString(RecordingHeaderWire.serializer(), text).let {
                RecordingSegmentHeader(it.formatVersion, it.recordingFormat, DefinitionId(it.definition), DefinitionVersion(it.version), StoreInstanceId(it.store), it.index, it.firstStep)
            }
        } catch (e: SerializationException) {
            return stopped(null, null, emptyList(), SegmentMark.Corrupt(name, frames[0].offset, 0, "header: ${e.message}"))
        } catch (e: IllegalArgumentException) {
            return stopped(null, null, emptyList(), SegmentMark.Corrupt(name, frames[0].offset, 0, "header: ${e.message}"))
        }
        if (header.fileFormatVersion > RECORDING_FILE_FORMAT_VERSION || header.recordingFormatVersion > RECORDING_FORMAT_VERSION) {
            return stopped(header, null, emptyList(), SegmentMark.UnsupportedFormat(name, header.fileFormatVersion, header.recordingFormatVersion))
        }
        if (frames.size < 2) return stopped(header, null, emptyList(), (read.mark as? SegmentMark.TruncatedTail)?.copy(recordsRead = 0) ?: SegmentMark.Corrupt(name, frames[0].offset, 0, "ended before the checkpoint"))
        val start = try {
            codec.decodeCheckpoint(untag(frames[1].payload, TAG_CHECKPOINT, "checkpoint"), header.definition, header.version)
        } catch (e: IllegalArgumentException) {
            return stopped(header, null, emptyList(), SegmentMark.Corrupt(name, frames[1].offset, 0, "checkpoint: ${e.message}"))
        }
        val steps = mutableListOf<RecordedStep<C, A, CMD, E>>()
        for (frame in frames.drop(2)) {
            val step = try {
                codec.decodeStep(untag(frame.payload, TAG_STEP, "step"), header.definition, header.version)
            } catch (e: IllegalArgumentException) {
                return stopped(header, start, steps, SegmentMark.Corrupt(name, frame.offset, steps.size, "step ${steps.size}: ${e.message}"))
            }
            steps += step
        }
        // The framing counted the header and the checkpoint among its frames; steps do not.
        val mark = when (val mark = read.mark) {
            is SegmentMark.TruncatedTail -> mark.copy(recordsRead = steps.size)
            is SegmentMark.Unfinished -> mark.copy(recordsRead = steps.size)
            is SegmentMark.Corrupt -> mark.copy(recordsRead = steps.size)
            else -> mark
        }
        return DecodedRecordingSegment(header, start, steps, mark, read.finished)
    }

    private fun tagged(tag: Int, text: String): ByteArray = byteArrayOf(tag.toByte()) + text.encodeToByteArray()

    private fun untag(payload: ByteArray, tag: Int, what: String): String {
        require(payload.isNotEmpty() && payload[0].toInt() == tag) { "not a $what frame (tag ${payload.firstOrNull()})" }
        return payload.decodeToString(1)
    }
}

@Serializable
internal class RecordingHeaderWire(val formatVersion: Int, val recordingFormat: Int, val definition: String, val version: String, val store: String, val index: Int, val firstStep: Int)

/** What the header of a recording segment says; [firstStep] is the index in the whole run of the segment's first step. */
data class RecordingSegmentHeader(
    val fileFormatVersion: Int,
    val recordingFormatVersion: Int,
    val definition: DefinitionId,
    val version: DefinitionVersion,
    val store: StoreInstanceId,
    val index: Int,
    val firstStep: Int,
)

/**
 * A recording segment as read: the header and the checkpoint it begins with (when readable),
 * the steps read in order, the mark of what stopped the reading, and whether the end frame
 * was there.
 */
data class DecodedRecordingSegment<C, A : Action, CMD, E : Event>(
    val header: RecordingSegmentHeader?,
    val start: ExecutorCheckpoint<C, CMD>?,
    val steps: List<RecordedStep<C, A, CMD, E>>,
    val mark: SegmentMark?,
    val finished: Boolean,
)

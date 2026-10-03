package koma.observability.file

import koma.core.InputId
import koma.observability.ActivationRef
import koma.observability.Capability
import koma.observability.CommandRef
import koma.observability.DiscardDescriptor
import koma.observability.DiscardKind
import koma.observability.ExecutionMode
import koma.observability.FailureDescriptor
import koma.observability.GroupSeq
import koma.observability.InputDescriptor
import koma.observability.JOURNAL_FORMAT_VERSION
import koma.observability.JournalEntry
import koma.observability.JournalRecord
import koma.observability.MachineGroupId
import koma.observability.MessageRef
import koma.observability.OutcomeDescriptor
import koma.observability.OutcomeKind
import koma.observability.Payload
import koma.observability.RuntimeSessionId
import koma.observability.StoreInstanceId
import koma.observability.StoreSeq
import koma.observability.TimerRef
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Version of the segment layout below. Bumped when the framing, the header or an encoding
 * changes; the record model has its own [JOURNAL_FORMAT_VERSION], written in every header. A
 * reader that meets a newer version of either refuses the segment, never guesses.
 */
const val JOURNAL_FILE_FORMAT_VERSION: Int = 1

/**
 * The binary layout of a journal segment, and the encoding of its records.
 *
 * A segment is `KOMAJRNL`, a header frame, record frames, and, once the segment is finished, an
 * end frame. Every frame is `[i32 length][i32 crc32][payload]` with the CRC-32 of the payload,
 * so a partial write (a crash) and a damaged byte are told apart from a record; the end frame
 * has length 0, so a segment without it was still being written. The header carries this
 * version, [JOURNAL_FORMAT_VERSION], the session, the group, the mode and the segment's index;
 * a record frame carries the store, the sequence numbers, the elapsed time and the entry.
 *
 * Payloads are written as what the policy kept: a projection's label and fields, `omitted`,
 * `unavailable`, or, for a retained object, its `toString()`, which reads back as
 * [Payload.Described]; a file holds no objects. Failures keep type, message, cause and
 * suppressed. Integers are big-endian, strings a byte length and UTF-8, durations whole
 * nanoseconds, enumerations their names (the wire vocabulary).
 */
object JournalFileFormat {
    /** The first eight bytes of every segment. */
    val MAGIC: ByteArray = "KOMAJRNL".encodeToByteArray()

    /** The end frame: a length of zero and the CRC of nothing; see [Framing.END]. */
    val END: ByteArray get() = Framing.END

    /** A frame longer than this is corruption, whatever its checksum says; see [Framing.MAX_FRAME_BYTES]. */
    const val MAX_FRAME_BYTES: Int = Framing.MAX_FRAME_BYTES

    /** The extension of segment names. */
    const val EXTENSION: String = ".journal"

    private const val HEADER_TAG = 0
    private const val PAYLOAD_OMITTED = 0
    private const val PAYLOAD_UNAVAILABLE = 1
    private const val PAYLOAD_PROJECTED = 2
    private const val PAYLOAD_DESCRIBED = 3

    /** `<session>-<index, six digits>.journal`. */
    fun segmentName(session: RuntimeSessionId, index: Int): String {
        require(index >= 0) { "[Koma] A segment index is not negative" }
        return "${session.value}-${index.toString().padStart(6, '0')}$EXTENSION"
    }

    /** The session and index of a segment name, or `null` for a name that is not one. */
    fun parseSegmentName(name: String): Pair<RuntimeSessionId, Int>? {
        if (!name.endsWith(EXTENSION)) return null
        val stem = name.removeSuffix(EXTENSION)
        val dash = stem.lastIndexOf('-')
        if (dash <= 0) return null
        val index = stem.substring(dash + 1).takeIf { it.length == 6 }?.toIntOrNull() ?: return null
        return RuntimeSessionId(stem.substring(0, dash)) to index
    }

    /** The bytes a segment begins with: the magic and the header frame. */
    fun header(session: RuntimeSessionId, group: MachineGroupId, mode: ExecutionMode, index: Int): ByteArray =
        header(session, group, mode, index, JOURNAL_FILE_FORMAT_VERSION, JOURNAL_FORMAT_VERSION)

    // With explicit versions, so tests can write what a newer writer would.
    internal fun header(session: RuntimeSessionId, group: MachineGroupId, mode: ExecutionMode, index: Int, fileFormatVersion: Int = JOURNAL_FILE_FORMAT_VERSION, recordFormatVersion: Int = JOURNAL_FORMAT_VERSION): ByteArray {
        val payload = ByteWriter(64).apply {
            u8(HEADER_TAG)
            i32(fileFormatVersion)
            i32(recordFormatVersion)
            string(session.value)
            string(group.value)
            string(mode.name)
            i32(index)
        }.toByteArray()
        return ByteWriter(MAGIC.size + 8 + payload.size).apply {
            raw(MAGIC)
            raw(Framing.frame(payload))
        }.toByteArray()
    }

    /** The frame of [record]: its store, sequence numbers, elapsed time and entry, framed. */
    fun frame(record: JournalRecord<*, *, *>): ByteArray = Framing.frame(encodeRecord(record, JOURNAL_FORMAT_VERSION))

    // What a writer of [recordFormatVersion] wrote, so tests can write what an earlier one did.
    internal fun frame(record: JournalRecord<*, *, *>, recordFormatVersion: Int): ByteArray = Framing.frame(encodeRecord(record, recordFormatVersion))

    /**
     * Reads [bytes] as the segment [name]: the header, the records that can be read, and what
     * stopped the reading, if anything. Never throws on damaged bytes: the records returned are
     * exactly the frames whose checksum and encoding held, in order, and the mark says why the
     * rest was not read.
     */
    fun decodeSegment(name: String, bytes: ByteArray): DecodedSegment {
        val read = Framing.read(name, bytes, MAGIC)
        val frames = read.frames
        if (frames.isEmpty()) {
            // Cut right after the magic: the header was never written, nothing was dropped.
            val mark = when (val mark = read.mark) {
                is SegmentMark.Unfinished -> SegmentMark.TruncatedTail(name, 0, 0)
                null -> SegmentMark.Corrupt(name, MAGIC.size, 0, "ended before the header")
                else -> mark
            }
            return DecodedSegment(null, emptyList(), mark, finished = false)
        }
        val header = try {
            decodeHeader(ByteReader(frames[0].payload))
        } catch (e: ByteReader.Malformed) {
            return DecodedSegment(null, emptyList(), SegmentMark.Corrupt(name, frames[0].offset, 0, "header: ${e.message}"), finished = false)
        }
        if (header.fileFormatVersion > JOURNAL_FILE_FORMAT_VERSION || header.recordFormatVersion > JOURNAL_FORMAT_VERSION) {
            return DecodedSegment(header, emptyList(), SegmentMark.UnsupportedFormat(name, header.fileFormatVersion, header.recordFormatVersion), finished = false)
        }
        val records = mutableListOf<JournalRecord<Nothing, Nothing, Nothing>>()
        for (frame in frames.drop(1)) {
            val record = try {
                decodeRecord(ByteReader(frame.payload), header)
            } catch (e: ByteReader.Malformed) {
                return DecodedSegment(header, records, SegmentMark.Corrupt(name, frame.offset, records.size, "record: ${e.message}"), finished = false)
            }
            records += record
        }
        // The framing counted the header among the frames it read; records do not include it.
        val mark = when (val mark = read.mark) {
            is SegmentMark.TruncatedTail -> mark.copy(recordsRead = records.size)
            is SegmentMark.Unfinished -> mark.copy(recordsRead = records.size)
            is SegmentMark.Corrupt -> mark.copy(recordsRead = records.size)
            else -> mark
        }
        return DecodedSegment(header, records, mark, read.finished)
    }

    // --- header ---

    private fun decodeHeader(reader: ByteReader): SegmentHeader {
        if (reader.u8() != HEADER_TAG) throw ByteReader.Malformed("not a header")
        val fileFormatVersion = reader.i32()
        val recordFormatVersion = reader.i32()
        val session = reader.string()
        val group = reader.string()
        val mode = reader.string()
        val index = reader.i32()
        reader.expectEnd()
        return SegmentHeader(
            fileFormatVersion,
            recordFormatVersion,
            RuntimeSessionId(session),
            MachineGroupId(group),
            enumNamed<ExecutionMode>(mode),
            index,
        )
    }

    // --- records ---

    private fun encodeRecord(record: JournalRecord<*, *, *>, recordFormatVersion: Int): ByteArray = ByteWriter().apply {
        u8(tagOf(record.entry))
        nullable(record.store) { string(it.value) }
        i64(record.groupSeq.value)
        nullable(record.storeSeq) { i64(it.value) }
        duration(record.elapsed)
        entry(record.entry, recordFormatVersion)
    }.toByteArray()

    private fun decodeRecord(reader: ByteReader, header: SegmentHeader): JournalRecord<Nothing, Nothing, Nothing> {
        val tag = reader.u8()
        val store = reader.nullable { StoreInstanceId(reader.string()) }
        val groupSeq = GroupSeq(reader.i64())
        val storeSeq = reader.nullable { StoreSeq(reader.i64()) }
        val elapsed = reader.duration()
        val entry = reader.entry(tag, header.recordFormatVersion)
        reader.expectEnd()
        return JournalRecord(header.recordFormatVersion, header.session, header.group, store, header.mode, groupSeq, storeSeq, elapsed, entry)
    }

    private fun tagOf(entry: JournalEntry<*, *, *>): Int = when (entry) {
        is JournalEntry.StoreRegistered -> 1
        is JournalEntry.InputAccepted -> 2
        is JournalEntry.InputDiscarded -> 3
        is JournalEntry.ProcessingStarted -> 4
        is JournalEntry.StateCommitted -> 5
        is JournalEntry.EventEmitted -> 6
        is JournalEntry.FailureReported -> 7
        is JournalEntry.ProcessingFinished -> 8
        JournalEntry.StoreClosed -> 9
        is JournalEntry.InputRejected -> 10
        is JournalEntry.DecisionCommitted -> 11
        is JournalEntry.DecisionIgnored -> 12
        is JournalEntry.JournalGap -> 13
        JournalEntry.RecordingStopped -> 14
        is JournalEntry.BridgeSent -> 15
        is JournalEntry.BridgeReceived -> 16
        is JournalEntry.EffectQueued -> 17
        is JournalEntry.EffectHandlingStarted -> 18
        is JournalEntry.EffectAcknowledged -> 19
        is JournalEntry.EffectDiscarded -> 20
        is JournalEntry.ExternalReceived -> 21
        is JournalEntry.CheckpointCreated -> 22
        is JournalEntry.CommandsAbandoned -> 23
        is JournalEntry.BridgeDropped -> 24
    }

    // [recordFormatVersion] decides the fields a variant has: a field added in a later version is
    // written only from that version on, and read only from a segment of that version on.
    private fun ByteWriter.entry(entry: JournalEntry<*, *, *>, recordFormatVersion: Int) {
        when (entry) {
            is JournalEntry.StoreRegistered -> string(entry.capability.name)
            is JournalEntry.InputAccepted -> {
                i64(entry.input.value)
                input(entry.kind)
            }
            is JournalEntry.InputDiscarded -> {
                i64(entry.input.value)
                string(entry.reason.kind.name)
                nullable(entry.reason.failure) { failure(it) }
            }
            is JournalEntry.ProcessingStarted -> {
                i64(entry.input.value)
                i64(entry.ordinal)
            }
            is JournalEntry.StateCommitted -> {
                i64(entry.input.value)
                i64(entry.revision)
                payload(entry.previous)
                payload(entry.state)
            }
            is JournalEntry.EventEmitted -> {
                nullable(entry.input) { i64(it.value) }
                payload(entry.event)
            }
            is JournalEntry.FailureReported -> {
                nullable(entry.input) { i64(it.value) }
                failure(entry.failure)
            }
            is JournalEntry.ProcessingFinished -> {
                i64(entry.input.value)
                i64(entry.ordinal)
                string(entry.outcome.kind.name)
                i32(entry.outcome.commits)
                nullable(entry.outcome.failure) { failure(it) }
                duration(entry.duration)
            }
            JournalEntry.StoreClosed -> Unit
            is JournalEntry.InputRejected -> {
                payload(entry.action)
                string(entry.reason)
            }
            is JournalEntry.DecisionCommitted -> {
                nullable(entry.input) { i64(it.value) }
                i64(entry.revision)
                list(entry.active) { string(it) }
                list(entry.transitions) { i32(it) }
                list(entry.exited) { activation(it) }
                list(entry.entered) { activation(it) }
                list(entry.commands) {
                    i64(it.id)
                    i64(it.scope)
                    nullable(it.lane) { lane -> string(lane) }
                    nullable(it.policy) { policy -> string(policy) }
                    payload(it.command)
                }
                list(entry.cancelledScopes) { i64(it) }
                list(entry.timersScheduled) {
                    i64(it.id)
                    i32(it.transition)
                    i64(it.activation)
                    duration(it.deadline)
                }
                list(entry.timersCancelled) { i64(it) }
                i32(entry.effects)
            }
            is JournalEntry.DecisionIgnored -> {
                nullable(entry.input) { i64(it.value) }
                string(entry.reason)
            }
            is JournalEntry.JournalGap -> i64(entry.dropped)
            JournalEntry.RecordingStopped -> Unit
            is JournalEntry.BridgeSent -> {
                nullable(entry.input) { i64(it.value) }
                message(entry.message)
                string(entry.to.value)
                bool(entry.delivered)
                if (recordFormatVersion >= 7) nullable(entry.cause) { message(it) }
            }
            is JournalEntry.BridgeReceived -> {
                nullable(entry.input) { i64(it.value) }
                message(entry.message)
            }
            is JournalEntry.BridgeDropped -> {
                message(entry.message)
                string(entry.to.value)
                string(entry.reason)
            }
            is JournalEntry.EffectQueued -> {
                nullable(entry.input) { i64(it.value) }
                i64(entry.effect)
                string(entry.policy)
                payload(entry.event)
            }
            is JournalEntry.EffectHandlingStarted -> {
                i64(entry.effect)
                i32(entry.attempt)
            }
            is JournalEntry.EffectAcknowledged -> i64(entry.effect)
            is JournalEntry.EffectDiscarded -> {
                i64(entry.effect)
                string(entry.reason)
            }
            is JournalEntry.ExternalReceived -> {
                nullable(entry.input) { i64(it.value) }
                string(entry.source)
            }
            is JournalEntry.CommandsAbandoned -> {
                string(entry.reason)
                list(entry.queued) { i64(it) }
                list(entry.running) { i64(it) }
            }
            is JournalEntry.CheckpointCreated -> {
                list(entry.members) { string(it.value) }
                list(entry.sources) { string(it) }
                i32(entry.inFlight)
            }
        }
    }

    private fun ByteReader.entry(tag: Int, recordFormatVersion: Int): JournalEntry<Nothing, Nothing, Nothing> = when (tag) {
        1 -> JournalEntry.StoreRegistered(enumNamed<Capability>(string()))
        2 -> JournalEntry.InputAccepted(InputId(i64()), input())
        3 -> JournalEntry.InputDiscarded(InputId(i64()), DiscardDescriptor(enumNamed<DiscardKind>(string()), nullable { failure() }))
        4 -> JournalEntry.ProcessingStarted(InputId(i64()), i64())
        5 -> JournalEntry.StateCommitted(InputId(i64()), i64(), payload(), payload())
        6 -> JournalEntry.EventEmitted(nullable { InputId(i64()) }, payload())
        7 -> JournalEntry.FailureReported(nullable { InputId(i64()) }, failure())
        8 -> JournalEntry.ProcessingFinished(InputId(i64()), i64(), OutcomeDescriptor(enumNamed<OutcomeKind>(string()), i32(), nullable { failure() }), duration())
        9 -> JournalEntry.StoreClosed
        10 -> JournalEntry.InputRejected(payload(), string())
        11 -> JournalEntry.DecisionCommitted(
            input = nullable { InputId(i64()) },
            revision = i64(),
            active = list { string() },
            transitions = list { i32() },
            exited = list { activation() },
            entered = list { activation() },
            commands = list { CommandRef(i64(), i64(), nullable { string() }, nullable { string() }, payload()) },
            cancelledScopes = list { i64() },
            timersScheduled = list { TimerRef(i64(), i32(), i64(), duration()) },
            timersCancelled = list { i64() },
            effects = i32(),
        )
        12 -> JournalEntry.DecisionIgnored(nullable { InputId(i64()) }, string())
        13 -> JournalEntry.JournalGap(i64())
        14 -> JournalEntry.RecordingStopped
        15 -> JournalEntry.BridgeSent(nullable { InputId(i64()) }, message(), StoreInstanceId(string()), bool(), if (recordFormatVersion >= 7) nullable { message() } else null)
        16 -> JournalEntry.BridgeReceived(nullable { InputId(i64()) }, message())
        17 -> JournalEntry.EffectQueued(nullable { InputId(i64()) }, i64(), string(), payload())
        18 -> JournalEntry.EffectHandlingStarted(i64(), i32())
        19 -> JournalEntry.EffectAcknowledged(i64())
        20 -> JournalEntry.EffectDiscarded(i64(), string())
        21 -> JournalEntry.ExternalReceived(nullable { InputId(i64()) }, string())
        22 -> JournalEntry.CheckpointCreated(list { StoreInstanceId(string()) }, list { string() }, i32())
        23 -> JournalEntry.CommandsAbandoned(string(), list { i64() }, list { i64() })
        24 -> JournalEntry.BridgeDropped(message(), StoreInstanceId(string()), string())
        else -> throw ByteReader.Malformed("unknown entry tag $tag")
    }

    private fun ByteWriter.message(message: MessageRef) {
        string(message.from.value)
        i64(message.effect)
    }

    private fun ByteReader.message(): MessageRef = MessageRef(StoreInstanceId(string()), i64())

    private fun ByteWriter.input(kind: InputDescriptor<*>) {
        when (kind) {
            InputDescriptor.Startup -> u8(1)
            is InputDescriptor.Dispatch -> {
                u8(2)
                payload(kind.action)
            }
            is InputDescriptor.Transaction -> {
                u8(3)
                nullable(kind.origin) { i64(it.value) }
            }
            is InputDescriptor.Recovery -> {
                u8(4)
                failure(kind.failure)
                nullable(kind.origin) { i64(it.value) }
            }
        }
    }

    private fun ByteReader.input(): InputDescriptor<Nothing> = when (val kind = u8()) {
        1 -> InputDescriptor.Startup
        2 -> InputDescriptor.Dispatch(payload())
        3 -> InputDescriptor.Transaction(nullable { InputId(i64()) })
        4 -> InputDescriptor.Recovery(failure(), nullable { InputId(i64()) })
        else -> throw ByteReader.Malformed("unknown input kind $kind")
    }

    private fun ByteWriter.payload(payload: Payload<*>) {
        when (payload) {
            Payload.Omitted -> u8(PAYLOAD_OMITTED)
            Payload.Unavailable -> u8(PAYLOAD_UNAVAILABLE)
            is Payload.Projected -> {
                u8(PAYLOAD_PROJECTED)
                string(payload.label)
                list(payload.fields.entries.toList()) {
                    string(it.key)
                    string(it.value)
                }
            }
            is Payload.Described -> {
                u8(PAYLOAD_DESCRIBED)
                string(payload.text)
            }
            is Payload.Retained -> {
                // The policy decided the object may be shown; a file keeps its text, not the object.
                u8(PAYLOAD_DESCRIBED)
                string(payload.value.toString())
            }
        }
    }

    private fun ByteReader.payload(): Payload<Nothing> = when (val kind = u8()) {
        PAYLOAD_OMITTED -> Payload.Omitted
        PAYLOAD_UNAVAILABLE -> Payload.Unavailable
        PAYLOAD_PROJECTED -> Payload.Projected(string(), list { string() to string() }.toMap())
        PAYLOAD_DESCRIBED -> Payload.Described(string())
        else -> throw ByteReader.Malformed("unknown payload kind $kind")
    }

    private fun ByteWriter.failure(failure: FailureDescriptor) {
        nullable(failure.type) { string(it) }
        nullable(failure.message) { string(it) }
        nullable(failure.cause) { failure(it) }
        list(failure.suppressed) { failure(it) }
    }

    private fun ByteReader.failure(): FailureDescriptor = FailureDescriptor(
        type = nullable { string() },
        message = nullable { string() },
        cause = nullable { failure() },
        suppressed = list { failure() },
    )

    private fun ByteWriter.activation(ref: ActivationRef) {
        string(ref.node)
        i64(ref.activation)
    }

    private fun ByteReader.activation(): ActivationRef = ActivationRef(string(), i64())

    private fun ByteWriter.duration(duration: Duration) = i64(duration.inWholeNanoseconds)

    private fun ByteReader.duration(): Duration = i64().nanoseconds

    private inline fun <reified T : Enum<T>> enumNamed(name: String): T =
        enumValues<T>().firstOrNull { it.name == name } ?: throw ByteReader.Malformed("unknown ${T::class.simpleName} '$name'")
}

/**
 * What the header of a segment says.
 */
data class SegmentHeader(
    val fileFormatVersion: Int,
    val recordFormatVersion: Int,
    val session: RuntimeSessionId,
    val group: MachineGroupId,
    val mode: ExecutionMode,
    val index: Int,
)

/**
 * A segment as read: its header (when it had a readable one), the records read in order, the
 * mark that says where the reading stopped and why, and whether the segment carried its end
 * frame. A finished segment without a mark was read whole.
 */
data class DecodedSegment(
    val header: SegmentHeader?,
    val records: List<JournalRecord<Nothing, Nothing, Nothing>>,
    val mark: SegmentMark?,
    val finished: Boolean,
)

/**
 * What a reader found instead of, or after, records. Marks are never thrown; they are part of
 * what was read, in its order.
 */
sealed interface SegmentMark {
    /** The bytes do not begin with the segment magic. */
    data class NotASegment(val segment: String, val reason: String) : SegmentMark

    /** The segment was written by a newer format or a newer record model; nothing of it is read. */
    data class UnsupportedFormat(val segment: String, val fileFormatVersion: Int, val recordFormatVersion: Int) : SegmentMark

    /**
     * A frame at [offset] failed its checksum or its encoding, or bytes follow the end frame; the
     * segment is not read past it. [recordsRead] frames before it were.
     */
    data class Corrupt(val segment: String, val offset: Int, val recordsRead: Int, val reason: String) : SegmentMark

    /**
     * The segment ends inside a frame: a write was cut short, by a crash or a full disk. The
     * [trailingBytes] of the partial frame are dropped; [recordsRead] frames before it were read.
     */
    data class TruncatedTail(val segment: String, val recordsRead: Int, val trailingBytes: Int) : SegmentMark

    /**
     * The segment ends at a frame boundary without its end frame: it was still being written, by
     * a sink that never closed. Nothing was dropped.
     */
    data class Unfinished(val segment: String, val recordsRead: Int) : SegmentMark

    /** Segments [fromIndex]..[toIndex] of [session] are absent: rotated away, pruned or lost. */
    data class MissingSegments(val session: RuntimeSessionId, val fromIndex: Int, val toIndex: Int) : SegmentMark

    /**
     * Records with the sequence numbers between [afterGroupSeq] and [nextGroupSeq] are absent
     * and no [JournalEntry.JournalGap] accounts for them: they were in a segment or a frame that
     * could not be read.
     */
    data class SequenceHole(val session: RuntimeSessionId, val afterGroupSeq: Long, val nextGroupSeq: Long) : SegmentMark {
        val missing: Long get() = nextGroupSeq - afterGroupSeq - 1
    }
}

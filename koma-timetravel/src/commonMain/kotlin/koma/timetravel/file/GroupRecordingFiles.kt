package koma.timetravel.file

import koma.core.Action
import koma.core.Event
import koma.core.InputId
import koma.observability.FailureDescriptor
import koma.observability.MachineGroupId
import koma.observability.StoreInstanceId
import koma.observability.file.Framing
import koma.observability.file.SegmentMark
import koma.observability.file.SegmentOutput
import koma.observability.file.SegmentStorage
import koma.statechart.machine.CutListener
import koma.statechart.machine.Decision
import koma.statechart.machine.DecisionObserver
import koma.statechart.machine.EffectId
import koma.statechart.machine.GroupCheckpoint
import koma.statechart.machine.IgnoreReason
import koma.statechart.machine.Machine
import koma.statechart.machine.MachineGroup
import koma.statechart.machine.MachineInput
import koma.statechart.machine.MessageId
import koma.statechart.machine.PairRole
import koma.statechart.machine.RoutePair
import koma.statechart.machine.SourceId
import koma.statechart.machine.SourceSnapshot
import koma.timetravel.GroupRecording
import koma.timetravel.GroupRoute
import koma.timetravel.GroupStep
import koma.timetravel.RecordedStep
import koma.timetravel.Recording
import koma.timetravel.RecordingCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The layout of a segment of a group's order file: `KOMAGRPO`, a header frame (the group, its
 * members, the bridge's routes, the attached sources, the index of the first entry, the
 * messages in flight before it and, when the segment begins at a cut, the cut: the members'
 * step counts and the sources' snapshots there), entry frames (which member decided which
 * step, the messages the step sent and the one it received), and the end frame. With the
 * members' recording files ([RecordingFileFormat]) it is the group container:
 * [GroupRecordingFiles] reads them back into a [GroupRecording] over the range every file
 * still covers.
 *
 * Format 2 added the cut to the header; a format 1 segment reads as one without cuts. Format 3
 * added the pair of a route; a format 2 segment reads as routes without pairs.
 */
object GroupRecordingFileFormat {
    val MAGIC: ByteArray = "KOMAGRPO".encodeToByteArray()

    const val EXTENSION: String = ".group"

    /** The version of this layout, written into every header. */
    const val VERSION: Int = 3

    private const val TAG_HEADER = 1
    private const val TAG_ENTRY = 2

    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = false
    }

    fun segmentName(group: MachineGroupId, index: Int): String = "${group.value}-${index.toString().padStart(6, '0')}$EXTENSION"

    fun parseSegmentName(name: String): Pair<MachineGroupId, Int>? {
        if (!name.endsWith(EXTENSION)) return null
        val stem = name.removeSuffix(EXTENSION)
        val dash = stem.lastIndexOf('-')
        if (dash <= 0) return null
        val index = stem.substring(dash + 1).takeIf { it.length == 6 }?.toIntOrNull() ?: return null
        return MachineGroupId(stem.substring(0, dash)) to index
    }

    fun header(header: GroupSegmentHeader): ByteArray {
        val wire = GroupHeaderWire(
            header.fileFormatVersion, header.group.value, header.members.map { it.value }, header.routes.map { RouteWire(it.from.value, it.to.value, it.pair?.name, it.pair?.role?.name) },
            header.sourceIds.map { it.value }, header.index, header.firstEntry, header.inFlight.map { MessageWireG(it.from.value, it.effect.value) },
            header.cut?.let { cut ->
                CutWire(
                    cut.counts.map { (store, count) -> store.value to count }.toMap(),
                    cut.sources.values.map { SourceSnapshotWire(it.source.value, it.kind, it.version, it.fields) },
                )
            },
        )
        return MAGIC + Framing.frame(byteArrayOf(TAG_HEADER.toByte()) + json.encodeToString(GroupHeaderWire.serializer(), wire).encodeToByteArray())
    }

    fun entryFrame(entry: GroupOrderEntry): ByteArray {
        val wire = EntryWire(entry.store.value, entry.step, entry.sent.map { MessageWireG(it.from.value, it.effect.value) }, entry.received?.let { MessageWireG(it.from.value, it.effect.value) })
        return Framing.frame(byteArrayOf(TAG_ENTRY.toByte()) + json.encodeToString(EntryWire.serializer(), wire).encodeToByteArray())
    }

    fun decodeSegment(name: String, bytes: ByteArray): DecodedGroupSegment {
        val read = Framing.read(name, bytes, MAGIC)
        val frames = read.frames
        if (frames.isEmpty()) {
            val mark = when (val mark = read.mark) {
                is SegmentMark.Unfinished -> SegmentMark.TruncatedTail(name, 0, 0)
                null -> SegmentMark.Corrupt(name, MAGIC.size, 0, "ended before the header")
                else -> mark
            }
            return DecodedGroupSegment(null, emptyList(), mark, finished = false)
        }
        val header = try {
            val payload = frames[0].payload
            require(payload.isNotEmpty() && payload[0].toInt() == TAG_HEADER) { "not a header frame" }
            val wire = json.decodeFromString(GroupHeaderWire.serializer(), payload.decodeToString(1))
            GroupSegmentHeader(
                wire.formatVersion, MachineGroupId(wire.group), wire.members.map(::StoreInstanceId), wire.routes.map { GroupRoute(StoreInstanceId(it.from), StoreInstanceId(it.to), it.pair()) },
                wire.sourceIds.map(::SourceId).toSet(), wire.index, wire.firstEntry, wire.inFlight.map { MessageId(StoreInstanceId(it.from), EffectId(it.effect)) },
                wire.cut?.let { cut ->
                    RecordedCut(
                        wire.firstEntry,
                        cut.counts.map { (store, count) -> StoreInstanceId(store) to count }.toMap(),
                        cut.sources.associate { SourceId(it.source) to SourceSnapshot(SourceId(it.source), it.kind, it.version, it.fields) },
                    )
                },
            )
        } catch (e: SerializationException) {
            return DecodedGroupSegment(null, emptyList(), SegmentMark.Corrupt(name, frames[0].offset, 0, "header: ${e.message}"), finished = false)
        } catch (e: IllegalArgumentException) {
            return DecodedGroupSegment(null, emptyList(), SegmentMark.Corrupt(name, frames[0].offset, 0, "header: ${e.message}"), finished = false)
        }
        if (header.fileFormatVersion > VERSION) return DecodedGroupSegment(header, emptyList(), SegmentMark.UnsupportedFormat(name, header.fileFormatVersion, 0), finished = false)
        val entries = mutableListOf<GroupOrderEntry>()
        for (frame in frames.drop(1)) {
            val entry = try {
                val payload = frame.payload
                require(payload.isNotEmpty() && payload[0].toInt() == TAG_ENTRY) { "not an entry frame" }
                val wire = json.decodeFromString(EntryWire.serializer(), payload.decodeToString(1))
                GroupOrderEntry(StoreInstanceId(wire.store), wire.step, wire.sent.map { MessageId(StoreInstanceId(it.from), EffectId(it.effect)) }, wire.received?.let { MessageId(StoreInstanceId(it.from), EffectId(it.effect)) })
            } catch (e: SerializationException) {
                return DecodedGroupSegment(header, entries, SegmentMark.Corrupt(name, frame.offset, entries.size, "entry: ${e.message}"), finished = false)
            } catch (e: IllegalArgumentException) {
                return DecodedGroupSegment(header, entries, SegmentMark.Corrupt(name, frame.offset, entries.size, "entry: ${e.message}"), finished = false)
            }
            entries += entry
        }
        val mark = when (val mark = read.mark) {
            is SegmentMark.TruncatedTail -> mark.copy(recordsRead = entries.size)
            is SegmentMark.Unfinished -> mark.copy(recordsRead = entries.size)
            is SegmentMark.Corrupt -> mark.copy(recordsRead = entries.size)
            else -> mark
        }
        return DecodedGroupSegment(header, entries, mark, read.finished)
    }
}

@Serializable
internal class GroupHeaderWire(
    val formatVersion: Int,
    val group: String,
    val members: List<String>,
    val routes: List<RouteWire>,
    val sourceIds: List<String>,
    val index: Int,
    val firstEntry: Int,
    val inFlight: List<MessageWireG> = emptyList(),
    val cut: CutWire? = null,
)

@Serializable
internal class CutWire(val counts: Map<String, Int>, val sources: List<SourceSnapshotWire>)

@Serializable
internal class SourceSnapshotWire(val source: String, val kind: String, val version: Int, val fields: Map<String, String>)

@Serializable
internal class RouteWire(val from: String, val to: String, val pair: String? = null, val role: String? = null) {
    fun pair(): RoutePair? = if (pair != null && role != null) RoutePair(pair, PairRole.valueOf(role)) else null
}

@Serializable
internal class MessageWireG(val from: String, val effect: Long)

@Serializable
internal class EntryWire(val store: String, val step: Int, val sent: List<MessageWireG> = emptyList(), val received: MessageWireG? = null)

/**
 * What the header of an order segment says; [firstEntry] is the index in the whole run of its
 * first entry, [inFlight] the messages sent before it and not yet received, [cut] the group's
 * cut the segment begins at, if it begins at one.
 */
data class GroupSegmentHeader(
    val fileFormatVersion: Int,
    val group: MachineGroupId,
    val members: List<StoreInstanceId>,
    val routes: List<GroupRoute>,
    val sourceIds: Set<SourceId>,
    val index: Int,
    val firstEntry: Int,
    val inFlight: List<MessageId>,
    val cut: RecordedCut? = null,
)

/**
 * A cut of the group as the files remember it: the [position] in the whole run of the first
 * entry after it, how many steps of each member came before it, and the sources' snapshots
 * there. A run since a cut knows its sources' state, as [GroupRecording.since] does in memory.
 */
data class RecordedCut(val position: Int, val counts: Map<StoreInstanceId, Int>, val sources: Map<SourceId, SourceSnapshot>)

/** One decision of the group in its order: which member, which of its steps, what it sent over the bridge and what it received. */
data class GroupOrderEntry(val store: StoreInstanceId, val step: Int, val sent: List<MessageId> = emptyList(), val received: MessageId? = null)

data class DecodedGroupSegment(val header: GroupSegmentHeader?, val entries: List<GroupOrderEntry>, val mark: SegmentMark?, val finished: Boolean)

/**
 * Records a [MachineGroup] into files as it runs: a [RecordingFileSink] per member and one
 * order file of the group, written by one writer each. [member] returns the observer to give a
 * member's store. Every order segment begins with the messages in flight before its first
 * entry, so a range that begins there checks the bridge's causality without the sends before
 * it. Every cut of the group ([MachineGroup.checkpoint]) begins a segment in every file, the
 * order segment's header carrying the cut's snapshots of the sources, so a range that begins
 * at a cut knows them; the sink registers itself as the group's [CutListener] and unregisters
 * on [close].
 *
 * @param group The group; its route history and sources are read when segments begin
 * @param id The group's name in the storage
 * @param storage Where the segments go
 * @param scope Runs the writers
 * @param config Budgets of every file
 */
class GroupRecordingFileSink(
    private val group: MachineGroup,
    private val id: MachineGroupId,
    private val storage: SegmentStorage,
    private val scope: CoroutineScope,
    private val config: RecordingFileConfig = RecordingFileConfig(),
) {
    private val lock = Mutex()
    private val sinks = linkedMapOf<StoreInstanceId, RecordingFileSink<*, *, *, *>>()
    private val counts = mutableMapOf<StoreInstanceId, Int>()
    private val pending = mutableMapOf<MessageId, Int>()
    private var nextEntry = 0
    private var beginSegment = true
    private var droppedEntries = 0L
    private var droppedCutCount = 0L
    private var closed = false

    /** An entry to write, or a cut ([entry] `null`) that begins a segment. */
    private class Item(val entry: GroupOrderEntry?, val index: Int, val inFlight: List<MessageId>, val beginSegment: Boolean, val cut: RecordedCut? = null)

    private val cutListener = CutListener { cut(it) }

    init {
        group.onCut(cutListener)
    }

    private val queue = Channel<Item>(config.queueCapacity)
    private var output: SegmentOutput? = null
    private var segmentIndex = -1
    private var segmentBytes = 0
    private var segmentEntries = 0
    private var unflushed = 0
    private var failed = false

    private val writer: Job = scope.launch {
        var failure: Throwable? = null
        try {
            for (item in queue) {
                if (failed) continue
                try {
                    write(item)
                } catch (e: Exception) {
                    if (e is CancellationException && !currentCoroutineContext().isActive) throw e
                    failed = true
                    release(e)
                    notifyFailure(e)
                }
            }
            if (!failed) finish()
        } catch (t: Throwable) {
            failure = t
            if (t is CancellationException || t !is Exception) throw t
            notifyFailure(t)
        } finally {
            // Cancellation leaves an unfinished segment; never append END to a failed frame.
            try {
                release(failure)
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

    private fun release(failure: Throwable?) {
        val output = output ?: return
        this.output = null
        closeOutput(output, failure)
    }

    private fun closeOutput(output: SegmentOutput, failure: Throwable?) {
        try {
            output.close()
        } catch (t: Throwable) {
            if (failure == null) throw t
            if (failure !== t) failure.addSuppressed(t)
        }
    }

    /** The members' sinks, by id. */
    val members: Map<StoreInstanceId, RecordingFileSink<*, *, *, *>> get() = locked { sinks.toMap() }

    /** Order entries the writer had no room for. */
    val dropped: Long get() = locked { droppedEntries }

    /** Cuts the writer had no room for: the files then have no boundary there, nothing else is lost. */
    val droppedCuts: Long get() = locked { droppedCutCount }

    /**
     * The observer for the store of [memberId]: its steps go to the member's own recording file
     * and to the group's order.
     */
    fun <C, A : Action, CMD, E : Event> member(memberId: StoreInstanceId, machine: Machine<C, A, CMD, E>, context: C, codec: RecordingCodec<C, A, CMD, E>): DecisionObserver<C, A, CMD, E> {
        val sink = locked {
            check(!closed) { "[Koma] This group sink is closed" }
            require(memberId !in sinks) { "[Koma] $memberId is already recorded by this group sink" }
            RecordingFileSink(memberId, machine, context, codec, storage, scope, config).also { sinks[memberId] = it }
        }
        return object : DecisionObserver<C, A, CMD, E> {
            override fun onCommitted(input: InputId?, machineInput: MachineInput<A>, decision: Decision<C, CMD, E>) {
                sink.onCommitted(input, machineInput, decision)
                val routes = group.routes.filter { it.from == memberId }
                val sent = if (routes.isEmpty()) emptyList() else decision.effects.flatMap { effect -> routes.mapNotNull { route -> route.mapUnchecked(effect.event)?.let { MessageId(memberId, effect.id) to route.to } } }
                order(memberId, (machineInput as? MachineInput.BridgeReceived)?.message, sent)
            }

            override fun onIgnored(input: InputId?, machineInput: MachineInput<A>, reason: IgnoreReason) {
                sink.onIgnored(input, machineInput, reason)
                order(memberId, (machineInput as? MachineInput.BridgeReceived)?.message, emptyList())
            }

            override fun onFailed(input: InputId?, machineInput: MachineInput<A>, failure: FailureDescriptor) {
                sink.onFailed(input, machineInput, failure)
                order(memberId, (machineInput as? MachineInput.BridgeReceived)?.message, emptyList())
            }
        }
    }

    /** Finishes every file with its end frame and stops the writers. */
    suspend fun close() {
        val members = locked {
            closed = true
            sinks.values.toList()
        }
        group.removeCutListener(cutListener)
        queue.close()
        writer.join()
        for (sink in members) sink.close()
    }

    private fun order(memberId: StoreInstanceId, received: MessageId?, sent: List<Pair<MessageId, StoreInstanceId>>) {
        val item = locked {
            if (closed) return
            val step = counts[memberId] ?: 0
            counts[memberId] = step + 1
            received?.let { message -> pending[message]?.let { count -> if (count <= 1) pending.remove(message) else pending[message] = count - 1 } }
            val inFlight = pending.keys.toList()
            for ((message, _) in sent) pending[message] = (pending[message] ?: 0) + 1
            Item(GroupOrderEntry(memberId, step, sent.map { it.first }.distinct(), received), nextEntry++, inFlight, beginSegment).also { beginSegment = false }
        }
        if (queue.trySend(item).isFailure) locked {
            droppedEntries++
            beginSegment = true
        }
    }

    // Inside the group's cut: the members are frozen and idle, so the counts are exactly the cut's.
    private fun cut(checkpoint: GroupCheckpoint) {
        val item = locked {
            if (closed) return
            val members = sinks.keys.associateWith { counts[it] ?: 0 }
            for (sink in sinks.values) sink.cut()
            beginSegment = false
            Item(null, nextEntry, pending.keys.toList(), beginSegment = true, cut = RecordedCut(nextEntry, members, checkpoint.sources))
        }
        if (queue.trySend(item).isFailure) locked { droppedCutCount++ }
    }

    private fun write(item: Item) {
        val entry = item.entry
        if (entry == null) {
            // A cut: the segment it begins carries it; the entries follow.
            finish()
            open(item)
            retain()
            return
        }
        val frame = GroupRecordingFileFormat.entryFrame(entry)
        if (item.beginSegment || output == null || (segmentEntries > 0 && segmentBytes + frame.size > config.maxSegmentBytes)) {
            finish()
            open(item)
            retain()
        }
        val output = checkNotNull(output)
        output.write(frame)
        segmentBytes += frame.size
        segmentEntries++
        if (++unflushed >= config.flushEveryFrames) {
            output.flush()
            unflushed = 0
        }
    }

    private fun open(item: Item) {
        segmentIndex = if (segmentIndex < 0) nextIndex() else segmentIndex + 1
        val header = GroupRecordingFileFormat.header(
            GroupSegmentHeader(GroupRecordingFileFormat.VERSION, id, locked { sinks.keys.toList() }, group.routeHistory.map { GroupRoute(it.from, it.to, it.pair) }, group.sourceIds.toSet(), segmentIndex, item.index, item.inFlight, item.cut),
        )
        val output = storage.append(GroupRecordingFileFormat.segmentName(id, segmentIndex))
        this.output = output
        output.write(header)
        segmentBytes = header.size
        segmentEntries = 0
        unflushed = 0
    }

    private fun nextIndex(): Int = storage.list().mapNotNull { GroupRecordingFileFormat.parseSegmentName(it.name) }.filter { it.first == id }.maxOfOrNull { it.second }?.plus(1) ?: 0

    private fun finish() {
        val output = output ?: return
        this.output = null
        var failure: Throwable? = null
        try {
            output.write(Framing.END)
        } catch (t: Throwable) {
            failure = t
            throw t
        } finally {
            closeOutput(output, failure)
        }
    }

    private fun retain() {
        val mine = storage.list().mapNotNull { info -> GroupRecordingFileFormat.parseSegmentName(info.name)?.takeIf { it.first == id }?.let { it.second to info.name } }.sortedBy { it.first }
        for ((_, name) in mine.dropLast(config.maxSegments)) storage.delete(name)
    }

    private inline fun <T> locked(block: () -> T): T {
        while (!lock.tryLock()) {
            // Spin: the holder assigns one entry.
        }
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}

private fun MachineGroup.Route.mapUnchecked(event: Event): Action? = mapEvent(event)

/**
 * What [GroupRecordingFiles.read] found: the group's run over the range every member's file and
 * the order file still cover, replayable from the members' checkpoints there, the cuts inside
 * that range, and the marks of everything else.
 *
 * @property recording The range as a group recording, or `null` when nothing could be read; its
 * sources' snapshots are known when the range begins at a cut
 * @property position The index in the whole run of the range's first entry
 * @property cuts The group's cuts inside the range, by position; [since] gives the run from one
 */
class GroupRecordingFileContents internal constructor(
    val recording: GroupRecording?,
    val position: Int?,
    val cuts: List<RecordedCut>,
    val marks: List<RecordingFileMark>,
    private val slice: (RecordedCut) -> GroupRecording,
) {
    /**
     * The range from [cut] on, with the sources' snapshots the cut holds and the messages in
     * flight there, as [GroupRecording.since] gives in memory.
     *
     * @throws IllegalArgumentException if [cut] is not one of [cuts]
     */
    fun since(cut: RecordedCut): GroupRecording {
        require(cut in cuts) { "[Koma] $cut is not a cut inside the range read" }
        return slice(cut)
    }

    override fun toString(): String = "GroupRecordingFileContents(position=$position, cuts=${cuts.map { it.position }}, marks=$marks)"
}

/**
 * Reads what a [GroupRecordingFileSink] wrote: the order file's last continuous range and every
 * member's, cut to the range they all cover. The members' recordings are trimmed to their
 * checkpoint at that range's first entry; the messages in flight there come from the order
 * segment's header and the entries before it. The cuts inside the range are offered too: a
 * range since a cut knows the sources' snapshots.
 */
class GroupRecordingFiles(private val storage: SegmentStorage) {
    /** The groups that have order files. */
    fun groups(): List<MachineGroupId> = storage.list().mapNotNull { GroupRecordingFileFormat.parseSegmentName(it.name)?.first }.distinct()

    fun read(group: MachineGroupId, codecs: Map<StoreInstanceId, RecordingCodec<*, *, *, *>>): GroupRecordingFileContents {
        val marks = mutableListOf<RecordingFileMark>()
        val nothing = { GroupRecordingFileContents(null, null, emptyList(), marks.toList()) { throw IllegalStateException("[Koma] Nothing was read") } }
        // The order file's last continuous range.
        val segments = storage.list().mapNotNull { info -> GroupRecordingFileFormat.parseSegmentName(info.name)?.takeIf { it.first == group }?.let { it.second to info.name } }.sortedBy { it.first }
        var open: OrderRun? = null
        var latest: OrderRun? = null
        var expectedIndex = 0
        for ((index, name) in segments) {
            if (index > expectedIndex) {
                marks += RecordingFileMark.MissingSegments(StoreInstanceId(group.value), expectedIndex, index - 1)
                open = null
            }
            expectedIndex = index + 1
            val decoded = GroupRecordingFileFormat.decodeSegment(name, storage.read(name))
            val header = decoded.header
            if (header == null) {
                decoded.mark?.let { marks += RecordingFileMark.Damaged(it) }
                open = null
                continue
            }
            val current = open
            if (current != null && header.firstEntry != current.nextEntry) {
                marks += RecordingFileMark.StepsMissing(StoreInstanceId(group.value), current.nextEntry, header.firstEntry)
                open = null
            }
            val run = open ?: OrderRun(header, mutableListOf()).also {
                open = it
                latest = it
            }
            header.cut?.let { run.cuts += it }
            run.entries += decoded.entries
            run.nextEntry = header.firstEntry + decoded.entries.size
            decoded.mark?.let {
                marks += RecordingFileMark.Damaged(it)
                if (it !is SegmentMark.Unfinished) open = null
            }
        }
        val order = latest ?: return nothing()
        // Every member's last continuous range.
        val members = mutableMapOf<StoreInstanceId, Pair<Recording<*, *, *, *>, Int>>()
        for (member in order.header.members) {
            val codec = codecs[member] ?: run {
                marks += RecordingFileMark.StartMismatch(member, "-", "no codec for $member")
                continue
            }
            val contents = storage.readMember(member, codec)
            marks += contents.marks
            val recording = contents.recording ?: continue
            members[member] = recording to checkNotNull(contents.firstStep)
        }
        // The range all files cover: from the latest start among the order's and the members', to the earliest end.
        val entries = order.entries
        val firstEntry = order.header.firstEntry
        var from = 0
        var to = entries.size
        for ((member, pair) in members) {
            val (recording, firstStep) = pair
            // Entries of steps the member's file no longer has are before the range.
            val lastUncovered = entries.indexOfLast { it.store == member && it.step < firstStep }
            from = maxOf(from, lastUncovered + 1)
            val beyond = entries.indexOfFirst { it.store == member && it.step >= firstStep + recording.length }
            if (beyond >= 0) to = minOf(to, beyond)
        }
        for (member in order.header.members) if (member !in members && entries.any { it.store == member }) {
            // A member whose file is gone: only the entries before its first one are usable.
            val first = entries.indexOfFirst { it.store == member }
            to = minOf(to, first)
        }
        if (from >= to) return nothing()
        // The cuts inside the range whose counts agree with the entries; one that does not is marked and left out.
        val cuts = order.cuts.filter { cut ->
            val at = cut.position - firstEntry
            if (at !in from..to) return@filter false
            val disagreeing = cut.counts.entries.firstOrNull { (member, count) -> stepsBefore(entries, at, member)?.let { it != count } == true }
            if (disagreeing != null) marks += RecordingFileMark.StartMismatch(disagreeing.key, "-", "the cut at ${cut.position} counts ${disagreeing.value} steps of ${disagreeing.key}, the order ${stepsBefore(entries, at, disagreeing.key)}")
            disagreeing == null
        }
        val sliceAt = { at: Int, sources: Map<SourceId, SourceSnapshot> -> slice(order, members, at, to, sources) }
        val recording = sliceAt(from, cuts.firstOrNull { it.position - firstEntry == from }?.sources ?: emptyMap())
        return GroupRecordingFileContents(recording, firstEntry + from, cuts, marks) { cut -> sliceAt(cut.position - firstEntry, cut.sources) }
    }

    // How many steps of `member` came before entry `at` of the run, from the steps the entries carry; `null` when no entry of it says.
    private fun stepsBefore(entries: List<GroupOrderEntry>, at: Int, member: StoreInstanceId): Int? =
        entries.subList(0, at).lastOrNull { it.store == member }?.let { it.step + 1 } ?: entries.subList(at, entries.size).firstOrNull { it.store == member }?.step

    // The run over entries `from` until `to`: every member trimmed to its checkpoint at its first entry there, the order re-based, the messages in flight computed.
    private fun slice(order: OrderRun, members: Map<StoreInstanceId, Pair<Recording<*, *, *, *>, Int>>, from: Int, to: Int, sources: Map<SourceId, SourceSnapshot>): GroupRecording {
        val entries = order.entries
        val range = entries.subList(from, to)
        val trimmed = mutableMapOf<StoreInstanceId, Recording<*, *, *, *>>()
        val offsets = mutableMapOf<StoreInstanceId, Int>()
        for ((member, pair) in members) {
            val (recording, firstStep) = pair
            val firstInRange = range.firstOrNull { it.store == member }?.step ?: (firstStep + recording.length)
            val skip = firstInRange - firstStep
            trimmed[member] = recording.sinceStep(skip)
            offsets[member] = firstInRange
        }
        val groupOrder = range.map { GroupStep(it.store, it.step - offsets.getValue(it.store)) }
        // Messages in flight at the range's start: the header's, plus what the skipped entries sent, minus what they received.
        val inFlight = order.header.inFlight.groupingBy { it }.eachCount().toMutableMap()
        for (entry in entries.subList(0, from)) {
            entry.received?.let { message -> inFlight[message]?.let { count -> if (count <= 1) inFlight.remove(message) else inFlight[message] = count - 1 } }
            for (message in entry.sent) inFlight[message] = (inFlight[message] ?: 0) + 1
        }
        return GroupRecording(trimmed, groupOrder, order.header.routes, inFlight.keys.toList(), order.header.sourceIds + sources.keys, sources)
    }

    private class OrderRun(val header: GroupSegmentHeader, val entries: MutableList<GroupOrderEntry>) {
        var nextEntry: Int = header.firstEntry
        val cuts = mutableListOf<RecordedCut>()
    }

    @Suppress("UNCHECKED_CAST")
    private fun SegmentStorage.readMember(member: StoreInstanceId, codec: RecordingCodec<*, *, *, *>): RecordingFileContents<*, *, *, *> =
        RecordingFiles(this).read(member, codec as RecordingCodec<Any?, Action, Any?, Event>)

    @Suppress("UNCHECKED_CAST")
    private fun Recording<*, *, *, *>.sinceStep(skip: Int): Recording<*, *, *, *> {
        val typed = this as Recording<Any?, Action, Any?, Event>
        return Recording(typed.definition, typed.version, typed.checkpointAt(skip), typed.steps.drop(skip))
    }
}

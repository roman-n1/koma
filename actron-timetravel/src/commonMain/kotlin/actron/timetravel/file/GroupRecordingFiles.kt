package actron.timetravel.file

import actron.core.InputAttribution

import actron.core.Action
import actron.core.Event
import actron.core.InputId
import actron.observability.FailureDescriptor
import actron.observability.MachineGroupId
import actron.observability.StoreInstanceId
import actron.observability.file.Framing
import actron.observability.file.SegmentMark
import actron.observability.file.SegmentEnding
import actron.observability.file.beforeHeader
import actron.observability.file.SegmentOutput
import actron.observability.file.SegmentStorage
import actron.statechart.machine.CutListener
import actron.statechart.machine.Decision
import actron.statechart.machine.DecisionObserver
import actron.statechart.machine.EffectId
import actron.statechart.machine.GroupCheckpoint
import actron.statechart.machine.IgnoreReason
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineGroup
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MessageId
import actron.statechart.machine.PairRole
import actron.statechart.machine.RoutePair
import actron.statechart.machine.RouteProtocol
import actron.statechart.machine.SourceId
import actron.statechart.machine.SourceSnapshot
import actron.timetravel.GroupRecording
import actron.timetravel.GroupRoute
import actron.timetravel.GroupStep
import actron.timetravel.RecordedStep
import actron.timetravel.Recording
import actron.timetravel.RecordingCodec
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

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

    fun parseSegmentName(name: String, accept: (MachineGroupId, Int) -> Unit): Boolean {
        if (!name.endsWith(EXTENSION)) return false
        val stem = name.removeSuffix(EXTENSION)
        val dash = stem.lastIndexOf('-')
        if (dash <= 0) return false
        val index = stem.substring(dash + 1).takeIf { it.length == 6 }?.toIntOrNull() ?: return false
        accept(MachineGroupId(stem.substring(0, dash)), index)
        return true
    }

    internal fun namedSegments(storage: SegmentStorage, owner: MachineGroupId): List<Pair<Int, String>> = buildList {
        for (info in storage.list()) parseSegmentName(info.name) { id, index ->
            if (id == owner) add(index to info.name)
        }
    }

    fun header(header: GroupSegmentHeader): ByteArray {
        val wire = GroupHeaderWire(
            header.fileFormatVersion, header.group.value, header.members.map { it.value }, header.routes.map { val pair = it.pair; if (pair is RoutePair) RouteWire(it.from.value, it.to.value, JsonPrimitive(pair.name), JsonPrimitive(pair.role.name)) else RouteWire(it.from.value, it.to.value) },
            header.sourceIds.map { it.value }, header.index, header.firstEntry, header.inFlight.map { MessageWireG(it.from.value, it.effect.value) },
            cutNode(header.boundary),
        )
        return MAGIC + Framing.frame(byteArrayOf(TAG_HEADER.toByte()) + json.encodeToString(GroupHeaderWire.serializer(), wire).encodeToByteArray())
    }

    private fun cutNode(boundary: GroupBoundary): JsonElement {
        var node: JsonElement = JsonNull
        boundary.withCut { cut ->
            node = json.encodeToJsonElement(CutWire.serializer(), CutWire(
                    cut.counts.map { (store, count) -> store.value to count }.toMap(),
                    cut.sources.values.map { SourceSnapshotWire(it.source.value, it.kind, it.version, it.fields) },
            ))
        }
        return node
    }

    fun entryFrame(entry: GroupOrderEntry): ByteArray {
        var received: JsonElement = JsonNull
        entry.delivery.withReceived { received = json.encodeToJsonElement(MessageWireG.serializer(), MessageWireG(it.from.value, it.effect.value)) }
        val wire = EntryWire(entry.store.value, entry.step, entry.sent.map { MessageWireG(it.from.value, it.effect.value) }, received)
        return Framing.frame(byteArrayOf(TAG_ENTRY.toByte()) + json.encodeToString(EntryWire.serializer(), wire).encodeToByteArray())
    }

    fun decodeSegment(name: String, bytes: ByteArray): DecodedGroupSegment {
        val read = Framing.read(name, bytes, MAGIC)
        val frames = read.frames
        if (frames.isEmpty()) {
            val mark = read.ending.beforeHeader(name, MAGIC.size)
            return DecodedGroupSegment.Unreadable(mark)
        }
        val header = try {
            val payload = frames[0].payload
            require(payload.isNotEmpty() && payload[0].toInt() == TAG_HEADER) { "not a header frame" }
            val wire = json.decodeFromString(GroupHeaderWire.serializer(), payload.decodeToString(1))
            GroupSegmentHeader(
                wire.formatVersion, MachineGroupId(wire.group), wire.members.map(::StoreInstanceId), wire.routes.map { GroupRoute(StoreInstanceId(it.from), StoreInstanceId(it.to), it.pair()) },
                wire.sourceIds.map(::SourceId).toSet(), wire.index, wire.firstEntry, wire.inFlight.map { MessageId(StoreInstanceId(it.from), EffectId(it.effect)) },
                if (wire.cut == JsonNull) GroupBoundary.Continuation else json.decodeFromJsonElement(CutWire.serializer(), wire.cut).let { cut ->
                    RecordedCut(
                        wire.firstEntry,
                        cut.counts.map { (store, count) -> StoreInstanceId(store) to count }.toMap(),
                        cut.sources.associate { SourceId(it.source) to SourceSnapshot(SourceId(it.source), it.kind, it.version, it.fields) },
                    )
                },
            )
        } catch (e: SerializationException) {
            return DecodedGroupSegment.Unreadable(SegmentMark.Corrupt(name, frames[0].offset, 0, "header: ${e.message}"))
        } catch (e: IllegalArgumentException) {
            return DecodedGroupSegment.Unreadable(SegmentMark.Corrupt(name, frames[0].offset, 0, "header: ${e.message}"))
        }
        if (header.fileFormatVersion > VERSION) return DecodedGroupSegment.Readable(header, emptyList(), SegmentEnding.Stopped(SegmentMark.UnsupportedFormat(name, header.fileFormatVersion, 0)))
        val entries = mutableListOf<GroupOrderEntry>()
        for (frame in frames.drop(1)) {
            val entry = try {
                val payload = frame.payload
                require(payload.isNotEmpty() && payload[0].toInt() == TAG_ENTRY) { "not an entry frame" }
                val wire = json.decodeFromString(EntryWire.serializer(), payload.decodeToString(1))
                GroupOrderEntry(StoreInstanceId(wire.store), wire.step, wire.sent.map { MessageId(StoreInstanceId(it.from), EffectId(it.effect)) }, if (wire.received == JsonNull) GroupDelivery.Local else json.decodeFromJsonElement(MessageWireG.serializer(), wire.received).let { GroupDelivery.Bridged(MessageId(StoreInstanceId(it.from), EffectId(it.effect))) })
            } catch (e: SerializationException) {
                return DecodedGroupSegment.Readable(header, entries, SegmentEnding.Stopped(SegmentMark.Corrupt(name, frame.offset, entries.size, "entry: ${e.message}")))
            } catch (e: IllegalArgumentException) {
                return DecodedGroupSegment.Readable(header, entries, SegmentEnding.Stopped(SegmentMark.Corrupt(name, frame.offset, entries.size, "entry: ${e.message}")))
            }
            entries += entry
        }
        return DecodedGroupSegment.Readable(header, entries, read.ending.countRecords(entries.size))
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
    val cut: JsonElement = JsonNull,
)

@Serializable
internal class CutWire(val counts: Map<String, Int>, val sources: List<SourceSnapshotWire>)

@Serializable
internal class SourceSnapshotWire(val source: String, val kind: String, val version: Int, val fields: Map<String, String>)

@Serializable
internal class RouteWire(val from: String, val to: String, val pair: JsonElement = JsonNull, val role: JsonElement = JsonNull) {
    fun pair(): RouteProtocol {
        require(pair == JsonNull || pair is JsonPrimitive && pair.isString) { "[Actron] Route pair must be a JSON string" }
        require(role == JsonNull || role is JsonPrimitive && role.isString) { "[Actron] Route role must be a JSON string" }
        return if (pair != JsonNull && role != JsonNull) RoutePair(pair.jsonPrimitive.content, PairRole.valueOf(role.jsonPrimitive.content)) else RouteProtocol.OneWay
    }
}

@Serializable
internal class MessageWireG(val from: String, val effect: Long)

@Serializable
internal class EntryWire(val store: String, val step: Int, val sent: List<MessageWireG> = emptyList(), val received: JsonElement = JsonNull)

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
    val boundary: GroupBoundary = GroupBoundary.Continuation,
)

/**
 * A cut of the group as the files remember it: the [position] in the whole run of the first
 * entry after it, how many steps of each member came before it, and the sources' snapshots
 * there. A run since a cut knows its sources' state, as [GroupRecording.since] does in memory.
 */
sealed interface GroupBoundary {
    fun withCut(accept: (RecordedCut) -> Unit): Boolean
    data object Continuation : GroupBoundary {
        override fun withCut(accept: (RecordedCut) -> Unit): Boolean = false
    }
}

data class RecordedCut(val position: Int, val counts: Map<StoreInstanceId, Int>, val sources: Map<SourceId, SourceSnapshot>) : GroupBoundary {
    override fun withCut(accept: (RecordedCut) -> Unit): Boolean { accept(this); return true }
}

/** How a member's decision reached the group: its local input or a delivered bridge message. */
sealed interface GroupDelivery {
    fun withReceived(accept: (MessageId) -> Unit): Boolean
    data object Local : GroupDelivery {
        override fun withReceived(accept: (MessageId) -> Unit): Boolean = false
    }
    data class Bridged(val message: MessageId) : GroupDelivery {
        override fun withReceived(accept: (MessageId) -> Unit): Boolean { accept(message); return true }
    }
}

/** One decision of the group in its order: which member, which of its steps, what it sent over the bridge and what it received. */
data class GroupOrderEntry(val store: StoreInstanceId, val step: Int, val sent: List<MessageId> = emptyList(), val delivery: GroupDelivery = GroupDelivery.Local) {
    constructor(store: StoreInstanceId, step: Int, sent: List<MessageId> = emptyList(), received: MessageId) : this(store, step, sent, GroupDelivery.Bridged(received))
}

sealed class DecodedGroupSegment {
    abstract val entries: List<GroupOrderEntry>
    abstract val ending: SegmentEnding
    val finished: Boolean get() = ending.finished
    data class Unreadable(val issue: SegmentMark) : DecodedGroupSegment() {
        override val entries: List<GroupOrderEntry> get() = emptyList()
        override val ending: SegmentEnding get() = SegmentEnding.Stopped(issue)
    }
    data class Readable(val header: GroupSegmentHeader, override val entries: List<GroupOrderEntry>, override val ending: SegmentEnding) : DecodedGroupSegment()
}

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

    /** A queued decision or an actual cut that begins a segment. */
    private sealed class Item {
        abstract val index: Int
        abstract val inFlight: List<MessageId>
        abstract val boundary: GroupBoundary
        class Order(val entry: GroupOrderEntry, override val index: Int, override val inFlight: List<MessageId>, val beginSegment: Boolean) : Item() {
            override val boundary: GroupBoundary get() = GroupBoundary.Continuation
        }
        class Cut(val cut: RecordedCut, override val inFlight: List<MessageId>) : Item() {
            override val index: Int get() = cut.position
            override val boundary: GroupBoundary get() = cut
        }
    }

    private val cutListener = CutListener { cut(it) }

    init {
        group.onCut(cutListener)
    }

    private val queue = Channel<Item>(config.queueCapacity)
    private sealed interface SegmentWriter {
        data object Detached : SegmentWriter
        data class Active(val output: SegmentOutput) : SegmentWriter
    }
    private var segmentWriter: SegmentWriter = SegmentWriter.Detached
    private var segmentIndex = -1
    private var segmentBytes = 0
    private var segmentEntries = 0
    private var unflushed = 0
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
    fun <C : Any, A : Action, CMD : Any, E : Event> member(memberId: StoreInstanceId, machine: Machine<C, A, CMD, E>, context: C, codec: RecordingCodec<C, A, CMD, E>): DecisionObserver<C, A, CMD, E> {
        val sink = locked {
            check(!closed) { "[Actron] This group sink is closed" }
            require(memberId !in sinks) { "[Actron] $memberId is already recorded by this group sink" }
            RecordingFileSink(memberId, machine, context, codec, storage, scope, config).also { sinks[memberId] = it }
        }
        return object : DecisionObserver<C, A, CMD, E> {
            override fun onCommitted(input: InputAttribution, machineInput: MachineInput<A>, decision: Decision<C, CMD, E>) {
                sink.onCommitted(input, machineInput, decision)
                val routes = group.routes.filter { it.from == memberId }
                val sent = buildList {
                    for (effect in decision.effects) for (route in routes) route.mapEvent(effect.event) {
                        add(MessageId(memberId, effect.id) to route.to)
                    }
                }
                order(memberId, if (machineInput is MachineInput.BridgeReceived) GroupDelivery.Bridged(machineInput.message) else GroupDelivery.Local, sent)
            }

            override fun onIgnored(input: InputAttribution, machineInput: MachineInput<A>, reason: IgnoreReason) {
                sink.onIgnored(input, machineInput, reason)
                order(memberId, if (machineInput is MachineInput.BridgeReceived) GroupDelivery.Bridged(machineInput.message) else GroupDelivery.Local, emptyList())
            }

            override fun onFailed(input: InputAttribution, machineInput: MachineInput<A>, failure: FailureDescriptor) {
                sink.onFailed(input, machineInput, failure)
                order(memberId, if (machineInput is MachineInput.BridgeReceived) GroupDelivery.Bridged(machineInput.message) else GroupDelivery.Local, emptyList())
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

    private fun order(memberId: StoreInstanceId, delivery: GroupDelivery, sent: List<Pair<MessageId, StoreInstanceId>>) {
        val item = locked {
            if (closed) return
            val step = counts[memberId] ?: 0
            counts[memberId] = step + 1
            delivery.withReceived { message -> if (message in pending) { val count = pending.getValue(message); if (count <= 1) pending.remove(message) else pending[message] = count - 1 } }
            val inFlight = pending.keys.toList()
            for ((message, _) in sent) pending[message] = (pending[message] ?: 0) + 1
            Item.Order(GroupOrderEntry(memberId, step, sent.map { it.first }.distinct(), delivery), nextEntry++, inFlight, beginSegment).also { beginSegment = false }
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
            Item.Cut(RecordedCut(nextEntry, members, checkpoint.sources), pending.keys.toList())
        }
        if (queue.trySend(item).isFailure) locked { droppedCutCount++ }
    }

    private fun write(item: Item) {
        if (item is Item.Cut) {
            // A cut: the segment it begins carries it; the entries follow.
            finish()
            open(item)
            retain()
            return
        }
        item as Item.Order
        val frame = GroupRecordingFileFormat.entryFrame(item.entry)
        if (item.beginSegment || segmentWriter is SegmentWriter.Detached || (segmentEntries > 0 && segmentBytes + frame.size > config.maxSegmentBytes)) {
            finish()
            open(item)
            retain()
        }
        val active = segmentWriter
        check(active is SegmentWriter.Active)
        val output = active.output
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
            GroupSegmentHeader(GroupRecordingFileFormat.VERSION, id, locked { sinks.keys.toList() }, group.routeHistory.map { GroupRoute(it.from, it.to, it.pair) }, group.sourceIds.toSet(), segmentIndex, item.index, item.inFlight, item.boundary),
        )
        val output = storage.append(GroupRecordingFileFormat.segmentName(id, segmentIndex))
        segmentWriter = SegmentWriter.Active(output)
        output.write(header)
        segmentBytes = header.size
        segmentEntries = 0
        unflushed = 0
    }

    private fun nextIndex(): Int = GroupRecordingFileFormat.namedSegments(storage, id).maxOfOrNull { it.first }?.plus(1) ?: 0

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
        val mine = GroupRecordingFileFormat.namedSegments(storage, id).sortedBy { it.first }
        for ((_, name) in mine.dropLast(config.maxSegments)) storage.delete(name)
    }

    private inline fun <T : Any> locked(block: () -> T): T {
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

/**
 * What [GroupRecordingFiles.read] found: the group's run over the range every member's file and
 * the order file still cover, replayable from the members' checkpoints there, the cuts inside
 * that range, and the marks of everything else.
 *
 * A [GroupRecordingFileContents.Readable] holds the range and its starting position; sources'
 * snapshots are known when the range begins at a cut. Unreadable results retain diagnostics
 * @property position The index in the whole run of the range's first entry
 * @property cuts The group's cuts inside the range, by position; [since] gives the run from one
 */
sealed class GroupRecordingFileContents {
    abstract val cuts: List<RecordedCut>
    abstract val marks: List<RecordingFileMark>
    protected abstract fun slice(cut: RecordedCut): GroupRecording

    /** Continues the readable group range from one of its recorded cuts. */
    fun since(cut: RecordedCut): GroupRecording {
        require(cut in cuts) { "[Actron] $cut is not a cut inside the range read" }
        return slice(cut)
    }
    class Unreadable(override val marks: List<RecordingFileMark>) : GroupRecordingFileContents() {
        override val cuts: List<RecordedCut> get() = emptyList()
        override fun slice(cut: RecordedCut): GroupRecording = error("[Actron] Nothing was read")
        override fun toString(): String = "GroupRecordingFileContents(position=null, cuts=[], marks=$marks)"
    }
    class Readable internal constructor(
        val recording: GroupRecording,
        val position: Int,
        override val cuts: List<RecordedCut>,
        override val marks: List<RecordingFileMark>,
        private val slicing: (RecordedCut) -> GroupRecording,
    ) : GroupRecordingFileContents() {
        override fun slice(cut: RecordedCut): GroupRecording = slicing(cut)
        override fun toString(): String = "GroupRecordingFileContents(position=$position, cuts=${cuts.map { it.position }}, marks=$marks)"
    }
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
    fun groups(): List<MachineGroupId> = buildList { for (info in storage.list()) GroupRecordingFileFormat.parseSegmentName(info.name) { id, _ -> add(id) } }.distinct()

    fun read(group: MachineGroupId, codecs: Map<StoreInstanceId, RecordingCodec<*, *, *, *>>): GroupRecordingFileContents {
        val marks = mutableListOf<RecordingFileMark>()
        val nothing = { GroupRecordingFileContents.Unreadable(marks.toList()) }
        // The order file's last continuous range.
        val segments = GroupRecordingFileFormat.namedSegments(storage, group).sortedBy { it.first }
        val runs = mutableListOf<OrderRun>()
        var canContinue = false
        var expectedIndex = 0
        for ((index, name) in segments) {
            if (index > expectedIndex) {
                marks += RecordingFileMark.MissingSegments(StoreInstanceId(group.value), expectedIndex, index - 1)
                canContinue = false
            }
            expectedIndex = index + 1
            val decoded = GroupRecordingFileFormat.decodeSegment(name, storage.read(name))
            if (decoded !is DecodedGroupSegment.Readable) {
                decoded.ending.withIssue { marks += RecordingFileMark.Damaged(it) }
                canContinue = false
                continue
            }
            val header = decoded.header
            if (canContinue && header.firstEntry != runs.last().nextEntry) {
                marks += RecordingFileMark.StepsMissing(StoreInstanceId(group.value), runs.last().nextEntry, header.firstEntry)
                canContinue = false
            }
            if (!canContinue) {
                runs += OrderRun(header, mutableListOf())
                canContinue = true
            }
            val run = runs.last()
            header.boundary.withCut { run.cuts += it }
            run.entries += decoded.entries
            run.nextEntry = header.firstEntry + decoded.entries.size
            decoded.ending.withIssue {
                marks += RecordingFileMark.Damaged(it)
                if (it !is SegmentMark.Unfinished) canContinue = false
            }
        }
        if (runs.isEmpty()) return nothing()
        val order = runs.last()
        // Every member's last continuous range.
        val members = mutableMapOf<StoreInstanceId, Pair<Recording<*, *, *, *>, Int>>()
        for (member in order.header.members) {
            if (member !in codecs) {
                marks += RecordingFileMark.StartMismatch(member, "-", "no codec for $member")
                continue
            }
            val contents = storage.readMember(member, codecs.getValue(member))
            marks += contents.marks
            if (contents !is RecordingFileContents.Readable) continue
            members[member] = contents.recording to contents.firstStep
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
        for ((member, pair) in members) {
            if (entries.subList(from, to).any { it.store == member }) continue
            val (recording, firstStep) = pair
            var covered = false
            idleStep(order, from, member) { covered = it in firstStep..(firstStep + recording.length) }
            if (!covered) {
                marks += RecordingFileMark.StartMismatch(member, "-", "no checkpoint for the member at the surviving order range")
                return nothing()
            }
        }
        // The cuts inside the range whose counts agree with the entries; one that does not is marked and left out.
        val cuts = order.cuts.filter { cut ->
            val at = cut.position - firstEntry
            if (at !in from..to) return@filter false
            for ((member, count) in cut.counts) {
                var agrees = true
                stepsBefore(entries, at, member) { step ->
                    if (step != count) {
                        marks += RecordingFileMark.StartMismatch(member, "-", "the cut at ${cut.position} counts $count steps of $member, the order $step")
                        agrees = false
                    }
                }
                if (!agrees) return@filter false
            }
            true
        }
        val sliceAt = { at: Int, sources: Map<SourceId, SourceSnapshot> -> slice(order, members, at, to, sources) }
        var sources: Map<SourceId, SourceSnapshot> = emptyMap()
        for (cut in cuts) if (cut.position - firstEntry == from) { sources = cut.sources; break }
        val recording = sliceAt(from, sources)
        return GroupRecordingFileContents.Readable(recording, firstEntry + from, cuts, marks) { cut -> sliceAt(cut.position - firstEntry, cut.sources) }
    }

    // The order can establish a member's count from its preceding or following entry.
    private fun stepsBefore(entries: List<GroupOrderEntry>, at: Int, member: StoreInstanceId, accept: (Int) -> Unit): Boolean {
        for (index in at - 1 downTo 0) if (entries[index].store == member) { accept(entries[index].step + 1); return true }
        for (index in at until entries.size) if (entries[index].store == member) { accept(entries[index].step); return true }
        return false
    }

    // An idle member still needs a checkpoint established by the order or an actual cut.
    private fun idleStep(order: OrderRun, at: Int, member: StoreInstanceId, accept: (Int) -> Unit): Boolean {
        if (stepsBefore(order.entries, at, member, accept)) return true
        var reported = false
        order.header.boundary.withCut { cut ->
            if (member in cut.counts) { accept(cut.counts.getValue(member)); reported = true }
        }
        if (reported) return true
        if (order.header.firstEntry == 0) { accept(0); return true }
        return false
    }

    // The run over entries `from` until `to`: every member trimmed to its checkpoint at its first entry there, the order re-based, the messages in flight computed.
    private fun slice(order: OrderRun, members: Map<StoreInstanceId, Pair<Recording<*, *, *, *>, Int>>, from: Int, to: Int, sources: Map<SourceId, SourceSnapshot>): GroupRecording {
        val entries = order.entries
        val range = entries.subList(from, to)
        val trimmed = mutableMapOf<StoreInstanceId, Recording<*, *, *, *>>()
        val offsets = mutableMapOf<StoreInstanceId, Int>()
        for ((member, pair) in members) {
            val (recording, firstStep) = pair
            fun trim(firstInRange: Int) {
                val skip = firstInRange - firstStep
                // Keep only the steps whose order entries survived the process stopping.
                trimmed[member] = recording.sinceStep(skip, range.count { it.store == member })
                offsets[member] = firstInRange
            }
            val first = range.indexOfFirst { it.store == member }
            if (first >= 0) trim(range[first].step)
            else check(idleStep(order, from, member, ::trim)) { "[Actron] No checkpoint for $member at the surviving order range" }
        }
        val groupOrder = range.map { GroupStep(it.store, it.step - offsets.getValue(it.store)) }
        // Messages in flight at the range's start: the header's, plus what the skipped entries sent, minus what they received.
        val inFlight = order.header.inFlight.groupingBy { it }.eachCount().toMutableMap()
        for (entry in entries.subList(0, from)) {
            entry.delivery.withReceived { message -> if (message in inFlight) { val count = inFlight.getValue(message); if (count <= 1) inFlight.remove(message) else inFlight[message] = count - 1 } }
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
        RecordingFiles(this).read(member, codec as RecordingCodec<Any, Action, Any, Event>)

    @Suppress("UNCHECKED_CAST")
    private fun Recording<*, *, *, *>.sinceStep(skip: Int, count: Int): Recording<*, *, *, *> {
        val typed = this as Recording<Any, Action, Any, Event>
        return Recording(typed.definition, typed.version, typed.checkpointAt(skip), typed.steps.drop(skip).take(count))
    }
}

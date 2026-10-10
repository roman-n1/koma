package actron.timetravel.inspect

import actron.core.ExperimentalActronApi
import actron.core.InputId
import actron.observability.Capability
import actron.observability.ExecutionMode
import actron.observability.GroupSeq
import actron.observability.InputDescriptor
import actron.observability.JournalEntry
import actron.observability.JournalRecord
import actron.observability.JournalStats
import actron.observability.MachineGroupId
import actron.observability.OutcomeDescriptor
import actron.observability.OutcomeKind
import actron.observability.Payload
import actron.observability.RecordingSession
import actron.observability.RuntimeSessionId
import actron.observability.StoreInstanceId
import actron.observability.file.JournalFileEvent
import actron.observability.file.JournalFiles
import actron.observability.file.SegmentMark
import actron.timetravel.RecordedStep
import actron.timetravel.Recording
import kotlin.time.Duration

/**
 * The read model of an inspected history (handoff §11, v1): the Stores of one group with their
 * capabilities, the timeline of what happened in the group's order, each position with its
 * input and cause, commits, decision, commands, timers, events and failures, and, above all,
 * an explicit [completeness]: what the journal does not hold, named, so that a full recording
 * is told from a partial one.
 *
 * The journal is the source of order and of what the policy kept. A Store whose
 * [actron.timetravel.MachineRecorder] recording is attached and matches the journal's decisions
 * gets its machine snapshots before and after every decision and their difference; a recording
 * that does not match is reported and not used. The inspector holds no Store, machine or
 * scope: it is data over data, for a text renderer ([InspectorText]) or a debug UI.
 *
 * @param events The journal, in order: records and, from files, the marks of what could not be read
 * @param recordings Recordings to attach, by Store
 * @param stats The session's counters, when inspecting a live session
 */
@ExperimentalActronApi
class Inspector(
    val events: List<JournalFileEvent>,
    recordings: Map<StoreInstanceId, Recording<*, *, *, *>> = emptyMap(),
    val stats: JournalStats? = null,
) {
    /** The journal's records, in order. */
    val records: List<JournalRecord<*, *, *>> = events.mapNotNull { (it as? JournalFileEvent.Record)?.record }

    /** The session the records belong to; `null` for an empty journal. */
    val session: RuntimeSessionId? = records.firstOrNull()?.session

    /** The group; `null` for an empty journal. */
    val group: MachineGroupId? = records.firstOrNull()?.group

    /** Live or replay; `null` for an empty journal. */
    val mode: ExecutionMode? = records.firstOrNull()?.mode

    /** Everything that happened, in the group's order. */
    val timeline: List<TimelineItem>

    /** The Stores of the group, in the order they first appear. */
    val stores: List<StoreView>

    /** What the whole history lacks; complete when nothing. */
    val completeness: Completeness

    private val recordingStatus: Map<StoreInstanceId, RecordingStatus>

    init {
        val built = Builder(events).build()
        val storeIds = built.storeIds
        val enriched = mutableMapOf<StoreInstanceId, RecordingStatus>()
        var timeline = built.items
        for (store in storeIds) {
            val recording = recordings[store]
            if (recording == null) {
                enriched[store] = RecordingStatus.None
                continue
            }
            when (val result = enrich(timeline, store, recording)) {
                is Enrichment.Done -> {
                    timeline = result.timeline
                    enriched[store] = RecordingStatus.Attached(recording.length)
                }
                is Enrichment.Mismatch -> enriched[store] = RecordingStatus.Mismatch(result.reason)
            }
        }
        for ((store, _) in recordings) if (store !in storeIds) enriched[store] = RecordingStatus.Mismatch("the journal has no records of $store")
        recordingStatus = enriched
        this.timeline = timeline
        stores = storeIds.map { store -> storeView(store, built, timeline) }
        completeness = Completeness(groupReasons(built) + stores.flatMap { it.completeness.reasons })
    }

    /** The timeline of one Store, with the session's own items (gaps, the stop, damage) between. */
    fun timelineOf(store: StoreInstanceId): List<TimelineItem> = timeline.filter { it.store == store || it.store == null }

    /**
     * Whether [store]'s history can be replayed by a `ReplaySession`, and if not, why: replay
     * decides a recording again, so it needs one that matches the journal; the journal alone is
     * `InspectOnly`.
     */
    fun replayability(store: StoreInstanceId): Availability {
        val view = stores.firstOrNull { it.id == store } ?: return Availability.Unavailable(listOf("the journal has no records of $store"))
        val reasons = mutableListOf<String>()
        when (val status = view.recording) {
            RecordingStatus.None -> reasons += "no recording is attached for $store; the journal's capability is ${view.capability ?: "unknown"}, which can be shown, not replayed"
            is RecordingStatus.Mismatch -> reasons += "the recording attached for $store does not match the journal: ${status.reason}"
            is RecordingStatus.Attached -> Unit
        }
        return if (reasons.isEmpty()) Availability.Available else Availability.Unavailable(reasons)
    }

    private fun storeView(store: StoreInstanceId, built: Built, timeline: List<TimelineItem>): StoreView {
        val items = timeline.filter { it.store == store }
        val processings = items.filterIsInstance<TimelineItem.Processing>()
        val status = recordingStatus.getValue(store)
        val reasons = mutableListOf<Incompleteness>()
        val registered = items.filterIsInstance<TimelineItem.Registered>().firstOrNull()
        if (registered == null) reasons += Incompleteness.NotRegistered(store)
        if (status !is RecordingStatus.Attached) {
            val payloads = built.payloads(store)
            if (payloads.omitted > 0) reasons += Incompleteness.PayloadsOmitted(store, payloads.omitted)
            if (payloads.unavailable > 0) reasons += Incompleteness.PayloadsUnavailable(store, payloads.unavailable)
        }
        val pending = items.count { it is TimelineItem.Pending } + processings.count { !it.isFinished }
        if (pending > 0) reasons += Incompleteness.InputsPending(store, pending)
        val unattributed = items.count { it is TimelineItem.Unattributed }
        if (unattributed > 0) reasons += Incompleteness.Unattributed(store, unattributed)
        val undelivered = items.count { it is TimelineItem.Sent && !it.delivered }
        if (undelivered > 0) reasons += Incompleteness.MessagesUndelivered(store, undelivered)
        if (status is RecordingStatus.Mismatch) reasons += Incompleteness.RecordingMismatch(store, status.reason)
        return StoreView(
            id = store,
            capability = registered?.capability,
            records = built.recordCounts[store] ?: 0,
            processings = processings.size,
            revision = processings.mapNotNull { it.revision }.maxOrNull(),
            closed = items.any { it is TimelineItem.Closed },
            recording = status,
            completeness = Completeness(reasons),
        )
    }

    private fun groupReasons(built: Built): List<Incompleteness> = buildList {
        val gaps = timeline.filterIsInstance<TimelineItem.Gap>()
        if (gaps.isNotEmpty()) add(Incompleteness.RecordsDropped(gaps.size, gaps.sumOf { it.dropped }))
        stats?.let { if (it.evicted > 0) add(Incompleteness.RecordsEvicted(it.evicted)) }
        stats?.let { if (it.publishedAfterStop > 0) add(Incompleteness.RecordsAfterStop(it.publishedAfterStop)) }
        for (mark in timeline.filterIsInstance<TimelineItem.Damage>()) if (mark.mark !is SegmentMark.Unfinished) add(Incompleteness.Damaged(mark.mark))
        if (timeline.any { it is TimelineItem.Stopped }) {
            val open = built.storeIds.filter { store -> timeline.none { it is TimelineItem.Closed && it.store == store } }
            if (open.isNotEmpty()) add(Incompleteness.StoppedWhileOpen(open))
        }
        val unattributed = timeline.count { it is TimelineItem.Unattributed && it.store == null }
        if (unattributed > 0) add(Incompleteness.Unattributed(null, unattributed))
    }

    // --- enrichment from a recording ---

    private sealed interface Enrichment {
        class Done(val timeline: List<TimelineItem>) : Enrichment

        class Mismatch(val reason: String) : Enrichment
    }

    private fun enrich(timeline: List<TimelineItem>, store: StoreInstanceId, recording: Recording<*, *, *, *>): Enrichment {
        val decisions = timeline.withIndex().filter { (_, item) -> item is TimelineItem.Processing && item.store == store && item.isDecision }
        // A recording that begins at a checkpoint covers the decisions after the one that committed its first revision.
        val startRevision = recording.initial.revision
        val from = if (startRevision == 0L) 0 else decisions.indexOfFirst { (_, item) -> (item as TimelineItem.Processing).revision == startRevision }.let {
            if (it < 0) return Enrichment.Mismatch("the recording begins at revision $startRevision, which the journal does not show for $store")
            it + 1
        }
        val aligned = decisions.drop(from)
        if (aligned.size < recording.length) return Enrichment.Mismatch("the recording has ${recording.length} steps, the journal ${aligned.size} decisions of $store after revision $startRevision")
        val enriched = timeline.toMutableList()
        for ((step, pair) in recording.steps.zip(aligned).withIndex()) {
            val (recorded, indexed) = pair
            val index = indexed.index
            val item = indexed.value as TimelineItem.Processing
            val problem: String? = when (recorded) {
                is RecordedStep.Committed<*, *, *, *> -> {
                    val revision = recorded.decision.snapshot.revision
                    if (item.revision != revision) "step $step: recorded revision $revision, the journal has ${item.revision ?: "no commit"} for input ${item.input}" else null
                }
                is RecordedStep.Ignored<*, *, *, *> ->
                    if (item.ignored != null && item.ignored != recorded.reason.name) "step $step: recorded as ignored (${recorded.reason}), the journal says ${item.ignored}"
                    else if (item.ignored == null && item.commits.isNotEmpty()) "step $step: recorded as ignored, the journal has a commit for input ${item.input}"
                    else null
                is RecordedStep.Failed<*, *, *, *> ->
                    if (item.outcome != null && item.outcome.kind != OutcomeKind.Failed && item.outcome.kind != OutcomeKind.Recovered) "step $step: recorded as failed, the journal says ${item.outcome.kind} for input ${item.input}" else null
                else -> null
            }
            if (problem != null) return Enrichment.Mismatch(problem)
            val before = recording.snapshotAt(step)
            val after = recording.snapshotAt(step + 1)
            enriched[index] = item.copy(recorded = recorded, before = before, after = after, diff = SnapshotDiff.between(before, after))
        }
        return Enrichment.Done(enriched)
    }

    // --- building the timeline from the journal ---

    private class PayloadCounts(var omitted: Int = 0, var unavailable: Int = 0)

    private class Built(val items: List<TimelineItem>, val storeIds: List<StoreInstanceId>, val recordCounts: Map<StoreInstanceId, Int>, private val payloadCounts: Map<StoreInstanceId, PayloadCounts>) {
        fun payloads(store: StoreInstanceId): PayloadCounts = payloadCounts[store] ?: PayloadCounts()
    }

    private class ProcessingBuilder(val groupSeq: GroupSeq, val elapsed: Duration, val store: StoreInstanceId, val input: InputId, val kind: InputDescriptor<*>?, val ordinal: Long) {
        var outcome: OutcomeDescriptor? = null
        var duration: Duration? = null
        val commits = mutableListOf<TimelineItem.Commit>()
        val events = mutableListOf<Payload<*>>()
        val failures = mutableListOf<actron.observability.FailureDescriptor>()
        var decision: JournalEntry.DecisionCommitted? = null
        var ignored: String? = null
        var message: actron.observability.MessageRef? = null
        var source: String? = null

        fun build(): TimelineItem.Processing = TimelineItem.Processing(
            groupSeq, elapsed, store, input, kind,
            cause = when (kind) {
                is InputDescriptor.Transaction -> kind.origin
                is InputDescriptor.Recovery -> kind.origin
                else -> null
            },
            ordinal = ordinal, outcome = outcome, duration = duration, commits = commits.toList(), events = events.toList(), failures = failures.toList(),
            decision = decision, ignored = ignored, recorded = null, before = null, after = null, diff = null, message = message, source = source,
        )
    }

    private class Accepted(val groupSeq: GroupSeq, val elapsed: Duration, val kind: InputDescriptor<*>) {
        var consumed = false
    }

    private class Builder(private val events: List<JournalFileEvent>) {
        // A slot is a finished item, a processing still collecting its records, or an acceptance
        // that becomes a Pending item unless a processing or a discard consumes it.
        private val slots = mutableListOf<Any>()
        private val processings = mutableMapOf<Pair<StoreInstanceId, InputId>, ProcessingBuilder>()
        private val accepted = mutableMapOf<Pair<StoreInstanceId, InputId>, Accepted>()
        private val storeIds = mutableListOf<StoreInstanceId>()
        private val recordCounts = mutableMapOf<StoreInstanceId, Int>()
        private val payloadCounts = mutableMapOf<StoreInstanceId, PayloadCounts>()

        fun build(): Built {
            for (event in events) {
                when (event) {
                    is JournalFileEvent.Mark -> slots += TimelineItem.Damage(event.mark)
                    is JournalFileEvent.Record -> record(event.record)
                }
            }
            val items = slots.mapNotNull { slot ->
                when (slot) {
                    is TimelineItem -> slot
                    is ProcessingBuilder -> slot.build()
                    is Accepted -> if (slot.consumed) null else null
                    else -> null
                }
            }
            // Acceptances nobody consumed are inputs without an end, at the position they were accepted.
            val pending = accepted.filterValues { !it.consumed }.map { (key, acceptance) -> TimelineItem.Pending(acceptance.groupSeq, acceptance.elapsed, key.first, key.second, acceptance.kind) }
            val ordered = (items + pending).sortedWith(compareBy<TimelineItem, Long?>(nullsLast()) { it.groupSeq?.value })
            return Built(stableDamage(items, pending, ordered), storeIds.toList(), recordCounts.toMap(), payloadCounts.toMap())
        }

        // Sorting by sequence would move damage marks (no sequence) to the end; keep them where they were read.
        private fun stableDamage(items: List<TimelineItem>, pending: List<TimelineItem.Pending>, ordered: List<TimelineItem>): List<TimelineItem> {
            if (pending.isEmpty()) return items
            if (items.none { it is TimelineItem.Damage }) return ordered
            val result = mutableListOf<TimelineItem>()
            val pendingBySeq = pending.sortedBy { it.groupSeq.value }.toMutableList()
            for (item in items) {
                val seq = item.groupSeq?.value
                if (seq != null) while (pendingBySeq.isNotEmpty() && pendingBySeq.first().groupSeq.value < seq) result += pendingBySeq.removeFirst()
                result += item
            }
            result += pendingBySeq
            return result
        }

        private fun record(record: JournalRecord<*, *, *>) {
            val store = record.store
            if (store != null) {
                if (store !in storeIds) storeIds += store
                recordCounts[store] = (recordCounts[store] ?: 0) + 1
            }
            val seq = record.groupSeq
            val elapsed = record.elapsed
            when (val entry = record.entry) {
                is JournalEntry.StoreRegistered -> slots += TimelineItem.Registered(seq, elapsed, checkNotNull(store), entry.capability)
                is JournalEntry.InputAccepted<*> -> {
                    count(store, entry.kind)
                    val acceptance = Accepted(seq, elapsed, entry.kind)
                    accepted[checkNotNull(store) to entry.input] = acceptance
                }
                is JournalEntry.InputDiscarded -> {
                    val acceptance = accepted[checkNotNull(store) to entry.input]?.also { it.consumed = true }
                    slots += TimelineItem.Discarded(seq, elapsed, store, entry.input, acceptance?.kind, entry.reason)
                }
                is JournalEntry.ProcessingStarted -> {
                    val acceptance = accepted[checkNotNull(store) to entry.input]?.also { it.consumed = true }
                    val processing = ProcessingBuilder(seq, elapsed, store, entry.input, acceptance?.kind, entry.ordinal)
                    processings[store to entry.input] = processing
                    slots += processing
                }
                is JournalEntry.StateCommitted<*> -> {
                    count(store, entry.previous)
                    count(store, entry.state)
                    attach(store, entry.input, record) { commits += TimelineItem.Commit(entry.revision, entry.previous, entry.state) }
                }
                is JournalEntry.EventEmitted<*> -> {
                    count(store, entry.event)
                    attach(store, entry.input, record) { events += entry.event }
                }
                is JournalEntry.FailureReported -> attach(store, entry.input, record) { failures += entry.failure }
                is JournalEntry.ProcessingFinished -> attach(store, entry.input, record) {
                    outcome = entry.outcome
                    duration = entry.duration
                }
                JournalEntry.StoreClosed -> slots += TimelineItem.Closed(seq, elapsed, checkNotNull(store))
                is JournalEntry.InputRejected<*> -> {
                    count(store, entry.action)
                    slots += TimelineItem.Rejected(seq, elapsed, checkNotNull(store), entry.action, entry.reason)
                }
                is JournalEntry.DecisionCommitted -> {
                    for (command in entry.commands) count(store, command.command)
                    attach(store, entry.input, record) { decision = entry }
                }
                is JournalEntry.DecisionIgnored -> attach(store, entry.input, record) { ignored = entry.reason }
                is JournalEntry.BridgeSent -> slots += TimelineItem.Sent(seq, elapsed, checkNotNull(store), entry.input, entry.message, entry.to, entry.delivered, entry.cause)
                is JournalEntry.BridgeReceived -> attach(store, entry.input, record) { message = entry.message }
                is JournalEntry.BridgeDropped -> slots += TimelineItem.Dropped(seq, elapsed, checkNotNull(store), entry.message, entry.reason)
                is JournalEntry.EffectQueued<*> -> {
                    count(store, entry.event)
                    slots += TimelineItem.Effect(seq, elapsed, checkNotNull(store), entry.effect, entry)
                }
                is JournalEntry.EffectHandlingStarted -> slots += TimelineItem.Effect(seq, elapsed, checkNotNull(store), entry.effect, entry)
                is JournalEntry.EffectAcknowledged -> slots += TimelineItem.Effect(seq, elapsed, checkNotNull(store), entry.effect, entry)
                is JournalEntry.EffectDiscarded -> slots += TimelineItem.Effect(seq, elapsed, checkNotNull(store), entry.effect, entry)
                is JournalEntry.ExternalReceived -> attach(store, entry.input, record) { source = entry.source }
                is JournalEntry.CheckpointCreated -> slots += TimelineItem.Checkpoint(seq, elapsed, entry.members, entry.sources, entry.inFlight)
                is JournalEntry.CommandsAbandoned -> slots += TimelineItem.Abandoned(seq, elapsed, checkNotNull(store), entry.reason, entry.queued, entry.running)
                is JournalEntry.JournalGap -> slots += TimelineItem.Gap(seq, elapsed, entry.dropped)
                JournalEntry.RecordingStopped -> slots += TimelineItem.Stopped(seq, elapsed)
            }
        }

        private inline fun attach(store: StoreInstanceId?, input: InputId?, record: JournalRecord<*, *, *>, update: ProcessingBuilder.() -> Unit) {
            val processing = if (store != null && input != null) processings[store to input] else null
            if (processing != null) processing.update() else slots += TimelineItem.Unattributed(record.groupSeq, record.elapsed, store, record.entry)
        }

        private fun count(store: StoreInstanceId?, kind: InputDescriptor<*>) {
            when (kind) {
                is InputDescriptor.Dispatch -> count(store, kind.action)
                else -> Unit
            }
        }

        private fun count(store: StoreInstanceId?, payload: Payload<*>) {
            val counts = payloadCounts.getOrPut(store ?: return) { PayloadCounts() }
            when (payload) {
                Payload.Omitted -> counts.omitted++
                Payload.Unavailable -> counts.unavailable++
                else -> Unit
            }
        }
    }

    companion object {
        /** An inspector of [records] as they are, with no marks. */
        fun of(records: List<JournalRecord<*, *, *>>, recordings: Map<StoreInstanceId, Recording<*, *, *, *>> = emptyMap(), stats: JournalStats? = null): Inspector =
            Inspector(records.map { JournalFileEvent.Record(it as JournalRecord<Nothing, Nothing, Nothing>) }, recordings, stats)

        /** An inspector of a live session's retained records, with its counters. */
        fun of(session: RecordingSession, recordings: Map<StoreInstanceId, Recording<*, *, *, *>> = emptyMap()): Inspector =
            of(session.records(), recordings, session.stats)

        /** An inspector of a session read from its files, with the marks of what could not be read. */
        fun of(files: JournalFiles, session: RuntimeSessionId, recordings: Map<StoreInstanceId, Recording<*, *, *, *>> = emptyMap()): Inspector =
            Inspector(files.read(session).events, recordings)
    }
}

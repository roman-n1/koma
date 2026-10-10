package actron.timetravel.inspect

import actron.core.InputAttribution

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
import actron.observability.RecordSubject
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
    private val statistics: InspectorStatistics = InspectorStatistics.FileHistory,
) {
    /** The journal's records, in order. */
    val records: List<JournalRecord<*, *, *>> = events.filterIsInstance<JournalFileEvent.Record>().map { it.record }

    constructor(events: List<JournalFileEvent>, recordings: Map<StoreInstanceId, Recording<*, *, *, *>>, stats: JournalStats) :
        this(events, recordings, InspectorStatistics.Live(stats))

    /** Identifies the observed run only when at least one journal record establishes it. */
    fun withRun(accept: (RuntimeSessionId, MachineGroupId, ExecutionMode) -> Unit): Boolean {
        if (records.isEmpty()) return false
        val first = records.first()
        accept(first.session, first.group, first.mode)
        return true
    }

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
            if (store !in recordings) {
                enriched[store] = RecordingStatus.Unrecorded
                continue
            }
            val recording = recordings.getValue(store)
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
    fun timelineOf(store: StoreInstanceId): List<TimelineItem> = timeline.filter { it.subject == store || it.subject == RecordSubject.Session }

    /**
     * Whether [store]'s history can be replayed by a `ReplaySession`, and if not, why: replay
     * decides a recording again, so it needs one that matches the journal; the journal alone is
     * `InspectOnly`.
     */
    fun replayability(store: StoreInstanceId): Availability {
        val view = stores.firstOrNull { it.id == store } ?: return Availability.Unavailable(listOf("the journal has no records of $store"))
        val reasons = mutableListOf<String>()
        when (val status = view.recording) {
            RecordingStatus.Unrecorded -> reasons += "no recording is attached for $store; the journal's capability is ${view.capabilityLabel("unknown")}, which can be shown, not replayed"
            is RecordingStatus.Mismatch -> reasons += "the recording attached for $store does not match the journal: ${status.reason}"
            is RecordingStatus.Attached -> Unit
        }
        return if (reasons.isEmpty()) Availability.Available else Availability.Unavailable(reasons)
    }

    private fun storeView(store: StoreInstanceId, built: Built, timeline: List<TimelineItem>): StoreView {
        val items = timeline.filter { it.subject == store }
        val processings = items.filterIsInstance<TimelineItem.Processing>()
        val status = recordingStatus.getValue(store)
        val reasons = mutableListOf<Incompleteness>()
        val registrations = items.filterIsInstance<TimelineItem.Registered>()
        if (registrations.isEmpty()) reasons += Incompleteness.NotRegistered(store)
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
            registrations = registrations,
            records = built.recordCounts[store] ?: 0,
            processings = processings.size,
            revisions = buildList { for (processing in processings) processing.withRevision { add(it) } },
            closed = items.any { it is TimelineItem.Closed },
            recording = status,
            completeness = Completeness(reasons),
        )
    }

    private fun groupReasons(built: Built): List<Incompleteness> = buildList {
        val gaps = timeline.filterIsInstance<TimelineItem.Gap>()
        if (gaps.isNotEmpty()) add(Incompleteness.RecordsDropped(gaps.size, gaps.sumOf { it.dropped }))
        statistics.withLive { if (it.evicted > 0) add(Incompleteness.RecordsEvicted(it.evicted)) }
        statistics.withLive { if (it.publishedAfterStop > 0) add(Incompleteness.RecordsAfterStop(it.publishedAfterStop)) }
        for (mark in timeline.filterIsInstance<TimelineItem.Damage>()) if (mark.mark !is SegmentMark.Unfinished) add(Incompleteness.Damaged(mark.mark))
        if (timeline.any { it is TimelineItem.Stopped }) {
            val open = built.storeIds.filter { store -> timeline.none { it is TimelineItem.Closed && it.subject == store } }
            if (open.isNotEmpty()) add(Incompleteness.StoppedWhileOpen(open))
        }
        val unattributed = timeline.count { it is TimelineItem.Unattributed && it.subject == RecordSubject.Session }
        if (unattributed > 0) add(Incompleteness.Unattributed(RecordSubject.Session, unattributed))
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
        val from = if (startRevision == 0L) 0 else decisions.indexOfFirst { (_, item) -> (item as TimelineItem.Processing).hasRevision(startRevision) }.let {
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
            when (recorded) {
                is RecordedStep.Committed -> {
                    val revision = recorded.decision.snapshot.revision
                    if (!item.hasRevision(revision)) return Enrichment.Mismatch("step $step: recorded revision $revision, the journal has ${item.revisionLabel("no commit")} for input ${item.input}")
                }
                is RecordedStep.Ignored -> {
                    var reason = ""
                    val ignored = item.withIgnored { reason = it }
                    if (ignored && reason != recorded.reason.name) return Enrichment.Mismatch("step $step: recorded as ignored (${recorded.reason}), the journal says $reason")
                    if (!ignored && item.commits.isNotEmpty()) return Enrichment.Mismatch("step $step: recorded as ignored, the journal has a commit for input ${item.input}")
                }
                is RecordedStep.Failed -> {
                    var agrees = true
                    var actual = ""
                    item.progress.withEnd { outcome, _ ->
                        actual = outcome.kind.toString()
                        agrees = outcome.kind == OutcomeKind.Failed || outcome.kind == OutcomeKind.Recovered
                    }
                    if (!agrees) return Enrichment.Mismatch("step $step: recorded as failed, the journal says $actual for input ${item.input}")
                }
            }
            val before = recording.snapshotAt(step)
            val after = recording.snapshotAt(step + 1)
            enriched[index] = item.copy(attachment = ProcessingAttachment.Recorded(recorded, before, after))
        }
        return Enrichment.Done(enriched)
    }

    // --- building the timeline from the journal ---

    private class PayloadCounts(var omitted: Int = 0, var unavailable: Int = 0)

    private class Built(val items: List<TimelineItem>, val storeIds: List<StoreInstanceId>, val recordCounts: Map<StoreInstanceId, Int>, private val payloadCounts: Map<StoreInstanceId, PayloadCounts>) {
        fun payloads(store: StoreInstanceId): PayloadCounts = payloadCounts[store] ?: PayloadCounts()
    }

    private class ProcessingBuilder(val groupSeq: GroupSeq, val elapsed: Duration, val store: StoreInstanceId, val input: InputId, val acceptance: InputTrace, val ordinal: Long) {
        var progress: ProcessingProgress = ProcessingProgress.Unfinished
        val commits = mutableListOf<TimelineItem.Commit>()
        val events = mutableListOf<Payload<*>>()
        val failures = mutableListOf<actron.observability.FailureDescriptor>()
        val decisions = mutableListOf<DecisionEvidence>()
        val provenance = mutableListOf<InputProvenance>()
        fun build(): TimelineItem.Processing = TimelineItem.Processing(
            groupSeq, elapsed, store, input, acceptance, acceptance.cause, ordinal, progress,
            commits.toList(), events.toList(), failures.toList(), decisions.toList(), ProcessingAttachment.JournalOnly, provenance.toList(),
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
            val items = buildList {
                for (slot in slots) when (slot) {
                    is TimelineItem -> add(slot)
                    is ProcessingBuilder -> add(slot.build())
                }
            }
            // Acceptances nobody consumed are inputs without an end, at the position they were accepted.
            val pending = accepted.filterValues { !it.consumed }.map { (key, acceptance) -> TimelineItem.Pending(acceptance.groupSeq, acceptance.elapsed, key.first, key.second, acceptance.kind) }
            val ordered = (items + pending).sortedWith { a: TimelineItem, b: TimelineItem ->
                when {
                    a is TimelineItem.JournalItem && b is TimelineItem.JournalItem -> a.groupSeq.value.compareTo(b.groupSeq.value)
                    a is TimelineItem.JournalItem -> -1
                    b is TimelineItem.JournalItem -> 1
                    else -> 0
                }
            }
            return Built(stableDamage(items, pending, ordered), storeIds.toList(), recordCounts.toMap(), payloadCounts.toMap())
        }

        // Sorting by sequence would move damage marks (no sequence) to the end; keep them where they were read.
        private fun stableDamage(items: List<TimelineItem>, pending: List<TimelineItem.Pending>, ordered: List<TimelineItem>): List<TimelineItem> {
            if (pending.isEmpty()) return items
            if (items.none { it is TimelineItem.Damage }) return ordered
            val result = mutableListOf<TimelineItem>()
            val pendingBySeq = pending.sortedBy { it.groupSeq.value }.toMutableList()
            for (item in items) {
                item.withPosition { seq, _ ->
                    while (pendingBySeq.isNotEmpty() && pendingBySeq.first().groupSeq.value < seq.value) result += pendingBySeq.removeFirst()
                }
                result += item
            }
            result += pendingBySeq
            return result
        }

        private fun record(record: JournalRecord<*, *, *>) {
            val subject = record.store
            if (subject is StoreInstanceId) {
                if (subject !in storeIds) storeIds += subject
                recordCounts[subject] = (recordCounts[subject] ?: 0) + 1
            }
            fun member(): StoreInstanceId {
                check(subject is StoreInstanceId)
                return subject
            }
            val seq = record.groupSeq
            val elapsed = record.elapsed
            when (val entry = record.entry) {
                is JournalEntry.StoreRegistered -> slots += TimelineItem.Registered(seq, elapsed, member(), entry.capability)
                is JournalEntry.InputAccepted<*> -> {
                    count(subject, entry.kind)
                    val acceptance = Accepted(seq, elapsed, entry.kind)
                    accepted[member() to entry.input] = acceptance
                }
                is JournalEntry.InputDiscarded -> {
                    val store = member()
                    val acceptance = consume(store to entry.input)
                    slots += TimelineItem.Discarded(seq, elapsed, store, entry.input, acceptance, entry.reason)
                }
                is JournalEntry.ProcessingStarted -> {
                    val store = member()
                    val acceptance = consume(store to entry.input)
                    val processing = ProcessingBuilder(seq, elapsed, store, entry.input, acceptance, entry.ordinal)
                    processings[store to entry.input] = processing
                    slots += processing
                }
                is JournalEntry.StateCommitted<*> -> {
                    count(subject, entry.previous)
                    count(subject, entry.state)
                    attach(subject, entry.input, record) { commits += TimelineItem.Commit(entry.revision, entry.previous, entry.state) }
                }
                is JournalEntry.EventEmitted<*> -> {
                    count(subject, entry.event)
                    attach(subject, entry.input, record) { events += entry.event }
                }
                is JournalEntry.FailureReported -> attach(subject, entry.input, record) { failures += entry.failure }
                is JournalEntry.ProcessingFinished -> attach(subject, entry.input, record) {
                    progress = ProcessingProgress.Finished(entry.outcome, entry.duration)
                }
                JournalEntry.StoreClosed -> slots += TimelineItem.Closed(seq, elapsed, member())
                is JournalEntry.InputRejected<*> -> {
                    count(subject, entry.action)
                    slots += TimelineItem.Rejected(seq, elapsed, member(), entry.action, entry.reason)
                }
                is JournalEntry.DecisionCommitted -> {
                    for (command in entry.commands) count(subject, command.command)
                    attach(subject, entry.input, record) { decisions += DecisionEvidence.Committed(entry) }
                }
                is JournalEntry.DecisionIgnored -> attach(subject, entry.input, record) { decisions += DecisionEvidence.Ignored(entry.reason) }
                is JournalEntry.BridgeSent -> slots += TimelineItem.Sent(seq, elapsed, member(), entry.input, entry.message, entry.to, entry.delivered, entry.cause)
                is JournalEntry.BridgeReceived -> attach(subject, entry.input, record) { provenance += InputProvenance.Bridge(entry.message) }
                is JournalEntry.BridgeDropped -> slots += TimelineItem.Dropped(seq, elapsed, member(), entry.message, entry.reason)
                is JournalEntry.EffectQueued<*> -> {
                    count(subject, entry.event)
                    slots += TimelineItem.Effect(seq, elapsed, member(), entry.effect, entry)
                }
                is JournalEntry.EffectHandlingStarted -> slots += TimelineItem.Effect(seq, elapsed, member(), entry.effect, entry)
                is JournalEntry.EffectAcknowledged -> slots += TimelineItem.Effect(seq, elapsed, member(), entry.effect, entry)
                is JournalEntry.EffectDiscarded -> slots += TimelineItem.Effect(seq, elapsed, member(), entry.effect, entry)
                is JournalEntry.ExternalReceived -> attach(subject, entry.input, record) { provenance += InputProvenance.External(entry.source) }
                is JournalEntry.CheckpointCreated -> slots += TimelineItem.Checkpoint(seq, elapsed, entry.members, entry.sources, entry.inFlight)
                is JournalEntry.CommandsAbandoned -> slots += TimelineItem.Abandoned(seq, elapsed, member(), entry.reason, entry.queued, entry.running)
                is JournalEntry.JournalGap -> slots += TimelineItem.Gap(seq, elapsed, entry.dropped)
                JournalEntry.RecordingStopped -> slots += TimelineItem.Stopped(seq, elapsed)
            }
        }

        private fun consume(key: Pair<StoreInstanceId, InputId>): InputTrace {
            if (key !in accepted) return InputTrace.UnrecordedAcceptance
            val acceptance = accepted.getValue(key)
            acceptance.consumed = true
            return InputTrace.Accepted(acceptance.groupSeq, acceptance.elapsed, acceptance.kind)
        }

        private inline fun attach(subject: RecordSubject, input: InputAttribution, record: JournalRecord<*, *, *>, update: ProcessingBuilder.() -> Unit) {
            if (subject is StoreInstanceId && input is InputId) {
                val key = subject to input
                if (key in processings) { processings.getValue(key).update(); return }
            }
            slots += TimelineItem.Unattributed(record.groupSeq, record.elapsed, subject, record.entry)
        }

        private fun count(subject: RecordSubject, kind: InputDescriptor<*>) {
            when (kind) {
                is InputDescriptor.Dispatch -> count(subject, kind.action)
                else -> Unit
            }
        }

        private fun count(subject: RecordSubject, payload: Payload<*>) {
            if (subject !is StoreInstanceId) return
            val counts = payloadCounts.getOrPut(subject) { PayloadCounts() }
            when (payload) {
                Payload.Omitted -> counts.omitted++
                Payload.Unavailable -> counts.unavailable++
                else -> Unit
            }
        }
    }

    companion object {
        /** An inspector of [records] as they are, with no marks. */
        fun of(records: List<JournalRecord<*, *, *>>, recordings: Map<StoreInstanceId, Recording<*, *, *, *>> = emptyMap(), statistics: InspectorStatistics = InspectorStatistics.FileHistory): Inspector =
            Inspector(records.map { JournalFileEvent.Record(it as JournalRecord<Nothing, Nothing, Nothing>) }, recordings, statistics)

        /** An inspector of a live session's retained records, with its counters. */
        fun of(session: RecordingSession, recordings: Map<StoreInstanceId, Recording<*, *, *, *>> = emptyMap()): Inspector =
            of(session.records(), recordings, InspectorStatistics.Live(session.stats))

        /** An inspector of a session read from its files, with the marks of what could not be read. */
        fun of(files: JournalFiles, session: RuntimeSessionId, recordings: Map<StoreInstanceId, Recording<*, *, *, *>> = emptyMap()): Inspector =
            Inspector(files.read(session).events, recordings)
    }
}

@ExperimentalActronApi
sealed interface InspectorStatistics {
    fun withLive(accept: (JournalStats) -> Unit)
    data object FileHistory : InspectorStatistics { override fun withLive(accept: (JournalStats) -> Unit) {} }
    data class Live(val stats: JournalStats) : InspectorStatistics { override fun withLive(accept: (JournalStats) -> Unit) { accept(stats) } }
}

@OptIn(ExperimentalActronApi::class)
internal fun TimelineItem.Processing.hasRevision(revision: Long): Boolean {
    var matches = false
    withRevision { matches = it == revision }
    return matches
}
@OptIn(ExperimentalActronApi::class)
internal fun TimelineItem.Processing.revisionLabel(fallback: String): String {
    var text = fallback
    withRevision { text = it.toString() }
    return text
}
@OptIn(ExperimentalActronApi::class)
internal fun StoreView.capabilityLabel(fallback: String): String {
    var text = fallback
    withCapability { text = it.toString() }
    return text
}

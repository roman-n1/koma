package actron.timetravel.inspect

import actron.core.ExperimentalActronApi
import actron.observability.InputDescriptor
import actron.observability.JournalFormat
import actron.observability.StoreInstanceId
import actron.observability.file.SegmentMark
import actron.statechart.machine.MachineSnapshot
import actron.timetravel.RecordedStep

/**
 * The inspected history as lines of text: for a terminal, a log, a test, or a debug UI that
 * has no widget for something yet. It prints what the items hold, as [JournalFormat] does.
 */
@ExperimentalActronApi
object InspectorText {
    /**
     * The session, its completeness and its Stores, one per line with their capability, counts,
     * recording status and completeness.
     */
    fun overview(inspector: Inspector): List<String> = buildList {
        add("session=${inspector.session ?: "-"} group=${inspector.group ?: "-"} mode=${inspector.mode ?: "-"} records=${inspector.records.size} ${completeness(inspector.completeness)}")
        for (reason in inspector.completeness.reasons.filter { it.store() == null }) add("  ! ${reason(reason)}")
        for (store in inspector.stores) {
            add("  ${store.id} ${store.capability ?: "unregistered"} records=${store.records} processings=${store.processings} revision=${store.revision ?: "-"}${if (store.closed) " closed" else " open"} recording=${recording(store.recording)} ${completeness(store.completeness)}")
            for (reason in store.completeness.reasons) add("    ! ${reason(reason)}")
        }
    }

    /**
     * One line per item of the timeline (of [store], when given): position, time, Store and what happened.
     */
    fun timeline(inspector: Inspector, store: StoreInstanceId? = null): List<String> =
        (if (store == null) inspector.timeline else inspector.timelineOf(store)).map { line(it) }

    /** One line for an item. */
    fun line(item: TimelineItem): String = buildString {
        append(item.groupSeq ?: "-")
        item.elapsed?.let { append(" +").append(it) }
        item.store?.let { append(' ').append(it) }
        append(' ')
        when (item) {
            is TimelineItem.Registered -> append("Registered ").append(item.capability)
            is TimelineItem.Processing -> {
                append("Processing ").append(item.input).append(' ').append(input(item))
                append(" -> ").append(item.outcome?.let { outcome(it) } ?: "unfinished")
                item.revision?.let { append(" revision=").append(it) }
                item.duration?.let { append(" in ").append(it) }
                item.ignored?.let { append(" ignored=").append(it) }
                item.message?.let { append(" via=").append(it) }
                item.source?.let { append(" from=").append(it) }
                item.decision?.let { decision ->
                    if (decision.transitions.isNotEmpty()) append(" transitions=").append(decision.transitions.joinToString(",", "[", "]") { "T$it" })
                    if (decision.commands.isNotEmpty()) append(" commands=").append(decision.commands.joinToString(",", "[", "]") { "c${it.id}${it.lane?.let { l -> " $l/${it.policy}" } ?: ""} ${JournalFormat.payload(it.command)}" })
                    if (decision.timersScheduled.isNotEmpty()) append(" timers=").append(decision.timersScheduled.joinToString(",", "[", "]") { "t${it.id}+${it.deadline}" })
                }
                item.activeNodes?.let { append(" active=").append(it.joinToString(",", "[", "]")) }
                if (item.events.isNotEmpty()) append(" events=").append(item.events.size)
                if (item.failures.isNotEmpty()) append(" failures=").append(item.failures.joinToString(",", "[", "]") { JournalFormat.failure(it) })
                if (item.recorded != null) append(" [recorded]")
            }
            is TimelineItem.Discarded -> append("Discarded ").append(item.input).append(' ').append(item.kind?.let { input(it) } ?: "?").append(" ").append(item.reason.kind).append(item.reason.failure?.let { " " + JournalFormat.failure(it) } ?: "")
            is TimelineItem.Pending -> append("Pending ").append(item.input).append(' ').append(input(item.kind)).append(" (no end in the journal)")
            is TimelineItem.Sent -> {
                append("Sent ").append(item.message).append(" -> ").append(item.to).append(if (item.delivered) "" else " undelivered")
                item.cause?.let { append(" reply-to=").append(it) }
            }
            is TimelineItem.Dropped -> append("Dropped ").append(item.message).append(' ').append(item.reason)
            is TimelineItem.Effect -> append(JournalFormat.entry(item.entry))
            is TimelineItem.Checkpoint -> append("Checkpoint members=").append(item.members.joinToString(",", "[", "]")).append(" sources=").append(item.sources.joinToString(",", "[", "]")).append(" inFlight=").append(item.inFlight)
            is TimelineItem.Rejected -> append("Rejected ").append(JournalFormat.payload(item.action)).append(' ').append(item.reason)
            is TimelineItem.Closed -> append("Closed")
            is TimelineItem.Abandoned -> append("Abandoned ").append(item.reason).append(" queued=").append(item.queued.joinToString(",", "[", "]") { "c$it" }).append(" running=").append(item.running.joinToString(",", "[", "]") { "c$it" })
            is TimelineItem.Gap -> append("Gap dropped=").append(item.dropped)
            is TimelineItem.Stopped -> append("Stopped")
            is TimelineItem.Unattributed -> append("Unattributed ").append(JournalFormat.entry(item.entry))
            is TimelineItem.Damage -> append("! ").append(mark(item.mark))
        }
    }

    /**
     * The detail of a position: input and cause, outcome, active nodes, the snapshots before and
     * after (from the recording, else what the journal kept), what changed, commands, timers,
     * events and failures.
     */
    fun detail(item: TimelineItem): List<String> = buildList {
        add(line(item))
        if (item !is TimelineItem.Processing) return@buildList
        item.cause?.let { add("  cause: input $it") }
        item.kind?.let { add("  input: ${input(it)}") }
        add("  outcome: ${item.outcome?.let { outcome(it) } ?: "unfinished: the journal ends before this processing did"}")
        item.activeNodes?.let { add("  active: ${it.joinToString(", ")}") }
        val before = item.before
        val after = item.after
        if (before != null && after != null) {
            add("  before: ${snapshot(before)}")
            add("  after:  ${snapshot(after)}")
            item.diff?.let { diff -> add("  changed: ${diff(diff)}") }
            when (val recorded = item.recorded) {
                is RecordedStep.Committed<*, *, *, *> -> {
                    val decision = recorded.decision
                    if (decision.commands.isNotEmpty()) add("  commands: ${decision.commands.joinToString { "${it.id} ${it.command}${it.lane?.let { l -> " in $l (${it.policy})" } ?: ""} for ${it.scope}" }}")
                    if (decision.cancelledScopes.isNotEmpty()) add("  cancelled: ${decision.cancelledScopes.joinToString()}")
                    if (decision.timersScheduled.isNotEmpty()) add("  timers: ${decision.timersScheduled.joinToString { "${it.id} T${it.transition.index} at ${it.deadline}" }}")
                    if (decision.timersCancelled.isNotEmpty()) add("  timers cancelled: ${decision.timersCancelled.joinToString()}")
                    if (decision.effects.isNotEmpty()) add("  effects: ${decision.effects.joinToString { "${it.id} ${it.event}" }}")
                }
                is RecordedStep.Ignored<*, *, *, *> -> add("  ignored: ${recorded.reason}")
                is RecordedStep.Failed<*, *, *, *> -> add("  failed: ${JournalFormat.failure(recorded.failure)}")
                null -> Unit
            }
        } else {
            for (commit in item.commits) add("  commit revision=${commit.revision}: ${JournalFormat.payload(commit.after)} <- ${JournalFormat.payload(commit.before)}")
            item.decision?.let { decision ->
                if (decision.commands.isNotEmpty()) add("  commands: ${decision.commands.joinToString { "c${it.id} ${JournalFormat.payload(it.command)}${it.lane?.let { l -> " in $l (${it.policy})" } ?: ""} for a${it.scope}" }}")
                if (decision.cancelledScopes.isNotEmpty()) add("  cancelled: ${decision.cancelledScopes.joinToString { "a$it" }}")
                if (decision.timersScheduled.isNotEmpty()) add("  timers: ${decision.timersScheduled.joinToString { "t${it.id} T${it.transition} at +${it.deadline}" }}")
                if (decision.timersCancelled.isNotEmpty()) add("  timers cancelled: ${decision.timersCancelled.joinToString { "t$it" }}")
                if (decision.effects > 0) add("  effects: ${decision.effects}")
            }
            item.ignored?.let { add("  ignored: $it") }
            if (item.recorded == null && item.commits.isEmpty() && item.decision == null && item.ignored == null) add("  (no snapshot in the journal for this processing)")
        }
        for (event in item.events) add("  event: ${JournalFormat.payload(event)}")
        for (failure in item.failures) add("  failure: ${JournalFormat.failure(failure)}")
    }

    /** A completeness as `complete` or `partial`. */
    fun completeness(completeness: Completeness): String = if (completeness.isComplete) "complete" else "partial (${completeness.reasons.size})"

    /** One reason, in words. */
    fun reason(reason: Incompleteness): String = when (reason) {
        is Incompleteness.PayloadsOmitted -> "${reason.store}: ${reason.count} payloads omitted by the policy; states, actions or events are not in the journal"
        is Incompleteness.PayloadsUnavailable -> "${reason.store}: ${reason.count} payloads lost because the policy threw"
        is Incompleteness.RecordsDropped -> "${reason.dropped} records dropped for the sinks in ${reason.gaps} gaps"
        is Incompleteness.RecordsEvicted -> "${reason.count} records evicted from the session's memory; the beginning is gone"
        is Incompleteness.RecordsAfterStop -> "${reason.count} records published after the session stopped were not kept"
        is Incompleteness.Damaged -> "journal files: ${mark(reason.mark)}"
        is Incompleteness.NotRegistered -> "${reason.store}: its registration is not in the journal; its beginning is gone and its capability unknown"
        is Incompleteness.StoppedWhileOpen -> "the session stopped while ${reason.stores.joinToString()} still ran; their later history is not here"
        is Incompleteness.InputsPending -> "${reason.store}: ${reason.count} inputs accepted without an end in the journal"
        is Incompleteness.Unattributed -> "${reason.store ?: "session"}: ${reason.count} records could not be attributed to a processing"
        is Incompleteness.RecordingMismatch -> "${reason.store}: the attached recording is not this run (${reason.reason})"
        is Incompleteness.MessagesUndelivered -> "${reason.store}: ${reason.count} bridge messages went to members that were not attached, had closed or had left; part of the group is not here"
    }

    /** A mark, in words. */
    fun mark(mark: SegmentMark): String = when (mark) {
        is SegmentMark.NotASegment -> "${mark.segment} is not a segment: ${mark.reason}"
        is SegmentMark.UnsupportedFormat -> "${mark.segment} was written by a newer format (file ${mark.fileFormatVersion}, records ${mark.recordFormatVersion})"
        is SegmentMark.Corrupt -> "${mark.segment} is damaged at byte ${mark.offset} after ${mark.recordsRead} records: ${mark.reason}; the rest of it is skipped"
        is SegmentMark.TruncatedTail -> "${mark.segment} was cut short after ${mark.recordsRead} records; ${mark.trailingBytes} bytes of a partial frame dropped"
        is SegmentMark.Unfinished -> "${mark.segment} was still being written (${mark.recordsRead} records, nothing lost)"
        is SegmentMark.MissingSegments -> "segments ${mark.fromIndex}..${mark.toIndex} of ${mark.session} are missing: rotated away, pruned or lost"
        is SegmentMark.SequenceHole -> "${mark.missing} records missing between #${mark.afterGroupSeq} and #${mark.nextGroupSeq}, unexplained by a gap record"
    }

    private fun Incompleteness.store(): StoreInstanceId? = when (this) {
        is Incompleteness.PayloadsOmitted -> store
        is Incompleteness.PayloadsUnavailable -> store
        is Incompleteness.NotRegistered -> store
        is Incompleteness.InputsPending -> store
        is Incompleteness.Unattributed -> store
        is Incompleteness.RecordingMismatch -> store
        is Incompleteness.MessagesUndelivered -> store
        else -> null
    }

    private fun recording(status: RecordingStatus): String = when (status) {
        RecordingStatus.Unrecorded -> "none"
        is RecordingStatus.Attached -> "attached(${status.steps} steps)"
        is RecordingStatus.Mismatch -> "mismatch"
    }

    private fun input(item: TimelineItem.Processing): String = item.kind?.let { input(it) } ?: "?"

    private fun input(kind: InputDescriptor<*>): String = when (kind) {
        InputDescriptor.Startup -> "Startup"
        is InputDescriptor.Dispatch -> "Dispatch ${JournalFormat.payload(kind.action)}"
        is InputDescriptor.Transaction -> "Transaction origin=${kind.origin ?: "?"}"
        is InputDescriptor.Recovery -> "Recovery origin=${kind.origin ?: "?"} ${JournalFormat.failure(kind.failure)}"
    }

    private fun outcome(outcome: actron.observability.OutcomeDescriptor): String = buildString {
        append(outcome.kind)
        if (outcome.commits > 0) append(" commits=").append(outcome.commits)
        outcome.failure?.let { append(' ').append(JournalFormat.failure(it)) }
    }

    private fun snapshot(snapshot: MachineSnapshot<*>): String =
        "revision=${snapshot.revision} active=${snapshot.configuration.active.map { it.value }.sorted().joinToString(",", "[", "]")} context=${snapshot.context}" +
            (if (snapshot.commands.isNotEmpty()) " commands=${snapshot.commands.keys.sortedBy { it.value }.joinToString(",", "[", "]")}" else "") +
            (if (snapshot.timers.isNotEmpty()) " timers=${snapshot.timers.keys.sortedBy { it.value }.joinToString(",", "[", "]")}" else "")

    private fun diff(diff: SnapshotDiff): String = if (diff.isEmpty) "nothing" else buildList {
        if (diff.contextChanged) add("context")
        if (diff.entered.isNotEmpty()) add("entered ${diff.entered.map { it.value }.sorted().joinToString(",")}")
        if (diff.exited.isNotEmpty()) add("exited ${diff.exited.map { it.value }.sorted().joinToString(",")}")
        if (diff.commandsRegistered.isNotEmpty()) add("commands +${diff.commandsRegistered.sortedBy { it.value }.joinToString(",")}")
        if (diff.commandsEnded.isNotEmpty()) add("commands -${diff.commandsEnded.sortedBy { it.value }.joinToString(",")}")
        if (diff.timersScheduled.isNotEmpty()) add("timers +${diff.timersScheduled.sortedBy { it.value }.joinToString(",")}")
        if (diff.timersCancelled.isNotEmpty()) add("timers -${diff.timersCancelled.sortedBy { it.value }.joinToString(",")}")
    }.joinToString("; ")
}

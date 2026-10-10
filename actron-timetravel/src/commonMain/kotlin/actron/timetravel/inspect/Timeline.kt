package actron.timetravel.inspect

import actron.core.InputAttribution

import actron.core.ExperimentalActronApi
import actron.core.InputId
import actron.observability.Capability
import actron.observability.DiscardDescriptor
import actron.observability.FailureDescriptor
import actron.observability.GroupSeq
import actron.observability.InputDescriptor
import actron.observability.JournalEntry
import actron.observability.MessageRef
import actron.observability.OutcomeDescriptor
import actron.observability.Payload
import actron.observability.RecordSubject
import actron.observability.StoreInstanceId
import actron.observability.file.SegmentMark
import actron.statechart.StateId
import actron.statechart.machine.CommandId
import actron.statechart.machine.MachineSnapshot
import actron.statechart.machine.TimerId
import actron.timetravel.RecordedStep
import kotlin.time.Duration

/**
 * One position of an inspected history: what happened, in the group's order, as the journal
 * recorded it and, for a Store with a recording attached, as the machine's snapshots show it.
 */
@ExperimentalActronApi
sealed interface TimelineItem {
    val subject: RecordSubject
    fun withPosition(accept: (GroupSeq, Duration) -> Unit): Boolean = false

    sealed interface JournalItem : TimelineItem {
        val groupSeq: GroupSeq
        val elapsed: Duration
        override fun withPosition(accept: (GroupSeq, Duration) -> Unit): Boolean { accept(groupSeq, elapsed); return true }
    }
    sealed interface StoreItem : JournalItem {
        val store: StoreInstanceId
        override val subject: RecordSubject get() = store
    }

    /** A Store started recording into the session. */
    data class Registered(override val groupSeq: GroupSeq, override val elapsed: Duration, override val store: StoreInstanceId, val capability: Capability) : StoreItem

    /**
     * One processing of one input: from `ProcessingStarted` to `ProcessingFinished`, with the
     * commits, the events, the failures and the decision the journal attributed to it, and, when
     * the Store's recording is attached and matches, the snapshots before and after with their
     * difference.
     *
     * @property acceptance What the journal establishes about the accepted input and its cause
     * @property cause The input whose handler launched this one, for a transaction or a recovery
     * @property progress Whether processing finished, with the actual outcome and duration
     * @property commits The snapshots committed, in order, as the policy kept them
     * @property decisions The actual journaled decision observations
     * @property provenance The observed bridge and external-source receipts
     * @property attachment A matching recording's step and both checkpoints, or journal-only inspection
     */
    data class Processing(
        override val groupSeq: GroupSeq,
        override val elapsed: Duration,
        override val store: StoreInstanceId,
        val input: InputId,
        val acceptance: InputTrace,
        val cause: InputAttribution,
        val ordinal: Long,
        val progress: ProcessingProgress,
        val commits: List<Commit>,
        val events: List<Payload<*>>,
        val failures: List<FailureDescriptor>,
        val decisions: List<DecisionEvidence>,
        val attachment: ProcessingAttachment,
        val provenance: List<InputProvenance> = emptyList(),
    ) : StoreItem {
        val isFinished: Boolean get() = progress is ProcessingProgress.Finished
        val isDecision: Boolean get() = acceptance.isDecision
        fun withRevision(accept: (Long) -> Unit): Boolean {
            if (commits.isNotEmpty()) { accept(commits.last().revision); return true }
            return withDecision { accept(it.revision) }
        }
        fun withActiveNodes(accept: (List<String>) -> Unit): Boolean {
            if (attachment is ProcessingAttachment.Recorded) { accept(attachment.after.configuration.active.map { it.value }.sorted()); return true }
            return withDecision { accept(it.active) }
        }
        fun withDecision(accept: (JournalEntry.DecisionCommitted) -> Unit): Boolean {
            for (evidence in decisions.asReversed()) if (evidence is DecisionEvidence.Committed) { accept(evidence.entry); return true }
            return false
        }
        fun withIgnored(accept: (String) -> Unit): Boolean {
            for (evidence in decisions.asReversed()) if (evidence is DecisionEvidence.Ignored) { accept(evidence.reason); return true }
            return false
        }
        fun withMessage(accept: (MessageRef) -> Unit): Boolean {
            for (evidence in provenance.asReversed()) if (evidence is InputProvenance.Bridge) { accept(evidence.message); return true }
            return false
        }
        fun withSource(accept: (String) -> Unit): Boolean {
            for (evidence in provenance.asReversed()) if (evidence is InputProvenance.External) { accept(evidence.source); return true }
            return false
        }
    }

    /** A snapshot committed by a processing, as the policy kept the two snapshots. */
    data class Commit(val revision: Long, val before: Payload<*>, val after: Payload<*>)

    /** An input the Store accepted and never processed. */
    data class Discarded(override val groupSeq: GroupSeq, override val elapsed: Duration, override val store: StoreInstanceId, val input: InputId, val acceptance: InputTrace, val reason: DiscardDescriptor) : StoreItem

    /** An input the Store accepted that the journal holds no end of: still queued when the journal ends, or its end is lost. */
    data class Pending(override val groupSeq: GroupSeq, override val elapsed: Duration, override val store: StoreInstanceId, val input: InputId, val kind: InputDescriptor<*>) : StoreItem

    /** A consistent cut of the group was taken here: [members] frozen and idle, [sources] snapshotted, [inFlight] messages on their way. */
    data class Checkpoint(override val groupSeq: GroupSeq, override val elapsed: Duration, val members: List<StoreInstanceId>, val sources: List<String>, val inFlight: Int) : JournalItem {
        override val subject: RecordSubject get() = RecordSubject.Session
    }

    /**
     * A bridge routed an effect of [store] to [to] as [message]; [delivered] is false when [to]
     * was not attached, had closed or had left; [cause] is the bridge message [store] was
     * deciding when it emitted the effect, when it was one: what a reply replies to.
     */
    data class Sent(override val groupSeq: GroupSeq, override val elapsed: Duration, override val store: StoreInstanceId, val input: InputAttribution, val message: MessageRef, val to: StoreInstanceId, val delivered: Boolean, val cause: actron.observability.MessageCause = actron.observability.MessageCause.Unprompted) : StoreItem

    /** The bridge message [message], delivered to [store] and not decided, never will be: the store it was delivered to closed ([reason]). */
    data class Dropped(override val groupSeq: GroupSeq, override val elapsed: Duration, override val store: StoreInstanceId, val message: MessageRef, val reason: String) : StoreItem

    /** What the mailbox did with the effect [effect]: [entry] is the journal's word for it. */
    data class Effect(override val groupSeq: GroupSeq, override val elapsed: Duration, override val store: StoreInstanceId, val effect: Long, val entry: JournalEntry<*, *, *>) : StoreItem

    /** An action refused at admission; it never became an input. */
    data class Rejected(override val groupSeq: GroupSeq, override val elapsed: Duration, override val store: StoreInstanceId, val action: Payload<*>, val reason: String) : StoreItem

    /** Every coroutine of the Store has ended. */
    data class Closed(override val groupSeq: GroupSeq, override val elapsed: Duration, override val store: StoreInstanceId) : StoreItem

    /** The Store closed with commands unfinished, for [reason]: [queued] never started, [running] were cancelled. */
    data class Abandoned(override val groupSeq: GroupSeq, override val elapsed: Duration, override val store: StoreInstanceId, val reason: String, val queued: List<Long>, val running: List<Long>) : StoreItem

    /** [dropped] records before this position never reached the sinks. */
    data class Gap(override val groupSeq: GroupSeq, override val elapsed: Duration, val dropped: Long) : JournalItem {
        override val subject: RecordSubject get() = RecordSubject.Session
    }

    /** The session stopped recording here. */
    data class Stopped(override val groupSeq: GroupSeq, override val elapsed: Duration) : JournalItem {
        override val subject: RecordSubject get() = RecordSubject.Session
    }

    /** A record the inspector could not attribute to a processing: it is shown where it is. */
    data class Unattributed(override val groupSeq: GroupSeq, override val elapsed: Duration, override val subject: RecordSubject, val entry: JournalEntry<*, *, *>) : JournalItem

    /** Segments or frames of the journal's files that are missing or damaged at this position. */
    data class Damage(val mark: SegmentMark) : TimelineItem {
        override val subject: RecordSubject get() = RecordSubject.Session
    }
}

/** Acceptance metadata establishes the input's cause and whether it represents a machine decision. */
@ExperimentalActronApi
sealed interface InputTrace {
    val cause: InputAttribution
    val isDecision: Boolean
    fun withKind(accept: (InputDescriptor<*>) -> Unit): Boolean
    data object UnrecordedAcceptance : InputTrace {
        override val cause: InputAttribution get() = InputAttribution.Unattributed
        override val isDecision: Boolean get() = false
        override fun withKind(accept: (InputDescriptor<*>) -> Unit): Boolean = false
    }
    data class Accepted(val groupSeq: GroupSeq, val elapsed: Duration, val kind: InputDescriptor<*>) : InputTrace {
        override val cause: InputAttribution get() = when (kind) {
            is InputDescriptor.Transaction -> kind.origin
            is InputDescriptor.Recovery -> kind.origin
            else -> InputAttribution.Unattributed
        }
        override val isDecision: Boolean get() = kind is InputDescriptor.Startup || kind is InputDescriptor.Dispatch
        override fun withKind(accept: (InputDescriptor<*>) -> Unit): Boolean { accept(kind); return true }
    }
}

@ExperimentalActronApi
sealed interface ProcessingProgress {
    fun withEnd(accept: (OutcomeDescriptor, Duration) -> Unit): Boolean
    data object Unfinished : ProcessingProgress {
        override fun withEnd(accept: (OutcomeDescriptor, Duration) -> Unit): Boolean = false
    }
    data class Finished(val outcome: OutcomeDescriptor, val duration: Duration) : ProcessingProgress {
        override fun withEnd(accept: (OutcomeDescriptor, Duration) -> Unit): Boolean { accept(outcome, duration); return true }
    }
}

/** The actual sequence of journaled machine decisions; repeated observations retain their order. */
@ExperimentalActronApi
sealed interface DecisionEvidence {
    data class Committed(val entry: JournalEntry.DecisionCommitted) : DecisionEvidence
    data class Ignored(val reason: String) : DecisionEvidence
}

@ExperimentalActronApi
sealed interface InputProvenance {
    data class Bridge(val message: MessageRef) : InputProvenance
    data class External(val source: String) : InputProvenance
}

/** An attached recording supplies both checkpoints and the decided step as one consistent unit. */
@ExperimentalActronApi
sealed interface ProcessingAttachment {
    fun withRecording(accept: (Recorded) -> Unit): Boolean
    data object JournalOnly : ProcessingAttachment {
        override fun withRecording(accept: (Recorded) -> Unit): Boolean = false
    }
    data class Recorded(val step: RecordedStep<*, *, *, *>, val before: MachineSnapshot<*>, val after: MachineSnapshot<*>) : ProcessingAttachment {
        val diff: SnapshotDiff get() = SnapshotDiff.between(before, after)
        override fun withRecording(accept: (Recorded) -> Unit): Boolean { accept(this); return true }
    }
}

/**
 * What changed between two snapshots of a machine, computed from the snapshots themselves.
 */
@ExperimentalActronApi
data class SnapshotDiff(
    val revisionAdvanced: Boolean,
    val contextChanged: Boolean,
    val entered: Set<StateId>,
    val exited: Set<StateId>,
    val commandsRegistered: Set<CommandId>,
    val commandsEnded: Set<CommandId>,
    val timersScheduled: Set<TimerId>,
    val timersCancelled: Set<TimerId>,
) {
    val isEmpty: Boolean
        get() = !revisionAdvanced && !contextChanged && entered.isEmpty() && exited.isEmpty() && commandsRegistered.isEmpty() && commandsEnded.isEmpty() && timersScheduled.isEmpty() && timersCancelled.isEmpty()

    companion object {
        fun between(before: MachineSnapshot<*>, after: MachineSnapshot<*>): SnapshotDiff = SnapshotDiff(
            revisionAdvanced = after.revision > before.revision,
            contextChanged = before.context != after.context,
            entered = after.configuration.active - before.configuration.active,
            exited = before.configuration.active - after.configuration.active,
            commandsRegistered = after.commands.keys - before.commands.keys,
            commandsEnded = before.commands.keys - after.commands.keys,
            timersScheduled = after.timers.keys - before.timers.keys,
            timersCancelled = before.timers.keys - after.timers.keys,
        )
    }
}

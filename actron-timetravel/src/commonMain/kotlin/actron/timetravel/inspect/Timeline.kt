package actron.timetravel.inspect

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
    /** The position in the group's journal: the sequence number of the record this item begins with. */
    val groupSeq: GroupSeq?

    /** The session's clock at that record. */
    val elapsed: Duration?

    /** The Store this item is about; `null` for an item of the session itself. */
    val store: StoreInstanceId?

    /** A Store started recording into the session. */
    data class Registered(override val groupSeq: GroupSeq, override val elapsed: Duration, override val store: StoreInstanceId, val capability: Capability) : TimelineItem

    /**
     * One processing of one input: from `ProcessingStarted` to `ProcessingFinished`, with the
     * commits, the events, the failures and the decision the journal attributed to it, and, when
     * the Store's recording is attached and matches, the snapshots before and after with their
     * difference.
     *
     * @property kind What the input was, as the journal kept it; `null` when its acceptance is not
     * in the journal
     * @property cause The input whose handler launched this one, for a transaction or a recovery
     * @property outcome How the processing ended; `null` when the journal ends before it did
     * @property commits The snapshots committed, in order, as the policy kept them
     * @property decision The machine's decision behind the commit, when the Store journals decisions
     * @property ignored The reason the machine ignored the input, when it did
     * @property message The bridge message this input was, when it came over the bridge
     * @property source The external source this input came from, when it did
     * @property recorded The recording's step for this processing, when attached and matching
     * @property before The machine's snapshot before the decision, from the recording
     * @property after The machine's snapshot after the decision, from the recording
     * @property diff What changed between [before] and [after]
     */
    data class Processing(
        override val groupSeq: GroupSeq,
        override val elapsed: Duration,
        override val store: StoreInstanceId,
        val input: InputId,
        val kind: InputDescriptor<*>?,
        val cause: InputId?,
        val ordinal: Long,
        val outcome: OutcomeDescriptor?,
        val duration: Duration?,
        val commits: List<Commit>,
        val events: List<Payload<*>>,
        val failures: List<FailureDescriptor>,
        val decision: JournalEntry.DecisionCommitted?,
        val ignored: String?,
        val recorded: RecordedStep<*, *, *, *>?,
        val before: MachineSnapshot<*>?,
        val after: MachineSnapshot<*>?,
        val diff: SnapshotDiff?,
        val message: MessageRef? = null,
        val source: String? = null,
    ) : TimelineItem {
        /** Whether the journal holds the end of this processing. */
        val isFinished: Boolean get() = outcome != null

        /** The last revision this processing committed, from the commits or the decision. */
        val revision: Long? get() = commits.lastOrNull()?.revision ?: decision?.revision

        /** The active nodes after this processing, from the recording or the journaled decision. */
        val activeNodes: List<String>? get() = after?.configuration?.active?.map { it.value }?.sorted() ?: decision?.active

        /** Whether this processing is a decision of a replay-ready machine: the startup or a dispatch. */
        val isDecision: Boolean get() = kind is InputDescriptor.Startup || kind is InputDescriptor.Dispatch
    }

    /** A snapshot committed by a processing, as the policy kept the two snapshots. */
    data class Commit(val revision: Long, val before: Payload<*>, val after: Payload<*>)

    /** An input the Store accepted and never processed. */
    data class Discarded(override val groupSeq: GroupSeq, override val elapsed: Duration, override val store: StoreInstanceId, val input: InputId, val kind: InputDescriptor<*>?, val reason: DiscardDescriptor) : TimelineItem

    /** An input the Store accepted that the journal holds no end of: still queued when the journal ends, or its end is lost. */
    data class Pending(override val groupSeq: GroupSeq, override val elapsed: Duration, override val store: StoreInstanceId, val input: InputId, val kind: InputDescriptor<*>) : TimelineItem

    /** A consistent cut of the group was taken here: [members] frozen and idle, [sources] snapshotted, [inFlight] messages on their way. */
    data class Checkpoint(override val groupSeq: GroupSeq, override val elapsed: Duration, val members: List<StoreInstanceId>, val sources: List<String>, val inFlight: Int) : TimelineItem {
        override val store: StoreInstanceId? get() = null
    }

    /**
     * A bridge routed an effect of [store] to [to] as [message]; [delivered] is false when [to]
     * was not attached, had closed or had left; [cause] is the bridge message [store] was
     * deciding when it emitted the effect, when it was one: what a reply replies to.
     */
    data class Sent(override val groupSeq: GroupSeq, override val elapsed: Duration, override val store: StoreInstanceId, val input: InputId?, val message: MessageRef, val to: StoreInstanceId, val delivered: Boolean, val cause: MessageRef? = null) : TimelineItem

    /** The bridge message [message], delivered to [store] and not decided, never will be: the store it was delivered to closed ([reason]). */
    data class Dropped(override val groupSeq: GroupSeq, override val elapsed: Duration, override val store: StoreInstanceId, val message: MessageRef, val reason: String) : TimelineItem

    /** What the mailbox did with the effect [effect]: [entry] is the journal's word for it. */
    data class Effect(override val groupSeq: GroupSeq, override val elapsed: Duration, override val store: StoreInstanceId, val effect: Long, val entry: JournalEntry<*, *, *>) : TimelineItem

    /** An action refused at admission; it never became an input. */
    data class Rejected(override val groupSeq: GroupSeq, override val elapsed: Duration, override val store: StoreInstanceId, val action: Payload<*>, val reason: String) : TimelineItem

    /** Every coroutine of the Store has ended. */
    data class Closed(override val groupSeq: GroupSeq, override val elapsed: Duration, override val store: StoreInstanceId) : TimelineItem

    /** The Store closed with commands unfinished, for [reason]: [queued] never started, [running] were cancelled. */
    data class Abandoned(override val groupSeq: GroupSeq, override val elapsed: Duration, override val store: StoreInstanceId, val reason: String, val queued: List<Long>, val running: List<Long>) : TimelineItem

    /** [dropped] records before this position never reached the sinks. */
    data class Gap(override val groupSeq: GroupSeq, override val elapsed: Duration, val dropped: Long) : TimelineItem {
        override val store: StoreInstanceId? get() = null
    }

    /** The session stopped recording here. */
    data class Stopped(override val groupSeq: GroupSeq, override val elapsed: Duration) : TimelineItem {
        override val store: StoreInstanceId? get() = null
    }

    /** A record the inspector could not attribute to a processing: it is shown where it is. */
    data class Unattributed(override val groupSeq: GroupSeq, override val elapsed: Duration, override val store: StoreInstanceId?, val entry: JournalEntry<*, *, *>) : TimelineItem

    /** Segments or frames of the journal's files that are missing or damaged at this position. */
    data class Damage(val mark: SegmentMark) : TimelineItem {
        override val groupSeq: GroupSeq? get() = null
        override val elapsed: Duration? get() = null
        override val store: StoreInstanceId? get() = null
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

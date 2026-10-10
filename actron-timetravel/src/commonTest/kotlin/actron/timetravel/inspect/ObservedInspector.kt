@file:OptIn(actron.core.ExperimentalActronApi::class)
package actron.timetravel.inspect

import actron.observability.*
import actron.statechart.machine.MachineSnapshot
import actron.timetravel.RecordedStep
import kotlin.time.Duration

internal val Inspector.session: RuntimeSessionId? get() { var result: RuntimeSessionId? = null; withRun { session, _, _ -> result = session }; return result }
internal val Inspector.group: MachineGroupId? get() { var result: MachineGroupId? = null; withRun { _, group, _ -> result = group }; return result }
internal val Inspector.mode: ExecutionMode? get() { var result: ExecutionMode? = null; withRun { _, _, mode -> result = mode }; return result }
internal val StoreView.capability: Capability? get() { var result: Capability? = null; withCapability { result = it }; return result }
internal val StoreView.revision: Long? get() { var result: Long? = null; withRevision { result = it }; return result }
internal val TimelineItem.store: StoreInstanceId? get() = subject as? StoreInstanceId
internal val TimelineItem.groupSeq: GroupSeq? get() = (this as? TimelineItem.JournalItem)?.groupSeq
internal val TimelineItem.elapsed: Duration? get() = (this as? TimelineItem.JournalItem)?.elapsed
internal val TimelineItem.Processing.kind: InputDescriptor<*>? get() { var result: InputDescriptor<*>? = null; acceptance.withKind { result = it }; return result }
internal val TimelineItem.Discarded.kind: InputDescriptor<*>? get() { var result: InputDescriptor<*>? = null; acceptance.withKind { result = it }; return result }
internal val TimelineItem.Processing.outcome: OutcomeDescriptor? get() { var result: OutcomeDescriptor? = null; progress.withEnd { outcome, _ -> result = outcome }; return result }
internal val TimelineItem.Processing.duration: Duration? get() { var result: Duration? = null; progress.withEnd { _, duration -> result = duration }; return result }
internal val TimelineItem.Processing.revision: Long? get() { var result: Long? = null; withRevision { result = it }; return result }
internal val TimelineItem.Processing.activeNodes: List<String>? get() { var result: List<String>? = null; withActiveNodes { result = it }; return result }
internal val TimelineItem.Processing.decision: JournalEntry.DecisionCommitted? get() { var result: JournalEntry.DecisionCommitted? = null; withDecision { result = it }; return result }
internal val TimelineItem.Processing.ignored: String? get() { var result: String? = null; withIgnored { result = it }; return result }
internal val TimelineItem.Processing.message: MessageRef? get() { var result: MessageRef? = null; withMessage { result = it }; return result }
internal val TimelineItem.Processing.source: String? get() { var result: String? = null; withSource { result = it }; return result }
internal val TimelineItem.Processing.recorded: RecordedStep<*, *, *, *>? get() = (attachment as? ProcessingAttachment.Recorded)?.step
internal val TimelineItem.Processing.before: MachineSnapshot<*>? get() = (attachment as? ProcessingAttachment.Recorded)?.before
internal val TimelineItem.Processing.after: MachineSnapshot<*>? get() = (attachment as? ProcessingAttachment.Recorded)?.after
internal val TimelineItem.Processing.diff: SnapshotDiff? get() = (attachment as? ProcessingAttachment.Recorded)?.diff

package actron.timetravel.file

private fun actron.observability.file.SegmentEnding.observedIssue(): actron.observability.file.SegmentMark? {
    var observed: actron.observability.file.SegmentMark? = null
    val reported = withIssue { check(observed == null); observed = it }
    kotlin.test.assertEquals(observed != null, reported)
    return observed
}
internal val DecodedGroupSegment.mark: actron.observability.file.SegmentMark? get() = ending.observedIssue()
internal val <C : Any, A : actron.core.Action, CMD : Any, E : actron.core.Event> DecodedRecordingSegment<C, A, CMD, E>.mark: actron.observability.file.SegmentMark? get() = ending.observedIssue()

internal val <C : Any, A : actron.core.Action, CMD : Any, E : actron.core.Event> DecodedRecordingSegment<C, A, CMD, E>.header: RecordingSegmentHeader? get() = when (this) {
    is DecodedRecordingSegment.Unreadable -> null
    is DecodedRecordingSegment.HeaderOnly -> header
    is DecodedRecordingSegment.Readable -> header
}
internal val <C : Any, A : actron.core.Action, CMD : Any, E : actron.core.Event> DecodedRecordingSegment<C, A, CMD, E>.start: actron.statechart.machine.ExecutorCheckpoint<C, CMD>? get() = (this as? DecodedRecordingSegment.Readable)?.start
internal val <C : Any, A : actron.core.Action, CMD : Any, E : actron.core.Event> RecordingFileContents<C, A, CMD, E>.recording: actron.timetravel.Recording<C, A, CMD, E>? get() = (this as? RecordingFileContents.Readable)?.recording
internal val <C : Any, A : actron.core.Action, CMD : Any, E : actron.core.Event> RecordingFileContents<C, A, CMD, E>.firstStep: Int? get() = (this as? RecordingFileContents.Readable)?.firstStep

internal val DecodedGroupSegment.header: GroupSegmentHeader? get() = (this as? DecodedGroupSegment.Readable)?.header
internal val GroupSegmentHeader.cut: RecordedCut? get() = boundary as? RecordedCut
internal val GroupOrderEntry.received: actron.statechart.machine.MessageId? get() = (delivery as? GroupDelivery.Bridged)?.message

internal val GroupRecordingFileContents.recording: actron.timetravel.GroupRecording? get() = (this as? GroupRecordingFileContents.Readable)?.recording
internal val GroupRecordingFileContents.position: Int? get() = (this as? GroupRecordingFileContents.Readable)?.position

internal val <C : Any, A : actron.core.Action, CMD : Any, E : actron.core.Event> RecordingFileSink<C, A, CMD, E>.problem: String? get() = (origin as? actron.timetravel.RecordingOrigin.UnknownBeginning)?.reason

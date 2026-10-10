package actron.observability.file

private fun SegmentEnding.observedIssue(): SegmentMark? {
    var observed: SegmentMark? = null
    val reported = withIssue { check(observed == null); observed = it }
    kotlin.test.assertEquals(observed != null, reported)
    return observed
}
internal val FramesRead.mark: SegmentMark? get() = ending.observedIssue()
internal val DecodedSegment.mark: SegmentMark? get() = ending.observedIssue()
internal val DecodedSegment.header: SegmentHeader? get() {
    var observed: SegmentHeader? = null
    prefix.withHeader { check(observed == null); observed = it }
    return observed
}

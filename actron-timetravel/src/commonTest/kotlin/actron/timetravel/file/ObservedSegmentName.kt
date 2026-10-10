package actron.timetravel.file

internal fun RecordingFileFormat.parseSegmentName(name: String): Pair<actron.observability.StoreInstanceId, Int>? {
    var observed: Pair<actron.observability.StoreInstanceId, Int>? = null
    val found = parseSegmentName(name) { id, index -> check(observed == null); observed = id to index }
    kotlin.test.assertEquals(observed != null, found)
    return observed
}

internal fun GroupRecordingFileFormat.parseSegmentName(name: String): Pair<actron.observability.MachineGroupId, Int>? {
    var observed: Pair<actron.observability.MachineGroupId, Int>? = null
    val found = parseSegmentName(name) { id, index -> check(observed == null); observed = id to index }
    kotlin.test.assertEquals(observed != null, found)
    return observed
}


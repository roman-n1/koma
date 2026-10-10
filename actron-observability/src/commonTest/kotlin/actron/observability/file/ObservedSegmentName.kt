package actron.observability.file

internal fun JournalFileFormat.parseSegmentName(name: String): Pair<actron.observability.RuntimeSessionId, Int>? {
    var observed: Pair<actron.observability.RuntimeSessionId, Int>? = null
    val found = parseSegmentName(name) { id, index -> check(observed == null); observed = id to index }
    kotlin.test.assertEquals(observed != null, found)
    return observed
}


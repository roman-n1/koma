package actron.statechart

internal fun StateChartDefinition.shortestPathTo(target: StateId): StateChartPath? {
    var observed: StateChartPath? = null
    val found = shortestPathTo(target) { check(observed == null); observed = it }
    kotlin.test.assertEquals(observed != null, found)
    return observed
}

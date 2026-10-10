package actron.statechart.test

internal fun <C : Any, CMD : Any> actron.statechart.machine.ExecutorCheckpoint<C, CMD>.remaining(id: actron.statechart.machine.TimerId): kotlin.time.Duration? {
    var observed: kotlin.time.Duration? = null
    val found = remaining(id) { check(observed == null); observed = it }
    kotlin.test.assertEquals(observed != null, found)
    return observed
}

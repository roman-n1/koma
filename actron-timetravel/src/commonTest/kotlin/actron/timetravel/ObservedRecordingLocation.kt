package actron.timetravel

internal val <C : Any, A : actron.core.Action, CMD : Any, E : actron.core.Event> DecodedRecording.Invalid<C, A, CMD, E>.at: String? get() {
    var observed: String? = null
    location.withPart { check(observed == null); observed = it }
    return observed
}

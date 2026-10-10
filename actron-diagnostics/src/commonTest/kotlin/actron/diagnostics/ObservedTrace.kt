package actron.diagnostics

internal val CausalTraceEvent.failureType: String? get() {
    var observed: String? = null
    withFailureType { check(observed == null); observed = it }
    return observed
}
internal val CausalTraceEvent.failureSource: FailureSource? get() = assessment.faults.firstOrNull()?.source
internal val CausalTraceEvent.selection: actron.statechart.machine.DecisionExplanation? get() {
    var observed: actron.statechart.machine.DecisionExplanation? = null
    assessment.withSelection { check(observed == null); observed = it }
    return observed
}

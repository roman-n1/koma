package actron.statechart.test

internal fun <C : Any, A : actron.core.Action, CMD : Any, E : actron.core.Event> actron.statechart.machine.Machine<C, A, CMD, E>.replaySequence(
    initial: actron.statechart.machine.MachineSnapshot<C>, inputs: List<actron.statechart.machine.MachineInput<A>>,
): SequenceFailure<C, A>? {
    var observed: SequenceFailure<C, A>? = null
    val failed = replaySequence(initial, inputs) { check(observed == null); observed = it }
    kotlin.test.assertEquals(observed != null, failed)
    return observed
}

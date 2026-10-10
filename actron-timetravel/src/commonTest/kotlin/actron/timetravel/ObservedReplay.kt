@file:OptIn(actron.core.ExperimentalActronApi::class)
package actron.timetravel

internal fun <C : Any, A : actron.core.Action, CMD : Any, E : actron.core.Event> ReplaySession<C, A, CMD, E>.verify(from: Int = 0, to: Int = length): ReplayMismatch<C, A, CMD, E>? {
    var observed: ReplayMismatch<C, A, CMD, E>? = null
    val reported = verify(from, to) { check(observed == null); observed = it }
    kotlin.test.assertEquals(observed != null, reported)
    return observed
}
internal fun <C : Any, A : actron.core.Action, CMD : Any, E : actron.core.Event> ReplaySession<C, A, CMD, E>.observedStepForward(): ReplayStep<C, A, CMD, E>? = stepForward().takeUnless { it is ReplayStep.Finished }
internal fun GroupReplaySession.observedStepForward(): GroupReplayStep? = stepForward().takeUnless { it is GroupReplayStep.Finished }

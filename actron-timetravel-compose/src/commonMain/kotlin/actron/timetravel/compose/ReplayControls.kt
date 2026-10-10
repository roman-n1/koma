package actron.timetravel.compose

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import actron.core.Action
import actron.core.Event
import actron.core.ExperimentalActronApi
import actron.observability.StoreInstanceId
import actron.statechart.machine.MachineSnapshot
import actron.statechart.toMermaid
import actron.timetravel.ReplaySession
import actron.timetravel.ReplayStep

/**
 * The replay of one Store's recording as the screen drives it (handoff §11, v2): the position,
 * stepping back and forward, seeking, verifying. It holds a [ReplaySession], which decides and
 * compares and never runs anything; the controls only move it and show what it says.
 *
 * @param store The Store the recording is of, for the timeline
 * @param session The replay
 */
@ExperimentalActronApi
@Stable
class ReplayControls<C, A : Action, CMD, E : Event>(val store: StoreInstanceId, val session: ReplaySession<C, A, CMD, E>) {
    /** The replay's position, mirrored for Compose. */
    var position: Int by mutableStateOf(session.position)
        private set

    /** The last divergence [stepForward] met, or `null`; cleared by a move. */
    var divergence: String? by mutableStateOf(null)
        private set

    /** What [verify] last found: `null` before it ran, the mismatch or "no divergence". */
    var verdict: String? by mutableStateOf(null)
        private set

    /** Explicitly requested diagnostic computation; composition itself never re-runs guards. */
    var explanation: actron.statechart.machine.DecisionExplanation? by mutableStateOf(null)
        private set
    var explanationOutcome: String? by mutableStateOf(null)
        private set
    var invariantViolations: List<actron.statechart.machine.InvariantViolation> by mutableStateOf(emptyList())
        private set

    fun explainNext() {
        val explained = session.explainNext()
        explanation = explained?.explanation
        explanationOutcome = explained?.decision?.outcome?.toString()
        invariantViolations = session.checkInvariants()
    }

    val length: Int get() = session.length

    /** The snapshot the replay is at. */
    val snapshot: MachineSnapshot<C>
        get() {
            position
            return session.snapshot
        }

    /** The machine's definition as Mermaid, the states active at the replay's position highlighted. */
    fun mermaid(): String = session.machine.chart.toMermaid(snapshot.configuration.active)

    val canStepBackward: Boolean get() = position > 0

    val canStepForward: Boolean get() = position < length

    /** Why a forward step is unavailable, or `null` when it is. */
    val forwardUnavailable: String? get() = if (position >= length) "at the end of the recording" else divergence?.let { "the replay diverged here: $it" }

    fun stepForward() {
        when (val step = session.stepForward()) {
            null -> Unit
            is ReplayStep.Matched -> divergence = null
            is ReplayStep.Diverged -> divergence = step.mismatch.describe()
        }
        position = session.position
        explanation = null
        explanationOutcome = null
        invariantViolations = emptyList()
    }

    fun stepBackward() {
        session.stepBackward()
        divergence = null
        position = session.position
        explanation = null
        explanationOutcome = null
        invariantViolations = emptyList()
    }

    fun seek(target: Int) {
        session.seek(target.coerceIn(0, length))
        divergence = null
        position = session.position
        explanation = null
        explanationOutcome = null
        invariantViolations = emptyList()
    }

    fun verify() {
        verdict = session.verify()?.describe() ?: "no divergence in ${session.length} steps"
    }
}

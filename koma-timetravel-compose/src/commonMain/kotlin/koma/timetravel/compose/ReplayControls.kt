package koma.timetravel.compose

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import koma.core.Action
import koma.core.Event
import koma.core.ExperimentalKomaApi
import koma.observability.StoreInstanceId
import koma.timetravel.ReplaySession
import koma.timetravel.ReplayStep

/**
 * The replay of one Store's recording as the screen drives it (handoff §11, v2): the position,
 * stepping back and forward, seeking, verifying. It holds a [ReplaySession], which decides and
 * compares and never runs anything; the controls only move it and show what it says.
 *
 * @param store The Store the recording is of, for the timeline
 * @param session The replay
 */
@ExperimentalKomaApi
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

    val length: Int get() = session.length

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
    }

    fun stepBackward() {
        session.stepBackward()
        divergence = null
        position = session.position
    }

    fun seek(target: Int) {
        session.seek(target.coerceIn(0, length))
        divergence = null
        position = session.position
    }

    fun verify() {
        verdict = session.verify()?.describe() ?: "no divergence in ${session.length} steps"
    }
}

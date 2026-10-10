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
class ReplayControls<C : Any, A : Action, CMD : Any, E : Event>(val store: StoreInstanceId, val session: ReplaySession<C, A, CMD, E>) {
    /** The replay's position, mirrored for Compose. */
    var position: Int by mutableStateOf(session.position)
        private set

    var movement: ReplayMovement by mutableStateOf(ReplayMovement.Open)
        private set
    var verification: ReplayVerification by mutableStateOf(ReplayVerification.Unchecked)
        private set
    var inspection: ChartInspection by mutableStateOf(ChartInspection.Unrequested)
        private set

    fun explainNext() {
        val violations = session.checkInvariants()
        inspection = ChartInspection.Checkpoint(violations)
        session.explainNext { explained ->
            inspection = ChartInspection.Decided(explained.explanation, explained.decision.outcome.toString(), violations)
        }
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

    /** Availability of a forward step, with explicit reasons when disabled. */
    val forwardAvailability: actron.timetravel.inspect.Availability get() = when {
        position >= length -> actron.timetravel.inspect.Availability.Unavailable(listOf("at the end of the recording"))
        movement is ReplayMovement.Diverged -> actron.timetravel.inspect.Availability.Unavailable(listOf("the replay diverged here: ${(movement as ReplayMovement.Diverged).description}"))
        else -> actron.timetravel.inspect.Availability.Available
    }

    fun stepForward() {
        when (val step = session.stepForward()) {
            is ReplayStep.Finished -> Unit
            is ReplayStep.Matched -> movement = ReplayMovement.Open
            is ReplayStep.Diverged -> movement = ReplayMovement.Diverged(step.mismatch.position, step.mismatch.describe())
        }
        position = session.position
        inspection = ChartInspection.Unrequested
    }

    fun stepBackward() {
        session.stepBackward()
        movement = ReplayMovement.Open
        position = session.position
        inspection = ChartInspection.Unrequested
    }

    fun seek(target: Int) {
        session.seek(target.coerceIn(0, length))
        movement = ReplayMovement.Open
        position = session.position
        inspection = ChartInspection.Unrequested
    }

    fun verify() {
        verification = ReplayVerification.Checked("no divergence in ${session.length} steps")
        session.verify { verification = ReplayVerification.Checked(it.describe()) }
    }
}

sealed interface ReplayMovement {
    data object Open : ReplayMovement
    data class Diverged(val position: Int, val description: String) : ReplayMovement
}
sealed interface ReplayVerification {
    data object Unchecked : ReplayVerification
    data class Checked(val verdict: String) : ReplayVerification
}

/** Diagnostic computation is explicitly requested for a checkpoint or its next decision. */
sealed interface ChartInspection {
    val violations: List<actron.statechart.machine.InvariantViolation>
    fun withExplanation(accept: (actron.statechart.machine.DecisionExplanation) -> Unit)
    data object Unrequested : ChartInspection {
        override val violations: List<actron.statechart.machine.InvariantViolation> get() = emptyList()
        override fun withExplanation(accept: (actron.statechart.machine.DecisionExplanation) -> Unit) {}
    }
    data class Checkpoint(override val violations: List<actron.statechart.machine.InvariantViolation>) : ChartInspection {
        override fun withExplanation(accept: (actron.statechart.machine.DecisionExplanation) -> Unit) {}
    }
    data class Decided(val explanation: actron.statechart.machine.DecisionExplanation, val outcome: String, override val violations: List<actron.statechart.machine.InvariantViolation>) : ChartInspection {
        override fun withExplanation(accept: (actron.statechart.machine.DecisionExplanation) -> Unit) { accept(explanation) }
    }
}

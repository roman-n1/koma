package koma.statechart.machine

import koma.core.ExperimentalKomaApi

/**
 * How many dispatched actions a [MachineStore] lets wait for processing. Inputs the machine's
 * own work sends (results, completions, timers) are never refused: refusing them would leave
 * the machine waiting for a command that already answered.
 */
sealed interface AdmissionPolicy {
    /** Every action is accepted; the queue grows without limit. */
    data object Unbounded : AdmissionPolicy

    /** At most [maxPending] actions wait for processing; further ones are refused. */
    data class Bounded(val maxPending: Int) : AdmissionPolicy {
        init {
            require(maxPending >= 1) { "[Koma] maxPending must be at least 1" }
        }
    }
}

/**
 * The outcome of offering an action to a [MachineStore]. `dispatch` returning does not mean
 * the action was accepted; [MachineStore.admit] says.
 */
sealed interface Admission {
    /** The action waits for processing. */
    data object Accepted : Admission

    /** The action was refused: [pending] actions already waited, the limit is [limit]. */
    data class Rejected(val pending: Int, val limit: Int) : Admission
}

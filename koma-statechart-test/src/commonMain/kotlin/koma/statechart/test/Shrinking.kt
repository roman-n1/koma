package koma.statechart.test

import koma.core.Action
import koma.core.Event
import koma.statechart.machine.*

/** Replay attempts and the smallest reproducing prefix found within the budget. */
data class ShrinkReport<C, A : Action>(
    val failure: SequenceFailure<C, A>,
    val attempts: Int,
    val truncated: Boolean,
)

/** Replays inputs from the same snapshot and reports the first failure, without executing IO. */
fun <C, A : Action, CMD, E : Event> Machine<C, A, CMD, E>.replaySequence(
    initial: MachineSnapshot<C>, inputs: List<MachineInput<A>>,
): SequenceFailure<C, A>? {
    val initialViolations = if (initial.isStarted) checkInvariants(initial) else {
        require(initial.definition == id && initial.version == version) { "[Koma] Snapshot belongs to another machine or version" }
        emptyList()
    }
    if (initialViolations.isNotEmpty()) return SequenceFailure(emptyList(), initial, initialViolations)
    var snapshot = initial
    val prefix = mutableListOf<MachineInput<A>>()
    for (input in inputs) {
        prefix += input
        val decision = decide(snapshot, input)
        sequenceFailure(prefix, decision)?.let { return it }
        snapshot = decision.snapshot
    }
    return null
}

/**
 * Removes chunks, then single inputs, preserving at least one original failure identity. Each
 * attempt replays from [initial], including Start when present. Inputs keep their actual payloads,
 * times and ids: stale command/timer inputs become ignored through normal Machine semantics.
 *
 * An untruncated result is deletion-minimal, not guaranteed globally shortest. No payload shrinking
 * or reassigning recorded command ids is implied. [maxAttempts] bounds replay work.
 */
fun <C, A : Action, CMD, E : Event> Machine<C, A, CMD, E>.shrink(
    initial: MachineSnapshot<C>, inputs: List<MachineInput<A>>, maxAttempts: Int = 1_000,
): ShrinkReport<C, A> {
    require(maxAttempts > 0) { "[Koma] Shrinking needs a positive attempt budget" }
    var attempts = 1
    var best = requireNotNull(replaySequence(initial, inputs)) { "[Koma] Sequence does not fail" }
    val identities = best.identities
    var size = maxOf(1, best.inputs.size / 2)
    var truncated = false
    while (best.inputs.isNotEmpty()) {
        var reduced = false
        var position = 0
        while (position < best.inputs.size) {
            if (attempts >= maxAttempts) { truncated = true; break }
            val candidate = best.inputs.take(position) + best.inputs.drop(position + size)
            attempts++
            val failure = replaySequence(initial, candidate)
            if (failure != null && failure.identities.any { it in identities }) {
                best = failure
                reduced = true
                break
            }
            position += size
        }
        if (truncated) break
        if (reduced) {
            // Restart large deletions after a reduction; the final pass tests every single input.
            size = maxOf(1, best.inputs.size / 2)
        } else if (size > 1) {
            size = maxOf(1, size / 2)
        } else break
    }
    return ShrinkReport(best, attempts, truncated)
}

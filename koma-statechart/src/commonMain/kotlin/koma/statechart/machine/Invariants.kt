package koma.statechart.machine

import koma.observability.FailureDescriptor

/** A stable name and a pure predicate of a complete, stable snapshot. */
class MachineInvariant<C>(val name: String, val predicate: (MachineSnapshot<C>) -> Boolean) {
    init {
        require(name.isNotBlank()) { "[Koma] Invariant name must not be blank" }
    }
}

/** A false predicate, or a predicate that threw [failure]. No business payload is copied here. */
data class InvariantViolation(val name: String, val failure: FailureDescriptor? = null)

/** Runtime enforcement rejected a complete decision; its intents must not execute. */
class InvariantViolationException(val violations: List<InvariantViolation>) : IllegalStateException(
    "[Koma] Invariants violated: " + violations.joinToString { it.name },
)

/** Checks all predicates in declaration order; predicate exceptions are reported as violations. */
fun <C> Iterable<MachineInvariant<C>>.check(snapshot: MachineSnapshot<C>): List<InvariantViolation> =
    mapNotNull { invariant ->
        try {
            if (invariant.predicate(snapshot)) null else InvariantViolation(invariant.name)
        } catch (error: Exception) {
            InvariantViolation(invariant.name, FailureDescriptor.of(error))
        }
    }

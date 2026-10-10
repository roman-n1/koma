package actron.statechart.machine

import actron.observability.FailureDescriptor

/** A stable name and a pure predicate of a complete, stable snapshot. */
class MachineInvariant<C : Any>(val name: String, val predicate: (MachineSnapshot<C>) -> Boolean) {
    init {
        require(name.isNotBlank()) { "[Actron] Invariant name must not be blank" }
    }
}

/** A rejected invariant or a failed predicate evaluation. No business payload is copied here. */
sealed interface InvariantViolation {
    val name: String
    val identity: String

    data class Rejected(override val name: String) : InvariantViolation {
        override val identity: String get() = "invariant:$name:false"
    }

    data class Failed(override val name: String, val failure: FailureDescriptor) : InvariantViolation {
        override val identity: String get() = "invariant:$name:${failure.typeLabel}"
    }
}

/** Runtime enforcement rejected a complete decision; its intents must not execute. */
class InvariantViolationException(val violations: List<InvariantViolation>) : IllegalStateException(
    "[Actron] Invariants violated: " + violations.joinToString { it.name },
)

/** Checks all predicates in declaration order; predicate exceptions are reported as violations. */
fun <C : Any> Iterable<MachineInvariant<C>>.check(snapshot: MachineSnapshot<C>): List<InvariantViolation> =
    buildList {
        for (invariant in this@check) try {
            if (!invariant.predicate(snapshot)) add(InvariantViolation.Rejected(invariant.name))
        } catch (error: Exception) {
            add(InvariantViolation.Failed(invariant.name, FailureDescriptor.of(error)))
        }
    }

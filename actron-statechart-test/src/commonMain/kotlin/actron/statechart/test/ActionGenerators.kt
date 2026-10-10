package actron.statechart.test

import actron.core.Action
import actron.core.Event
import actron.statechart.ActionMatcher
import actron.statechart.machine.*

/** Finite deterministic payload domain. Seeded randomness belongs to the caller, not wall time. */
class ActionGenerator<C, A : Action>(val matcher: ActionMatcher, private val generate: (MachineSnapshot<C>, MachineTime) -> List<A>) {
    fun actions(snapshot: MachineSnapshot<C>, now: MachineTime): List<A> = generate(snapshot, now).also {
        require(it.all(matcher::matches)) { "[Actron] Generator emitted an action outside its declared matcher" }
    }
}

inline fun <C, reified A : Action> actionGenerator(
    name: String, noinline generate: (MachineSnapshot<C>, MachineTime) -> List<A>,
): ActionGenerator<C, A> = ActionGenerator(ActionMatcher.of<A>(name), generate)

/**
 * Uses active declarations, payload domains and custom constraints. includeBlocked keeps false
 * guard branches observable; otherwise the selection query filters guards (never reducer/IO).
 * Explicit extraInputs supplies command results or other external responses. Timers use deadlines.
 */
fun <C, A : Action, CMD, E : Event> Machine<C, A, CMD, E>.inputGenerator(
    generators: List<ActionGenerator<C, out A>>,
    includeBlocked: Boolean = true,
    includeTimers: Boolean = true,
    constraint: (MachineSnapshot<C>, A) -> Boolean = { _, _ -> true },
    extraInputs: MachineInputGenerator<C, A> = MachineInputGenerator { _, _ -> emptyList() },
): MachineInputGenerator<C, A> = MachineInputGenerator { snapshot, now ->
    val declared = declaredActions(snapshot).map { it.matcher }
    val actions = generators.flatMap { it.actions(snapshot, now) }.filter { action -> declared.any { it.matches(action) } }.distinct().filter { constraint(snapshot, it) }
    val selected = if (includeBlocked) actions else availableActions(snapshot, actions).executable.map { it.action }
    selected.map { MachineInput.Dispatch(it, now) } + extraInputs.inputs(snapshot, now) +
        if (includeTimers) snapshot.timers.entries.sortedWith(compareBy({ it.value.deadline }, { it.key.value }))
            .map { (id, timer) -> MachineInput.TimerFired(id, maxOf(now, timer.deadline)) } else emptyList()
}

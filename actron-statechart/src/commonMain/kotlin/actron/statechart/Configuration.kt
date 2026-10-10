package actron.statechart

import actron.core.ExperimentalActronApi

/**
 * The active part of a running statechart. Immutable; [StateChartRuntime.step] returns a new one.
 *
 * The runtime does not hold it: the caller does, for example a Actron Store in a state field.
 *
 * @property active Active nodes, each together with all its ancestors. For a chart built only from
 * atomic and compound states this is one chain from a top-level node down to one active leaf,
 * listed outermost first. An active [ParallelState] has all its regions active, so there is one
 * active leaf per active region.
 * @property history What each [HistoryState] remembers, keyed by the history state's id: the
 * children of its parent that were active (shallow; one for a compound parent, every region for a
 * parallel one) or the active descendants of its parent without active children (deep) when the
 * parent was last exited. A history state that has not recorded anything yet
 * has no entry. Never contains nodes of [active] that are history states: those are never active.
 */
data class StateConfiguration(
    val active: Set<StateId>,
    val history: Map<StateId, Set<StateId>> = emptyMap(),
) {
    // A Set's hash is the plain sum of its elements' hashes, so ids named by a pattern such as
    // "R1_S2" give the same sum for many different configurations and the configuration graph's
    // seen-set degenerates to quadratic time. Mixing each element's hash first keeps the sums apart.
    override fun hashCode(): Int = mixedHash(active) * 31 + history.entries.sumOf { (id, ids) -> mix(id.hashCode()) xor mixedHash(ids) }

    override fun equals(other: Any?): Boolean = other is StateConfiguration && active == other.active && history == other.history

    private companion object {
        fun mixedHash(ids: Set<StateId>): Int = ids.sumOf { mix(it.hashCode()) }

        fun mix(h: Int): Int {
            var x = h * -0x61c88647 // golden-ratio multiplier
            x = x xor (x ushr 15)
            x *= -0x7a143595
            return x xor (x ushr 13)
        }
    }
}

/**
 * Returns the active nodes of [configuration] that have no active child, in declaration order
 * (undeclared ids last). For a configuration produced by this chart's runtime it is exactly one
 * node, the active leaf, unless a [ParallelState] is active: then there is one per active region.
 */
fun StateChartDefinition.activeLeaves(configuration: StateConfiguration): List<StateId> =
    configuration.active
        .filter { id -> childrenOf(id).none { it.id in configuration.active } }
        .sortedBy { declarationOrder(it) }

/**
 * Exit and entry caused by a set of transitions; see [microstep].
 */
internal class Microstep(
    val exited: List<StateId>,
    val entered: List<StateId>,
    val configuration: StateConfiguration,
) {
    /** The active leaves after the step in declaration order, or the last entered node when there is none. */
    fun leaves(definition: StateChartDefinition): List<StateId> =
        definition.activeLeaves(configuration).ifEmpty { listOf(entered.last()) }
}

internal fun StateChartDefinition.declarationOrder(id: StateId): Int = hierarchy.order[id] ?: Int.MAX_VALUE

private fun StateChartDefinition.depth(id: StateId): Int = ancestorsOf(id).size

/**
 * The configuration after entering [StateChartDefinition.initial] from the root.
 */
internal fun StateChartDefinition.initialConfiguration(): StateConfiguration =
    StateConfiguration(active = inEntryOrder(entrySet(listOf(null to initial), history = emptyMap())).toSet())

/** [ids] outermost first, ties in declaration order. */
internal fun StateChartDefinition.inEntryOrder(ids: Collection<StateId>): List<StateId> = ids.sortedWith(entryOrder())

/** The timers ([Trigger.After] transitions) of [states], in the order of [states], each state's in declaration order. */
internal fun StateChartDefinition.timersOf(states: List<StateId>): List<Transition> =
    states.flatMap { hierarchy.timersBySource[it].orEmpty() }

/**
 * Transitions that may fire from [leaf], in priority order: the leaf's own, then each ancestor's,
 * from the innermost outwards, each group in declaration order.
 */
internal fun StateChartDefinition.candidatesFor(leaf: StateId): Sequence<Transition> =
    (sequenceOf(leaf) + ancestorsOf(leaf).asSequence()).flatMap { transitionsFrom(it).asSequence() }

/**
 * Picks the transitions to fire in [configuration], as SCXML does: for each active leaf in
 * declaration order, the first [enabled] candidate (see [candidatesFor]). Two picks conflict when
 * their exit sets meet. On a conflict the new pick replaces the earlier ones it conflicts with when
 * its source is a proper descendant of all of their sources (an inner transition beats an outer
 * one, whichever leaf found it first); otherwise the earlier pick wins and the new one is dropped.
 * So a transition shared by several leaves fires once, and transitions in different regions of a
 * parallel state fire together.
 */
internal fun StateChartDefinition.selectTransitions(
    configuration: StateConfiguration,
    enabled: (Transition) -> Boolean,
): List<Transition> {
    val selected = mutableListOf<Pair<Transition, Set<StateId>>>()
    for (leaf in activeLeaves(configuration)) {
        val transition = candidatesFor(leaf).firstOrNull(enabled) ?: continue
        if (selected.any { it.first == transition }) continue
        val exitSet = exitSet(configuration, transition).toSet()
        val conflicting = selected.filter { (other, otherExit) ->
            otherExit.any { it in exitSet } || other.source in exitSet || transition.source in otherExit
        }
        if (conflicting.all { (other, _) -> isDescendant(transition.source, other.source) }) {
            selected -= conflicting.toSet()
            selected += transition to exitSet
        }
    }
    return selected.map { it.first }
}

/**
 * The step the configuration graph takes for [trigger], whose source is active in
 * [configuration]. For an action transition: the runtime's selection (see [selectTransitions]) for
 * an action that matches exactly the action transitions whose matcher equals `trigger.on`, with
 * every guard true, except that [trigger] is taken for the leaves below its source and transitions
 * that would conflict with it are left out; without parallel states this is [trigger] alone. A
 * timer fires alone, as in [StateChartRuntime.fire].
 */
internal fun StateChartDefinition.graphStep(configuration: StateConfiguration, trigger: Transition): Microstep {
    if (trigger.on == null) return microstep(configuration, listOf(trigger))
    val triggerExit = exitSet(configuration, trigger).toSet()
    val taken = selectTransitions(configuration) { transition ->
        transition == trigger || (transition.on == trigger.on && exitSet(configuration, transition).none { it in triggerExit })
    }
    // Only in a malformed hierarchy can the source have no active leaf below it; take the trigger alone then.
    return microstep(configuration, if (trigger in taken) taken else listOf(trigger))
}

/**
 * The transition's domain: the innermost compound state that is a proper ancestor of both its
 * source and its target, or `null` for the implicit root. Parallel states are never domains, as in
 * SCXML: a transition between two regions of a parallel state exits and re-enters the parallel
 * state. Because the domain is a *proper* ancestor of the source, every transition is external: a
 * self-loop, a transition from a state to its own descendant and one from a state to its own
 * ancestor all exit and re-enter the state.
 */
internal fun StateChartDefinition.domainOf(transition: Transition): StateId? = domainOf(transition.source, transition.target)

/** The domain of a transition from [source] to [target]; see the other overload. */
internal fun StateChartDefinition.domainOf(source: StateId, target: StateId): StateId? {
    val targetAncestors = ancestorsOf(target)
    return ancestorsOf(source).firstOrNull { node(it) is CompoundState && it in targetAncestors }
}

internal fun StateChartDefinition.exitSet(configuration: StateConfiguration, transition: Transition): List<StateId> {
    if (transition.kind == TransitionKind.Internal) return emptyList()
    val domain = domainOf(transition)
    return configuration.active.filter { domain == null || isDescendant(it, domain) }
}

/**
 * Nodes entered for [targets], each a pair of a domain and a target, as SCXML computes the entry set: first
 * each target with its descendants, then each target's ancestors below the domain.
 *
 * - A compound node enters its initial child too, unless a descendant is already entered.
 * - A parallel node enters every region that has no entered descendant yet, whether the parallel
 *   node is a target or an ancestor of one.
 * - A [HistoryState] target is not entered itself: the nodes it restores (see [restoredBy]) are
 *   entered with their descendants, then with their ancestors below the history's parent. A
 *   history that cannot be resolved (only in a malformed chart, see [validate]) is entered like an
 *   atomic state, so tools keep working on such charts.
 *
 * Every node is entered at most once, so this ends even for a malformed hierarchy. The result is
 * in no particular order.
 */
private fun StateChartDefinition.entrySet(targets: List<Pair<StateId?, StateId>>, history: Map<StateId, Set<StateId>>): Set<StateId> {
    val entered = linkedSetOf<StateId>()
    fun hasEnteredDescendant(id: StateId) = entered.any { isDescendant(it, id) }
    fun enterRegions(parallel: StateId, enterDescendants: (StateId) -> Unit) {
        for (region in hierarchy.regions.getValue(parallel)) if (!hasEnteredDescendant(region)) enterDescendants(region)
    }
    fun enterAncestors(id: StateId, below: StateId?, enterDescendants: (StateId) -> Unit) {
        for (ancestor in ancestorsOf(id).takeWhile { it != below }.asReversed()) {
            entered += ancestor
            if (node(ancestor) is ParallelState) enterRegions(ancestor, enterDescendants)
        }
    }
    fun enterDescendants(id: StateId) {
        if (id in entered) return
        val node = node(id)
        val restored = (node as? HistoryState)?.let { restoredBy(it, history) }
        if (node is HistoryState && restored != null) {
            restored.forEach(::enterDescendants)
            restored.forEach { enterAncestors(it, node.parent, ::enterDescendants) }
            return
        }
        entered += id
        when (node) {
            is CompoundState -> {
                val initial = node(node.initial)
                if (initial != null && initial !is HistoryState && initial.parent == id && !hasEnteredDescendant(id)) enterDescendants(initial.id)
            }
            is ParallelState -> enterRegions(id, ::enterDescendants)
            else -> Unit
        }
    }
    targets.forEach { (_, target) -> enterDescendants(target) }
    targets.forEach { (domain, target) -> enterAncestors(target, domain, ::enterDescendants) }
    return entered
}
/**
 * The nodes [history] restores: what it remembers in [remembered], else its default, else the
 * initial child of its compound parent or every region of its parallel parent. Only proper
 * descendants of the parent that are not history states count; `null` when there is nothing to
 * restore (the parent is neither compound nor parallel, or the fallback is not such a descendant).
 */
private fun StateChartDefinition.restoredBy(history: HistoryState, remembered: Map<StateId, Set<StateId>>): List<StateId>? {
    val parent = node(history.parent)
    if (parent !is CompoundState && parent !is ParallelState) return null
    fun restorable(id: StateId): Boolean = node(id).let { it != null && it !is HistoryState } && isDescendant(id, parent.id)
    val recorded = remembered[history.id].orEmpty().filter(::restorable)
    if (recorded.isNotEmpty()) return recorded.sortedBy { declarationOrder(it) }
    val default = history.default
    val fallback = when {
        default != null -> listOf(default)
        parent is CompoundState -> listOf(parent.initial)
        else -> hierarchy.regions.getValue(parent.id)
    }
    return if (fallback.all(::restorable)) fallback else null
}

/**
 * [configuration]'s history after exiting [exited]: for every exited node, each of its history
 * states remembers the node's active children (shallow) or its active atomic descendants (deep), as
 * they are in [configuration]. Other entries are kept.
 */
internal fun StateChartDefinition.recordHistory(configuration: StateConfiguration, exited: Collection<StateId>): Map<StateId, Set<StateId>> {
    val history = configuration.history.toMutableMap()
    for (id in exited) {
        for (node in hierarchy.histories[id].orEmpty()) {
            val recorded = if (node.deep) {
                activeLeaves(configuration).filter { isDescendant(it, id) }
            } else {
                childrenOf(id).filter { it !is HistoryState && it.id in configuration.active }.map { it.id }
            }
            if (recorded.isNotEmpty()) history[node.id] = recorded.toSet()
        }
    }
    return history
}

/** Exit order: innermost first, ties in reverse declaration order. */
private fun StateChartDefinition.exitOrder(): Comparator<StateId> =
    compareByDescending<StateId> { depth(it) }.thenByDescending { declarationOrder(it) }

/** Entry order: outermost first, ties in declaration order. */
private fun StateChartDefinition.entryOrder(): Comparator<StateId> =
    compareBy<StateId> { depth(it) }.thenBy { declarationOrder(it) }

/**
 * Fires [transitions] (already selected, non-conflicting) in [configuration]: exits the union of
 * their exit sets innermost first (ties in reverse declaration order), records history for the
 * exited nodes (see [recordHistory]), then enters their entry set (see [entrySet]) outermost first
 * (ties in declaration order). A transition into a history state enters what the history
 * remembers after this step's recording.
 */
internal fun StateChartDefinition.microstep(configuration: StateConfiguration, transitions: List<Transition>): Microstep {
    val exitSet = linkedSetOf<StateId>()
    for (transition in transitions) exitSet += exitSet(configuration, transition)
    val history = recordHistory(configuration, exitSet)
    val entrySet = entrySet(transitions.filter { it.kind != TransitionKind.Internal }.map { domainOf(it) to it.target }, history)
    val exited = exitSet.sortedWith(exitOrder())
    val entered = entrySet.sortedWith(entryOrder())
    return Microstep(exited, entered, StateConfiguration(active = (configuration.active - exitSet) + entered, history = history))
}

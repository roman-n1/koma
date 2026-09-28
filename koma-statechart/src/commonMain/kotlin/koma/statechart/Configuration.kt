package koma.statechart

import koma.core.ExperimentalKomaApi

/**
 * The active part of a running statechart. Immutable; [StateChartRuntime.step] returns a new one.
 *
 * The runtime does not hold it: the caller does, for example a Koma Store in a state field.
 *
 * @property active Active nodes, each together with all its ancestors. For a chart built only from
 * atomic and compound states this is one chain from a top-level node down to one active leaf,
 * listed outermost first.
 * @property history What each [HistoryState] remembers, keyed by the history state's id: the
 * child of its parent that was active (shallow) or the atomic descendants of its parent that were
 * active (deep) when the parent was last exited. A history state that has not recorded anything yet
 * has no entry. Never contains nodes of [active] that are history states: those are never active.
 */
@ExperimentalKomaApi
data class StateConfiguration(
    val active: Set<StateId>,
    val history: Map<StateId, Set<StateId>> = emptyMap(),
)

/**
 * Returns the active nodes of [configuration] that have no active child, in declaration order
 * (undeclared ids last). For a configuration produced by this chart's runtime it is exactly one
 * node, the active leaf.
 */
@ExperimentalKomaApi
fun StateChartDefinition.activeLeaves(configuration: StateConfiguration): List<StateId> =
    configuration.active
        .filter { id -> childrenOf(id).none { it.id in configuration.active } }
        .sortedBy { declarationOrder(it) }

/**
 * Exit and entry caused by a set of transitions; see [microstep].
 */
@OptIn(ExperimentalKomaApi::class)
internal class Microstep(
    val exited: List<StateId>,
    val entered: List<StateId>,
    val configuration: StateConfiguration,
) {
    /** The first active leaf after the step, or the last entered node when there is none. */
    fun leaf(definition: StateChartDefinition): StateId =
        definition.activeLeaves(configuration).firstOrNull() ?: entered.last()
}

@OptIn(ExperimentalKomaApi::class)
internal fun StateChartDefinition.declarationOrder(id: StateId): Int = hierarchy.order[id] ?: Int.MAX_VALUE

@OptIn(ExperimentalKomaApi::class)
private fun StateChartDefinition.depth(id: StateId): Int = ancestorsOf(id).size

/**
 * The configuration after entering [StateChartDefinition.initial] from the root.
 */
@OptIn(ExperimentalKomaApi::class)
internal fun StateChartDefinition.initialConfiguration(): StateConfiguration =
    StateConfiguration(active = entryPath(domain = null, target = initial, history = emptyMap()).toSet())

/**
 * Transitions that may fire from [leaf], in priority order: the leaf's own, then each ancestor's,
 * from the innermost outwards, each group in declaration order.
 */
@OptIn(ExperimentalKomaApi::class)
internal fun StateChartDefinition.candidatesFor(leaf: StateId): Sequence<Transition> =
    (sequenceOf(leaf) + ancestorsOf(leaf).asSequence()).flatMap { transitionsFrom(it).asSequence() }

/**
 * Picks the transitions to fire in [configuration]: for each active leaf in declaration order, the
 * first [enabled] candidate (see [candidatesFor]). A transition whose exit set meets the exit set
 * of an earlier pick is dropped, so a transition shared by several leaves fires once.
 */
@OptIn(ExperimentalKomaApi::class)
internal inline fun StateChartDefinition.selectTransitions(
    configuration: StateConfiguration,
    enabled: (Transition) -> Boolean,
): List<Transition> {
    val selected = mutableListOf<Transition>()
    val exiting = mutableSetOf<StateId>()
    for (leaf in activeLeaves(configuration)) {
        val transition = candidatesFor(leaf).firstOrNull(enabled) ?: continue
        val exitSet = exitSet(configuration, transition)
        if (exitSet.none { it in exiting }) {
            selected += transition
            exiting += exitSet
        }
    }
    return selected
}

/**
 * The transition's domain: the innermost compound state that is a proper ancestor of both its
 * source and its target, or `null` for the implicit root. Because the domain is a *proper*
 * ancestor of the source, every transition is external: a self-loop, a transition from a state to
 * its own descendant and one from a state to its own ancestor all exit and re-enter the state.
 */
@OptIn(ExperimentalKomaApi::class)
internal fun StateChartDefinition.domainOf(transition: Transition): StateId? {
    val targetAncestors = ancestorsOf(transition.target)
    return ancestorsOf(transition.source).firstOrNull { node(it) is CompoundState && it in targetAncestors }
}

@OptIn(ExperimentalKomaApi::class)
private fun StateChartDefinition.exitSet(configuration: StateConfiguration, transition: Transition): List<StateId> {
    val domain = domainOf(transition)
    return configuration.active.filter { domain == null || isDescendant(it, domain) }
}

/**
 * Nodes entered when entering [target] from [domain]: the ancestors of [target] below [domain],
 * then [target] and, while the entered node is compound, its initial child. Outermost first.
 *
 * When [target] is a [HistoryState] it is not entered itself: the nodes it restores (see
 * [restoredBy]) are entered instead, each with its ancestors below the history's parent and its
 * initial descendants. A history that cannot be resolved (only in a malformed chart, see
 * [validate]) is entered like an atomic state, so tools keep working on such charts.
 */
@OptIn(ExperimentalKomaApi::class)
private fun StateChartDefinition.entryPath(domain: StateId?, target: StateId, history: Map<StateId, Set<StateId>>): List<StateId> {
    val entered = ancestorsOf(target).takeWhile { it != domain }.reversed().toMutableList()
    val pseudo = node(target) as? HistoryState
    val restored = pseudo?.let { restoredBy(it, history) }
    if (pseudo == null || restored == null) {
        enterWithInitials(target, entered)
    } else {
        for (id in restored) {
            ancestorsOf(id).takeWhile { it != pseudo.parent }.asReversed().forEach { if (it !in entered) entered += it }
            if (id !in entered) enterWithInitials(id, entered)
        }
    }
    return entered
}

/**
 * Adds [id] to [entered] and then, while the entered node is compound, its initial child. Stops at
 * an initial state that is not a child, is a history state or was already entered.
 */
@OptIn(ExperimentalKomaApi::class)
private fun StateChartDefinition.enterWithInitials(id: StateId, entered: MutableList<StateId>) {
    entered += id
    var current = node(id)
    while (current is CompoundState) {
        val next = node(current.initial)
        if (next == null || next is HistoryState || next.parent != current.id || next.id in entered) break
        entered += next.id
        current = next
    }
}

/**
 * The nodes [history] restores: what it remembers in [remembered], else its default, else the
 * initial child of its parent. Only proper descendants of the parent that are not history states
 * count; `null` when there is nothing to restore (the parent is not a compound state, or the
 * fallback is not such a descendant).
 */
@OptIn(ExperimentalKomaApi::class)
private fun StateChartDefinition.restoredBy(history: HistoryState, remembered: Map<StateId, Set<StateId>>): List<StateId>? {
    val parent = node(history.parent) as? CompoundState ?: return null
    fun restorable(id: StateId): Boolean = node(id).let { it != null && it !is HistoryState } && isDescendant(id, parent.id)
    val recorded = remembered[history.id].orEmpty().filter(::restorable)
    if (recorded.isNotEmpty()) return recorded.sortedBy { declarationOrder(it) }
    val fallback = history.default ?: parent.initial
    return if (restorable(fallback)) listOf(fallback) else null
}

/**
 * [configuration]'s history after exiting [exited]: for every exited node, each of its history
 * states remembers the node's active children (shallow) or its active atomic descendants (deep), as
 * they are in [configuration]. Other entries are kept.
 */
@OptIn(ExperimentalKomaApi::class)
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

/**
 * Fires [transitions] (already selected, non-conflicting) in [configuration]: exits the union of
 * their exit sets innermost first (ties in reverse declaration order), records history for the
 * exited nodes (see [recordHistory]), then enters the union of their entry paths outermost first
 * (ties in declaration order). A transition into a history state enters what the history
 * remembers after this step's recording.
 */
@OptIn(ExperimentalKomaApi::class)
internal fun StateChartDefinition.microstep(configuration: StateConfiguration, transitions: List<Transition>): Microstep {
    val exitSet = linkedSetOf<StateId>()
    for (transition in transitions) exitSet += exitSet(configuration, transition)
    val history = recordHistory(configuration, exitSet)
    val entrySet = linkedSetOf<StateId>()
    for (transition in transitions) entrySet += entryPath(domainOf(transition), transition.target, history)
    val exited = exitSet.sortedWith(compareByDescending<StateId> { depth(it) }.thenByDescending { declarationOrder(it) })
    val entered = entrySet.sortedWith(compareBy<StateId> { depth(it) }.thenBy { declarationOrder(it) })
    return Microstep(exited, entered, StateConfiguration(active = (configuration.active - exitSet) + entered, history = history))
}

package koma.statechart

import koma.core.Action
import koma.core.Event
import koma.core.ExperimentalKomaApi
import koma.core.Plugin
import koma.core.PluginScope
import koma.core.State

/**
 * A place where a running Store did something its [StateChartDefinition] does not declare.
 */
@ExperimentalKomaApi
sealed interface ConformanceViolation {
    /**
     * The Store entered [id], which the chart does not declare.
     */
    data class UndeclaredState(val id: StateId) : ConformanceViolation

    /**
     * The Store moved from [from] to [to], but the chart has no transition between them.
     *
     * @property lastAction The most recent action seen before the change, if any. It is a hint,
     * not proof: a change made from `launch {}` or a chained `enter {}` may come after an
     * unrelated action.
     */
    data class UndeclaredTransition(val from: StateId, val to: StateId, val lastAction: Action?) : ConformanceViolation

    /**
     * The Store moved from [from] to [to], and the chart declares transitions between them, but
     * none of them is triggered by [action], the action the change is attributed to.
     *
     * This also catches a change made from `launch {}` that completes after an unrelated action:
     * the plugin cannot tell which action launched the work, so the chart has to declare the
     * transition for the action that is most recent when the change is committed.
     *
     * @property action The most recent action, not yet used for another state change
     */
    data class UnexpectedTrigger(val from: StateId, val to: StateId, val action: Action) : ConformanceViolation
}

/**
 * A [Plugin] that checks a running Store against a [StateChartDefinition] and records which
 * declared transitions the Store actually took.
 *
 * It only observes, through the public plugin hooks, so it works with an unmodified Koma Store
 * and does not change how the Store behaves. Register it in tests, or in debug builds, with
 * `plugin(conformance)` or `koma-test`'s `patch { plugin(conformance) }`.
 *
 * Each committed state is mapped to its active leaf with [stateIdOf], or, for a chart with
 * [ParallelState]s, to its set of active leaves (one per active region) with the mapping given to
 * [withActiveLeaves]. The mapping is explicit because class names are not stable under code
 * shrinking; a `when` over a sealed state hierarchy is the usual choice. A change that keeps the
 * same leaves (for example a data update inside one variant) is not a transition. Below, a
 * one-leaf mapping is the set of that one leaf.
 *
 * Transitions from a leaf: in a hierarchical chart a transition may leave the leaf or any of its
 * ancestors, and they are considered in the runtime's priority order: the leaf's own in
 * declaration order, then its parent's, outwards (see [StateChartRuntime]). A transition leads
 * from leaves `from` to leaves `to` when, taken from the configuration of `from` (see
 * [StateChartDefinition.configurationOf]), it enters every leaf of `to` inside its domain (the
 * part of the chart it exits and re-enters), and there is at least one; so a transition into a
 * compound state matches a change to any leaf it enters. In a flat chart this is simply a
 * transition from `from` to `to`.
 *
 * Parallel states: a change is explained region by region. Each leaf of `from` that is not in
 * `to`, in declaration order, needs a transition that exits it, leads to `to` as above and does
 * not exit what an earlier chosen transition exits; the chosen transitions together are the step.
 * Transitions from the leaf and its ancestors come first, in priority order, then those of the
 * other leaves of `from` in declaration order, for a transition from another region that exits
 * and re-enters the parallel state. A leaf already exited by a chosen transition needs nothing more, so a
 * transition of the parallel state itself explains all its regions at once. A leaf without such a
 * transition is reported, with the leaf of `to` closest to it (sharing its innermost ancestor;
 * the first one in declaration order among equals) as the new state, and the part of the chart a
 * transition between the two would exit counts as explained by that report. A new leaf of `to`
 * that no chosen transition entered and no report covers is reported too, from the closest leaf of
 * `from`. Regions whose leaf stays the same are not checked: re-entering a region leaves nothing
 * to observe.
 *
 * History: the plugin remembers what the chart's [HistoryState]s would record as the Store moves
 * (the same rules as [StateChartRuntime], applied to the configuration of the old leaves; after a
 * change no declared transition explains, the nodes of the old configuration that are not in the
 * new one count as exited). A transition is then taken from the old leaves' configuration with
 * that history, so a transition into a history state leads exactly to what the chart would
 * restore, and a Store that forgets to restore is reported. When the plugin has not seen the
 * history record anything, the Store may have started from a saved state whose history the plugin
 * never saw, so any leaves the history could restore are accepted: the transition is also tried
 * with the history remembering the new leaves inside its parent (deep), or the children of its
 * parent that contain them (shallow). A self-loop records nothing.
 *
 * Start: the Store may start in any declared state, not only [StateChartDefinition.initial],
 * because a [koma.core.StateSaver] or `patch { initialState(...) }` may provide the first state.
 * Only an undeclared start state is reported, as [ConformanceViolation.UndeclaredState].
 *
 * Attribution: plugin hooks do not say which action caused a state change. A change is attributed
 * to the most recent action that has not been used for an earlier change (its *trigger*). For
 * each leaf that needs a transition:
 * - no transition leads from it to the new leaves: [ConformanceViolation.UndeclaredTransition];
 * - a declared transition that does matches the trigger: the first such one, in priority order,
 *   is covered;
 * - transitions lead there but none matches the trigger and none is a timer:
 *   [ConformanceViolation.UnexpectedTrigger], and nothing is covered;
 * - no transition that leads there matches the trigger, but a timer ([Trigger.After]) does: the
 *   first such timer, in priority order, is covered, and the trigger stays unused when timers
 *   explain the whole change;
 * - there is no trigger, because no action has arrived yet (`enter {}` at startup) or the last
 *   action already caused a change (a chained `enter {}` after it): the change is automatic and
 *   the first transition that leads there, in priority order, is covered, whatever its trigger.
 *
 * Timers: plugin hooks do not show timers either, so a change is credited to a timer only as
 * above, when no action explains it. A change that both an action transition matching the trigger
 * and a timer explain is credited to the action. A timer self-loop keeps the same leaves, so Koma
 * does not report it and it is never covered.
 *
 * Self-transitions: Koma does not notify plugins when a handler keeps the state, so a self-loop
 * is credited by action. When an action arrives, the transitions the runtime would take for it
 * are chosen, guards ignored (see [StateChartRuntime]); if there are some and together they end
 * in the same leaves, they are held as pending. In a hierarchical chart, a self-loop is a
 * transition that ends in the same leaf, for example one from a compound ancestor to itself whose
 * initial leaf is the current one. If a change of leaves is committed before the next action, the
 * pending self-loops are dropped and the change is attributed as above. Otherwise they are
 * covered: they are committed when the next action arrives, and [coveredTransitions] and
 * [uncoveredTransitions] already count them.
 *
 * @param definition The chart the Store should follow
 * @param stateIdOf Maps a Koma state to its active leaf; for charts with parallel states use
 * [withActiveLeaves]
 */
@ExperimentalKomaApi
class StateChartConformance<S : State, A : Action, E : Event> private constructor(
    val definition: StateChartDefinition,
    private val mapping: LeafMapping<S>,
) : Plugin<S, A, E> {
    constructor(definition: StateChartDefinition, stateIdOf: (S) -> StateId) : this(definition, LeafMapping { setOf(stateIdOf(it)) })

    private fun interface LeafMapping<S> {
        fun leavesOf(state: S): Set<StateId>
    }

    private val declaredStates = definition.states.map { it.id }.toSet()
    private val recordedViolations = mutableListOf<ConformanceViolation>()
    private val recordedCovered = linkedSetOf<Transition>()
    private var lastAction: A? = null
    private var trigger: A? = null
    private var pendingSelfLoops: List<Transition> = emptyList()
    private var history: Map<StateId, Set<StateId>> = emptyMap()

    /**
     * Violations seen so far, in the order they happened.
     */
    val violations: List<ConformanceViolation> get() = recordedViolations.toList()

    /**
     * Declared transitions the Store took at least once, in the order first taken, including
     * pending self-loops (see the class documentation).
     */
    val coveredTransitions: Set<Transition> get() = (recordedCovered + pendingSelfLoops).toSet()

    /**
     * Declared transitions the Store has not taken yet, in declaration order.
     */
    val uncoveredTransitions: List<Transition> get() = coveredTransitions.let { covered -> definition.transitions.filter { it !in covered } }

    override suspend fun onStart(scope: PluginScope<S, A>, state: S) {
        mapping.leavesOf(state).forEach(::checkDeclared)
    }

    override suspend fun onAction(scope: PluginScope<S, A>, state: S, action: A) {
        recordedCovered += pendingSelfLoops
        lastAction = action
        trigger = action
        val leaves = mapping.leavesOf(state)
        val configuration = definition.configurationOf(leaves).copy(history = history)
        val taken = definition.selectTransitions(configuration) { it.on?.matches(action) == true }
        val same = taken.isNotEmpty() && definition.microstep(configuration, taken).leaves(definition).toSet() == leaves
        pendingSelfLoops = if (same) taken else emptyList()
    }

    override suspend fun onState(scope: PluginScope<S, A>, prevState: S, state: S) {
        val from = mapping.leavesOf(prevState)
        val to = mapping.leavesOf(state)
        if (from == to) return
        pendingSelfLoops = emptyList()
        (to - from).forEach(::checkDeclared)

        val tracked = definition.configurationOf(from).copy(history = history)
        val action = trigger
        trigger = null
        val byDeclaration = compareBy<StateId> { definition.declarationOrder(it) }
        val added = (to - from).sortedWith(byDeclaration)
        val fromLeaves = definition.activeLeaves(tracked)
        val chosen = mutableListOf<Transition>()
        val exited = mutableSetOf<StateId>()
        // Domains of the chosen transitions and of the reports: what they explain.
        val domains = mutableListOf<StateId?>()
        fun report(domain: StateId?, violation: ConformanceViolation) {
            domains += domain
            exited += tracked.active.filter { domain == null || definition.isDescendant(it, domain) }
            recordedViolations += violation
        }
        for (leaf in (from - to).sortedWith(byDeclaration)) {
            if (leaf in exited) continue
            val candidates = (sequenceOf(leaf) + fromLeaves.asSequence().filter { it != leaf })
                .flatMap { definition.candidatesFor(it) }
                .distinct()
                .filter { t ->
                    val exitSet = definition.exitSet(tracked, t)
                    leaf in exitSet && exitSet.none { it in exited } && leadsTo(tracked, t, to)
                }.toList()
            val taken = if (action == null) {
                candidates.firstOrNull()
            } else {
                candidates.firstOrNull { it.on?.matches(action) == true } ?: candidates.firstOrNull { it.isTimer }
            }
            if (taken != null) {
                chosen += taken
                domains += definition.domainOf(taken)
                exited += definition.exitSet(tracked, taken)
                continue
            }
            val target = closest(leaf, added.ifEmpty { to.sortedWith(byDeclaration) })
            val domain = definition.domainOf(leaf, target)
            report(
                domain,
                if (candidates.isEmpty()) {
                    ConformanceViolation.UndeclaredTransition(from = leaf, to = target, lastAction = lastAction)
                } else {
                    ConformanceViolation.UnexpectedTrigger(from = leaf, to = target, action = action!!)
                },
            )
        }
        for (leaf in added) {
            if (domains.any { it == null || definition.isDescendant(leaf, it) }) continue
            val source = closest(leaf, from.sortedWith(byDeclaration))
            report(definition.domainOf(source, leaf), ConformanceViolation.UndeclaredTransition(from = source, to = leaf, lastAction = lastAction))
        }
        recordedCovered += chosen
        // A change only timers explain used no action: the trigger stays for the next change.
        if (action != null && domains.size == chosen.size && chosen.all { it.isTimer }) trigger = action
        val step = definition.microstep(tracked, chosen)
        val kept = definition.configurationOf(to).active
        val chosenExits = step.exited.toSet()
        history = definition.recordHistory(tracked.copy(history = step.configuration.history), tracked.active.filter { it !in kept && it !in chosenExits })
    }

    /** The first of [candidates] (a non-empty list) that shares the innermost ancestor with [leaf]. */
    private fun closest(leaf: StateId, candidates: List<StateId>): StateId {
        val chain = listOf(leaf) + definition.ancestorsOf(leaf)
        fun shared(other: StateId): Int {
            val otherChain = (listOf(other) + definition.ancestorsOf(other)).toSet()
            val common = chain.indexOfFirst { it in otherChain }
            return if (common < 0) Int.MAX_VALUE else common
        }
        return candidates.minBy(::shared)
    }

    /**
     * Whether [transition], taken from [configuration], enters every leaf of [to] inside its
     * domain, and there is one; for a history target the plugin has seen record nothing, whether
     * it does so when the history remembers what [to] shows inside its parent.
     */
    private fun leadsTo(configuration: StateConfiguration, transition: Transition, to: Set<StateId>): Boolean {
        val domain = definition.domainOf(transition)
        val reported = to.filter { domain == null || definition.isDescendant(it, domain) }
        if (reported.isEmpty()) return false
        if (enteredBy(configuration, transition).containsAll(reported)) return true
        val target = definition.node(transition.target) as? HistoryState ?: return false
        if (target.id in configuration.history) return false
        val record = if (target.deep) {
            reported.filter { definition.isDescendant(it, target.parent) }
        } else {
            definition.childrenOf(target.parent)
                .filter { child -> child !is HistoryState && reported.any { it == child.id || definition.isDescendant(it, child.id) } }
                .map { it.id }
        }
        if (record.isEmpty()) return false
        return enteredBy(configuration.copy(history = configuration.history + (target.id to record.toSet())), transition).containsAll(reported)
    }

    private fun enteredBy(configuration: StateConfiguration, transition: Transition): List<StateId> =
        definition.microstep(configuration, listOf(transition)).entered

    private fun checkDeclared(id: StateId) {
        if (id !in declaredStates) recordedViolations += ConformanceViolation.UndeclaredState(id)
    }

    companion object {
        /**
         * Creates the plugin for a chart with [ParallelState]s, where one leaf cannot describe a
         * state: [activeLeavesOf] maps a Koma state to all its active leaves, one per active
         * region (see [StateChartDefinition.configurationOf]). For a chart without parallel states
         * it behaves exactly like the constructor with `stateIdOf = { activeLeavesOf(it).single() }`.
         *
         * @param definition The chart the Store should follow
         * @param activeLeavesOf Maps a Koma state to its active leaves
         */
        fun <S : State, A : Action, E : Event> withActiveLeaves(
            definition: StateChartDefinition,
            activeLeavesOf: (S) -> Set<StateId>,
        ): StateChartConformance<S, A, E> = StateChartConformance(definition, LeafMapping { activeLeavesOf(it) })
    }
}

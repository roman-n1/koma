package actron.statechart

import actron.core.Action
import actron.core.Event
import actron.core.ExperimentalActronApi
import actron.core.Plugin
import actron.core.PluginScope
import actron.core.State

/** Action attribution inferred from Store hooks, which do not expose a causal input. */
sealed interface ActionObservation {
    val observed: Boolean
    fun matches(transition: Transition): Boolean
    fun unexpected(from: StateId, to: StateId): ConformanceViolation.UnexpectedTrigger

    data object Unobserved : ActionObservation {
        override val observed: Boolean = false
        override fun matches(transition: Transition): Boolean = false
        override fun unexpected(from: StateId, to: StateId): ConformanceViolation.UnexpectedTrigger =
            error("[Actron] An unobserved action cannot be an unexpected trigger")
    }

    data class Observed(val action: Action) : ActionObservation {
        override val observed: Boolean = true
        override fun matches(transition: Transition): Boolean = transition.matchesAction(action)
        override fun unexpected(from: StateId, to: StateId): ConformanceViolation.UnexpectedTrigger =
            ConformanceViolation.UnexpectedTrigger(from, to, action)
    }
}

/**
 * A place where a running Store did something its [StateChartDefinition] does not declare.
 */
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
    data class UndeclaredTransition(val from: StateId, val to: StateId, val lastAction: ActionObservation) : ConformanceViolation {
        constructor(from: StateId, to: StateId, lastAction: Action) : this(from, to, ActionObservation.Observed(lastAction))
    }

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
 * It only observes, through the public plugin hooks, so it works with an unmodified Actron Store
 * and does not change how the Store behaves. Register it in tests, or in debug builds, with
 * `plugin(conformance)` or `actron-test`'s `patch { plugin(conformance) }`.
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
 * Explaining a change: the plugin first asks the runtime. With the transitions matching the
 * trigger from the active leaves and their ancestors as candidates (priority order, see
 * [StateChartRuntime]), it takes the runtime's own selection with every candidate enabled (so
 * the transitions of several regions taken together, or one transition that exits a whole
 * parallel state, explain the change as one step); then the first candidate that leads to `to`
 * alone; then the selection under the other guard assignments (guards are unknown to the
 * plugin; at most the first eight candidates are enumerated); then a timer of an active source
 * fired alone. The transitions of the explanation are covered.
 *
 * Only when nothing explains the change is it attributed region by region, to report it: each
 * leaf of `from` that is not in `to`, in declaration order, needs a transition that exits it,
 * leads to `to` as above and does not exit what an earlier chosen transition exits. A leaf
 * without such a transition is reported, with the leaf of `to` closest to it (sharing its
 * innermost ancestor; the first one in declaration order among equals) as the new state, and the
 * part of the chart a transition between the two would exit counts as explained by that report.
 * A new leaf of `to` that no chosen transition entered and no report covers is reported too,
 * from the closest leaf of `from`. Regions whose leaf stays the same are not checked: re-entering
 * a region leaves nothing to observe.
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
 * parent that contain them (shallow). A self-loop that keeps the leaves is not shown by Actron, so
 * the history it may have recorded is kept as a possibility next to the previous one until a
 * later change settles which was the case.
 *
 * Start: the Store may start in any declared state, not only [StateChartDefinition.initial],
 * because a [actron.core.StateSaver] or `patch { initialState(...) }` may provide the first state.
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
 * above, when no action transition explains it; without a trigger, a timer and an action
 * transition compete in priority order. A change that both an action transition matching the
 * trigger and a timer explain is credited to the action. A timer self-loop keeps the same
 * leaves, so Actron does not report it and it is never covered.
 *
 * Self-transitions: Actron does not notify plugins when a handler keeps the state, so a self-loop
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
 * @param stateIdOf Maps a Actron state to its active leaf; for charts with parallel states use
 * [withActiveLeaves]
 */
class StateChartConformance<S : State, A : Action, E : Event> private constructor(
    val definition: StateChartDefinition,
    private val mapping: LeafMapping<S>,
) : Plugin<S, A, E> {
    constructor(definition: StateChartDefinition, stateIdOf: (S) -> StateId) : this(definition, LeafMapping { setOf(stateIdOf(it)) })

    private fun interface LeafMapping<S : Any> {
        fun leavesOf(state: S): Set<StateId>
    }

    private val declaredStates = definition.states.map { it.id }.toSet()
    private val recordedViolations = mutableListOf<ConformanceViolation>()
    private val recordedCovered = linkedSetOf<Transition>()
    private var lastAction: ActionObservation = ActionObservation.Unobserved
    private var trigger: ActionObservation = ActionObservation.Unobserved
    private var pendingSelfLoops: List<Transition> = emptyList()

    // What the history states may remember, one entry per possibility. Actron does not show a
    // self-loop that keeps the leaves, and the runtime records history on it, so after such an
    // action both "taken" and "not taken" stay possible until a later change settles it.
    private var histories: List<Map<StateId, Set<StateId>>> = listOf(emptyMap())

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
        lastAction = ActionObservation.Observed(action)
        trigger = lastAction
        val leaves = mapping.leavesOf(state)
        var selfLoops: List<Transition> = emptyList()
        val possible = histories.toMutableList()
        for (history in histories) {
            val configuration = definition.configurationOf(leaves).copy(history = history)
            val taken = definition.selectTransitions(configuration) { it.matchesAction(action) }
            if (taken.isEmpty()) continue
            val step = definition.microstep(configuration, taken)
            if (step.leaves(definition).toSet() != leaves) continue
            if (selfLoops.isEmpty()) selfLoops = taken
            // The self-loop may have recorded history; whether it ran (its guards) is unknown.
            if (step.configuration.history !in possible && possible.size < MAX_HISTORIES) possible += step.configuration.history
        }
        histories = possible
        pendingSelfLoops = selfLoops
    }

    override suspend fun onState(scope: PluginScope<S, A>, prevState: S, state: S) {
        val from = mapping.leavesOf(prevState)
        val to = mapping.leavesOf(state)
        if (from == to) return
        pendingSelfLoops = emptyList()
        (to - from).forEach(::checkDeclared)

        val action = trigger
        trigger = ActionObservation.Unobserved
        // First ask the runtime: is there a choice of transitions (guards unknown, so every
        // assignment is tried) that takes a possible configuration exactly to the new leaves?
        // Every possibility that explains the change survives, mapped through its explanation.
        var explained = false
        var explainedTransitions: List<Transition> = emptyList()
        val next = mutableListOf<Map<StateId, Set<StateId>>>()
        for (history in histories) {
            val tracked = definition.configurationOf(from).copy(history = history)
            explain(tracked, action, to) { taken ->
                if (!explained) {
                    explained = true
                    explainedTransitions = taken.transitions
                }
                // Reapply batches to known history; inferred history is not retained.
                val after = taken.batches.fold(tracked) { current, batch -> definition.microstep(current, batch).configuration }.history
                if (after !in next) next += after
            }
        }
        if (explained) {
            recordedCovered += explainedTransitions
            // A change only timers explain used no action: the trigger stays for the next change.
            if (action.observed && explainedTransitions.all { it.isTimer }) trigger = action
            histories = next.take(MAX_HISTORIES)
            return
        }
        // Otherwise attribute leaf by leaf, reporting what no transition explains.
        val history = histories.first()
        val tracked = definition.configurationOf(from).copy(history = history)
        val byDeclaration = compareBy<StateId> { definition.declarationOrder(it) }
        val added = (to - from).sortedWith(byDeclaration)
        val fromLeaves = definition.activeLeaves(tracked)
        val chosen = mutableListOf<Transition>()
        val exited = mutableSetOf<StateId>()
        // Domains of the chosen transitions and of the reports: what they explain.
        val domains = mutableListOf<StateParent>()
        fun report(domain: StateParent, violation: ConformanceViolation) {
            domains += domain
            exited += tracked.active.filter { definition.isDescendant(it, domain) }
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
            val eligible = if (!action.observed) candidates else candidates.filter(action::matches).ifEmpty { candidates.filter { it.isTimer } }
            if (eligible.isNotEmpty()) {
                val taken = eligible.first()
                chosen += taken
                domains += definition.domainOf(taken)
                exited += definition.exitSet(tracked, taken)
                continue
            }
            val target = closest(leaf, added.ifEmpty { to.sortedWith(byDeclaration) })
            val domain = definition.domainOf(leaf, target)
            report(
                domain,
                if (candidates.isEmpty() || !action.observed) {
                    ConformanceViolation.UndeclaredTransition(from = leaf, to = target, lastAction = lastAction)
                } else {
                    action.unexpected(leaf, target)
                },
            )
        }
        for (leaf in added) {
            if (domains.any { definition.isDescendant(leaf, it) }) continue
            val source = closest(leaf, from.sortedWith(byDeclaration))
            report(definition.domainOf(source, leaf), ConformanceViolation.UndeclaredTransition(from = source, to = leaf, lastAction = lastAction))
        }
        recordedCovered += chosen
        // A change only timers explain used no action: the trigger stays for the next change.
        if (action.observed && domains.size == chosen.size && chosen.all { it.isTimer }) trigger = action
        val step = definition.microstep(tracked, chosen)
        val kept = definition.configurationOf(to).active
        val chosenExits = step.exited.toSet()
        histories = listOf(definition.recordHistory(tracked.copy(history = step.configuration.history), tracked.active.filter { it !in kept && it !in chosenExits }))
    }

    /**
     * The transitions that explain the change from [tracked] to the leaves [to], reported to a callback. A
     * transition into a history state is evaluated with an inferred record (see
     * [withInferredHistory]). Candidates are the transitions of the active leaves and
     * their ancestors in priority order. For an action: the runtime's selection with every
     * matching transition enabled (several regions of a parallel state moving together, or one
     * transition exiting the whole parallel state), then the first matching transition that ends
     * in [to] alone, then the other guard assignments, then a timer of an active source fired
     * alone. Without an action: the first transition that ends in [to] alone, whatever its trigger.
     */
    private data class Explanation(val batches: List<List<Transition>>) {
        val transitions: List<Transition> get() = batches.flatten()
    }

    private fun explain(tracked: StateConfiguration, action: ActionObservation, to: Set<StateId>, accept: (Explanation) -> Unit): Boolean {
        fun ending(configuration: StateConfiguration, transitions: List<Transition>): Boolean {
            val first = if (transitions.isEmpty()) configuration else definition.microstep(configuration, transitions).configuration
            if (definition.transitions.none { it.trigger == Trigger.Eventless || it.trigger == Trigger.Completion }) {
                if (transitions.isEmpty()) return false
                if (definition.activeLeaves(first).toSet() != to) return false
                accept(Explanation(listOf(transitions)))
                return true
            }
            // Guards/context are unknown to this observer. Enumerate potential automatic choices,
            // preserving batch order/history and requiring a potentially stable final configuration.
            data class Path(val configuration: StateConfiguration, val batches: List<List<Transition>>)
            val queue = ArrayDeque(listOf(Path(first, if (transitions.isEmpty()) emptyList() else listOf(transitions))))
            val seen = mutableSetOf<StateConfiguration>()
            while (queue.isNotEmpty() && seen.size < 256) {
                val path = queue.removeFirst()
                if (!seen.add(path.configuration)) continue
                val automatic = definition.transitions.filter {
                    it.source in path.configuration.active && (it.trigger == Trigger.Eventless ||
                        (it.trigger == Trigger.Completion && definition.isComplete(path.configuration, it.source)))
                }
                val guarded = automatic.filter { it.guard != actron.statechart.GuardCondition.Unconditional }.take(MAX_GUARD_ENUMERATION)
                for (mask in 0 until (1 shl guarded.size)) {
                    val enabled = automatic.filter { it.guard == actron.statechart.GuardCondition.Unconditional }.toSet() + guarded.filterIndexed { i, _ -> mask and (1 shl i) != 0 }
                    var selected = definition.selectTransitions(path.configuration) { it in enabled && it.trigger == Trigger.Eventless }
                    if (selected.isEmpty()) selected = definition.selectTransitions(path.configuration) { it in enabled && it.trigger == Trigger.Completion }
                    if (selected.isEmpty()) {
                        if (definition.activeLeaves(path.configuration).toSet() == to) {
                            accept(Explanation(path.batches))
                            return true
                        }
                    } else {
                        val next = definition.microstep(path.configuration, selected).configuration
                        if (next !in seen) queue.addLast(Path(next, path.batches + listOf(selected)))
                    }
                }
            }
            return false
        }
        fun alone(transition: Transition): Boolean = ending(withInferredHistory(tracked, transition, to), listOf(transition))
        val candidates = definition.activeLeaves(tracked).flatMap { definition.candidatesFor(it) }.distinct()
        if (!action.observed) return candidates.any(::alone)
        val matching = candidates.filter(action::matches)
        val enumerated = matching.take(MAX_GUARD_ENUMERATION)
        val allEnabled = enumerated.toSet()
        if (ending(tracked, definition.selectTransitions(tracked) { it in allEnabled })) return true
        if (matching.any(::alone)) return true
        for (mask in (1 shl enumerated.size) - 2 downTo 1) {
            val enabled = enumerated.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.toSet()
            if (ending(tracked, definition.selectTransitions(tracked) { it in enabled })) return true
        }
        if (ending(tracked, emptyList())) return true
        return candidates.filter { it.isTimer }.any(::alone)
    }

    /**
     * [configuration] with, for a [transition] into a history state that has recorded nothing
     * yet, the record that would make it restore the leaves in [to] (nothing is known about what
     * was active before the plugin was attached). Otherwise [configuration] itself.
     */
    private fun withInferredHistory(configuration: StateConfiguration, transition: Transition, to: Set<StateId>): StateConfiguration {
        if (!definition.hasNode(transition.target)) return configuration
        val target = definition.node(transition.target)
        if (target !is HistoryState) return configuration
        if (target.id in configuration.history) return configuration
        val domain = definition.domainOf(transition)
        val reported = to.filter { definition.isDescendant(it, domain) }
        val record = if (target.deep) {
            reported.filter { definition.isDescendant(it, target.parent) }
        } else {
            definition.childrenOf(target.parent)
                .filter { child -> child !is HistoryState && reported.any { it == child.id || definition.isDescendant(it, child.id) } }
                .map { it.id }
        }
        if (record.isEmpty()) return configuration
        return configuration.copy(history = configuration.history + (target.id to record.toSet()))
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
        val reported = to.filter { definition.isDescendant(it, domain) }
        if (reported.isEmpty()) return false
        if (enteredBy(configuration, transition).containsAll(reported)) return true
        val inferred = withInferredHistory(configuration, transition, to)
        return inferred !== configuration && enteredBy(inferred, transition).containsAll(reported)
    }

    private fun enteredBy(configuration: StateConfiguration, transition: Transition): List<StateId> =
        definition.microstep(configuration, listOf(transition)).entered

    private fun checkDeclared(id: StateId) {
        if (id !in declaredStates) recordedViolations += ConformanceViolation.UndeclaredState(id)
    }

    companion object {
        private const val MAX_GUARD_ENUMERATION = 8
        private const val MAX_HISTORIES = 8

        /**
         * Creates the plugin for a chart with [ParallelState]s, where one leaf cannot describe a
         * state: [activeLeavesOf] maps a Actron state to all its active leaves, one per active
         * region (see [StateChartDefinition.configurationOf]). For a chart without parallel states
         * it behaves exactly like the constructor with `stateIdOf = { activeLeavesOf(it).single() }`.
         *
         * @param definition The chart the Store should follow
         * @param activeLeavesOf Maps a Actron state to its active leaves
         */
        fun <S : State, A : Action, E : Event> withActiveLeaves(
            definition: StateChartDefinition,
            activeLeavesOf: (S) -> Set<StateId>,
        ): StateChartConformance<S, A, E> = StateChartConformance(definition, LeafMapping { activeLeavesOf(it) })
    }
}

package koma.statechart

import koma.core.Action
import koma.core.ExperimentalKomaApi
import kotlin.jvm.JvmInline
import kotlin.reflect.KClass
import kotlin.time.Duration

/**
 * Stable identifier of a state node in a [StateChartDefinition].
 *
 * @property value The identifier text; must not be blank
 */
@JvmInline
value class StateId(val value: String) {
    init {
        require(value.isNotBlank()) { "[Koma] StateId must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * A node in a statechart.
 *
 * The hierarchy is given by [parent] links, so a definition stays a flat list of nodes. More
 * kinds of nodes may be added, so code that matches on this type should expect more cases.
 */
sealed interface StateNode {
    /**
     * The identifier of this node.
     */
    val id: StateId

    /**
     * The compound or parallel node that contains this node, or `null` for a top-level node.
     */
    val parent: StateId?
}

/**
 * A state without child states.
 *
 * @property parent The compound or parallel state that contains this state, or `null` for a
 * top-level state
 */
data class AtomicState(
    override val id: StateId,
    override val parent: StateId? = null,
) : StateNode

/**
 * A state with child states, of which exactly one is active while this state is active.
 *
 * Children are the nodes whose [StateNode.parent] is [id]. Entering this state enters [initial]
 * too, unless a transition targets a deeper descendant directly.
 *
 * @property initial The child entered by default; must be a child of this state
 * @property parent The compound or parallel state that contains this state, or `null` for a
 * top-level state
 */
data class CompoundState(
    override val id: StateId,
    val initial: StateId,
    override val parent: StateId? = null,
) : StateNode

/**
 * A state whose children, its *regions*, are all active while this state is active.
 *
 * Children are the nodes whose [StateNode.parent] is [id]; every child that is not a
 * [HistoryState] is a region. A region may be an [AtomicState] (always active with this state), a
 * [CompoundState] (one active child at a time) or another [ParallelState]. Entering this state
 * enters every region, in declaration order, each through its own initial states unless a
 * transition targets a descendant of it directly. Exiting it exits every region.
 *
 * In one step each active leaf may select a transition, so transitions in different regions fire
 * together for one action (see [StateChartRuntime]). A transition from a descendant of one region
 * into another region leaves this state: its domain is the nearest compound ancestor, so the whole
 * parallel state is exited and entered again. A parallel state should have at least two regions;
 * see [validate].
 *
 * @property parent The compound or parallel state that contains this state, or `null` for a
 * top-level state
 */
data class ParallelState(
    override val id: StateId,
    override val parent: StateId? = null,
) : StateNode

/**
 * A history pseudo-state of the compound or parallel state [parent]. It is never active itself: a transition
 * that targets it enters [parent] (and the ancestors of [parent] it has to) and restores what was
 * active in [parent] when [parent] was last exited.
 *
 * - A shallow history (`deep = false`) remembers the child of [parent] that was active (for a
 *   parallel [parent], all its regions), and restores it; a compound child is entered through its
 *   own initial states.
 * - A deep history (`deep = true`) remembers the active atomic descendants of [parent], and
 *   restores them with every ancestor between them and [parent], outermost first.
 *
 * Until [parent] has been exited once, nothing is remembered, and the transition enters [default],
 * or the initial child of [parent] when [default] is `null` (every region, when [parent] is a
 * [ParallelState]). Regions of a parallel [parent] that the restored nodes do not cover are
 * entered through their initial states. What is remembered lives in
 * [StateConfiguration.history], keyed by this state's [id]; it is recorded right before [parent] is
 * exited, so a transition that exits [parent] and targets this history restores what it just
 * recorded.
 *
 * A history state has no children and no outgoing transitions, and it cannot be the initial state
 * of the chart or of [parent]. A compound state may have several history states, for example one
 * shallow and one deep; each remembers independently. See [validate].
 *
 * @property parent The compound or parallel state whose configuration is remembered
 * @property deep Whether the active atomic descendants are remembered rather than the active child
 * @property default Entered while nothing is remembered: a child of [parent] for a shallow history,
 * any proper descendant of [parent] for a deep one; `null` means the initial child of [parent]
 */
data class HistoryState(
    override val id: StateId,
    override val parent: StateId,
    val deep: Boolean = false,
    val default: StateId? = null,
) : StateNode

/**
 * Describes which action triggers a [Transition].
 *
 * @property name Stable display name used by tools such as the Mermaid exporter. It is also how
 * the matcher recognizes actions when [type] is null (see [matches]).
 * @property type The action type, when the matcher was created from one
 */
data class ActionMatcher(
    val name: String,
    val type: KClass<out Action>? = null,
) {
    init {
        require(name.isNotBlank()) { "[Koma] ActionMatcher name must not be blank" }
    }

    /**
     * Whether [action] triggers transitions with this matcher: `type.isInstance(action)` when
     * [type] is set, so a supertype matcher also matches its subtypes; otherwise the action's
     * simple class name must equal [name].
     *
     * The runtime and [validate] both use this definition.
     */
    fun matches(action: Action): Boolean {
        val type = type
        return if (type != null) type.isInstance(action) else action::class.simpleName == name
    }

    companion object {
        /**
         * Creates a matcher for the action type [A] with the display name [name].
         *
         * The name is explicit rather than taken from the class name, because class names change
         * under code shrinking and obfuscation (R8, ProGuard) and nested classes in different
         * hierarchies can share a simple name. Matching uses the type, not the name.
         *
         * @param name Stable display name; must not be blank
         */
        inline fun <reified A : Action> of(name: String): ActionMatcher = ActionMatcher(name = name, type = A::class)
    }
}

/**
 * What makes a [Transition] fire.
 *
 * More kinds of triggers may be added, so code that matches on this type should expect more cases.
 */
sealed interface Trigger {
    /**
     * The transition fires when an action matching [matcher] arrives (see [StateChartRuntime.step]).
     */
    data class OnAction(val matcher: ActionMatcher) : Trigger

    /**
     * The transition is a timer: it fires [delay] after its source is entered, unless the source
     * is exited first (see [StateChartRuntime.fire]).
     *
     * The timer starts every time its source is entered and is cancelled every time the source is
     * exited, so re-entering the source (for example through a self-loop) restarts it. The runtime
     * only says which timers to start and cancel ([StepResult.Transitioned.timersToStart] and
     * [StepResult.Transitioned.timersToCancel]); scheduling them is the caller's job.
     *
     * @property delay How long the source must stay active; should be positive (see [validate])
     */
    data class After(val delay: Duration) : Trigger
}

/**
 * A transition from [source] to [target], fired by [trigger]: an action ([Trigger.OnAction]) or a
 * timer ([Trigger.After]).
 *
 * `Transition(source, target, on = matcher, guard)` builds an action transition, as before timers
 * existed; `Transition(source, target, Trigger.After(5.seconds))` builds a timer. Two transitions
 * are equal when all their fields are, so two declarations with the same endpoints, trigger,
 * guard and effect are indistinguishable (see [validate]).
 *
 * @property source The state the transition leaves
 * @property target The state the transition enters
 * @property trigger What fires the transition
 * @property guard Optional label of the condition that must hold; the model only records the label.
 * For a timer, the guard is asked when the timer fires, with [TimerFired] as the action.
 * @property effect Optional label of what the transition does, such as updating data; the model
 * only records the label, and the runtime ignores it
 */
data class Transition(
    val source: StateId,
    val target: StateId,
    val trigger: Trigger,
    val guard: String? = null,
    val effect: String? = null,
) {
    /**
     * A transition fired by an action matching [on].
     */
    constructor(
        source: StateId,
        target: StateId,
        on: ActionMatcher,
        guard: String? = null,
        effect: String? = null,
    ) : this(source, target, Trigger.OnAction(on), guard, effect)

    /**
     * The matcher of an action transition, or `null` for a timer.
     */
    val on: ActionMatcher? get() = (trigger as? Trigger.OnAction)?.matcher

    /**
     * The delay of a timer, or `null` for an action transition.
     */
    val after: Duration? get() = (trigger as? Trigger.After)?.delay

    /**
     * Whether this transition is a timer ([Trigger.After]).
     */
    val isTimer: Boolean get() = trigger is Trigger.After
}

/**
 * Immutable description of a statechart: its states, its initial state and its transitions.
 *
 * The definition is plain data. It does not run anything, so tools can read it fully:
 * see [validate] and [toMermaid].
 *
 * Nodes form a tree through [StateNode.parent]. Top-level nodes behave as children of an implicit
 * root. When an id is declared more than once, the hierarchy helpers below use its first
 * declaration; [validate] reports the duplicate.
 *
 * @property initial The state the chart starts in. Usually a top-level node; a nested one is
 * entered together with its ancestors. A compound one is entered together with its initial
 * descendants. It must not be a [HistoryState].
 * @property states All state nodes, in declaration order
 * @property transitions All transitions, in declaration order
 */
data class StateChartDefinition(
    val initial: StateId,
    val states: List<StateNode>,
    val transitions: List<Transition>,
) {
    internal val hierarchy: HierarchyIndex by lazy { HierarchyIndex(this) }

    /** The configuration search behind paths and reachability; computed once per definition. */
    internal val configurationGraph: ConfigurationGraph by lazy { buildConfigurationGraph() }

    /**
     * Returns the transitions that leave [source], in declaration order.
     */
    fun transitionsFrom(source: StateId): List<Transition> = hierarchy.transitionsBySource[source].orEmpty()

    /**
     * Returns the first declared node with [id], or `null` when [id] is not declared.
     */
    fun node(id: StateId): StateNode? = hierarchy.nodes[id]

    /**
     * Returns the nodes whose parent is [parent], in declaration order, history states included;
     * `null` returns the top-level nodes.
     */
    fun childrenOf(parent: StateId?): List<StateNode> = hierarchy.children[parent].orEmpty()

    /**
     * Returns the declared ancestors of [id], innermost first, without [id] itself.
     *
     * The walk stops at a parent that is not declared and before an id it has already visited, so
     * it ends even for a malformed hierarchy (see [validate]).
     */
    fun ancestorsOf(id: StateId): List<StateId> = hierarchy.ancestors(id)

    /**
     * Whether [id] is a proper descendant of [ancestor], that is [ancestor] is in [ancestorsOf].
     */
    fun isDescendant(id: StateId, ancestor: StateId): Boolean = ancestor in hierarchy.ancestors(id)

    /**
     * Returns the configuration in which [leaf] is the active leaf: [leaf] and its ancestors,
     * outermost first. It maps a Koma state, through its leaf id, back to a chart configuration.
     *
     * Nothing is entered below [leaf], so pass a leaf, not a compound state. Inside a
     * [ParallelState] one leaf does not describe the other regions; use the overload that takes
     * all active leaves.
     */
    fun configurationOf(leaf: StateId): StateConfiguration =
        StateConfiguration(active = (hierarchy.ancestors(leaf).asReversed() + leaf).toSet())

    /**
     * Returns the configuration whose active leaves are [leaves]: each leaf after its ancestors,
     * outermost first, leaves in the order given. It maps a Koma state of a chart with
     * [ParallelState]s, through its active leaves (one per active region), back to a chart
     * configuration. Nothing is entered below the leaves, and no missing region is filled in.
     */
    fun configurationOf(leaves: Collection<StateId>): StateConfiguration =
        StateConfiguration(active = leaves.flatMapTo(linkedSetOf()) { hierarchy.ancestors(it).asReversed() + it })
}

/**
 * Lookup tables for one definition, built on first use.
 */
internal class HierarchyIndex(definition: StateChartDefinition) {
    val nodes: Map<StateId, StateNode> = buildMap { definition.states.forEach { if (it.id !in this) put(it.id, it) } }
    val children: Map<StateId?, List<StateNode>> = nodes.values.groupBy { it.parent }
    val transitionsBySource: Map<StateId, List<Transition>> = definition.transitions.groupBy { it.source }

    /** Timers ([Trigger.After] transitions) by source, in declaration order. */
    val timersBySource: Map<StateId, List<Transition>> = definition.transitions.filter { it.isTimer }.groupBy { it.source }

    /** Regions of each parallel state: its children that are not history states, in declaration order. */
    val regions: Map<StateId, List<StateId>> = nodes.values.filterIsInstance<ParallelState>().associate { p ->
        p.id to children[p.id].orEmpty().filter { it !is HistoryState }.map { it.id }
    }

    /** History states by the id of their parent, in declaration order. */
    val histories: Map<StateId, List<HistoryState>> = nodes.values.filterIsInstance<HistoryState>().groupBy { it.parent }

    /** Declaration position of each first-declared id; undeclared ids sort after all of them. */
    val order: Map<StateId, Int> = nodes.keys.withIndex().associate { (i, id) -> id to i }

    /** Ancestors of every declared id, innermost first; computed eagerly so the index is immutable. */
    private val ancestorsById: Map<StateId, List<StateId>> = nodes.keys.associateWith { id ->
        val chain = mutableListOf<StateId>()
        var current = nodes.getValue(id).parent
        while (current != null && current != id && current !in chain && current in nodes) {
            chain += current
            current = nodes.getValue(current).parent
        }
        chain
    }

    fun ancestors(id: StateId): List<StateId> = ancestorsById[id].orEmpty()
}

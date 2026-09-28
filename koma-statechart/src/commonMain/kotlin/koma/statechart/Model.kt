package koma.statechart

import koma.core.Action
import koma.core.ExperimentalKomaApi
import kotlin.jvm.JvmInline
import kotlin.reflect.KClass

/**
 * Stable identifier of a state node in a [StateChartDefinition].
 *
 * @property value The identifier text; must not be blank
 */
@ExperimentalKomaApi
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
 * The hierarchy is given by [parent] links, so a definition stays a flat list of nodes. Parallel
 * and history nodes will be added as new implementations, so code that matches on this type
 * should expect more cases.
 */
@ExperimentalKomaApi
sealed interface StateNode {
    /**
     * The identifier of this node.
     */
    val id: StateId

    /**
     * The compound node that contains this node, or `null` for a top-level node.
     */
    val parent: StateId?
}

/**
 * A state without child states.
 *
 * @property parent The compound state that contains this state, or `null` for a top-level state
 */
@ExperimentalKomaApi
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
 * @property parent The compound state that contains this state, or `null` for a top-level state
 */
@ExperimentalKomaApi
data class CompoundState(
    override val id: StateId,
    val initial: StateId,
    override val parent: StateId? = null,
) : StateNode

/**
 * Describes which action triggers a [Transition].
 *
 * @property name Stable display name used by tools such as the Mermaid exporter. It is also how
 * the matcher recognizes actions when [type] is null (see [matches]).
 * @property type The action type, when the matcher was created from one
 */
@ExperimentalKomaApi
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
 * A transition from [source] to [target] when an action matching [on] arrives.
 *
 * @property source The state the transition leaves
 * @property target The state the transition enters
 * @property on The action that triggers the transition
 * @property guard Optional label of the condition that must hold; the model only records the label
 */
@ExperimentalKomaApi
data class Transition(
    val source: StateId,
    val target: StateId,
    val on: ActionMatcher,
    val guard: String? = null,
)

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
 * descendants.
 * @property states All state nodes, in declaration order
 * @property transitions All transitions, in declaration order
 */
@ExperimentalKomaApi
data class StateChartDefinition(
    val initial: StateId,
    val states: List<StateNode>,
    val transitions: List<Transition>,
) {
    internal val hierarchy: HierarchyIndex by lazy { HierarchyIndex(this) }

    /**
     * Returns the transitions that leave [source], in declaration order.
     */
    fun transitionsFrom(source: StateId): List<Transition> = hierarchy.transitionsBySource[source].orEmpty()

    /**
     * Returns the first declared node with [id], or `null` when [id] is not declared.
     */
    fun node(id: StateId): StateNode? = hierarchy.nodes[id]

    /**
     * Returns the nodes whose parent is [parent], in declaration order; `null` returns the
     * top-level nodes.
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
     * Nothing is entered below [leaf], so pass a leaf, not a compound state.
     */
    fun configurationOf(leaf: StateId): StateConfiguration =
        StateConfiguration(active = (hierarchy.ancestors(leaf).asReversed() + leaf).toSet())
}

/**
 * Lookup tables for one definition, built on first use.
 */
@OptIn(ExperimentalKomaApi::class)
internal class HierarchyIndex(definition: StateChartDefinition) {
    val nodes: Map<StateId, StateNode> = buildMap { definition.states.forEach { if (it.id !in this) put(it.id, it) } }
    val children: Map<StateId?, List<StateNode>> = nodes.values.groupBy { it.parent }
    val transitionsBySource: Map<StateId, List<Transition>> = definition.transitions.groupBy { it.source }

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

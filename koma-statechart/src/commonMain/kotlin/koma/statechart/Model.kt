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
 * Only [AtomicState] exists for now. Compound and parallel states will be added as new
 * implementations, so code that matches on this type should expect more cases.
 */
@ExperimentalKomaApi
sealed interface StateNode {
    /**
     * The identifier of this node.
     */
    val id: StateId
}

/**
 * A state without child states.
 */
@ExperimentalKomaApi
data class AtomicState(override val id: StateId) : StateNode

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
 * @property initial The state the chart starts in
 * @property states All state nodes, in declaration order
 * @property transitions All transitions, in declaration order
 */
@ExperimentalKomaApi
data class StateChartDefinition(
    val initial: StateId,
    val states: List<StateNode>,
    val transitions: List<Transition>,
) {
    /**
     * Returns the transitions that leave [source], in declaration order.
     */
    fun transitionsFrom(source: StateId): List<Transition> = transitions.filter { it.source == source }
}

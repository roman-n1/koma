package actron.statechart

import actron.core.Action
import actron.core.ActronStoreDsl
import kotlin.jvm.JvmInline
import kotlin.time.Duration

/** Typed, stable guard label shared by model and implementation. */
@JvmInline value class GuardKey(val name: String) : GuardCondition {
    init { require(name.isNotBlank()) { "[Actron] Guard key must not be blank" } }
    override fun withLabel(visit: (String) -> Unit) { visit(name) }
    override val displayLabel: String get() = name
}
/** Typed, stable reducer label shared by model and implementation. */
@JvmInline value class EffectKey(val name: String) : TransitionEffect {
    init { require(name.isNotBlank()) { "[Actron] Effect key must not be blank" } }
    override fun withLabel(visit: (String) -> Unit) { visit(name) }
    override val displayLabel: String get() = name
}

/** Whether selection evaluates an application guard. */
sealed interface GuardCondition {
    fun withLabel(visit: (String) -> Unit)
    val displayLabel: String
    data object Unconditional : GuardCondition {
        override fun withLabel(visit: (String) -> Unit) {}
        override val displayLabel: String = "—"
    }
}

/** Whether taking a transition invokes an application reducer. */
sealed interface TransitionEffect {
    fun withLabel(visit: (String) -> Unit)
    val displayLabel: String
    data object NoEffect : TransitionEffect {
        override fun withLabel(visit: (String) -> Unit) {}
        override val displayLabel: String = "—"
    }
}

/** Builds immutable definition data; runtime implementations are supplied separately. */
fun stateChart(initial: StateId, build: StateChartBuilder.() -> Unit): StateChartDefinition =
    StateChartBuilder().apply(build).definition(initial)

@ActronStoreDsl
class StateChartBuilder internal constructor(private val parent: StateParent = StateParent.Root) {
    private val nodes = mutableListOf<StateNode>()
    private val transitions = mutableListOf<Transition>()

    fun state(id: StateId, build: StateTransitionsBuilder.() -> Unit = {}) {
        nodes += AtomicState(id, parent)
        transitions += StateTransitionsBuilder(id).apply(build).build()
    }

    fun final(id: StateId) { nodes += FinalState(id, parent) }
    fun history(id: StateId, deep: Boolean = false, default: HistoryFallback = HistoryFallback.InitialConfiguration) {
        val owner = parent
        require(owner is StateId) { "[Actron] History needs a declared parent" }
        nodes += HistoryState(id, owner, deep, default)
    }

    fun compound(id: StateId, initial: StateId, build: StateChartBuilder.() -> Unit) {
        nodes += CompoundState(id, initial, parent)
        nested(id, build)
    }
    fun parallel(id: StateId, build: StateChartBuilder.() -> Unit) {
        nodes += ParallelState(id, parent)
        nested(id, build)
    }
    /** Transitions of a compound/parallel node, declared independently from its children. */
    fun transitions(id: StateId, build: StateTransitionsBuilder.() -> Unit) {
        transitions += StateTransitionsBuilder(id).apply(build).build()
    }
    private fun nested(id: StateId, build: StateChartBuilder.() -> Unit) {
        val nested = StateChartBuilder(id).apply(build)
        nodes += nested.nodes
        transitions += nested.transitions
    }
    internal fun definition(initial: StateId) = StateChartDefinition(initial, nodes.toList(), transitions.toList())
}

@ActronStoreDsl
class StateTransitionsBuilder internal constructor(private val source: StateId) {
    private val transitions = mutableListOf<Transition>()

    fun on(matcher: ActionMatcher, target: StateId, build: TransitionBuilder.() -> Unit = {}) = add(target, Trigger.OnAction(matcher), build)
    inline fun <reified A : Action> on(name: String, target: StateId, noinline build: TransitionBuilder.() -> Unit = {}) = on(ActionMatcher.of<A>(name), target, build)
    fun always(target: StateId, build: TransitionBuilder.() -> Unit = {}) = add(target, Trigger.Eventless, build)
    fun onDone(target: StateId, build: TransitionBuilder.() -> Unit = {}) = add(target, Trigger.Completion, build)
    fun after(delay: Duration, target: StateId, build: TransitionBuilder.() -> Unit = {}) = add(target, Trigger.After(delay), build)
    /** An internal handler represented in the chart, preserving state lifetime. */
    fun internal(matcher: ActionMatcher, build: TransitionBuilder.() -> Unit = {}) = add(source, Trigger.OnAction(matcher)) {
        internal(); build()
    }
    private fun add(target: StateId, trigger: Trigger, build: TransitionBuilder.() -> Unit) {
        val rule = TransitionBuilder().apply(build)
        transitions += Transition(source, target, trigger, rule.guard, rule.effect, rule.kind)
    }
    internal fun build(): List<Transition> = transitions.toList()
}

@ActronStoreDsl
class TransitionBuilder internal constructor() {
    internal var guard: GuardCondition = GuardCondition.Unconditional
    internal var effect: TransitionEffect = TransitionEffect.NoEffect
    internal var kind = TransitionKind.External
    fun guard(key: GuardKey) { guard = key }
    fun effect(key: EffectKey) { effect = key }
    fun internal() { kind = TransitionKind.Internal }
}

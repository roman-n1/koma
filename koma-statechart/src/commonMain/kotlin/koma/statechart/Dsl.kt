package koma.statechart

import koma.core.Action
import koma.core.KomaStoreDsl
import kotlin.jvm.JvmInline
import kotlin.time.Duration

/** Typed, stable guard label shared by model and implementation. */
@JvmInline value class GuardKey(val name: String) {
    init { require(name.isNotBlank()) { "[Koma] Guard key must not be blank" } }
}
/** Typed, stable reducer label shared by model and implementation. */
@JvmInline value class EffectKey(val name: String) {
    init { require(name.isNotBlank()) { "[Koma] Effect key must not be blank" } }
}

/** Builds immutable definition data; runtime implementations are supplied separately. */
fun stateChart(initial: StateId, build: StateChartBuilder.() -> Unit): StateChartDefinition =
    StateChartBuilder().apply(build).definition(initial)

@KomaStoreDsl
class StateChartBuilder internal constructor(private val parent: StateId? = null) {
    private val nodes = mutableListOf<StateNode>()
    private val transitions = mutableListOf<Transition>()

    fun state(id: StateId, build: StateTransitionsBuilder.() -> Unit = {}) {
        nodes += AtomicState(id, parent)
        transitions += StateTransitionsBuilder(id).apply(build).build()
    }

    fun final(id: StateId) { nodes += FinalState(id, parent) }
    fun history(id: StateId, deep: Boolean = false, default: StateId? = null) {
        nodes += HistoryState(id, requireNotNull(parent) { "[Koma] History needs a parent" }, deep, default)
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

@KomaStoreDsl
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

@KomaStoreDsl
class TransitionBuilder internal constructor() {
    internal var guard: String? = null
    internal var effect: String? = null
    internal var kind = TransitionKind.External
    fun guard(key: GuardKey) { guard = key.name }
    fun effect(key: EffectKey) { effect = key.name }
    fun internal() { kind = TransitionKind.Internal }
}

package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import actron.core.ExperimentalActronApi
import actron.core.Plugin
import actron.core.PluginScope
import kotlin.coroutines.CoroutineContext

/**
 * A [Plugin] written for the [MachineStore] (state `MachineSnapshot`, actions `A`, effects `E`)
 * running inside the inner Actron Store, whose actions are [MachineInput]s. `onStart` and `onState`
 * pass through with an adapted scope; `onAction` reaches the plugin for the inputs that carry an
 * action of the store ([MachineInput.Dispatch], [MachineInput.External],
 * [MachineInput.BridgeReceived]), not for the executor's own inputs (results, completions,
 * timers). `onEvent` never comes from the inner store, whose events bypass it: the executor
 * calls [deliver] with every effect of a committed decision, transient and retained alike, in
 * decision order.
 */
internal class AdaptedPlugin<C : Any, A : Action, E : Event>(
    val plugin: Plugin<MachineSnapshot<C>, A, E>,
    private val store: MachineStore<C, A, *, E>,
) : Plugin<MachineSnapshot<C>, MachineInput<A>, E> {
    override suspend fun onStart(scope: PluginScope<MachineSnapshot<C>, MachineInput<A>>, state: MachineSnapshot<C>) = plugin.onStart(scope.adapted(), state)

    override suspend fun onAction(scope: PluginScope<MachineSnapshot<C>, MachineInput<A>>, state: MachineSnapshot<C>, action: MachineInput<A>) {
        when (action) {
            is MachineInput.Dispatch -> plugin.onAction(scope.adapted(), state, action.action)
            is MachineInput.External -> plugin.onAction(scope.adapted(), state, action.action)
            is MachineInput.BridgeReceived -> plugin.onAction(scope.adapted(), state, action.action)
            else -> Unit
        }
    }

    override suspend fun onState(scope: PluginScope<MachineSnapshot<C>, MachineInput<A>>, prevState: MachineSnapshot<C>, state: MachineSnapshot<C>) = plugin.onState(scope.adapted(), prevState, state)

    /** The executor's `onEvent`: [event] is an effect of the decision that committed [state]. */
    suspend fun deliver(scope: PluginScope<MachineSnapshot<C>, MachineInput<A>>, state: MachineSnapshot<C>, event: E) = plugin.onEvent(scope.adapted(), state, event)

    private fun PluginScope<MachineSnapshot<C>, MachineInput<A>>.adapted(): PluginScope<MachineSnapshot<C>, A> = AdaptedPluginScope(this, store)
}

/** The [MachineStore]'s side of the inner store's plugin scope: a dispatch goes through admission. */
private class AdaptedPluginScope<C : Any, A : Action>(
    private val inner: PluginScope<MachineSnapshot<C>, MachineInput<A>>,
    private val store: MachineStore<C, A, *, *>,
) : PluginScope<MachineSnapshot<C>, A> {
    override fun dispatch(action: A) = store.dispatch(action)

    override fun launch(dispatcher: CoroutineContext, block: suspend PluginScope.LaunchScope<MachineSnapshot<C>, A>.() -> Unit) {
        val target = store
        inner.launch(dispatcher) { AdaptedLaunchScope(this, target).block() }
    }
}

private class AdaptedLaunchScope<C : Any, A : Action>(
    private val inner: PluginScope.LaunchScope<MachineSnapshot<C>, MachineInput<A>>,
    private val store: MachineStore<C, A, *, *>,
) : PluginScope.LaunchScope<MachineSnapshot<C>, A> {
    override val currentState: MachineSnapshot<C> get() = inner.currentState

    override fun dispatch(action: A) = store.dispatch(action)
}

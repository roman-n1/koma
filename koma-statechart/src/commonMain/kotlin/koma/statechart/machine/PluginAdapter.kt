package koma.statechart.machine

import koma.core.Action
import koma.core.Event
import koma.core.ExperimentalKomaApi
import koma.core.Plugin
import koma.core.PluginScope
import kotlinx.coroutines.CoroutineDispatcher

/**
 * A [Plugin] written for the [MachineStore] (state `MachineSnapshot`, actions `A`, effects `E`)
 * running inside the inner Koma Store, whose actions are [MachineInput]s. `onStart` and `onState`
 * pass through with an adapted scope; `onAction` reaches the plugin for the inputs that carry an
 * action of the store ([MachineInput.Dispatch], [MachineInput.External],
 * [MachineInput.BridgeReceived]), not for the executor's own inputs (results, completions,
 * timers). `onEvent` never comes from the inner store, whose events bypass it: the executor
 * calls [deliver] with every effect of a committed decision, transient and retained alike, in
 * decision order.
 */
@OptIn(ExperimentalKomaApi::class)
internal class AdaptedPlugin<C, A : Action, E : Event>(
    val plugin: Plugin<MachineSnapshot<C>, A, E>,
    private val store: MachineStore<C, A, *, E>,
) : Plugin<MachineSnapshot<C>, MachineInput<A>, E> {
    override suspend fun onStart(scope: PluginScope<MachineSnapshot<C>, MachineInput<A>>, state: MachineSnapshot<C>) = plugin.onStart(scope.adapted(), state)

    override suspend fun onAction(scope: PluginScope<MachineSnapshot<C>, MachineInput<A>>, state: MachineSnapshot<C>, action: MachineInput<A>) {
        val carried = action.carriedAction() ?: return
        plugin.onAction(scope.adapted(), state, carried)
    }

    override suspend fun onState(scope: PluginScope<MachineSnapshot<C>, MachineInput<A>>, prevState: MachineSnapshot<C>, state: MachineSnapshot<C>) = plugin.onState(scope.adapted(), prevState, state)

    /** The executor's `onEvent`: [event] is an effect of the decision that committed [state]. */
    suspend fun deliver(scope: PluginScope<MachineSnapshot<C>, MachineInput<A>>, state: MachineSnapshot<C>, event: E) = plugin.onEvent(scope.adapted(), state, event)

    private fun PluginScope<MachineSnapshot<C>, MachineInput<A>>.adapted(): PluginScope<MachineSnapshot<C>, A> = AdaptedPluginScope(this, store)
}

/** The action of the store an input carries, or `null` for the executor's own inputs. */
@OptIn(ExperimentalKomaApi::class)
internal fun <A : Action> MachineInput<A>.carriedAction(): A? = when (this) {
    is MachineInput.Dispatch -> action
    is MachineInput.External -> action
    is MachineInput.BridgeReceived -> action
    else -> null
}

/** The [MachineStore]'s side of the inner store's plugin scope: a dispatch goes through admission. */
@OptIn(ExperimentalKomaApi::class)
private class AdaptedPluginScope<C, A : Action>(
    private val inner: PluginScope<MachineSnapshot<C>, MachineInput<A>>,
    private val store: MachineStore<C, A, *, *>,
) : PluginScope<MachineSnapshot<C>, A> {
    override fun dispatch(action: A) = store.dispatch(action)

    override fun launch(dispatcher: CoroutineDispatcher?, block: suspend PluginScope.LaunchScope<MachineSnapshot<C>, A>.() -> Unit) {
        val target = store
        inner.launch(dispatcher) { AdaptedLaunchScope(this, target).block() }
    }
}

@OptIn(ExperimentalKomaApi::class)
private class AdaptedLaunchScope<C, A : Action>(
    private val inner: PluginScope.LaunchScope<MachineSnapshot<C>, MachineInput<A>>,
    private val store: MachineStore<C, A, *, *>,
) : PluginScope.LaunchScope<MachineSnapshot<C>, A> {
    override val currentState: MachineSnapshot<C> get() = inner.currentState

    override fun dispatch(action: A) = store.dispatch(action)
}

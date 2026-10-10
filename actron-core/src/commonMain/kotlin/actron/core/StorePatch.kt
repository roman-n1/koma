@file:OptIn(InternalActronApi::class)

package actron.core

import kotlin.coroutines.CoroutineContext

/**
 * @property probes [StoreProbe]s to append; an internal observation API (see [StoreProbe])
 */
data class StorePatch<S : State, A : Action, E : Event>(
    val settings: List<StoreSetting<S>> = emptyList(),
    val pluginPatches: List<PluginPatch<S, A, E>> = emptyList(),
    val probes: List<StoreProbe<S, A, E>> = emptyList(),
)

/** Commands applied before a Store starts. An empty patch performs no configuration changes. */
sealed interface StoreSetting<out S : State> {
    data class InitialState<S : State>(val state: S) : StoreSetting<S>
    data class ExecutionContext(val context: CoroutineContext) : StoreSetting<Nothing>
    data class Persistence<S : State>(val saver: StateSaver<S>) : StoreSetting<S>
    data class FailureHandler(val handler: ExceptionHandler) : StoreSetting<Nothing>
    data class AutoStart(val policy: AutoStartPolicy) : StoreSetting<Nothing>
    data class PendingActions(val policy: PendingActionPolicy) : StoreSetting<Nothing>
    data class PluginExecution(val policy: PluginExecutionPolicy) : StoreSetting<Nothing>
}

sealed interface PluginPatch<S : State, A : Action, E : Event> {
    data class Append<S : State, A : Action, E : Event>(val plugins: List<Plugin<S, A, E>>) : PluginPatch<S, A, E>
    data class Replace<S : State, A : Action, E : Event>(val plugins: List<Plugin<S, A, E>>) : PluginPatch<S, A, E>
    class Clear<S : State, A : Action, E : Event> : PluginPatch<S, A, E> {
        override fun equals(other: Any?): Boolean = other is Clear<*, *, *>
        override fun hashCode(): Int = 0
        override fun toString(): String = "Clear"
    }
}

/**
 * Builder used to construct a [StorePatch] via a DSL.
 *
 * This is the canonical builder shared by `:actron-test`'s public `Store.patch { ... }` extension
 * and Actron's own internal tests. It exposes Store configuration values that can be patched before
 * the Store has consumed them.
 */
@Suppress("unused")
class StorePatchBuilder<S : State, A : Action, E : Event> {
    private val settings = mutableListOf<StoreSetting<S>>()
    private val pluginPatches = mutableListOf<PluginPatch<S, A, E>>()
    private val probePatches = mutableListOf<StoreProbe<S, A, E>>()

    fun initialState(state: S) {
        settings += StoreSetting.InitialState(state)
    }

    fun coroutineContext(coroutineContext: CoroutineContext) {
        settings += StoreSetting.ExecutionContext(coroutineContext)
    }

    fun stateSaver(stateSaver: StateSaver<S>) {
        settings += StoreSetting.Persistence(stateSaver)
    }

    fun exceptionHandler(exceptionHandler: ExceptionHandler) {
        settings += StoreSetting.FailureHandler(exceptionHandler)
    }

    fun autoStartPolicy(policy: AutoStartPolicy) {
        settings += StoreSetting.AutoStart(policy)
    }

    fun pendingActionPolicy(policy: PendingActionPolicy) {
        settings += StoreSetting.PendingActions(policy)
    }

    fun pluginExecutionPolicy(policy: PluginExecutionPolicy) {
        settings += StoreSetting.PluginExecution(policy)
    }

    fun plugin(first: Plugin<S, A, E>, vararg rest: Plugin<S, A, E>) {
        pluginPatches += PluginPatch.Append(plugins = listOf(first) + rest)
    }

    fun replacePlugins(first: Plugin<S, A, E>, vararg rest: Plugin<S, A, E>) {
        pluginPatches += PluginPatch.Replace(plugins = listOf(first) + rest)
    }

    fun clearPlugins() {
        pluginPatches += PluginPatch.Clear()
    }

    /**
     * Appends [StoreProbe]s that observe the processing boundaries of the Store. Probes are an
     * internal observation API for journals and inspectors, not an extension point.
     */
    @InternalActronApi
    fun probe(first: StoreProbe<S, A, E>, vararg rest: StoreProbe<S, A, E>) {
        probePatches += first
        probePatches += rest
    }

    fun build(): StorePatch<S, A, E> {
        return StorePatch(
            settings = settings.toList(),
            pluginPatches = pluginPatches.toList(),
            probes = probePatches.toList(),
        )
    }
}

package koma.statechart

import koma.core.Action
import koma.core.AutoStartPolicy
import koma.core.Event
import koma.core.ExceptionHandler
import koma.core.InternalKomaApi
import koma.core.KomaStoreDsl
import koma.core.Plugin
import koma.core.PluginExecutionPolicy
import koma.core.State
import koma.core.StateSaver
import koma.core.StoreBuilder
import koma.core.StoreProbe
import koma.core.StoreScope
import koma.observability.recordTo
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Runtime settings shared by chart and machine adapters. The adapter owns its initial state
 * and handlers; this scope cannot install competing handlers or write a machine snapshot.
 * Settings called repeatedly use the last value; plugins and probes append in call order.
 */
@KomaStoreDsl
open class StoreConfiguration<S : State, A : Action, E : Event> internal constructor(
    @PublishedApi internal val delegate: StoreBuilder<S, A, E>,
) {
    /** Sets the Store processing context. Command execution retains the MachineStore's own scope. */
    fun coroutineContext(context: CoroutineContext) = delegate.coroutineContext(context)
    /** Sets application-owned snapshot persistence; see the adapter's restoration contract. */
    fun stateSaver(saver: StateSaver<S>) = delegate.stateSaver(saver)
    /** Handles failures which have not been recovered by the chart. Machines report failures here. */
    fun exceptionHandler(handler: ExceptionHandler) = delegate.exceptionHandler(handler)
    /** Selects which implicit triggers start the Store; explicit start is always available. */
    fun autoStartPolicy(policy: AutoStartPolicy) = delegate.autoStartPolicy(policy)
    /** Selects plugin execution order. All matching hooks finish before processing continues. */
    fun pluginExecutionPolicy(policy: PluginExecutionPolicy) = delegate.pluginExecutionPolicy(policy)
    /** Appends plugins observing the adapter's committed snapshots and inputs. */
    fun plugin(first: Plugin<S, A, E>, vararg rest: Plugin<S, A, E>) = delegate.plugin(first, *rest)
    /** Appends internal processing-boundary observers for journals and inspectors. */
    @InternalKomaApi
    fun probe(first: StoreProbe<S, A, E>, vararg rest: StoreProbe<S, A, E>) = delegate.probe(first, *rest)
}

/** Recovery of chart data without access to configuration, timers, or arbitrary state writes. */
@KomaStoreDsl
interface ChartRecoveryScope<C, E : Event, T : Exception> {
    /** The underlying handler scope, for extensions such as `store.message(...)`. */
    val store: StoreScope
    /** The failure being handled. */
    val error: T
    /** Working context; committed once if this recovery handler completes successfully. */
    var context: C
    /** Emits an event immediately, before recovery commits. */
    suspend fun event(event: E)
}

/** Chart runtime settings and recovery handlers which can change context only. */
@KomaStoreDsl
class ChartStoreConfiguration<C, A : Action, E : Event> internal constructor(
    delegate: StoreBuilder<ChartState<C>, A, E>,
) : StoreConfiguration<ChartState<C>, A, E>(delegate) {
    /**
     * Handles [T], updating only context. The first matching handler wins. A throwing handler
     * commits no context change and reaches the exception handler. To change active nodes,
     * dispatch a declared chart action after recovery.
     */
    inline fun <reified T : Exception> recover(
        dispatcher: CoroutineDispatcher? = null,
        noinline block: suspend ChartRecoveryScope<C, E, T>.() -> Unit,
    ) {
        delegate.state<ChartState<C>> {
            recover<T>(dispatcher) {
                val underlying = this
                val scope = object : ChartRecoveryScope<C, E, T> {
                    override val store: StoreScope = underlying
                    override val error: T = underlying.error
                    override var context: C = underlying.state.context
                    override suspend fun event(event: E) = underlying.event(event)
                }
                scope.block()
                nextState { state.copy(context = scope.context) }
            }
        }
    }
}

/** Records adapter processing using the same journal contract as the core Store builder. */
fun <S : State, A : Action, E : Event> StoreConfiguration<S, A, E>.recordTo(
    session: koma.observability.RecordingSession,
    store: koma.observability.StoreInstanceId,
    policy: koma.observability.PayloadPolicy<S, A, E> = koma.observability.PayloadPolicy.metadataOnly(),
) {
    delegate.recordTo(session, store, policy)
}

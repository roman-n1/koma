package koma.statechart.machine

import koma.core.Action
import koma.core.Event
import koma.core.ExperimentalKomaApi
import koma.core.InternalKomaApi
import koma.core.Plugin
import koma.core.PluginScope
import koma.core.Store
import koma.core.StoreBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

/**
 * A [Machine] running as a Koma Store: the state is the [MachineSnapshot], actions are decided
 * by the machine, and the decisions' commands, timers and events are carried out after each
 * commit. See [MachineStore] (the factory) for the protocol.
 */
@ExperimentalKomaApi
interface MachineStore<C, A : Action, E : Event> : Store<MachineSnapshot<C>, A, E>

/**
 * Creates a Store that runs [machine] from [context].
 *
 * The protocol for one input, inside the store's lock:
 *
 * 1. The input is accepted with the clock's time; a dispatched action becomes
 *    [MachineInput.Dispatch], the startup [MachineInput.Start].
 * 2. [Machine.decide] computes the [Decision] without I/O.
 * 3. A [DecisionOutcome.Handled] decision commits its snapshot; the plugins' `onState` runs
 *    after the commit, and the store's own plugin hands the decision to the scheduler and queues
 *    its events. A [DecisionOutcome.Ignored] input commits nothing. A [DecisionOutcome.Failed]
 *    input commits nothing, registers nothing and fails the handler, so its cause reaches the
 *    exception handler; commands already running are untouched.
 *
 * Outside the lock, in [scope]: the scheduler starts the commands under their lane policies and
 * runs them through [handler], which feeds results, completion and failures back as inputs;
 * timers wait on [clock] and feed [MachineInput.TimerFired]; events are delivered through
 * [Store.event] in decision order. A command sees the committed snapshot in [Store.currentState].
 * If the store closes between the commit and the scheduler's turn, nothing starts.
 *
 * `recover {}` handlers configured in [builder] may not change the snapshot: the machine is the
 * only writer. A `StateSaver` that restores an already started snapshot cannot restore its
 * commands (they are not in the snapshot), so the store starts over from the initial
 * configuration with the restored context.
 *
 * @param machine The machine to run
 * @param context The initial context
 * @param handler Executes the commands
 * @param scope Runs commands, timers and event delivery; needs a real dispatcher, not
 * [Dispatchers.Unconfined], or handlers would run under the store's lock
 * @param clock The machine's clock
 * @param coroutineContext The Store's coroutine context, as in Koma's `Store(...)`
 * @param builder Store configuration: plugins, exception handler, state saver, journal
 * @throws IllegalArgumentException if [scope] uses [Dispatchers.Unconfined]
 */
@ExperimentalKomaApi
fun <C, A : Action, CMD, E : Event> MachineStore(
    machine: Machine<C, A, CMD, E>,
    context: C,
    handler: CommandHandler<CMD, A>,
    scope: CoroutineScope,
    clock: MachineClock = MachineClock.monotonic(),
    coroutineContext: CoroutineContext? = null,
    builder: StoreBuilder<MachineSnapshot<C>, MachineInput<A>, E>.() -> Unit = {},
): MachineStore<C, A, E> = MachineStoreImpl(machine, context, handler, scope, clock, coroutineContext, builder)

@OptIn(ExperimentalKomaApi::class, InternalKomaApi::class)
internal class MachineStoreImpl<C, A : Action, CMD, E : Event>(
    private val machine: Machine<C, A, CMD, E>,
    context: C,
    handler: CommandHandler<CMD, A>,
    scope: CoroutineScope,
    private val clock: MachineClock,
    coroutineContext: CoroutineContext?,
    builder: StoreBuilder<MachineSnapshot<C>, MachineInput<A>, E>.() -> Unit,
) : MachineStore<C, A, E> {
    init {
        require(scope.coroutineContext[ContinuationInterceptor] !== Dispatchers.Unconfined) {
            "[Koma] MachineStore needs an execution scope with a real dispatcher: with Dispatchers.Unconfined, command handlers would run under the store's lock"
        }
    }

    private val executionScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    // Events of committed decisions, delivered in decision order by one coroutine.
    private val effects = Channel<E>(Channel.UNLIMITED)
    private val _event = MutableSharedFlow<E>()
    private val eventPump: Job = executionScope.launch {
        for (event in effects) _event.emit(event)
    }

    // The decision whose snapshot the handler is committing; consumed by the plugin after the commit.
    @Volatile
    private var pending: Decision<C, CMD, E>? = null

    private var pluginScope: PluginScope<MachineSnapshot<C>, MachineInput<A>>? = null

    private val scheduler = CommandScheduler<C, A, CMD, E>(executionScope, handler, clock, feed = { inner.dispatch(it) }, report = ::report)

    /** The underlying Koma Store; tests await it. */
    internal val inner: Store<MachineSnapshot<C>, MachineInput<A>, E> =
        Store(machine.initialSnapshot(context), coroutineContext) {
            // Registered before the configuration, so first-match selection never lets a
            // configured enter {} or action {} run in the machine's place; recover {} handlers
            // configured below still apply.
            state<MachineSnapshot<C>> {
                enter {
                    val foreign = state.definition != machine.id || state.version != machine.version
                    // A restored snapshot of another machine or version cannot be decided by this
                    // one, and a restored, already started snapshot has lost its commands: both
                    // start over with the restored context. The foreign one is reported.
                    val base = if (foreign || state.isStarted) machine.initialSnapshot(state.context) else state
                    decide(base, MachineInput.Start(clock.now())) { nextState { it } }
                    if (foreign) {
                        val restored = state
                        launch {
                            throw IllegalStateException(
                                "[Koma] MachineStore restored a snapshot of ${restored.definition} version ${restored.version} into ${machine.id} version ${machine.version}; started over with the restored context",
                            )
                        }
                    }
                }
                action<MachineInput<A>> {
                    decide(state, action) { nextState { it } }
                }
            }
            builder()
            plugin(Executor())
            validateRecovery { previous, recovered ->
                require(previous == recovered) { "[Koma] MachineStore recover {} may not change the snapshot; dispatch an action the machine decides" }
            }
        }

    private inline fun decide(snapshot: MachineSnapshot<C>, input: MachineInput<A>, commit: (MachineSnapshot<C>) -> Unit) {
        val decision = machine.decide(snapshot, input)
        when (val outcome = decision.outcome) {
            DecisionOutcome.Handled -> {
                pending = decision
                commit(decision.snapshot)
            }
            is DecisionOutcome.Ignored -> Unit
            is DecisionOutcome.Failed -> throw outcome.cause
        }
    }

    private fun report(error: Throwable) {
        // Reaches the store's exception handler (and its probes) like any launched failure.
        pluginScope?.launch { throw error }
    }

    private inner class Executor : Plugin<MachineSnapshot<C>, MachineInput<A>, E> {
        override suspend fun onStart(scope: PluginScope<MachineSnapshot<C>, MachineInput<A>>, state: MachineSnapshot<C>) {
            pluginScope = scope
        }

        override suspend fun onState(scope: PluginScope<MachineSnapshot<C>, MachineInput<A>>, prevState: MachineSnapshot<C>, state: MachineSnapshot<C>) {
            val decision = pending ?: return
            check(decision.snapshot === state) { "[Koma] MachineStore committed a snapshot that is not the pending decision's" }
            pending = null
            for (effect in decision.effects) effects.trySend(effect.event)
            scheduler.apply(decision)
        }
    }

    override val state: StateFlow<MachineSnapshot<C>> get() = inner.state

    override val event: Flow<E> get() = _event

    override val currentState: MachineSnapshot<C> get() = inner.currentState

    override fun start() = inner.start()

    override fun dispatch(action: A) = inner.dispatch(MachineInput.Dispatch(action, clock.now()))

    override fun collectState(state: (MachineSnapshot<C>) -> Unit) = inner.collectState(state)

    override fun collectEvent(event: (E) -> Unit) {
        executionScope.launch { _event.collect { event(it) } }
    }

    override fun close() {
        inner.close()
        scheduler.close()
        effects.close()
        executionScope.cancel()
    }
}

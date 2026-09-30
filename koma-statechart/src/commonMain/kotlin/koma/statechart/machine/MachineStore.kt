package koma.statechart.machine

import koma.core.Action
import koma.core.Event
import koma.core.ExperimentalKomaApi
import koma.core.InputId
import koma.core.InternalKomaApi
import koma.core.Plugin
import koma.core.PluginScope
import koma.core.Store
import koma.core.StoreBuilder
import koma.core.currentInputId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
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
interface MachineStore<C, A : Action, E : Event> : Store<MachineSnapshot<C>, A, E> {
    /**
     * Offers [action] and says whether it was accepted, according to the [AdmissionPolicy].
     * [dispatch] is this with the answer dropped.
     */
    fun admit(action: A): Admission
}

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
 * @param admission How many dispatched actions may wait; see [AdmissionPolicy]
 * @param observers See the decisions as they are committed, ignored or refused; for example
 * `session.decisionsOf(store)` to journal them
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
    admission: AdmissionPolicy = AdmissionPolicy.Unbounded,
    observers: List<DecisionObserver<C, A, CMD, E>> = emptyList(),
    builder: StoreBuilder<MachineSnapshot<C>, MachineInput<A>, E>.() -> Unit = {},
): MachineStore<C, A, E> = MachineStoreImpl(machine, context, handler, scope, clock, coroutineContext, admission, observers, builder)

@OptIn(ExperimentalKomaApi::class, InternalKomaApi::class)
internal class MachineStoreImpl<C, A : Action, CMD, E : Event>(
    private val machine: Machine<C, A, CMD, E>,
    context: C,
    handler: CommandHandler<CMD, A>,
    scope: CoroutineScope,
    private val clock: MachineClock,
    coroutineContext: CoroutineContext?,
    private val admission: AdmissionPolicy,
    private val observers: List<DecisionObserver<C, A, CMD, E>>,
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

    // The decision whose snapshot the handler is committing, with the input it was decided for;
    // consumed by the plugin after the commit.
    private class Pending<C, A : Action, CMD, E : Event>(val decision: Decision<C, CMD, E>, val input: InputId?, val machineInput: MachineInput<A>)

    @Volatile
    private var pending: Pending<C, A, CMD, E>? = null

    // Dispatched actions accepted and not yet processed; a MutableStateFlow as a thread-safe counter.
    private val waiting = MutableStateFlow(0)

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
                    val input = currentInputId()
                    val foreign = state.definition != machine.id || state.version != machine.version
                    // A restored snapshot of another machine or version cannot be decided by this
                    // one, and a restored, already started snapshot has lost its commands: both
                    // start over with the restored context. The foreign one is reported.
                    val base = if (foreign || state.isStarted) machine.initialSnapshot(state.context) else state
                    decide(base, MachineInput.Start(clock.now()), input) { nextState { it } }
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
                    if (action is MachineInput.Dispatch) waiting.update { it - 1 }
                    decide(state, action, currentInputId()) { nextState { it } }
                }
            }
            builder()
            plugin(Executor())
            validateRecovery { previous, recovered ->
                require(previous == recovered) { "[Koma] MachineStore recover {} may not change the snapshot; dispatch an action the machine decides" }
            }
        }

    private inline fun decide(snapshot: MachineSnapshot<C>, input: MachineInput<A>, inputId: InputId?, commit: (MachineSnapshot<C>) -> Unit) {
        val decision = machine.decide(snapshot, input)
        when (val outcome = decision.outcome) {
            DecisionOutcome.Handled -> {
                pending = Pending(decision, inputId, input)
                commit(decision.snapshot)
            }
            is DecisionOutcome.Ignored -> observe { it.onIgnored(inputId, input, outcome.reason) }
            is DecisionOutcome.Failed -> throw outcome.cause
        }
    }

    private inline fun observe(call: (DecisionObserver<C, A, CMD, E>) -> Unit) {
        for (observer in observers) {
            try {
                call(observer)
            } catch (e: Exception) {
                report(e)
            }
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
            val committed = pending ?: return
            val decision = committed.decision
            check(decision.snapshot === state) { "[Koma] MachineStore committed a snapshot that is not the pending decision's" }
            pending = null
            for (effect in decision.effects) effects.trySend(effect.event)
            scheduler.apply(decision)
            observe { it.onCommitted(committed.input, committed.machineInput, decision) }
        }
    }

    override val state: StateFlow<MachineSnapshot<C>> get() = inner.state

    override val event: Flow<E> get() = _event

    override val currentState: MachineSnapshot<C> get() = inner.currentState

    override fun start() = inner.start()

    override fun dispatch(action: A) {
        admit(action)
    }

    override fun admit(action: A): Admission {
        val limit = (admission as? AdmissionPolicy.Bounded)?.maxPending
        if (limit != null) {
            while (true) {
                val current = waiting.value
                if (current >= limit) {
                    val rejection = Admission.Rejected(current, limit)
                    observe { it.onRejected(action, rejection) }
                    return rejection
                }
                if (waiting.compareAndSet(current, current + 1)) break
            }
        } else {
            waiting.update { it + 1 }
        }
        inner.dispatch(MachineInput.Dispatch(action, clock.now()))
        return Admission.Accepted
    }

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

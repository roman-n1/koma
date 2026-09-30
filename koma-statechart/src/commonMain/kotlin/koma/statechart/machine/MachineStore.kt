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
import koma.core.StoreProbe
import koma.core.StoreTrace
import koma.core.currentInputId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

/**
 * A [Machine] running as a Koma Store: the state is the [MachineSnapshot], actions are decided
 * by the machine, and the decisions' commands, timers and events are carried out after each
 * commit. See [MachineStore] (the factory) for the protocol.
 */
@ExperimentalKomaApi
interface MachineStore<C, A : Action, CMD, E : Event> : Store<MachineSnapshot<C>, A, E> {
    /**
     * Offers [action] and says whether it was accepted, according to the [AdmissionPolicy].
     * [dispatch] is this with the answer dropped.
     */
    fun admit(action: A): Admission

    /**
     * Offers [action] as an input of the external source [source], under the [AdmissionPolicy]
     * like a dispatch: a source that is refused decides itself whether to retry or drop. The
     * input is a [MachineInput.External], so a recording knows where it came from.
     */
    fun feed(source: SourceId, action: A): Admission

    /**
     * The retained mailbox of the effects whose [EffectPolicy] keeps them; [Store.event] carries
     * the transient ones. See [EffectMailbox].
     */
    val mailbox: EffectMailbox<E>

    /**
     * Delivers the bridge message [message] carrying [action]: it becomes a
     * [MachineInput.BridgeReceived] input, decided like a dispatch and never refused by the
     * [AdmissionPolicy], as refusing it would lose a message another Store already sent.
     * [MachineGroup] calls this for its routes; a replay checks the ids it produced.
     */
    fun deliver(message: MessageId, action: A)

    /**
     * The executor's state as data, taken at a message boundary of its scheduler: the snapshot
     * of the last decision it carried out, its clock, the commands running and waiting in their
     * lanes, and the commands it has finished with whose last input the machine has not decided
     * yet. The store may have committed later decisions by the time this returns; they are the
     * steps after the checkpoint. A command the snapshot no longer holds is booked as ended,
     * whatever its job is still doing, so the lanes are what the executor settles to.
     *
     * Every position of a `koma-timetravel` recording has such a checkpoint; this one is the
     * live executor's, for a branch that starts where the run is, or for a recording that
     * begins here.
     *
     * @throws IllegalStateException if the store is closed
     */
    suspend fun checkpoint(): ExecutorCheckpoint<C, CMD>
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
 * Outside the lock, in [scope]: the scheduler starts the commands under their lane policies
 * (the pure [Lanes]) and runs them through [handler], which feeds results, completion and
 * failures back as inputs; timers wait on [clock] and feed [MachineInput.TimerFired]; events are
 * delivered through [Store.event] in decision order. A command sees the committed snapshot in
 * [Store.currentState]. If the store closes between the commit and the scheduler's turn, nothing
 * starts. [MachineStore.checkpoint] is the scheduler's state as data.
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
 * @param mailbox What the mailbox keeps of the effects and for whom; see [MailboxConfig]
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
    mailbox: MailboxConfig<E> = MailboxConfig(),
    builder: StoreBuilder<MachineSnapshot<C>, MachineInput<A>, E>.() -> Unit = {},
): MachineStore<C, A, CMD, E> = MachineStoreImpl(machine, context, handler, scope, clock, coroutineContext, admission, observers, mailbox, builder)

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
    mailboxConfig: MailboxConfig<E>,
    builder: StoreBuilder<MachineSnapshot<C>, MachineInput<A>, E>.() -> Unit,
) : MachineStore<C, A, CMD, E> {
    init {
        require(scope.coroutineContext[ContinuationInterceptor] !== Dispatchers.Unconfined) {
            "[Koma] MachineStore needs an execution scope with a real dispatcher: with Dispatchers.Unconfined, command handlers would run under the store's lock"
        }
    }

    private val executionScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    // The effects of committed decisions: transient ones to the event flow, the others retained.
    private val mailboxImpl = MailboxImpl(mailboxConfig, executionScope, ::report)

    // The decision whose snapshot the handler is committing, with the input it was decided for;
    // consumed by the plugin after the commit.
    private class Pending<C, A : Action, CMD, E : Event>(val decision: Decision<C, CMD, E>, val input: InputId?, val machineInput: MachineInput<A>)

    @Volatile
    private var pending: Pending<C, A, CMD, E>? = null

    // Dispatched actions accepted and not yet processed; a MutableStateFlow as a thread-safe counter.
    private val waiting = MutableStateFlow(0)

    private var pluginScope: PluginScope<MachineSnapshot<C>, MachineInput<A>>? = null

    private val scheduler = CommandScheduler<C, A, CMD, E>(
        executionScope, machine.initialSnapshot(context), handler, clock, mailboxImpl, feed = ::enqueue, report = ::report,
        onClosed = { queued, running -> observe { it.onClosed(queued, running) } },
    )

    // The controlled queue of a group cut: while frozen, every input (dispatched, delivered, fed
    // by the executor) waits here instead of entering the inner store, in arrival order.
    private val gate = Mutex()
    private var frozen = false
    private val held = ArrayDeque<MachineInput<A>>()

    // Inputs the inner store accepted and has not finished or discarded: zero means idle.
    private val pendingInputs = MutableStateFlow(0)

    /** The underlying Koma Store; tests await it. */
    internal val inner: Store<MachineSnapshot<C>, MachineInput<A>, E> =
        Store(machine.initialSnapshot(context), coroutineContext) {
            probe(
                StoreProbe { trace ->
                    when (trace) {
                        is StoreTrace.InputAccepted<*> -> pendingInputs.update { it + 1 }
                        is StoreTrace.ProcessingFinished, is StoreTrace.InputDiscarded -> pendingInputs.update { it - 1 }
                        else -> Unit
                    }
                },
            )
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
                    if (action is MachineInput.Dispatch || action is MachineInput.External) waiting.update { it - 1 }
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
            is DecisionOutcome.Failed -> {
                observe { it.onFailed(inputId, input, outcome.failure) }
                throw outcome.cause
            }
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
            scheduler.apply(decision, committed.input)
            observe { it.onCommitted(committed.input, committed.machineInput, decision) }
        }
    }

    override val state: StateFlow<MachineSnapshot<C>> get() = inner.state

    override val event: Flow<E> get() = mailboxImpl.transient

    override val mailbox: EffectMailbox<E> get() = mailboxImpl

    override val currentState: MachineSnapshot<C> get() = inner.currentState

    override fun start() = inner.start()

    override fun dispatch(action: A) {
        admit(action)
    }

    override fun admit(action: A): Admission = admitting(action) { MachineInput.Dispatch(action, clock.now()) }

    override fun feed(source: SourceId, action: A): Admission = admitting(action) { MachineInput.External(source, action, clock.now()) }

    private inline fun admitting(action: A, input: () -> MachineInput<A>): Admission {
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
        enqueue(input())
        return Admission.Accepted
    }

    override fun deliver(message: MessageId, action: A) {
        enqueue(MachineInput.BridgeReceived(message, action, clock.now()))
    }

    // For a group's routes, whose actions lost their type on the way.
    internal fun deliverUnchecked(message: MessageId, action: Action) {
        @Suppress("UNCHECKED_CAST")
        deliver(message, action as A)
    }

    override suspend fun checkpoint(): ExecutorCheckpoint<C, CMD> = scheduler.checkpoint()

    private fun enqueue(input: MachineInput<A>) {
        gated {
            if (frozen) {
                held += input
                return
            }
        }
        inner.dispatch(input)
    }

    /**
     * Holds every new input in the controlled queue until [thaw]; the inputs already inside the
     * inner store are still processed. For a group cut.
     */
    internal fun freeze() {
        gated { frozen = true }
    }

    /**
     * Lets the held inputs into the inner store, in arrival order, ahead of any new one, and
     * stops holding.
     */
    internal fun thaw() {
        gated {
            frozen = false
            // Under the gate, so an input arriving now cannot overtake the held ones.
            while (held.isNotEmpty()) inner.dispatch(held.removeFirst())
        }
    }

    /** How many inputs the controlled queue holds now. */
    internal val heldInputs: Int
        get() = gated { held.size }

    /**
     * Waits until the inner store has finished or discarded every input it accepted, at most
     * [timeout]; `false` on timeout. Meaningful while frozen, when no new input can enter.
     */
    internal suspend fun awaitIdle(timeout: Duration): Boolean =
        withTimeoutOrNull(timeout) { pendingInputs.first { it <= 0 } } != null

    private inline fun <T> gated(block: () -> T): T {
        while (!gate.tryLock()) {
            // Spin: the holder appends one input, or drains the held ones.
        }
        try {
            return block()
        } finally {
            gate.unlock()
        }
    }

    override fun collectState(state: (MachineSnapshot<C>) -> Unit) = inner.collectState(state)

    override fun collectEvent(event: (E) -> Unit) {
        executionScope.launch { this@MachineStoreImpl.event.collect { event(it) } }
    }

    override fun close() {
        inner.close()
        scheduler.close()
        mailboxImpl.close()
        executionScope.cancel()
    }
}

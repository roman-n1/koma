package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import actron.core.ExperimentalActronApi
import actron.core.InputId
import actron.core.InputKind
import actron.core.InternalActronApi
import actron.core.ActionHandlerMatch
import actron.core.Plugin
import actron.core.PluginPatch
import actron.core.PluginScope
import actron.core.Store
import actron.statechart.StoreConfiguration
import actron.core.StoreHandlerMetadata
import actron.core.StoreInternalApi
import actron.core.StorePatch
import actron.core.StoreProbe
import actron.core.StoreTrace
import actron.core.StorePendingWork
import actron.core.currentInputId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
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
 * A [Machine] running as a Actron Store: the state is the [MachineSnapshot], actions are decided
 * by the machine, and the decisions' commands, timers and events are carried out after each
 * commit. See [MachineStore] (the factory) for the protocol.
 */
interface MachineStore<C, A : Action, CMD, E : Event> : Store<MachineSnapshot<C>, A, E> {
    /**
     * Offers [action] and says whether it was accepted, according to the [AdmissionPolicy].
     * [dispatch] is this with the answer dropped. A closed store returns [Admission.Closed];
     * an accepted input may still be discarded by a later close before processing.
     */
    fun admit(action: A): Admission

    /**
     * Offers [action] as an input of the external source [source], under the [AdmissionPolicy]
     * like a dispatch: a source that is refused decides itself whether to retry or drop.
     * [Admission.Closed] is terminal for this store, rather than backpressure to retry. The
     * input is a [MachineInput.External], so a recording knows where it came from.
     */
    @ExperimentalActronApi
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
     * Every position of a `actron-timetravel` recording has such a checkpoint; this one is the
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
 * The machine is the only snapshot writer; [builder] exposes runtime settings without arbitrary
 * state handlers or recovery. A `StateSaver` that restores an already started snapshot cannot restore its
 * commands (they are not in the snapshot), so the store starts over from the initial
 * configuration with the restored context.
 *
 * @param machine The machine to run
 * @param context The initial context
 * @param handler Executes the commands
 * @param scope Runs commands, timers and event delivery; needs a real dispatcher, not
 * [Dispatchers.Unconfined], or handlers would run under the store's lock
 * @param clock The machine's clock
 * @param coroutineContext The Store's coroutine context, as in Actron's `Store(...)`
 * @param admission How many dispatched actions may wait; see [AdmissionPolicy]
 * @param mailbox What the mailbox keeps of the effects and for whom; see [MailboxConfig]
 * @param observers See the decisions as they are committed, ignored or refused; for example
 * `session.decisionsOf(store)` to journal them
 * @param builder Store configuration: plugins, exception handler, state saver, journal
 * @throws IllegalArgumentException if [scope] uses [Dispatchers.Unconfined]
 */
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
    builder: StoreConfiguration<MachineSnapshot<C>, MachineInput<A>, E>.() -> Unit = {},
): MachineStore<C, A, CMD, E> = MachineStoreImpl(machine, context, handler, scope, clock, coroutineContext, admission, observers, mailbox, builder)

@OptIn(InternalActronApi::class)
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
    builder: StoreConfiguration<MachineSnapshot<C>, MachineInput<A>, E>.() -> Unit,
) : MachineStore<C, A, CMD, E>, StoreInternalApi<MachineSnapshot<C>, A, E> {
    init {
        require(scope.coroutineContext[ContinuationInterceptor] !== Dispatchers.Unconfined) {
            "[Actron] MachineStore needs an execution scope with a real dispatcher: with Dispatchers.Unconfined, command handlers would run under the store's lock"
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

    // Separate from the cut gate: acceptance traces run synchronously inside inner.dispatch,
    // and processing/discard traces may arrive concurrently from the inner store.
    private val tracking = Mutex()
    private var waiting = 0
    private class Submission<A : Action>(
        val input: MachineInput<A>,
        var counted: Boolean = false,
        val completion: CompletableDeferred<Unit>? = null,
    )
    private val submitted = mutableMapOf<InputId, Submission<A>>()
    private var registering: Submission<A>? = null

    private var pluginScope: PluginScope<MachineSnapshot<C>, MachineInput<A>>? = null

    private val scheduler = CommandScheduler<C, A, CMD, E>(
        executionScope, machine.initialSnapshot(context), handler, clock, mailboxImpl, feed = ::enqueue, report = ::report,
        onClosed = { queued, running -> observe { it.onClosed(queued, running) } },
    )

    // The controlled queue of a group cut: while frozen, every input (dispatched, delivered, fed
    // by the executor) waits here instead of entering the inner store, in arrival order.
    private val gate = Mutex()
    private var frozen = false
    private val held = ArrayDeque<Submission<A>>()
    private val entering = ArrayDeque<Submission<A>>()
    private var handoffs = 0
    private val handoffChanges = MutableStateFlow(0L)
    private var draining = false
    private var handingOff: Submission<A>? = null

    // Plugins given to the store through `patch {}` (actron-test), adapted to the inner store; the
    // executor hands them every effect, since the inner store never sees the effects.
    private val adaptedPlugins = mutableListOf<AdaptedPlugin<C, A, E>>()

    // Told once, after StoreClosed: a group drops only what processing never decided.
    private val closeListeners = mutableListOf<() -> Unit>()
    private var processingEnded = false

    @Volatile
    private var closed = false

    /** Whether [close] was called: nothing enters or is decided any more. */
    internal val isClosed: Boolean get() = closed

    /** Calls [listener] once when the store closes, after nothing can be decided any more; at once when it has closed already. */
    internal fun onClose(listener: () -> Unit) {
        val now = gated {
            if (processingEnded) {
                true
            } else {
                closeListeners += listener
                false
            }
        }
        if (now) listener()
    }

    /** The underlying Actron Store; the module's own tests feed it raw machine inputs. */
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
                                "[Actron] MachineStore restored a snapshot of ${restored.definition} version ${restored.version} into ${machine.id} version ${machine.version}; started over with the restored context",
                            )
                        }
                    }
                }
                action<MachineInput<A>> {
                    decide(state, action, currentInputId()) { nextState { it } }
                }
            }
            probe(StoreProbe { trace ->
                track(trace)
                if (trace === StoreTrace.StoreClosed) {
                    val listeners = gated {
                        processingEnded = true
                        closeListeners.toList().also { closeListeners.clear() }
                    }
                    for (listener in listeners) listener()
                }
            })
            StoreConfiguration(this).builder()
            plugin(Executor())
            validateRecovery { previous, recovered ->
                require(previous == recovered) { "[Actron] MachineStore recover {} may not change the snapshot; dispatch an action the machine decides" }
            }
        }

    // The inner store is Actron's own, so it is also the bridge actron-test speaks.
    @Suppress("UNCHECKED_CAST")
    private val innerApi: StoreInternalApi<MachineSnapshot<C>, MachineInput<A>, E>
        get() = inner as StoreInternalApi<MachineSnapshot<C>, MachineInput<A>, E>

    @Suppress("UNCHECKED_CAST")
    private inline fun decide(snapshot: MachineSnapshot<C>, input: MachineInput<A>, inputId: InputId?, commit: (MachineSnapshot<C>) -> Unit) {
        val explained = if (observers.any { it is DecisionExplanationObserver || it is DecisionTraceObserver<*, *, *, *> }) machine.decideExplained(snapshot, input) else null
        val decision = explained?.decision ?: machine.decide(snapshot, input)
        if (explained != null) observe { observer ->
            (observer as? DecisionExplanationObserver)?.onExplained(inputId, explained.explanation)
            (observer as? DecisionTraceObserver<C, A, CMD, E>)?.onDecided(inputId, input, explained)
        }
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
            check(decision.snapshot === state) { "[Actron] MachineStore committed a snapshot that is not the pending decision's" }
            pending = null
            scheduler.apply(decision, committed.input)
            observe { it.onCommitted(committed.input, committed.machineInput, decision) }
            observe { observer ->
                if (observer is MachineGroup.Member<*, *, *, *>) {
                    @Suppress("UNCHECKED_CAST")
                    (observer as MachineGroup.Member<C, A, CMD, E>).send(committed.input, committed.machineInput, decision)
                }
            }
            if (adaptedPlugins.isNotEmpty()) {
                for (effect in decision.effects) {
                    for (plugin in adaptedPlugins) {
                        try {
                            plugin.deliver(scope, state, effect.event)
                        } catch (e: Exception) {
                            report(e)
                        }
                    }
                }
            }
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

    @ExperimentalActronApi
    override fun feed(source: SourceId, action: A): Admission = admitting(action) { MachineInput.External(source, action, clock.now()) }

    private inline fun admitting(action: A, input: () -> MachineInput<A>): Admission {
        if (closed) return Admission.Closed
        // An application clock may throw or block: it must not hold the cut gate or reserve space.
        return submit(action, Submission(input()))
    }

    private fun submit(action: A, submission: Submission<A>): Admission {
        val result = gated {
            if (closed) return@gated Admission.Closed
            val rejection = locked(tracking) {
                val limit = (admission as? AdmissionPolicy.Bounded)?.maxPending
                if (limit != null && waiting >= limit) {
                    Admission.Rejected(waiting, limit)
                } else {
                    waiting++
                    submission.counted = true
                    null
                }
            }
            if (rejection != null) return@gated rejection
            queueOpen(submission)
            Admission.Accepted
        }
        // Observer callbacks may dispatch or close. They cannot run under either gate.
        if (result is Admission.Rejected) observe { it.onRejected(action, result) }
        if (result == Admission.Accepted) drain()
        return result
    }

    private fun track(trace: StoreTrace<MachineSnapshot<C>, MachineInput<A>, E>) {
        val completions = locked(tracking) {
            when (trace) {
                is StoreTrace.InputAccepted -> {
                    val input = (trace.kind as? InputKind.Dispatch)?.action
                    registering?.takeIf { it.input === input }?.let { submitted[trace.input] = it }
                    emptyList()
                }
                is StoreTrace.ProcessingStarted -> {
                    submitted[trace.input]?.let { releaseReservation(it) }
                    emptyList()
                }
                is StoreTrace.InputDiscarded -> submitted.remove(trace.input)?.let {
                    releaseReservation(it)
                    listOfNotNull(it.completion)
                } ?: emptyList()
                is StoreTrace.ProcessingFinished -> listOfNotNull(submitted.remove(trace.input)?.completion)
                StoreTrace.StoreClosed -> {
                    // This terminal trace follows every inner job, including a blocking observer.
                    // It is safe to release any remaining waiter now, never at close() request time.
                    val remaining = submitted.values.toList()
                    submitted.clear()
                    remaining.forEach { releaseReservation(it) }
                    remaining.mapNotNull { it.completion }
                }
                else -> emptyList()
            }
        }
        completions.forEach { it.complete(Unit) }
    }

    // Called only with tracking held. A discard frees a reservation even if no handler ran.
    private fun releaseReservation(submission: Submission<A>) {
        if (submission.counted) {
            submission.counted = false
            waiting--
        }
    }

    // --- StoreInternalApi: what actron-test's extensions speak ---

    override suspend fun startAndAwait() = innerApi.startAndAwait()

    /**
     * Admits [action] like [dispatch] and waits until the machine decided it: the commit and the
     * observers, not the commands the decision started. A rejected action throws, since it never
     * became an input. During a group cut the input waits in the controlled queue; the call then
     * returns after the thaw, once this input finished. A close discards held or queued inputs
     * and releases their waiters; acceptance does not guarantee processing. An already closed
     * store throws before accepting the action.
     */
    override suspend fun dispatchAndAwait(action: A) {
        // A zero-time idle query never waits, but retains the core's guard against awaiting
        // this same store from its handler, hook, observer or transaction.
        innerApi.awaitIdle(Duration.ZERO)
        check(!closed) { "[Actron] dispatchAndAwait: the store is closed" }
        val completion = CompletableDeferred<Unit>()
        when (val result = submit(action, Submission(MachineInput.Dispatch(action, clock.now()), completion = completion))) {
            Admission.Accepted -> completion.await()
            Admission.Closed -> throw IllegalStateException("[Actron] dispatchAndAwait: the store is closed")
            is Admission.Rejected -> throw IllegalStateException("[Actron] dispatchAndAwait: the action was rejected by the admission policy (pending=${result.pending}, limit=${result.limit})")
        }
    }

    /**
     * Idle for a machine: the inner store has no input queued or being processed and the
     * executor has carried out every decision committed so far, so the results it fed are inputs
     * already counted; iterated until nothing new appeared. Commands still running, timers
     * scheduled and effects pending are data ([checkpoint]), not work to wait for. During a cut
     * the held inputs are outside the inner store, so a frozen store is idle once it drained.
     */
    override suspend fun awaitIdle(timeout: Duration): StorePendingWork {
        // Check the core's same-store await guard before waiting for the handoff queue.
        innerApi.awaitIdle(Duration.ZERO)
        val settled = withTimeoutOrNull(timeout) {
            while (true) {
                // A dispatcher may execute inline, so handing an input off can itself run a
                // handler. A cut includes all offers made before freeze, even those still here.
                while (true) {
                    val observed = handoffChanges.value
                    if (gated { handoffs == 0 }) break
                    handoffChanges.first { it != observed }
                }
                innerApi.awaitIdle(Duration.INFINITE)
                try {
                    // The actor answers after every decision queued before: all of them carried out.
                    scheduler.checkpoint()
                } catch (e: IllegalStateException) {
                    break // closed: nothing more will happen
                }
                if (gated { handoffs == 0 } && innerApi.awaitIdle(Duration.ZERO).isIdle) break
            }
        }
        if (settled != null) return StorePendingWork(0, 0)
        val innerPending = innerApi.awaitIdle(Duration.ZERO)
        return StorePendingWork(innerPending.inputs + gated { handoffs }, innerPending.launches)
    }

    override fun dispatchIf(action: A, isValid: () -> Boolean) {
        throw UnsupportedOperationException("[Actron] dispatchIf is not supported on a MachineStore: an admitted action is booked by the admission policy and cannot be discarded by a predicate; a stale input is the machine's decision")
    }

    /**
     * Applies the settings of [patch] to the inner store and appends its plugins, adapted; the
     * plugins of a MachineStore can be appended but not replaced or cleared (its executor is one
     * of them), and it cannot be probed through a patch (its inputs are machine inputs).
     */
    override fun patch(patch: StorePatch<MachineSnapshot<C>, A, E>): Store<MachineSnapshot<C>, A, E> {
        require(patch.probes.isEmpty()) { "[Actron] A MachineStore cannot be patched with probes: journal it with recordTo() and decisionsOf() instead" }
        val adapted = patch.pluginPatches.map { pluginPatch ->
            when (pluginPatch) {
                is PluginPatch.Append -> pluginPatch.plugins.map { AdaptedPlugin(it, this) }
                is PluginPatch.Replace, is PluginPatch.Clear -> throw IllegalArgumentException("[Actron] A MachineStore's plugins can be appended, not replaced or cleared: its executor is one of them")
            }
        }
        innerApi.patch(
            StorePatch(
                initialState = patch.initialState,
                coroutineContext = patch.coroutineContext,
                stateSaver = patch.stateSaver,
                exceptionHandler = patch.exceptionHandler,
                autoStartPolicy = patch.autoStartPolicy,
                pendingActionPolicy = patch.pendingActionPolicy,
                pluginExecutionPolicy = patch.pluginExecutionPolicy,
                pluginPatches = adapted.map { PluginPatch.Append(it) },
            ),
        )
        for (plugins in adapted) adaptedPlugins += plugins
        return this
    }

    /** The inner store's single handler, matched for a dispatch of [action]; the chart's tools diagnose transitions. */
    override fun matchActionHandlers(state: MachineSnapshot<C>, action: A): List<ActionHandlerMatch> = innerApi.matchActionHandlers(state, MachineInput.Dispatch(action, clock.now()))

    override fun handlerMetadata(): StoreHandlerMetadata = innerApi.handlerMetadata()

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
            if (!closed) queueOpen(Submission(input))
        }
        drain()
    }

    // Called only while holding gate, serializing reservation, cut order and close.
    private fun queueOpen(submission: Submission<A>) {
        if (frozen) held += submission else {
            entering += submission
            handoffs++
        }
    }

    // Exactly one caller hands queued inputs to the core, outside gate. With an immediate
    // dispatcher the core may run an observer here; a reentrant offer only appends to entering.
    private fun drain() {
        val elected = gated {
            if (draining || entering.isEmpty()) false else {
                draining = true
                true
            }
        }
        if (!elected) return
        while (true) {
            val submission = gated {
                if (entering.isEmpty()) {
                    draining = false
                    null
                } else entering.removeFirst().also { handingOff = it }
            } ?: return
            try {
                dispatchSubmission(submission)
            } catch (failure: Throwable) {
                // A fatal probe throwable can abort synchronous dispatch. Close the remaining
                // queue rather than strand reservations and waiters behind a lost drainer.
                close()
                locked(tracking) { submitted.entries.removeAll { it.value === submission } }
                submission.completion?.completeExceptionally(failure)
                gated { draining = false }
                throw failure
            } finally {
                gated {
                    handingOff = null
                    handoffs--
                }
                // StateFlow may resume an immediate collector inline. Notify only after gate
                // is released, just as per-input completions are outside tracking.
                handoffChanges.update { it + 1 }
            }
        }
    }

    private fun dispatchSubmission(submission: Submission<A>) {
        locked(tracking) { registering = submission }
        try {
            inner.dispatch(submission.input)
        } finally {
            locked(tracking) { registering = null }
        }
    }

    /**
     * Holds every new input in the controlled queue until [thaw]; the inputs already inside the
     * inner store are still processed. For a group cut.
     */
    internal fun freeze() {
        gated { if (!closed) frozen = true }
    }

    /**
     * Lets the held inputs into the inner store, in arrival order, ahead of any new one, and
     * stops holding.
     */
    internal fun thaw() {
        gated {
            // Transfer before admitting new arrivals, preserving held-input order. Dispatching
            // happens after unlocking, so even inline observers can safely dispatch or close.
            handoffs += held.size
            while (held.isNotEmpty()) entering += held.removeFirst()
            frozen = false
        }
        drain()
    }

    /** How many inputs the controlled queue holds now. */
    internal val heldInputs: Int
        get() = gated { held.size }

    /** Accepted dispatches/feeds still waiting to start processing. */
    internal val pendingAdmissions: Int get() = locked(tracking) { waiting }

    private inline fun <T> gated(block: () -> T): T = locked(gate, block)

    private inline fun <T> locked(lock: Mutex, block: () -> T): T {
        while (!lock.tryLock()) {
            // Spin: the holder appends one input, or drains the held ones.
        }
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    override fun collectState(state: (MachineSnapshot<C>) -> Unit) = inner.collectState(state)

    override fun collectEvent(event: (E) -> Unit) {
        executionScope.launch { this@MachineStoreImpl.event.collect { event(it) } }
    }

    override fun close() {
        val discarded = gated {
            if (closed) return
            closed = true
            val queued = held.toList() + entering.toList()
            handoffs -= entering.size
            held.clear()
            entering.clear()
            frozen = false
            locked(tracking) {
                waiting = 0
                queued.forEach { it.counted = false }
                handingOff?.counted = false
                submitted.values.forEach { it.counted = false }
            }
            queued
        }
        handoffChanges.update { it + 1 }
        discarded.forEach { it.completion?.complete(Unit) }
        scheduler.close()
        mailboxImpl.close()
        executionScope.cancel()
        // Cancellation may return while a non-suspending observer finishes. The StoreClosed
        // probe notifies listeners only after all inner processing has actually ended.
        inner.close()
    }
}

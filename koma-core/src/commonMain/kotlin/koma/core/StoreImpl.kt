package koma.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.reflect.KClass

@OptIn(InternalKomaApi::class)
internal abstract class StoreImpl<S : State, A : Action, E : Event> : Store<S, A, E>, StoreInternalApi<S, A, E> {
    private val _state: MutableStateFlow<S> by lazy {
        isStateRestored = true
        MutableStateFlow(
            try {
                stateSaver.restore() ?: initialState
            } catch (t: Throwable) {
                handleException(t)
                if (t is Exception) {
                    initialState
                } else {
                    throw t
                }
            },
        )
    }

    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    final override val state: StateFlow<S> by lazy {
        object : StateFlow<S> {
            override val replayCache: List<S> get() = _state.replayCache
            override val value: S get() = _state.value
            // Collects in the caller's coroutine so operators that stop early, such as first() or
            // take(n), end the collection. Startup is requested once the initial value has been
            // read, so the collector still sees the state from before startup processing.
            override suspend fun collect(collector: FlowCollector<S>): Nothing {
                var startupRequested = false
                _state.collect { value ->
                    if (!startupRequested) {
                        startupRequested = true
                        if (autoStartPolicy == AutoStartPolicy.OnDispatchOrStateCollection) {
                            launchStartup()
                        }
                    }
                    collector.emit(value)
                }
            }
        }
    }

    private val _event: MutableSharedFlow<E> = MutableSharedFlow()
    final override val event: Flow<E> = _event

    final override val currentState: S get() = _state.value

    protected abstract var initialState: S

    protected abstract var coroutineContext: CoroutineContext

    protected abstract var stateSaver: StateSaver<S>

    protected abstract var exceptionHandler: ExceptionHandler

    protected abstract var autoStartPolicy: AutoStartPolicy

    protected abstract var pendingActionPolicy: PendingActionPolicy

    protected abstract var pluginExecutionPolicy: PluginExecutionPolicy

    protected abstract val plugins: MutableList<Plugin<S, A, E>>

    protected abstract val onEnter: suspend EnterScope<S, E, S>.() -> Unit

    protected abstract val onAction: suspend ActionScope<S, A, E, S>.() -> Unit

    protected abstract val onExit: suspend ExitScope<S, E, S>.() -> Unit

    protected abstract val onError: suspend RecoverScope<S, E, S, Exception>.() -> Unit

    protected abstract val validateRecoveredState: (S, S) -> Unit

    internal abstract val handlerRegistry: HandlerRegistry<S, A>

    private val coroutineScope by lazy {
        isCoroutineScopeCreated = true
        CoroutineScope(
            coroutineContext + SupervisorJob(coroutineContext[Job]) + CoroutineExceptionHandler { _, exception ->
                handleException(exception)
            },
        )
    }

    private val dispatchScope by lazy {
        CoroutineScope(
            coroutineScope.coroutineContext + SupervisorJob(coroutineScope.coroutineContext[Job]),
        )
    }

    private val pluginScope by lazy {
        object : PluginScope<S, A> {
            override fun dispatch(action: A) {
                this@StoreImpl.dispatch(action)
            }

            override fun launch(dispatcher: CoroutineDispatcher?, block: suspend PluginLaunchScope<S, A>.() -> Unit) {
                coroutineScope.launch(dispatcher ?: EmptyCoroutineContext) {
                    block(
                        object : PluginLaunchScope<S, A> {
                            override val currentState: S get() = this@StoreImpl.currentState

                            override fun dispatch(action: A) {
                                this@StoreImpl.dispatch(action)
                            }
                        },
                    )
                }
            }
        }
    }

    private val mutex = Mutex()

    // Serializes plugin hook rounds. Handlers run under `mutex`, but events emitted from launched
    // coroutines are processed outside it, so without this lock two `onEvent` rounds could run at
    // once and a plugin keeping plain state (a recorder) would race with itself.
    private val pluginMutex = Mutex()

    private val stateRuntimes = mutableMapOf<KClass<out S>, StateRuntime>()

    private var activeDispatchJob: Job? = null

    // The completion signal of the most recent dispatch. Each dispatch waits for its predecessor
    // before it competes for `mutex`, so actions are processed in dispatch order even on a
    // multi-threaded dispatcher, where freshly launched coroutines would otherwise reach the lock
    // in arbitrary order. MutableStateFlow is used as a thread-safe atomic reference (Job equality
    // is identity).
    private val lastDispatchDone = MutableStateFlow<Job?>(null)

    @Volatile
    private var isStateRestored: Boolean = false

    @Volatile
    private var isCoroutineScopeCreated: Boolean = false

    private var isInitialized: Boolean = false

    @Volatile
    private var isStartupRequested: Boolean = false

    // Only unsuccessful plugin registrations retry startup. Successful hooks may have already
    // subscribed or launched work, even when another plugin in the round failed.
    private var arePluginsStarted: Boolean = false
    private val startedPluginIndices = mutableSetOf<Int>()

    private data class StateRuntime(
        val scope: CoroutineScope,
        val actionLaunchJobs: MutableMap<Any, Job> = mutableMapOf(),
    )

    final override fun dispatch(action: A) {
        launchDispatch(action)
    }

    final override fun dispatchIf(action: A, isValid: () -> Boolean) {
        launchDispatch(action, isValid)
    }

    final override fun start() {
        launchStartup()
    }

    final override suspend fun startAndAwait() {
        checkNotInsideThisStore("startAndAwait")
        launchStartup().join()
    }

    final override suspend fun dispatchAndAwait(action: A) {
        checkNotInsideThisStore("dispatchAndAwait")
        launchDispatch(action).join()
    }

    // Handlers, plugin hooks and transactions run in coroutines that carry this marker. Awaiting
    // the Store from one of them would wait for the lock the caller holds: a permanent deadlock
    // that used to be silent.
    private val insideStore = InsideStore(this)

    private suspend fun checkNotInsideThisStore(name: String) {
        check(currentCoroutineContext()[InsideStore]?.store !== this) {
            "[Koma] $name must not be called from a handler, plugin hook or transaction of the same Store: it would wait for itself. Call it from a launch {} or from outside the Store"
        }
    }

    final override fun matchActionHandlers(state: S, action: A): List<ActionHandlerMatch> {
        val registry = handlerRegistry
        return registry.actionPredicates.mapIndexed { index, predicate ->
            val matcher = registry.action[index]
            ActionHandlerMatch(
                index = index,
                stateType = matcher?.stateType,
                actionType = matcher?.inputType,
                matches = predicate(state, action),
            )
        }
    }

    final override fun handlerMetadata(): StoreHandlerMetadata {
        fun List<HandlerMatcher?>.toMetadata() = mapIndexed { index, matcher ->
            HandlerMetadata(index = index, stateType = matcher?.stateType, inputType = matcher?.inputType)
        }
        val registry = handlerRegistry
        return StoreHandlerMetadata(
            enter = registry.enter.toMetadata(),
            action = registry.action.toMetadata(),
            exit = registry.exit.toMetadata(),
            recover = registry.recover.toMetadata(),
        )
    }

    final override fun patch(patch: StorePatch<S, A, E>): Store<S, A, E> {
        check(mutex.tryLock()) { "[Koma] Failed to configure the Store because it is starting or already started" }
        try {
            check(!isStartupRequested) { "[Koma] Store configuration must be applied before startup is requested" }
            if (patch.initialState != null) {
                check(!isStateRestored) { "[Koma] initialState cannot be patched after the state has been read" }
            }
            if (patch.stateSaver != null) {
                check(!isStateRestored) { "[Koma] stateSaver cannot be patched after the state has been read" }
            }
            if (patch.coroutineContext != null) {
                check(!isCoroutineScopeCreated) { "[Koma] coroutineContext cannot be patched after the Store has begun running coroutines" }
            }
            applyConfigurationPatch(patch)
            return this
        } finally {
            mutex.unlock()
        }
    }

    private fun launchStartup(): Job {
        isStartupRequested = true
        return coroutineScope.launch(insideStore) {
            mutex.withLock {
                initializeIfNeeded()
            }
        }
    }

    private fun launchDispatch(action: A, isValid: () -> Boolean = { true }): Job {
        isStartupRequested = true
        val previousDone = CompletableDeferred<Job?>()
        // Completed when this dispatch's work under the lock is over (or the dispatch never ran),
        // not when its coroutine ends: a handler that leaves a child coroutine behind in its own
        // Job must not hold up every later dispatch.
        val done = Job()
        val job = dispatchScope.launch(insideStore) {
            // Keeps dispatch order: a cancelled or failed predecessor completes too, so this never
            // waits forever. Waiting here costs nothing extra, as the lock serializes dispatches anyway.
            previousDone.await()?.join()
            mutex.withLock {
                val dispatchJob = coroutineContext[Job]
                activeDispatchJob = dispatchJob
                try {
                    initializeIfNeeded()
                    if (isValid()) onActionDispatched(currentState, action)
                } finally {
                    if (activeDispatchJob == dispatchJob) {
                        activeDispatchJob = null
                    }
                    done.complete()
                }
            }
        }
        job.invokeOnCompletion { done.complete() }
        previousDone.complete(lastDispatchDone.getAndUpdate { done })
        return job
    }

    final override fun collectState(state: (S) -> Unit) {
        coroutineScope.launch {
            this@StoreImpl.state.collect { state(it) }
        }
    }

    final override fun collectEvent(event: (E) -> Unit) {
        coroutineScope.launch {
            this@StoreImpl.event.collect { event(it) }
        }
    }

    final override fun close() {
        coroutineScope.cancel()
    }

    private fun applyConfigurationPatch(patch: StorePatch<S, A, E>) {
        patch.initialState?.let { initialState = it }
        patch.coroutineContext?.let { coroutineContext = it }
        patch.stateSaver?.let { stateSaver = it }
        patch.exceptionHandler?.let { exceptionHandler = it }
        patch.autoStartPolicy?.let { autoStartPolicy = it }
        patch.pendingActionPolicy?.let { pendingActionPolicy = it }
        patch.pluginExecutionPolicy?.let { pluginExecutionPolicy = it }
        patch.pluginPatches.forEach { pluginPatch ->
            when (pluginPatch) {
                is PluginPatch.Append -> plugins.addAll(pluginPatch.plugins)

                is PluginPatch.Replace -> {
                    plugins.clear()
                    plugins.addAll(pluginPatch.plugins)
                }

                is PluginPatch.Clear -> plugins.clear()
            }
        }
    }

    // Must be called only while holding `mutex`.
    private suspend fun initializeIfNeeded() {
        if (isInitialized) return
        if (!arePluginsStarted) {
            processPlugins(starting = true) { onStart(pluginScope, currentState) }
            arePluginsStarted = true
        }
        // A failed initial `enter {}` (no recover {} handled it) is reported, and the Store counts
        // as started, as after a failed `enter {}` of a later state: otherwise every later dispatch
        // would run the handler again and drop its action while it keeps failing.
        try {
            onStateEntered(currentState)
        } catch (t: InternalError) {
            reportWithoutAborting(t)
        } finally {
            isInitialized = true
        }
    }

    private suspend fun emit(event: E) {
        processEventEmit(currentState, event)
    }

    private suspend fun onActionDispatched(state: S, action: A) {
        try {
            val nextState = processActionDispatch(state, action)
            commitTransition(state, nextState, inErrorHandling = false)
        } catch (t: Throwable) {
            rethrowIfNonRecoverable(t)
            onErrorOccurred(currentState, t as Exception)
        }
    }

    private suspend fun onStateChanged(state: S, nextState: S) {
        try {
            commitTransition(state, nextState, inErrorHandling = false)
        } catch (t: Throwable) {
            rethrowIfNonRecoverable(t)
            onErrorOccurred(currentState, t as Exception)
        }
    }

    /**
     * Applies [nextState] after a handler finished in [state].
     *
     * When the state variant changes, the old state exits first and pending actions are cleared
     * (per [pendingActionPolicy]) before the new state is committed, so actions dispatched in
     * reaction to the new state, for example by plugins or state collectors, are kept.
     */
    private suspend fun commitTransition(state: S, nextState: S, inErrorHandling: Boolean) {
        // Nothing commits after close(): a handler past its last suspension point would otherwise
        // still update the state, call the saver and the plugins after the Store was closed. The
        // current coroutine is checked, not the Store scope, so the cancellation seen here is the
        // handler's own and is never mistaken for a handler failure.
        currentCoroutineContext().ensureActive()
        val variantChanged = state::class != nextState::class
        if (variantChanged) {
            // Refused before anything is exited, so the state entered last keeps its runtime.
            check(enterChainDepth < MAX_ENTER_CHAIN) {
                "[Koma] More than $MAX_ENTER_CHAIN states were entered in one transition; enter {} handlers moving to each other in a loop?"
            }
            processStateExit(state)
            // An exit hook may finish cleanup under NonCancellable after close() cancelled us.
            currentCoroutineContext().ensureActive()
            clearPendingActionsOnStateExitIfNeeded()
        }

        if (state != nextState) {
            processStateChange(state, nextState)
        }

        if (variantChanged) {
            onStateEntered(nextState, inErrorHandling = inErrorHandling)
        }
    }

    private suspend fun onStateEntered(state: S, inErrorHandling: Boolean = false) {
        try {
            enterChainDepth++
            val nextState = processStateEnter(state)
            commitTransition(state, nextState, inErrorHandling = inErrorHandling)
        } catch (t: Throwable) {
            rethrowIfNonRecoverable(t)
            if (inErrorHandling) {
                throw InternalError(t)
            }
            onErrorOccurred(currentState, t as Exception)
        } finally {
            enterChainDepth--
        }
    }

    // Depth of nested `enter {}` transitions in the current commit; a chain longer than
    // [MAX_ENTER_CHAIN] is an enter loop, which would otherwise overflow the stack or, when the
    // handlers suspend, spin forever while holding the lock.
    private var enterChainDepth = 0

    private suspend fun onErrorOccurred(state: S, exception: Exception) {
        try {
            val nextState = processError(state, exception)
            validateRecoveredState(state, nextState)
            commitTransition(state, nextState, inErrorHandling = true)
        } catch (t: Throwable) {
            // A failing recover {}, or a failing enter {} of the state it moved to, must not hide
            // the error that was being handled.
            val failure = if (t is InternalError) t.original else t
            if (failure !== exception && failure !is CancellationException) failure.addSuppressed(exception)
            rethrowIfNonRecoverable(t)
            throw InternalError(t)
        }
    }

    private suspend fun processActionDispatch(state: S, action: A): S {
        processPlugins { onAction(pluginScope, state, action) }
        var newState: S? = null
        onAction.invoke(
            object : ActionScope<S, A, E, S> {
                override val state = state
                override val action = action
                override fun nextState(block: () -> S) {
                    newState = block()
                }

                override fun clearPendingActions() {
                    clearPendingDispatchJobs()
                }

                override suspend fun event(event: E) {
                    emit(event)
                }

                override fun cancelLaunch(lane: LaunchLane) {
                    val stateRuntime = stateRuntimes[state::class] ?: throw InternalError(IllegalStateException("[Koma] State scope is not found"))
                    cancelTrackedActionLaunch(stateRuntime, lane)
                }

                override fun launch(
                    dispatcher: CoroutineDispatcher?,
                    control: LaunchControl,
                    block: suspend ActionLaunchScope<S, A, E, S>.() -> Unit,
                ) {
                    val stateRuntime = stateRuntimes[state::class] ?: throw InternalError(IllegalStateException("[Koma] State scope is not found"))
                    launchActionInStateRuntime(
                        stateRuntime = stateRuntime,
                        action = action,
                        control = control,
                        dispatcher = dispatcher,
                        buildLaunchScope = { buildActionLaunchScope(stateRuntime.scope, action) },
                        block = block,
                    )
                }
            },
        )
        return newState ?: state
    }

    private suspend fun processStateEnter(state: S): S {
        stateRuntimes[state::class]?.scope?.cancel()
        val stateRuntime = StateRuntime(
            scope = CoroutineScope(coroutineScope.coroutineContext + SupervisorJob(coroutineScope.coroutineContext[Job])),
        )
        stateRuntimes[state::class] = stateRuntime
        var newState: S? = null
        onEnter.invoke(
            object : EnterScope<S, E, S> {
                override val state = state
                override fun nextState(block: () -> S) {
                    newState = block()
                }

                override fun clearPendingActions() {
                    clearPendingDispatchJobs()
                }

                override suspend fun event(event: E) {
                    emit(event)
                }

                override fun launch(dispatcher: CoroutineDispatcher?, block: suspend EnterLaunchScope<S, E, S>.() -> Unit) {
                    launchInStateRuntime(
                        stateRuntime = stateRuntime,
                        dispatcher = dispatcher,
                        buildLaunchScope = { buildEnterLaunchScope(stateRuntime.scope) },
                        block = block,
                    )
                }
            },
        )
        return newState ?: state
    }

    private fun <LS> launchInStateRuntime(
        stateRuntime: StateRuntime,
        dispatcher: CoroutineDispatcher?,
        buildLaunchScope: () -> LS,
        block: suspend LS.() -> Unit,
    ): Job {
        return stateRuntime.scope.launch(dispatcher ?: EmptyCoroutineContext) {
            executeLaunchInStateRuntime(
                stateRuntime = stateRuntime,
                dispatcher = dispatcher,
                buildLaunchScope = buildLaunchScope,
                block = block,
            )
        }
    }

    private fun <LS> launchActionInStateRuntime(
        stateRuntime: StateRuntime,
        action: A,
        control: LaunchControl,
        dispatcher: CoroutineDispatcher?,
        buildLaunchScope: () -> LS,
        block: suspend LS.() -> Unit,
    ) {
        when (control) {
            LaunchControl.Untracked -> {
                launchInStateRuntime(
                    stateRuntime = stateRuntime,
                    dispatcher = dispatcher,
                    buildLaunchScope = buildLaunchScope,
                    block = block,
                )
            }

            is LaunchControl.CancelPrevious -> {
                val trackedKey = resolveTrackedActionLaunchKey(action = action, control = control)
                cancelTrackedActionLaunch(stateRuntime, trackedKey)
                val job = launchInStateRuntime(
                    stateRuntime = stateRuntime,
                    dispatcher = dispatcher,
                    buildLaunchScope = buildLaunchScope,
                    block = block,
                )
                trackActionLaunch(stateRuntime, trackedKey, job)
            }

            is LaunchControl.DropIfRunning -> {
                val trackedKey = resolveTrackedActionLaunchKey(action = action, control = control)
                if (stateRuntime.actionLaunchJobs[trackedKey]?.isActive == true) return
                val job = launchInStateRuntime(
                    stateRuntime = stateRuntime,
                    dispatcher = dispatcher,
                    buildLaunchScope = buildLaunchScope,
                    block = block,
                )
                trackActionLaunch(stateRuntime, trackedKey, job)
            }
        }
    }

    private fun resolveTrackedActionLaunchKey(action: A, control: LaunchControl): Any {
        return when (control) {
            LaunchControl.Untracked -> error("Untracked launches do not have a tracked lane")
            is LaunchControl.CancelPrevious -> control.lane ?: action::class
            is LaunchControl.DropIfRunning -> control.lane ?: action::class
        }
    }

    private fun cancelTrackedActionLaunch(stateRuntime: StateRuntime, trackedKey: Any) {
        stateRuntime.actionLaunchJobs.remove(trackedKey)?.cancel()
    }

    private fun trackActionLaunch(stateRuntime: StateRuntime, key: Any, job: Job) {
        stateRuntime.actionLaunchJobs[key] = job
        job.invokeOnCompletion {
            // Completion may run on any thread. Keep every access to the lane map under mutex,
            // and do not remove a newer launch that has already replaced this one.
            coroutineScope.launch {
                mutex.withLock {
                    if (stateRuntime.actionLaunchJobs[key] === job) stateRuntime.actionLaunchJobs.remove(key)
                }
            }
        }
    }

    private suspend fun <LS> executeLaunchInStateRuntime(
        stateRuntime: StateRuntime,
        dispatcher: CoroutineDispatcher?,
        buildLaunchScope: () -> LS,
        block: suspend LS.() -> Unit,
    ) {
        val launchScope = buildLaunchScope()
        try {
            block(launchScope)
        } catch (t: Throwable) {
            rethrowIfNonRecoverable(t)
            coroutineScope.launch((dispatcher ?: EmptyCoroutineContext) + insideStore) {
                mutex.withLock {
                    if (stateRuntime.scope.isActive) {
                        onErrorOccurred(currentState, t as Exception)
                    }
                }
            }
        }
    }

    // A transaction runs in the Store's root scope so that it is atomic once started, but it is
    // skipped if, by the time it gets the lock, its state has exited or the coroutine that
    // requested it was cancelled (for example by [LaunchControl.CancelPrevious] or
    // `cancelLaunch()`); otherwise a cancelled launch could still commit a stale result.
    private fun canRunTransaction(stateScope: CoroutineScope, caller: Job?): Boolean {
        return stateScope.isActive && caller?.isActive != false
    }

    private fun buildEnterLaunchScope(stateScope: CoroutineScope): EnterLaunchScope<S, E, S> {
        return object : EnterLaunchScope<S, E, S> {
            override val isActive: Boolean get() = stateScope.isActive

            override suspend fun event(event: E) {
                emit(event)
            }

            override suspend fun transaction(dispatcher: CoroutineDispatcher?, block: suspend EnterTransactionScope<S, E, S>.() -> Unit) {
                val caller = currentCoroutineContext()[Job]
                val job = coroutineScope.launch((dispatcher ?: EmptyCoroutineContext) + insideStore) {
                    mutex.withLock {
                        if (canRunTransaction(stateScope, caller)) {
                            var newState: S? = null
                            val transactionScope = object : EnterTransactionScope<S, E, S> {
                                override val state: S = currentState

                                override fun nextState(block: () -> S) {
                                    newState = block()
                                }

                                override fun clearPendingActions() {
                                    clearPendingDispatchJobs()
                                }

                                override suspend fun event(event: E) {
                                    emit(event)
                                }
                            }
                            try {
                                block(transactionScope)
                            } catch (t: Throwable) {
                                rethrowIfNonRecoverable(t)
                                onErrorOccurred(currentState, t as Exception)
                                return@withLock
                            }
                            val nextState = newState ?: currentState
                            if (nextState != currentState) {
                                onStateChanged(currentState, nextState)
                            }
                        }
                    }
                }
                job.join()
            }
        }
    }

    private fun buildActionLaunchScope(stateScope: CoroutineScope, launchedAction: A): ActionLaunchScope<S, A, E, S> {
        return object : ActionLaunchScope<S, A, E, S> {
            override val isActive: Boolean get() = stateScope.isActive
            override val action: A = launchedAction

            override suspend fun event(event: E) {
                emit(event)
            }

            override suspend fun transaction(dispatcher: CoroutineDispatcher?, block: suspend ActionTransactionScope<S, A, E, S>.() -> Unit) {
                val caller = currentCoroutineContext()[Job]
                val job = coroutineScope.launch((dispatcher ?: EmptyCoroutineContext) + insideStore) {
                    mutex.withLock {
                        if (canRunTransaction(stateScope, caller)) {
                            var newState: S? = null
                            val transactionScope = object : ActionTransactionScope<S, A, E, S> {
                                override val state: S = currentState
                                override val action: A = launchedAction

                                override fun nextState(block: () -> S) {
                                    newState = block()
                                }

                                override fun clearPendingActions() {
                                    clearPendingDispatchJobs()
                                }

                                override suspend fun event(event: E) {
                                    emit(event)
                                }
                            }
                            try {
                                block(transactionScope)
                            } catch (t: Throwable) {
                                rethrowIfNonRecoverable(t)
                                onErrorOccurred(currentState, t as Exception)
                                return@withLock
                            }
                            val nextState = newState ?: currentState
                            if (nextState != currentState) {
                                onStateChanged(currentState, nextState)
                            }
                        }
                    }
                }
                job.join()
            }
        }
    }

    // If the exit handler throws, the transition is aborted and the Store stays in [state], so its
    // runtime (state-scoped launches and tracked lanes) is kept.
    private suspend fun processStateExit(state: S) {
        onExit.invoke(
            object : ExitScope<S, E, S> {
                override val state = state

                override fun clearPendingActions() {
                    clearPendingDispatchJobs()
                }

                override suspend fun event(event: E) {
                    emit(event)
                }
            },
        )
        stateRuntimes.remove(state::class)?.scope?.cancel()
    }

    // Once the new state is committed it is visible to collectors, so a failing saver or plugin
    // hook is reported instead of aborting the rest of the transition; otherwise the new state's
    // `enter {}` would never run and its runtime would be missing, so every later `launch {}` in
    // that state would fail.
    private suspend fun processStateChange(state: S, nextState: S) {
        _state.update { nextState }
        try {
            stateSaver.save(nextState)
        } catch (t: Throwable) {
            reportWithoutAborting(t)
        }
        try {
            processPlugins { onState(pluginScope, state, nextState) }
        } catch (t: InternalError) {
            reportWithoutAborting(t)
        }
    }

    private suspend fun processError(state: S, throwable: Exception): S {
        var newState: S? = null
        onError.invoke(
            object : RecoverScope<S, E, S, Exception> {
                override val state = state
                override val error = throwable
                override fun nextState(block: () -> S) {
                    newState = block()
                }

                override fun clearPendingActions() {
                    clearPendingDispatchJobs()
                }

                override suspend fun event(event: E) {
                    emit(event)
                }
            },
        )
        return newState ?: state
    }

    // The event has already reached its collectors, so a failing plugin hook is reported instead
    // of aborting the handler that emitted it.
    private suspend fun processEventEmit(state: S, event: E) {
        _event.emit(event)
        // Emitted from a launched coroutine, this round runs outside the handler coroutines that
        // carry the marker; it holds the plugin lock, so awaiting the Store from the hook would
        // deadlock just the same.
        withContext(insideStore) {
            try {
                processPlugins { onEvent(pluginScope, state, event) }
            } catch (t: InternalError) {
                reportWithoutAborting(t)
            }
        }
    }

    private fun clearPendingActionsOnStateExitIfNeeded() {
        if (pendingActionPolicy == PendingActionPolicy.ClearOnStateExit && isInitialized) {
            clearPendingDispatchJobs()
        }
    }

    private fun clearPendingDispatchJobs() {
        val currentJob = activeDispatchJob
        val dispatchScopeJob = dispatchScope.coroutineContext[Job] ?: return
        dispatchScopeJob.children
            .filter { it != currentJob && it.isActive }
            .forEach { it.cancel() }
    }

    private suspend fun processPlugins(starting: Boolean = false, block: suspend Plugin<S, A, E>.() -> Unit) {
        if (plugins.isEmpty()) return
        try {
            pluginMutex.withLock {
                val round = plugins.withIndex().filter { !starting || it.index !in startedPluginIndices }
                // Every plugin sees every round: one plugin's failure must not hide the state or
                // event from the plugins after it (a recorder, a conformance checker). Failures are
                // collected and the first is thrown with the others suppressed.
                val outcomes: List<Throwable?> =
                    // A single plugin needs no scope: its hooks are sequential under either policy.
                    if (pluginExecutionPolicy == PluginExecutionPolicy.InRegistrationOrder || round.size == 1) {
                        round.map { (_, plugin) -> hookFailure { plugin.block() } }
                    } else {
                        coroutineScope {
                            round.map { (_, plugin) -> async { hookFailure { plugin.block() } } }.awaitAll()
                        }
                    }
                if (starting) round.forEachIndexed { i, plugin -> if (outcomes[i] == null) startedPluginIndices += plugin.index }
                val failures = outcomes.filterNotNull()
                // A fatal error (a non-Exception throwable) from any plugin stays fatal.
                (failures.firstOrNull { it !is Exception } ?: failures.firstOrNull())?.let { first ->
                    failures.forEach { if (it !== first) first.addSuppressed(it) }
                    throw first
                }
            }
        } catch (t: Throwable) {
            rethrowIfNonRecoverable(t)
            throw InternalError(t)
        }
    }

    // Runs one plugin hook and returns its failure, if any. The coroutine's own cancellation (the
    // Store closing, a concurrent round being cancelled) is not a failure and propagates.
    private suspend inline fun hookFailure(block: () -> Unit): Throwable? {
        return try {
            block()
            null
        } catch (t: Throwable) {
            if (t is CancellationException && !currentCoroutineContext().isActive) throw t
            t
        }
    }

    /**
     * Reports a recoverable [t] to the exception handler without aborting the current transition.
     * Cancellation and fatal errors are rethrown. If the handler itself throws, as
     * [ExceptionHandler.Rethrow] does, the transition is aborted and the error reaches the
     * coroutine exception handler as before.
     */
    private suspend fun reportWithoutAborting(t: Throwable) {
        if (t is InternalError && t.reported) throw t
        val original = if (t is InternalError) t.original else t
        rethrowIfNonRecoverable(original)
        try {
            exceptionHandler.handle(original)
        } catch (handlerError: Throwable) {
            throw InternalError(handlerError, reported = true)
        }
    }

    private fun handleException(t: Throwable) {
        // The handler already saw this one and threw: it is not asked again, the error escapes.
        if (t is InternalError && t.reported) throw t.original
        val handled = if (t is InternalError) t.original else t
        exceptionHandler.handle(handled)
    }

    /**
     * Rethrows [t] when it must not be handled by `recover {}` or the exception handler: fatal
     * errors (non-[Exception] throwables) and the cancellation of the current coroutine, that is
     * the Store closing or a state exiting. A [CancellationException] thrown while the current
     * coroutine is still active, for example a `withTimeout {}` in a handler that expired or an
     * `await()` on a cancelled `Deferred`, is an ordinary failure of that handler and is handled.
     */
    private suspend fun rethrowIfNonRecoverable(t: Throwable) {
        if (t !is Exception) throw t
        if (t is CancellationException && !currentCoroutineContext().isActive) throw t
    }

    private class InternalError(val original: Throwable, val reported: Boolean = false) : Throwable(original)

    private class InsideStore(val store: Any) : AbstractCoroutineContextElement(InsideStore) {
        companion object Key : CoroutineContext.Key<InsideStore>
    }

    private companion object {
        const val MAX_ENTER_CHAIN = 500
    }
}

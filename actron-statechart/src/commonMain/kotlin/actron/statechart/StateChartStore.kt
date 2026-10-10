@file:OptIn(ExperimentalActronApi::class)

package actron.statechart

import actron.core.Action
import actron.core.EnterLaunchScope
import actron.core.EnterScope
import actron.core.EnterTransactionScope
import actron.core.Event
import actron.core.ExperimentalActronApi
import actron.core.InternalActronApi
import actron.core.ActronStoreDsl
import actron.core.State
import actron.core.Store
import actron.core.StoreBuilder
import actron.core.StoreInternalApi
import actron.core.StoreScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlin.coroutines.CoroutineContext

/**
 * The Actron state of a Store created by [StateChartStore]: where the chart is, the data that goes
 * with it, and which timers are running.
 *
 * It is plain immutable data, so a [actron.core.StateSaver] can persist it and restore it later (see
 * [StateChartStore] for what happens on restore).
 *
 * @property configuration The active nodes and the history of the chart
 * @property context The data of the Store, which effects and hooks update (for example a retry
 * counter that a guard reads)
 * @property timers The running timers; bookkeeping of the Store (see [ChartTimers])
 */
data class ChartState<C>(
    val configuration: StateConfiguration,
    val context: C,
    val timers: ChartTimers = ChartTimers(),
) : State {
    /**
     * Whether the node [id] is active: an active leaf or an ancestor of one.
     */
    fun isActive(id: StateId): Boolean = id in configuration.active

    /**
     * The active leaves of [definition] in this state, in declaration order: one per active
     * region (see [StateChartDefinition.activeLeaves]).
     */
    fun activeLeaves(definition: StateChartDefinition): List<StateId> = definition.activeLeaves(configuration)
}

/**
 * The timers a [StateChartStore] is running, with a token per running timer.
 *
 * A timer is identified by its index in [StateChartDefinition.transitions], because two
 * declarations with equal fields are equal values. Every start of a timer gets a new token, so a
 * firing that belongs to an earlier start (its source was exited, and maybe entered again, before
 * the cancellation took effect) carries a token that no longer matches and is ignored. Correctness
 * does not depend on when a cancelled coroutine stops.
 *
 * @property running The token of each running timer, by the timer's index in
 * [StateChartDefinition.transitions]
 * @property issued The last token issued; the next start gets `issued + 1`
 */
data class ChartTimers(
    val running: Map<Int, Long> = emptyMap(),
    val issued: Long = 0,
)

/**
 * Scope of an [StateChartStoreBuilder.onExit] hook, and the base of the scope of an
 * [StateChartStoreBuilder.onEnter] hook.
 */
@ActronStoreDsl
interface ChartHookScope<C, E : Event> {
    /** Underlying handler scope for extensions such as `store.message(...)`. */
    val store: StoreScope

    /**
     * The node being exited or entered.
     */
    val node: StateId

    /**
     * What caused the step: the dispatched action, [TimerFired] for a timer, or `null` when the
     * Store enters its initial configuration on start.
     */
    val action: Action?

    /**
     * The context as updated so far in this step. Assign it to update the context; the step
     * commits the final value once, together with the new configuration.
     */
    var context: C

    /**
     * Emits [event] immediately, before the step's new state is committed.
     */
    suspend fun event(event: E)
}

/**
 * Scope of an [StateChartStoreBuilder.onEnter] hook.
 */
@ActronStoreDsl
interface ChartEnterScope<C, A : Action, E : Event> : ChartHookScope<C, E> {
    /**
     * Starts work that lives while [node] stays active: it is cancelled when [node] is exited (or
     * when the Store closes), and it does not start at all when the step that entered [node]
     * fails. It runs in the Store's coroutine context.
     */
    fun launch(block: suspend ChartLaunchScope<C, A, E>.() -> Unit)
}

/**
 * Scope of work that lives while [node] is active: started by [ChartEnterScope.launch] or declared
 * with [StateChartStoreBuilder.activity].
 *
 * An exception other than a cancellation is handled like one from a Actron handler: by the Store's
 * `recover {}` handlers, otherwise by its exception handler.
 */
@ActronStoreDsl
interface ChartLaunchScope<C, A : Action, E : Event> {
    /** Underlying launch scope for StoreScope extensions. */
    val store: StoreScope

    /**
     * The node this work belongs to.
     */
    val node: StateId

    /**
     * Whether [node] is still in the activation this work was started for.
     */
    val isActive: Boolean

    /**
     * Emits [event] immediately if this activation is still active and the Store is open.
     * Calls from a previous activation are ignored, even if the same node has been entered again.
     */
    suspend fun event(event: E)

    /**
     * Updates the context in a Store transaction, if [node] is still active by then.
     *
     * @return Whether the update was applied
     */
    suspend fun updateContext(transform: (C) -> C): Boolean

    /**
     * Enqueues [action] while this activation is active. If the node is exited before the action
     * is processed, the action is discarded, including when the same node has been entered again.
     */
    fun dispatch(action: A)
}

/**
 * Builder of a [StateChartStore]: implementations of the chart's guard and effect labels, hooks
 * for entering and exiting nodes, and the Actron Store configuration.
 */
@ActronStoreDsl
class StateChartStoreBuilder<C, A : Action, E : Event> internal constructor() {
    internal var maxMicrosteps = 100

    /** Bounds automatic transitions per input; lifecycle changes are staged until stability. */
    fun maxMicrosteps(limit: Int) {
        require(limit > 0) { "[Actron] Microstep limit must be positive" }
        maxMicrosteps = limit
    }

    internal val guards = mutableMapOf<String, (ChartState<C>, Action) -> Boolean>()
    internal val effects = mutableMapOf<String, (C, Action) -> C>()
    internal val enterHooks = mutableMapOf<StateId, MutableList<suspend ChartEnterScope<C, A, E>.() -> Unit>>()
    internal val exitHooks = mutableMapOf<StateId, MutableList<suspend ChartHookScope<C, E>.() -> Unit>>()
    internal val activities = mutableMapOf<StateId, MutableList<suspend ChartLaunchScope<C, A, E>.() -> Unit>>()
    internal val storeBlocks = mutableListOf<ChartStoreConfiguration<C, A, E>.() -> Unit>()

    /**
     * Implements the guard [label]. It receives the state before the step and the action, or
     * [TimerFired] for a timer.
     *
     * @throws IllegalArgumentException if [label] already has an implementation
     */
    fun guard(label: String, guard: (state: ChartState<C>, action: Action) -> Boolean) {
        require(guards.put(label, guard) == null) { "[Actron] Guard '$label' is implemented twice" }
    }

    /**
     * Implements the effect [label]: a pure update of the context, run when a transition with this
     * effect is taken, after the exit hooks and before the enter hooks of the step. It receives
     * the action, or [TimerFired] for a timer.
     *
     * @throws IllegalArgumentException if [label] already has an implementation
     */
    fun effect(label: String, effect: (context: C, action: Action) -> C) {
        require(effects.put(label, effect) == null) { "[Actron] Effect '$label' is implemented twice" }
    }

    fun guard(key: GuardKey, guard: (ChartState<C>, Action) -> Boolean) = guard(key.name, guard)
    fun effect(key: EffectKey, effect: (C, Action) -> C) = effect(key.name, effect)

    /**
     * Adds a hook run whenever [id] is entered, outermost nodes first; hooks of one node run in
     * the order added. It can update the context, emit events and [launch][ChartEnterScope.launch]
     * work that lives while [id] is active. It is not run again for a configuration restored by a
     * [actron.core.StateSaver]; use [activity] for work that must run then too.
     */
    fun onEnter(id: StateId, hook: suspend ChartEnterScope<C, A, E>.() -> Unit) {
        enterHooks.getOrPut(id) { mutableListOf() } += hook
    }

    /**
     * Adds a hook run whenever [id] is exited, innermost nodes first; hooks of one node run in the
     * order added. It can update the context and emit events. Work launched for [id] is cancelled
     * right after the step that exits it.
     */
    fun onExit(id: StateId, hook: suspend ChartHookScope<C, E>.() -> Unit) {
        exitHooks.getOrPut(id) { mutableListOf() } += hook
    }

    /**
     * Adds work that runs while [id] is active: it starts after each step that enters [id] (after
     * the step's hooks), when the Store starts in a configuration where [id] is active, restored
     * or not, and it is cancelled when [id] is exited.
     */
    fun activity(id: StateId, block: suspend ChartLaunchScope<C, A, E>.() -> Unit) {
        activities.getOrPut(id) { mutableListOf() } += block
    }

    /**
     * Configures runtime settings and context-only recovery. The chart owns the initial
     * configuration and handlers; settings cannot register competing transitions or timers.
     * Blocks run in declaration order. See [ChartStoreConfiguration.recover].
     */
    fun store(block: ChartStoreConfiguration<C, A, E>.() -> Unit) {
        storeBlocks += block
    }
}

/**
 * Creates a Actron Store that runs [definition]: a plain `Store<ChartState<C>, A, E>`, so Compose,
 * `actron-test`, plugins and state savers work as with any Store.
 *
 * The Store starts in the chart's initial configuration with [context]. Each dispatched action is
 * one [StateChartRuntime.step]; when a transition is taken, in one Actron handler and one commit:
 * the exit hooks of the exited nodes (innermost first), the effects of the transitions (in
 * selection order), the enter hooks of the entered nodes (outermost first), then work of the
 * exited nodes is cancelled, activities of the entered nodes and their timers are started. An
 * action no transition takes leaves the state unchanged and runs nothing.
 *
 * Timers ([Trigger.After]) run as coroutines in the Store: a timer starts when its source is
 * entered and fires [StateChartRuntime.fire] in a Store transaction after its delay, unless its
 * source has been exited. A firing whose guard is false changes nothing but stops the timer.
 *
 * When a hook or effect of a step throws, the step is abandoned: the chart stays in its previous
 * configuration and the error reaches the Store's `recover {}` handlers. A timer whose firing
 * failed that way is spent, so it stops; its source restarts it only when it is entered again.
 * When an enter hook fails on a fresh start, the Store still is in the initial configuration, so
 * its activities and timers start; the context changes and launches of that start's hooks are
 * dropped, and the error reaches `recover {}` once the state is committed (a dispatch queued
 * meanwhile may run first).
 *
 * On start, with the declared initial state: the enter hooks of the initial configuration run
 * (with a `null` action), then activities and timers start. With a state restored by a
 * [actron.core.StateSaver], the enter hooks do not run again (the context already reflects them),
 * but activities start and the running timers restart with their full delay. A restored
 * configuration that [definition] cannot produce, such as one naming a state that no longer
 * exists or missing a region, is replaced by the initial configuration as on a fresh start; the
 * restored context is kept.
 *
 * @param definition The chart; its hierarchy must be well formed (see [StateChartRuntime])
 * @param context The initial context
 * @param coroutineContext The Store's coroutine context, as in Actron's `Store(...)`
 * @param builder Guards, effects, hooks and Store configuration
 * @throws IllegalArgumentException if a guard or effect label used by [definition] has no
 * implementation, if a hook or activity is added for an undeclared node or a [HistoryState], if
 * a guard or effect label is implemented twice, if the hierarchy of [definition] is malformed or
 * refers to undeclared states, or if timers without a positive delay restart each other in a loop
 */
fun <C, A : Action, E : Event> StateChartStore(
    definition: StateChartDefinition,
    context: C,
    coroutineContext: CoroutineContext? = null,
    builder: StateChartStoreBuilder<C, A, E>.() -> Unit = {},
): Store<ChartState<C>, A, E> = ChartStoreHost(definition, context, StateChartStoreBuilder<C, A, E>().apply(builder)).build(coroutineContext)

/**
 * Everything one [StateChartStore] keeps besides its Actron state. Its mutable parts are touched only
 * inside Actron handlers and transactions, which the Store runs one at a time, except [tasks], which
 * is a channel.
 */
@OptIn(InternalActronApi::class)
internal class ChartStoreHost<C, A : Action, E : Event>(
    private val definition: StateChartDefinition,
    context: C,
    private val config: StateChartStoreBuilder<C, A, E>,
    /** Whether a timer coroutine is cancelled with its source; tests turn it off to check the tokens alone. */
    private val cancelTimers: Boolean = true,
) {
    private val runtime = StateChartRuntime<ChartState<C>>(definition, { definition.activeLeaves(it.configuration).first() }, config.guards)

    init {
        val missing = definition.transitions.mapNotNull { it.effect }.distinct().filter { it !in config.effects }
        require(missing.isEmpty()) { "[Actron] Missing effect implementations: ${missing.joinToString()}" }
        val declared = definition.states.filter { it !is HistoryState }.map { it.id }.toSet()
        val undeclared = (config.enterHooks.keys + config.exitHooks.keys + config.activities.keys).filter { it !in declared }
        require(undeclared.isEmpty()) { "[Actron] Hooks for undeclared states: ${undeclared.joinToString()}" }
        // The runtime steps any chart; a Store refuses charts whose steps would leave the declared
        // states, where a typo in a target would silently exit the whole configuration.
        val undeclaredStates = definition.endpointIssues()
        require(undeclaredStates.isEmpty()) { "[Actron] Chart refers to undeclared or duplicate states: ${undeclaredStates.joinToString()}" }
        val loops = definition.instantTimerCycles()
        require(loops.isEmpty()) {
            "[Actron] Timers without a positive delay restart each other forever: " +
                loops.joinToString { cycle -> cycle.joinToString(" -> ") { "${it.source.value} --after ${it.after}--> ${it.target.value}" } }
        }
    }

    private val timersBySource: Map<StateId, List<Int>> = definition.transitions.withIndex()
        .filter { it.value.isTimer }
        .groupBy({ it.value.source }, { it.index })

    private val declaredInitial = ChartState(
        configuration = runtime.initialConfiguration(),
        context = context,
    ).let { it.copy(timers = issue(it.timers, timersOf(definition.inEntryOrder(it.configuration.active)))) }

    private class Task(val activation: Job, val block: suspend () -> Unit)

    private val tasks = Channel<Task>(Channel.UNLIMITED)
    private val activations = mutableMapOf<StateId, Job>()
    private lateinit var transactor: EnterLaunchScope<ChartState<C>, E, ChartState<C>>
    private lateinit var workScope: CoroutineScope
    private lateinit var store: Store<ChartState<C>, A, E>

    /** Timer firings ignored because their token was stale; read by tests. */
    internal var staleFirings: Int = 0
        private set

    fun build(coroutineContext: CoroutineContext?): Store<ChartState<C>, A, E> {
        store = Store(declaredInitial, coroutineContext) {
            // The action type is erased; every action this Store receives is an A.
            @Suppress("UNCHECKED_CAST")
            (this as StoreBuilder<ChartState<C>, Action, E>).state<ChartState<C>> {
                enter { start() }
                action<Action> {
                    val result = runtime.step(state.configuration, state, action)
                    if (result is StepResult.Transitioned) {
                        val next = takeStep(state, result, action, this) { event(it) }
                        nextState { next }
                    }
                }
            }
            @Suppress("UNCHECKED_CAST")
            val settings = this as StoreBuilder<ChartState<C>, A, E>
            config.storeBlocks.forEach { block -> block(ChartStoreConfiguration(settings)) }
            validateRecovery { previous, recovered ->
                require(previous.configuration == recovered.configuration && previous.timers == recovered.timers) {
                    "[Actron] StateChartStore recover {} may update context only; dispatch a declared chart action to change configuration or timers"
                }
            }
            initialState(declaredInitial)
        }
        return store
    }

    private fun timersOf(states: List<StateId>): List<Int> = states.flatMap { timersBySource[it].orEmpty() }

    private fun issue(timers: ChartTimers, indices: List<Int>): ChartTimers {
        var issued = timers.issued
        val running = timers.running.toMutableMap()
        for (index in indices) running[index] = ++issued
        return ChartTimers(running, issued)
    }

    private suspend fun EnterScope<ChartState<C>, E, ChartState<C>>.start() {
        subscribe { work(this) }
        val restored = state
        // A restored configuration whose active nodes this chart cannot produce (for example one
        // saved by an older version of the chart) starts over from the initial configuration,
        // keeping the context. History records the chart cannot restore are dropped on their own.
        val consistent = if (restored === declaredInitial) restored.configuration else definition.consistentPart(restored.configuration)
        val state = when (consistent) {
            null -> declaredInitial.copy(context = restored.context)
            restored.configuration -> restored
            else -> restored.copy(configuration = consistent)
        }
        val fresh = state === declaredInitial || consistent == null
        val active = definition.inEntryOrder(state.configuration.active)
        var context = state.context
        val entered = linkedMapOf<StateId, Job>()
        val launches = mutableListOf<Task>()
        var failure: Exception? = null
        // Every active node gets its activation before any hook runs: a failing hook of an outer
        // node must not leave the nodes after it without one.
        for (id in active) entered[id] = Job()
        try {
            if (fresh) for (id in active) context = enter(id, entered.getValue(id), context, null, launches, this) { event(it) }
        } catch (e: Exception) {
            // The Store's own cancellation ends the start; any other exception, including an
            // expired withTimeout, is a failed hook (the core rule).
            if (e is CancellationException && !currentCoroutineContext().isActive) {
                entered.values.forEach { it.cancel() }
                throw e
            }
            // Unlike a failed action step, a failed start has no previous configuration to stay
            // in: the Store is in this one, so its activations, activities and timers must exist.
            // Only the failed hooks' context changes and launches are dropped; the error reaches
            // the recover {} handlers once the state is committed.
            failure = e
            context = state.context
            launches.clear()
        }
        activations.putAll(entered)
        val expected = timersOf(active)
        val timers = if (state.timers.running.keys == expected.toSet()) state.timers else issue(ChartTimers(issued = state.timers.issued), expected)
        var next = state.copy(context = context, timers = timers)
        if (failure == null) {
            try {
                val automatic = runtime.automaticStep(next.configuration, next)
                if (automatic is StepResult.Transitioned) {
                    next = takeStep(next, automatic, AutomaticTransition(automatic.transitions.first().trigger == Trigger.Completion), this) { event(it) }
                }
            } catch (error: Exception) {
                failure = error
                next = state.copy(timers = timers)
                launches.clear()
            }
        }
        launches.filter { it.activation in activations.values }.forEach(tasks::trySend)
        active.filter { activations[it] === entered[it] }.forEach(::startActivities)
        expected.filter { next.timers.running[it] == timers.running[it] }.forEach { schedule(it, timers.running.getValue(it)) }
        if (next != restored) nextState { next }
        failure?.let { error -> launch { transaction { throw error } } }
    }

    /** Runs the hooks and effects of [result] and returns the state to commit. */
    private suspend fun takeStep(state: ChartState<C>, result: StepResult.Transitioned, action: Action, storeScope: StoreScope, emit: suspend (E) -> Unit): ChartState<C> {
        var current = state
        var step: StepResult.Transitioned = result
        var stepAction = action
        val working = activations.toMutableMap()
        val created = mutableListOf<Job>()
        val cancelled = mutableListOf<Job>()
        val launches = mutableListOf<Task>()
        val automatic = if (action is AutomaticTransition) result.transitions.toMutableList() else mutableListOf()
        var microsteps = if (action is AutomaticTransition) 1 else 0
        try {
            while (true) {
                var context = current.context
                for (id in step.exited) {
                    for (hook in config.exitHooks[id].orEmpty()) {
                        val scope = ExitScope(id, stepAction, context, storeScope, emit)
                        scope.hook()
                        context = scope.context
                    }
                    working.remove(id)?.let(cancelled::add)
                }
                for (transition in step.transitions) {
                    transition.effect?.let { context = config.effects.getValue(it)(context, stepAction) }
                }
                for (id in step.entered) {
                    val activation = Job()
                    created += activation
                    working[id] = activation
                    context = enter(id, activation, context, stepAction, launches, storeScope, emit)
                }
                val timers = issue(current.timers.copy(running = current.timers.running - timersOf(step.exited).toSet()), timersOf(step.entered))
                current = ChartState(step.configuration, context, timers)
                val next = runtime.automaticStep(current.configuration, current)
                if (next !is StepResult.Transitioned) break
                if (microsteps >= config.maxMicrosteps) throw MicrostepLimitException(config.maxMicrosteps, automatic + next.transitions)
                automatic += next.transitions
                microsteps++
                step = next
                stepAction = AutomaticTransition(next.transitions.first().trigger == Trigger.Completion)
            }
        } catch (error: Throwable) {
            created.forEach { it.cancel() }
            throw error
        }
        val entered = working.filter { (id, job) -> activations[id] !== job }.keys
        cancelled.forEach { it.cancel() }
        activations.clear()
        activations.putAll(working)
        launches.filter { it.activation.isActive }.forEach(tasks::trySend)
        entered.forEach(::startActivities)
        current.timers.running.filter { (index, token) -> state.timers.running[index] != token }
            .forEach { (index, token) -> schedule(index, token) }
        return current
    }

    private suspend fun enter(
        id: StateId,
        activation: Job,
        context: C,
        action: Action?,
        launches: MutableList<Task>,
        storeScope: StoreScope,
        emit: suspend (E) -> Unit,
    ): C {
        var current = context
        for (hook in config.enterHooks[id].orEmpty()) {
            val scope = EnterHookScope(id, action, current, storeScope, emit, activation, launches)
            try {
                scope.hook()
            } finally {
                scope.open = false
            }
            current = scope.context
        }
        return current
    }

    private fun startActivities(id: StateId) {
        val activation = activations.getValue(id)
        for (block in config.activities[id].orEmpty()) launchFor(id, activation, block)
    }

    private fun launchFor(id: StateId, activation: Job, block: suspend ChartLaunchScope<C, A, E>.() -> Unit) {
        tasks.trySend(taskFor(id, activation, block))
    }

    private fun taskFor(id: StateId, activation: Job, block: suspend ChartLaunchScope<C, A, E>.() -> Unit): Task =
        Task(activation) { LaunchScope(id, activation).block() }

    private fun schedule(index: Int, token: Long) {
        val timer = definition.transitions[index]
        val activation = if (cancelTimers) activations.getValue(timer.source) else Job()
        tasks.trySend(
            Task(activation) {
                delay(timer.after!!)
                transactor.transaction {
                    try {
                        fire(this, index, token)
                    } catch (e: Exception) {
                        if (e is CancellationException && !currentCoroutineContext().isActive) throw e
                        // The step failed, so the chart stays where it is and the timer is spent:
                        // drop it, or `timers.running` would list a timer that never fires again.
                        // The error reaches the Store's recover {} handlers once this has committed.
                        nextState { state.copy(timers = state.timers.copy(running = state.timers.running - index)) }
                        report(e)
                    }
                }
            },
        )
    }

    private suspend fun fire(scope: EnterTransactionScope<ChartState<C>, E, ChartState<C>>, index: Int, token: Long) {
        val state = scope.state
        if (state.timers.running[index] != token) {
            staleFirings++
            return
        }
        val timer = definition.transitions[index]
        val next = when (val result = runtime.fire(state.configuration, state, timer)) {
            is StepResult.Transitioned -> takeStep(state.copy(timers = state.timers.copy(running = state.timers.running - index)), result, TimerFired(timer), scope) { scope.event(it) }
            StepResult.Ignored -> state.copy(timers = state.timers.copy(running = state.timers.running - index))
        }
        scope.nextState { next }
    }

    /** The Store-lifetime coroutine that runs [tasks]; subscribed once from the chart's `enter {}`, so a test's `awaitIdle` does not wait for it: activities and timers are the chart's data. */
    private suspend fun work(scope: EnterLaunchScope<ChartState<C>, E, ChartState<C>>) {
        transactor = scope
        supervisorScope {
            workScope = this
            for (task in tasks) {
                if (!task.activation.isActive) continue
                launch { runTask(task) }
            }
        }
    }

    private suspend fun runTask(task: Task) {
        val job = currentCoroutineContext().job
        val handle = task.activation.invokeOnCompletion { job.cancel() }
        try {
            task.block()
        } catch (e: Exception) {
            // The task's own cancellation (its node exited, the Store closed) ends it; any other
            // exception, including an expired withTimeout, is a failure of the task.
            if (e is CancellationException && !currentCoroutineContext().isActive) throw e
            report(e)
        } finally {
            handle.dispose()
        }
    }

    /**
     * Reports a failure of a task or timer step to the Store's `recover {}` handlers by rethrowing
     * it inside a transaction. The transaction is requested from the chart's own work scope, not
     * from the failed task: a transaction whose caller is cancelled is skipped, and the task's node
     * may exit (cancelling the task) before the transaction gets the lock. The transaction still
     * runs after the step that exits the node, so the handlers see the committed state.
     */
    private fun report(failure: Exception) {
        workScope.launch { transactor.transaction { throw failure } }
    }

    private inner class ExitScope(
        override val node: StateId,
        override val action: Action?,
        override var context: C,
        override val store: StoreScope,
        private val emit: suspend (E) -> Unit,
    ) : ChartHookScope<C, E> {
        override suspend fun event(event: E) = emit(event)
    }

    private inner class EnterHookScope(
        override val node: StateId,
        override val action: Action?,
        override var context: C,
        override val store: StoreScope,
        private val emit: suspend (E) -> Unit,
        private val activation: Job,
        private val launches: MutableList<Task>,
    ) : ChartEnterScope<C, A, E> {
        var open = true

        override suspend fun event(event: E) = emit(event)

        override fun launch(block: suspend ChartLaunchScope<C, A, E>.() -> Unit) {
            check(open) { "[Actron] launch {} was called after the onEnter hook of $node returned; call it from the hook itself" }
            launches += taskFor(node, activation, block)
        }
    }

    private inner class LaunchScope(override val node: StateId, private val activation: Job) : ChartLaunchScope<C, A, E> {
        override val store: StoreScope get() = transactor

        // The activation is a plain Job that close() does not cancel; the transactor's scope is.
        override val isActive: Boolean get() = activation.isActive && transactor.isActive

        override suspend fun event(event: E) {
            if (isActive) transactor.event(event)
        }

        override suspend fun updateContext(transform: (C) -> C): Boolean {
            var applied = false
            val activation = activation
            transactor.transaction {
                if (activation.isActive) {
                    // Computed before `applied` is set: a throwing transform reaches recover {}
                    // and the caller learns that nothing was applied.
                    val updated = transform(state.context)
                    applied = true
                    nextState { state.copy(context = updated) }
                }
            }
            return applied
        }

        override fun dispatch(action: A) {
            @Suppress("UNCHECKED_CAST")
            (this@ChartStoreHost.store as StoreInternalApi<ChartState<C>, A, E>).dispatchIf(action) { activation.isActive }
        }
    }
}

package koma.statechart

import koma.core.Action
import koma.core.EnterLaunchScope
import koma.core.EnterScope
import koma.core.EnterTransactionScope
import koma.core.Event
import koma.core.ExperimentalKomaApi
import koma.core.KomaStoreDsl
import koma.core.State
import koma.core.Store
import koma.core.StoreBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlin.coroutines.CoroutineContext

/**
 * The Koma state of a Store created by [StateChartStore]: where the chart is, the data that goes
 * with it, and which timers are running.
 *
 * It is plain immutable data, so a [koma.core.StateSaver] can persist it and restore it later (see
 * [StateChartStore] for what happens on restore).
 *
 * @property configuration The active nodes and the history of the chart
 * @property context The data of the Store, which effects and hooks update (for example a retry
 * counter that a guard reads)
 * @property timers The running timers; bookkeeping of the Store (see [ChartTimers])
 */
@ExperimentalKomaApi
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
@ExperimentalKomaApi
data class ChartTimers(
    val running: Map<Int, Long> = emptyMap(),
    val issued: Long = 0,
)

/**
 * Scope of an [StateChartStoreBuilder.onExit] hook, and the base of the scope of an
 * [StateChartStoreBuilder.onEnter] hook.
 */
@ExperimentalKomaApi
@KomaStoreDsl
interface ChartHookScope<C, E : Event> {
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
@ExperimentalKomaApi
@KomaStoreDsl
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
 * An exception other than a cancellation is handled like one from a Koma handler: by the Store's
 * `recover {}` handlers, otherwise by its exception handler.
 */
@ExperimentalKomaApi
@KomaStoreDsl
interface ChartLaunchScope<C, A : Action, E : Event> {
    /**
     * The node this work belongs to.
     */
    val node: StateId

    /**
     * Whether [node] is still in the activation this work was started for.
     */
    val isActive: Boolean

    /**
     * Emits [event] immediately.
     */
    suspend fun event(event: E)

    /**
     * Updates the context in a Store transaction, if [node] is still active by then.
     *
     * @return Whether the update was applied
     */
    suspend fun updateContext(transform: (C) -> C): Boolean

    /**
     * Dispatches [action] to the Store, as [Store.dispatch] does.
     */
    fun dispatch(action: A)
}

/**
 * Builder of a [StateChartStore]: implementations of the chart's guard and effect labels, hooks
 * for entering and exiting nodes, and the Koma Store configuration.
 */
@ExperimentalKomaApi
@KomaStoreDsl
class StateChartStoreBuilder<C, A : Action, E : Event> internal constructor() {
    internal val guards = mutableMapOf<String, (ChartState<C>, Action) -> Boolean>()
    internal val effects = mutableMapOf<String, (C, Action) -> C>()
    internal val enterHooks = mutableMapOf<StateId, MutableList<suspend ChartEnterScope<C, A, E>.() -> Unit>>()
    internal val exitHooks = mutableMapOf<StateId, MutableList<suspend ChartHookScope<C, E>.() -> Unit>>()
    internal val activities = mutableMapOf<StateId, MutableList<suspend ChartLaunchScope<C, A, E>.() -> Unit>>()
    internal val storeBlocks = mutableListOf<StoreBuilder<ChartState<C>, A, E>.() -> Unit>()

    /**
     * Implements the guard [label]. It receives the state before the step and the action, or
     * [TimerFired] for a timer.
     *
     * @throws IllegalArgumentException if [label] already has an implementation
     */
    fun guard(label: String, guard: (state: ChartState<C>, action: Action) -> Boolean) {
        require(guards.put(label, guard) == null) { "[Koma] Guard '$label' is implemented twice" }
    }

    /**
     * Implements the effect [label]: a pure update of the context, run when a transition with this
     * effect is taken, after the exit hooks and before the enter hooks of the step. It receives
     * the action, or [TimerFired] for a timer.
     *
     * @throws IllegalArgumentException if [label] already has an implementation
     */
    fun effect(label: String, effect: (context: C, action: Action) -> C) {
        require(effects.put(label, effect) == null) { "[Koma] Effect '$label' is implemented twice" }
    }

    /**
     * Adds a hook run whenever [id] is entered, outermost nodes first; hooks of one node run in
     * the order added. It can update the context, emit events and [launch][ChartEnterScope.launch]
     * work that lives while [id] is active. It is not run again for a configuration restored by a
     * [koma.core.StateSaver]; use [activity] for work that must run then too.
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
     * Configures the underlying Koma Store: coroutine context, state saver, plugins, exception
     * handler, policies and `recover {}` handlers. Blocks run in the order added, after the chart's
     * own handlers are registered, so `enter {}` and `action {}` handlers registered here never run.
     * The initial state is the chart's: an `initialState` set here is ignored.
     */
    fun store(block: StoreBuilder<ChartState<C>, A, E>.() -> Unit) {
        storeBlocks += block
    }
}

/**
 * Creates a Koma Store that runs [definition]: a plain `Store<ChartState<C>, A, E>`, so Compose,
 * `koma-test`, plugins and state savers work as with any Store.
 *
 * The Store starts in the chart's initial configuration with [context]. Each dispatched action is
 * one [StateChartRuntime.step]; when a transition is taken, in one Koma handler and one commit:
 * the exit hooks of the exited nodes (innermost first), the effects of the transitions (in
 * selection order), the enter hooks of the entered nodes (outermost first), then work of the
 * exited nodes is cancelled, activities of the entered nodes and their timers are started. An
 * action no transition takes leaves the state unchanged and runs nothing.
 *
 * Timers ([Trigger.After]) run as coroutines in the Store: a timer starts when its source is
 * entered and fires [StateChartRuntime.fire] in a Store transaction after its delay, unless its
 * source has been exited. A firing whose guard is false changes nothing but stops the timer.
 *
 * On start, with the declared initial state: the enter hooks of the initial configuration run
 * (with a `null` action), then activities and timers start. With a state restored by a
 * [koma.core.StateSaver], the enter hooks do not run again (the context already reflects them),
 * but activities start and the running timers restart with their full delay.
 *
 * @param definition The chart; its hierarchy must be well formed (see [StateChartRuntime])
 * @param context The initial context
 * @param coroutineContext The Store's coroutine context, as in Koma's `Store(...)`
 * @param builder Guards, effects, hooks and Store configuration
 * @throws IllegalArgumentException if a guard or effect label used by [definition] has no
 * implementation, if a hook or activity is added for an undeclared node or a [HistoryState], if
 * a guard or effect label is implemented twice, or if the hierarchy of [definition] is malformed
 */
@ExperimentalKomaApi
fun <C, A : Action, E : Event> StateChartStore(
    definition: StateChartDefinition,
    context: C,
    coroutineContext: CoroutineContext? = null,
    builder: StateChartStoreBuilder<C, A, E>.() -> Unit = {},
): Store<ChartState<C>, A, E> = ChartStoreHost(definition, context, StateChartStoreBuilder<C, A, E>().apply(builder)).build(coroutineContext)

/**
 * Everything one [StateChartStore] keeps besides its Koma state. Its mutable parts are touched only
 * inside Koma handlers and transactions, which the Store runs one at a time, except [tasks], which
 * is a channel.
 */
@OptIn(ExperimentalKomaApi::class)
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
        require(missing.isEmpty()) { "[Koma] Missing effect implementations: ${missing.joinToString()}" }
        val declared = definition.states.filter { it !is HistoryState }.map { it.id }.toSet()
        val undeclared = (config.enterHooks.keys + config.exitHooks.keys + config.activities.keys).filter { it !in declared }
        require(undeclared.isEmpty()) { "[Koma] Hooks for undeclared states: ${undeclared.joinToString()}" }
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
                        val next = takeStep(state, result, action) { event(it) }
                        nextState { next }
                    }
                }
            }
            config.storeBlocks.forEach { it() }
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
        launch { work(this) }
        val state = state
        val fresh = state === declaredInitial
        val active = definition.inEntryOrder(state.configuration.active)
        var context = state.context
        val entered = linkedMapOf<StateId, Job>()
        try {
            for (id in active) {
                val activation = Job()
                entered[id] = activation
                if (fresh) context = enter(id, activation, context, null) { event(it) }
            }
        } catch (e: Throwable) {
            entered.values.forEach { it.cancel() }
            throw e
        }
        activations.putAll(entered)
        active.forEach(::startActivities)
        val expected = timersOf(active)
        val timers = if (state.timers.running.keys == expected.toSet()) state.timers else issue(ChartTimers(issued = state.timers.issued), expected)
        expected.forEach { schedule(it, timers.running.getValue(it)) }
        val next = state.copy(context = context, timers = timers)
        if (next != state) nextState { next }
    }

    /** Runs the hooks and effects of [result] and returns the state to commit. */
    private suspend fun takeStep(state: ChartState<C>, result: StepResult.Transitioned, action: Action, emit: suspend (E) -> Unit): ChartState<C> {
        var context = state.context
        val entered = linkedMapOf<StateId, Job>()
        try {
            for (id in result.exited) {
                for (hook in config.exitHooks[id].orEmpty()) {
                    val scope = ExitScope(id, action, context, emit)
                    scope.hook()
                    context = scope.context
                }
            }
            for (transition in result.transitions) {
                transition.effect?.let { context = config.effects.getValue(it)(context, action) }
            }
            for (id in result.entered) {
                val activation = Job()
                entered[id] = activation
                context = enter(id, activation, context, action, emit)
            }
        } catch (e: Throwable) {
            entered.values.forEach { it.cancel() }
            throw e
        }
        result.exited.forEach { activations.remove(it)?.cancel() }
        activations.putAll(entered)
        result.entered.forEach(::startActivities)
        val cancelled = timersOf(result.exited)
        val started = timersOf(result.entered)
        val timers = issue(state.timers.copy(running = state.timers.running - cancelled.toSet()), started)
        started.forEach { schedule(it, timers.running.getValue(it)) }
        return ChartState(result.configuration, context, timers)
    }

    private suspend fun enter(id: StateId, activation: Job, context: C, action: Action?, emit: suspend (E) -> Unit): C {
        var current = context
        for (hook in config.enterHooks[id].orEmpty()) {
            val scope = EnterHookScope(id, action, current, emit, activation)
            scope.hook()
            current = scope.context
        }
        return current
    }

    private fun startActivities(id: StateId) {
        val activation = activations.getValue(id)
        for (block in config.activities[id].orEmpty()) launchFor(id, activation, block)
    }

    private fun launchFor(id: StateId, activation: Job, block: suspend ChartLaunchScope<C, A, E>.() -> Unit) {
        tasks.trySend(Task(activation) { LaunchScope(id, activation).block() })
    }

    private fun schedule(index: Int, token: Long) {
        val timer = definition.transitions[index]
        val activation = if (cancelTimers) activations.getValue(timer.source) else Job()
        tasks.trySend(
            Task(activation) {
                delay(timer.after!!)
                transactor.transaction { fire(this, index, token) }
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
            is StepResult.Transitioned -> takeStep(state, result, TimerFired(timer)) { scope.event(it) }
            StepResult.Ignored -> state.copy(timers = state.timers.copy(running = state.timers.running - index))
        }
        scope.nextState { next }
    }

    /** The Store-lifetime coroutine that runs [tasks]; launched once from the chart's `enter {}`. */
    private suspend fun work(scope: EnterLaunchScope<ChartState<C>, E, ChartState<C>>) {
        transactor = scope
        supervisorScope {
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Rethrown inside a transaction, the error reaches the Store's recover {} handlers.
            transactor.transaction { throw e }
        } finally {
            handle.dispose()
        }
    }

    private inner class ExitScope(
        override val node: StateId,
        override val action: Action?,
        override var context: C,
        private val emit: suspend (E) -> Unit,
    ) : ChartHookScope<C, E> {
        override suspend fun event(event: E) = emit(event)
    }

    private inner class EnterHookScope(
        override val node: StateId,
        override val action: Action?,
        override var context: C,
        private val emit: suspend (E) -> Unit,
        private val activation: Job,
    ) : ChartEnterScope<C, A, E> {
        override suspend fun event(event: E) = emit(event)

        override fun launch(block: suspend ChartLaunchScope<C, A, E>.() -> Unit) = launchFor(node, activation, block)
    }

    private inner class LaunchScope(override val node: StateId, private val activation: Job) : ChartLaunchScope<C, A, E> {
        override val isActive: Boolean get() = activation.isActive

        override suspend fun event(event: E) = transactor.event(event)

        override suspend fun updateContext(transform: (C) -> C): Boolean {
            var applied = false
            val activation = activation
            transactor.transaction {
                if (activation.isActive) {
                    applied = true
                    nextState { state.copy(context = transform(state.context)) }
                }
            }
            return applied
        }

        override fun dispatch(action: A) = store.dispatch(action)
    }
}

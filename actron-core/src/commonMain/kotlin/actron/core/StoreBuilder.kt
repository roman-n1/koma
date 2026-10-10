package actron.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass

/**
 * Builder used by the Store DSL to configure runtime behavior and register state-specific
 * handlers.
 *
 * Most users interact with this type through the top-level `Store(...)` factory overloads.
 */
@Suppress("unused")
@ActronStoreDsl
class StoreBuilder<S : State, A : Action, E : Event> internal constructor() {
    private var storeInitialState: S? = null
    private var storeCoroutineContext: CoroutineContext = Dispatchers.Default
    private var storeStateSaver: StateSaver<S> = StateSaver.Noop()
    private var storeExceptionHandler: ExceptionHandler = ExceptionHandler.Rethrow
    private var storeAutoStartPolicy: AutoStartPolicy = AutoStartPolicy.OnDispatchOrStateCollection
    private var storePendingActionPolicy: PendingActionPolicy = PendingActionPolicy.ClearOnStateExit
    private var storePluginExecutionPolicy: PluginExecutionPolicy = PluginExecutionPolicy.Concurrent
    private val storePlugins: MutableList<Plugin<S, A, E>> = mutableListOf()

    @OptIn(InternalActronApi::class)
    private val storeProbes: MutableList<StoreProbe<S, A, E>> = mutableListOf()
    private val recoveryValidators = mutableListOf<(S, S) -> Unit>()

    /**
     * Lets an adapter reject recovery updates that would bypass its runtime bookkeeping.
     * Validators run under the Store lock before a recovered state is committed. A failure is
     * reported to the exception handler with the original error suppressed; the old state stays.
     */
    @InternalActronApi
    fun validateRecovery(validator: (state: S, recoveredState: S) -> Unit) {
        recoveryValidators += validator
    }

    /**
     * Sets the declared initial state.
     *
     * This value is used when no [StateSaver] restores a saved snapshot.
     * Later calls overwrite earlier ones.
     *
     * @param state The initial state to set
     */
    fun initialState(state: S) {
        storeInitialState = state
    }

    /**
     * Sets the root coroutine context used by Store processing.
     *
     * The default is `Dispatchers.Default`.
     *
     * @param coroutineContext The coroutine context to use
     */
    fun coroutineContext(coroutineContext: CoroutineContext) {
        storeCoroutineContext = coroutineContext
    }

    /**
     * Sets the saver used to restore a snapshot before startup and persist committed state changes.
     *
     * @param stateSaver The state saver implementation to use
     */
    fun stateSaver(stateSaver: StateSaver<S>) {
        storeStateSaver = stateSaver
    }

    /**
     * Sets the handler for non-fatal exceptions raised during Store processing.
     *
     * @param exceptionHandler The exception handler to use
     */
    fun exceptionHandler(exceptionHandler: ExceptionHandler) {
        storeExceptionHandler = exceptionHandler
    }

    /**
     * Sets which implicit triggers start a lazy Store.
     *
     * @param policy The auto-start policy to use
     */
    fun autoStartPolicy(policy: AutoStartPolicy) {
        storeAutoStartPolicy = policy
    }

    /**
     * Sets how queued actions are treated when a committed state change leaves the current state
     * variant.
     *
     * @param policy The pending action policy to use
     */
    fun pendingActionPolicy(policy: PendingActionPolicy) {
        storePendingActionPolicy = policy
    }

    /**
     * Sets how the Store invokes plugins when multiple plugin instances are registered.
     *
     * Regardless of policy, the Store waits for all matching plugin hooks to complete before it
     * continues processing.
     *
     * @param policy The plugin execution policy to use
     */
    fun pluginExecutionPolicy(policy: PluginExecutionPolicy) {
        storePluginExecutionPolicy = policy
    }

    /**
     * Appends one or more plugins to the Store.
     *
     * @param first The first plugin instance to add
     * @param rest Additional plugin instances to add
     */
    fun plugin(first: Plugin<S, A, E>, vararg rest: Plugin<S, A, E>) {
        storePlugins.add(first)
        storePlugins.addAll(rest)
    }

    /**
     * Appends one or more [StoreProbe]s that observe the processing boundaries of the Store:
     * accepted and discarded inputs, each processing and its outcome, commits, events and reported
     * failures. Probes are an internal observation API for journals and inspectors, not an
     * extension point; use [plugin] to react to the Store.
     *
     * @param first The first probe to add
     * @param rest Additional probes to add
     */
    @InternalActronApi
    fun probe(first: StoreProbe<S, A, E>, vararg rest: StoreProbe<S, A, E>) {
        storeProbes.add(first)
        storeProbes.addAll(rest)
    }

    @PublishedApi
    internal class StateHandler<P, SC : StoreScope>(
        val predicate: P,
        val handler: suspend SC.() -> Unit,
        val matcher: HandlerMatcher?,
    ) {
        // Keeps inline code compiled against earlier Actron versions working; such handlers have no matcher.
        constructor(predicate: P, handler: suspend SC.() -> Unit) : this(predicate, handler, null)
    }

    @PublishedApi
    internal val registeredEnterHandlers = mutableListOf<StateHandler<(S) -> Boolean, EnterScope<S, E, S>>>()

    @PublishedApi
    internal val registeredActionHandlers = mutableListOf<StateHandler<(S, A) -> Boolean, ActionScope<S, A, E, S>>>()

    @PublishedApi
    internal val registeredExitHandlers = mutableListOf<StateHandler<(S) -> Boolean, ExitScope<S, E, S>>>()

    @PublishedApi
    internal val registeredErrorHandlers = mutableListOf<StateHandler<(S, Exception) -> Boolean, RecoverScope<S, E, S, Exception>>>()

    private val onEnter: suspend EnterScope<S, E, S>.() -> Unit = {
        val matchingHandler = this@StoreBuilder.registeredEnterHandlers.firstOrNull { it.predicate(state) }
        matchingHandler?.handler?.invoke(this) ?: state
    }

    private val onAction: suspend ActionScope<S, A, E, S>.() -> Unit = {
        val matchingHandler = this@StoreBuilder.registeredActionHandlers.firstOrNull { it.predicate(state, action) }
        matchingHandler?.handler?.invoke(this) ?: state
    }

    private val onExit: suspend ExitScope<S, E, S>.() -> Unit = {
        val matchingHandler = this@StoreBuilder.registeredExitHandlers.firstOrNull { it.predicate(state) }
        matchingHandler?.handler?.invoke(this)
    }

    private val onError: suspend RecoverScope<S, E, S, Exception>.() -> Unit = {
        val matchingHandler = this@StoreBuilder.registeredErrorHandlers.firstOrNull { it.predicate(state, error) }
        matchingHandler?.handler?.invoke(this) ?: throw error
    }

    @ActronStoreDsl
    class StateHandlerConfig<S : State, A : Action, E : Event, S2 : S> {

        @PublishedApi
        internal class ThreadedHandler<P, SC : StoreScope>(
            private val dispatcher: CoroutineDispatcher?,
            val predicate: P,
            private val handler: suspend SC.() -> Unit,
            val inputType: KClass<*>?,
        ) {
            // Keeps inline code compiled against earlier Actron versions working; such handlers have no input type.
            constructor(dispatcher: CoroutineDispatcher?, predicate: P, handler: suspend SC.() -> Unit) :
                this(dispatcher, predicate, handler, null)

            suspend operator fun invoke(scope: SC) {
                if (dispatcher == null) {
                    handler(scope)
                } else {
                    withContext(dispatcher) {
                        handler(scope)
                    }
                }
            }
        }

        @PublishedApi
        internal val stateEnterHandlers = mutableListOf<ThreadedHandler<Nothing?, EnterScope<S, E, S2>>>()

        @PublishedApi
        internal val stateActionHandlers = mutableListOf<ThreadedHandler<(A) -> Boolean, ActionScope<S, A, E, S2>>>()

        @PublishedApi
        internal val stateExitHandlers = mutableListOf<ThreadedHandler<Nothing?, ExitScope<S, E, S2>>>()

        @PublishedApi
        internal val stateErrorHandlers = mutableListOf<ThreadedHandler<(Exception) -> Boolean, RecoverScope<S, E, S2, Exception>>>()

        /**
         * Registers a handler to be invoked when entering this state with the specified CoroutineDispatcher.
         * If no dispatcher is provided, the handler runs in the Store's current execution context.
         * If multiple `enter {}` handlers can match the current state, the first registered handler is used.
         * Supplying a dispatcher changes where the handler runs, but the Store still waits for it to finish.
         *
         * @param dispatcher Optional CoroutineDispatcher override for executing the enter handler
         * @param block The handler function that will be executed when entering this state
         */
        fun enter(dispatcher: CoroutineDispatcher? = null, block: suspend EnterScope<S, E, S2>.() -> Unit) {
            stateEnterHandlers.add(ThreadedHandler(dispatcher, null, block))
        }

        /**
         * Registers a handler for a specific action type in the current state configuration
         * with an optional CoroutineDispatcher.
         * If multiple `action {}` handlers can match the current state and action,
         * the first registered handler is used.
         * Supplying a dispatcher changes where the handler runs, but the Store still waits for it to finish.
         *
         * @param dispatcher Optional CoroutineDispatcher override for executing the action handler
         * @param block The handler function that processes the action and updates the state
         */
        inline fun <reified A2 : A> action(dispatcher: CoroutineDispatcher? = null, noinline block: suspend ActionScope<S, A2, E, S2>.() -> Unit) {
            stateActionHandlers.add(
                ThreadedHandler(
                    dispatcher = dispatcher,
                    predicate = { it is A2 },
                    handler = {
                        @Suppress("UNCHECKED_CAST")
                        block(this as ActionScope<S, A2, E, S2>)
                    },
                    inputType = A2::class,
                ),
            )
        }

        /**
         * Registers a handler to be invoked when exiting this state with the specified CoroutineDispatcher.
         * If no dispatcher is provided, the handler runs in the Store's current execution context.
         * If multiple `exit {}` handlers can match the current state, the first registered handler is used.
         * Supplying a dispatcher changes where the handler runs, but the Store still waits for it to finish.
         *
         * @param dispatcher Optional CoroutineDispatcher override for executing the exit handler
         * @param block The handler function that will be executed when exiting this state
         */
        fun exit(dispatcher: CoroutineDispatcher? = null, block: suspend ExitScope<S, E, S2>.() -> Unit) {
            stateExitHandlers.add(ThreadedHandler(dispatcher, null, block))
        }

        /**
         * Registers a recovery handler for a specific exception type in the current state configuration
         * with an optional CoroutineDispatcher.
         * If multiple `recover {}` handlers can match the current state and error,
         * the first registered handler is used.
         * Supplying a dispatcher changes where the handler runs, but the Store still waits for it to finish.
         *
         * @param dispatcher Optional CoroutineDispatcher override for executing the recover handler
         * @param block The handler function that processes the exception and updates the state
         */
        inline fun <reified T : Exception> recover(dispatcher: CoroutineDispatcher? = null, noinline block: suspend RecoverScope<S, E, S2, T>.() -> Unit) {
            stateErrorHandlers.add(
                ThreadedHandler(
                    dispatcher = dispatcher,
                    predicate = { it is T },
                    handler = {
                        @Suppress("UNCHECKED_CAST")
                        block(this as RecoverScope<S, E, S2, T>)
                    },
                    inputType = T::class,
                ),
            )
        }

        /**
         * @deprecated Use `recover {}`.
         */
        @Deprecated(
            message = "Use recover<T>(dispatcher, block)",
            replaceWith = ReplaceWith("recover<T>(dispatcher, block)"),
        )
        inline fun <reified T : Exception> error(dispatcher: CoroutineDispatcher? = null, noinline block: suspend RecoverScope<S, E, S2, T>.() -> Unit) {
            recover(dispatcher, block)
        }
    }

    /**
     * Configures handlers for a specific state subtype.
     *
     * Inside the block, you can register `enter {}`, `action {}`, `exit {}`, and `recover {}`
     * handlers narrowed to [S2].
     * Handler selection is first-match in registration order.
     * If both broad and specific handlers can match, place broader handlers later.
     *
     * @param block Configuration block for handlers that target [S2]
     */
    inline fun <reified S2 : S> state(noinline block: StateHandlerConfig<S, A, E, S2>.() -> Unit) {
        val config = StateHandlerConfig<S, A, E, S2>().apply(block)

        for (enterHandler in config.stateEnterHandlers) {
            registeredEnterHandlers.add(
                StateHandler(
                    predicate = { it is S2 },
                    handler = {
                        @Suppress("UNCHECKED_CAST")
                        enterHandler.invoke(this as EnterScope<S, E, S2>)
                    },
                    matcher = HandlerMatcher(stateType = S2::class),
                ),
            )
        }

        for (actionHandler in config.stateActionHandlers) {
            registeredActionHandlers.add(
                StateHandler(
                    predicate = { state, action -> state is S2 && actionHandler.predicate(action) },
                    handler = {
                        @Suppress("UNCHECKED_CAST")
                        actionHandler.invoke(this as ActionScope<S, A, E, S2>)
                    },
                    matcher = HandlerMatcher(stateType = S2::class, inputType = actionHandler.inputType),
                ),
            )
        }

        for (exitHandler in config.stateExitHandlers) {
            registeredExitHandlers.add(
                StateHandler(
                    predicate = { it is S2 },
                    handler = {
                        @Suppress("UNCHECKED_CAST")
                        exitHandler.invoke(this as ExitScope<S, E, S2>)
                    },
                    matcher = HandlerMatcher(stateType = S2::class),
                ),
            )
        }

        for (errorHandler in config.stateErrorHandlers) {
            registeredErrorHandlers.add(
                StateHandler(
                    predicate = { state, throwable -> state is S2 && errorHandler.predicate(throwable) },
                    handler = {
                        @Suppress("UNCHECKED_CAST")
                        errorHandler.invoke(this as RecoverScope<S, E, S2, Exception>)
                    },
                    matcher = HandlerMatcher(stateType = S2::class, inputType = errorHandler.inputType),
                ),
            )
        }
    }

    @OptIn(InternalActronApi::class)
    internal fun build(): Store<S, A, E> {
        val state = requireNotNull(storeInitialState) { "[Actron] InitialState must be set in Store{} DSL" }
        return object : StoreImpl<S, A, E>() {
            override var initialState: S = state
            override var coroutineContext: CoroutineContext = storeCoroutineContext
            override var stateSaver: StateSaver<S> = storeStateSaver
            override var exceptionHandler: ExceptionHandler = storeExceptionHandler
            override var autoStartPolicy: AutoStartPolicy = storeAutoStartPolicy
            override var pendingActionPolicy: PendingActionPolicy = storePendingActionPolicy
            override var pluginExecutionPolicy: PluginExecutionPolicy = storePluginExecutionPolicy
            override val plugins: MutableList<Plugin<S, A, E>> = storePlugins
            override val probes: MutableList<StoreProbe<S, A, E>> = storeProbes
            override val onEnter: suspend EnterScope<S, E, S>.() -> Unit = this@StoreBuilder.onEnter
            override val onAction: suspend ActionScope<S, A, E, S>.() -> Unit = this@StoreBuilder.onAction
            override val onExit: suspend ExitScope<S, E, S>.() -> Unit = this@StoreBuilder.onExit
            override val onError: suspend RecoverScope<S, E, S, Exception>.() -> Unit = this@StoreBuilder.onError
            override val validateRecoveredState: (S, S) -> Unit = { previous, recovered ->
                recoveryValidators.forEach { it(previous, recovered) }
            }
            override val handlerRegistry: HandlerRegistry<S, A> = HandlerRegistry(
                enter = registeredEnterHandlers.map { it.matcher },
                action = registeredActionHandlers.map { it.matcher },
                exit = registeredExitHandlers.map { it.matcher },
                recover = registeredErrorHandlers.map { it.matcher },
                actionPredicates = registeredActionHandlers.map { it.predicate },
            )
        }
    }
}

/**
 * Creates a Store with optional initial state and root coroutine context.
 *
 * [initialState] and [context], when non-null, are applied before [builder].
 * If [initialState] is null, the initial state must be set inside [builder] by calling
 * [StoreBuilder.initialState].
 *
 * @param initialState The initial state of the Store when no saved snapshot is restored
 * @param context The coroutine context to use for Store processing
 * @param builder StoreBuilder lambda used to customize the store
 * @return A configured Store instance
 * @throws IllegalArgumentException if the initial state is not set
 */
fun <S : State, A : Action, E : Event> Store(
    initialState: S? = null,
    context: CoroutineContext? = null,
    builder: StoreBuilder<S, A, E>.() -> Unit,
): Store<S, A, E> {
    val storeBuilder = StoreBuilder<S, A, E>()
    initialState?.let(storeBuilder::initialState)
    context?.let(storeBuilder::coroutineContext)
    storeBuilder.builder()
    return storeBuilder.build()
}

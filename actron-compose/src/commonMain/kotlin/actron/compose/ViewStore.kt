package actron.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import actron.core.Action
import actron.core.Event
import actron.core.State
import actron.core.Store
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import androidx.compose.runtime.State as ComposeState

/**
 * Compose-friendly state holder for a [Store].
 *
 * A [ViewStore] is tied to a Store instance and exposes its latest state, a dispatch function,
 * and access to the Store's event stream.
 *
 * When created by [rememberViewStore], the same [ViewStore] instance is retained while the
 * underlying Store instance remains the same. Its [state] property always reads the latest
 * collected Store state from Compose-managed state.
 *
 * The secondary constructor that accepts a plain [state] creates a standalone holder with a fixed
 * initial value, which is useful for previews, tests, and other non-Store-backed usage.
 *
 * @param stateRef Compose state that always holds the latest Store state
 * @param dispatch Function to dispatch actions
 * @param eventFlow Flow used to receive one-off events
 */
@Stable
class ViewStore<S : State, A : Action, E : Event> internal constructor(
    private val stateRef: ComposeState<S>,
    val dispatch: (action: A) -> Unit = {},
    @PublishedApi internal val eventFlow: Flow<E> = emptyFlow(),
) {
    constructor(
        state: S,
        dispatch: (A) -> Unit = {},
        eventFlow: Flow<E> = emptyFlow(),
    ) : this(
        stateRef = mutableStateOf(state),
        dispatch = dispatch,
        eventFlow = eventFlow,
    )

    val state: S
        get() = stateRef.value

    /**
     * Derives a value from the [state] for this composition: [mapper] runs again when the state
     * changes, and what reads the result recomposes only when the derived value changed
     * (structural equality). A UI model projected from a large state, a field several states
     * share, a flag computed from a few of them.
     *
     * The derivation is remembered per call site for this [ViewStore]; the latest [mapper] is
     * used. The mapper must be pure: it runs during composition and again when the state
     * changes, on the snapshot's thread.
     *
     * @param mapper The projection of the state
     * @return The derived value, read from Compose state
     */
    @Composable
    fun <T : Any> select(mapper: (S) -> T): T {
        val currentMapper = rememberUpdatedState(mapper)
        val derived = remember(this) { derivedStateOf { currentMapper.value(stateRef.value) } }
        return derived.value
    }

    /**
     * Invokes [block] only when the current [state] is of type [S2].
     *
     * Inside [block], this [ViewStore] is narrowed to [S2]: its `state` is the latest state of type
     * [S2]. Callbacks created in [block], such as click handlers, may run after the Store has moved
     * to another state type but before recomposition removes them; they then read the last [S2]
     * state instead of failing with a `ClassCastException`.
     *
     * @param block Composable function to render content for the narrowed state
     */
    @Suppress("ComposableNaming")
    @Composable
    inline fun <reified S2 : S> stateContent(block: @Composable ViewStore<S2, A, E>.() -> Unit) {
        if (state is S2) {
            val narrowed = remember(this) { narrow<S2> { it is S2 } }
            block(narrowed)
        }
    }

    @PublishedApi
    internal fun <S2 : S> narrow(isNarrowed: (S) -> Boolean): ViewStore<S2, A, E> =
        ViewStore(stateRef = NarrowedState(stateRef, isNarrowed), dispatch = dispatch, eventFlow = eventFlow)

    /**
     * Reads [source] (so Compose still tracks it) and returns its value while it is of type [S2],
     * otherwise the last value that was.
     */
    private class NarrowedState<T : Any, N : T>(
        private val source: ComposeState<T>,
        private val isNarrowed: (T) -> Boolean,
    ) : ComposeState<N> {
        // Seeded at creation: a callback that never read `state` during composition must still
        // find the last narrowed value after the Store moved on.
        private var last: N = source.value.narrowed()

        override val value: N
            get() {
                val current = source.value
                if (isNarrowed(current)) last = current.narrowed()
                return last
            }

        @Suppress("UNCHECKED_CAST")
        private fun T.narrowed(): N = this as N
    }

    @Deprecated(
        message = "Use stateContent instead.",
        replaceWith = ReplaceWith("stateContent<S2>(block)"),
        level = DeprecationLevel.WARNING,
    )
    @Suppress("ComposableNaming")
    @Composable
    inline fun <reified S2 : S> render(block: @Composable ViewStore<S2, A, E>.() -> Unit) = stateContent<S2>(block)

    /**
     * Collects only events of type [E2] while this composable is in the composition.
     *
     * Collection starts after the composable enters the composition.
     * Events emitted earlier are not replayed: the Store commits a state and runs its `enter {}`
     * before the frame that composes `stateContent` for it, so an `eventEffect` placed inside a
     * `stateContent` block misses the events of that state's `enter {}`, and one placed at screen
     * level misses the events of the startup `enter {}`. Model signals that must not be lost
     * as state, and keep `eventEffect` at screen level for the rest.
     *
     * @param block Function to process the event
     */
    @Suppress("ComposableNaming")
    @Composable
    inline fun <reified E2 : E> eventEffect(noinline block: ViewStore<S, A, E>.(event: E2) -> Unit) {
        val currentViewStore = rememberUpdatedState(this)
        val currentBlock = rememberUpdatedState(block)
        LaunchedEffect(eventFlow) {
            eventFlow.filter { it is E2 }.collect {
                currentBlock.value(currentViewStore.value, it as E2)
            }
        }
    }

    @Deprecated(
        message = "Use eventEffect instead.",
        replaceWith = ReplaceWith("eventEffect<E2>(block)"),
        level = DeprecationLevel.WARNING,
    )
    @Suppress("ComposableNaming")
    @Composable
    inline fun <reified E2 : E> handle(noinline block: ViewStore<S, A, E>.(event: E2) -> Unit) = eventEffect<E2>(block)
}

/**
 * Collects the state of an existing [Store] as Compose state and exposes it through a [ViewStore].
 *
 * Use this overload when a Store is already provided by a ViewModel or dependency injection.
 * For a given Store instance, this function returns the same [ViewStore] instance across
 * recompositions. A new [ViewStore] is created only when [store] changes.
 *
 * @param store Source Store instance
 * @param autoClose Whether to close the Store when the composable leaves the composition
 * @return A ViewStore state holder backed by the Store
 */
@Composable
fun <S : State, A : Action, E : Event> rememberViewStore(store: Store<S, A, E>, autoClose: Boolean = false): ViewStore<S, A, E> {
    val closeStoreOnDispose = remember(store) { autoClose }

    val state = key(store) {
        store.state.collectAsState()
    }

    DisposableEffect(store) {
        onDispose {
            if (closeStoreOnDispose) {
                store.close()
            }
        }
    }

    return remember(store) {
        ViewStore(
            stateRef = state,
            dispatch = store::dispatch,
            eventFlow = store.event,
        )
    }
}

/**
 * Remembers a [Store], collects its state as Compose state, and exposes it through a [ViewStore].
 *
 * The [store] lambda is used only when a new remembered Store must be created for [key].
 * For a given remembered Store instance, this function returns the same [ViewStore] instance across
 * recompositions. A new [ViewStore] is created only when a new Store instance is remembered for
 * [key].
 * The [autoClose] value is fixed when that remembered Store instance is adopted. Recomposition
 * alone does not change close ownership for the same Store, but when [key] causes a different
 * Store instance to be remembered, the current [autoClose] value is used for that new Store.
 *
 * @param key Key used to remember and retain the Store instance
 * @param autoClose Whether to close the Store when the composable leaves the composition
 * @param store Function to create the source Store instance
 * @return A ViewStore state holder backed by the remembered Store
 */
@Composable
fun <S : State, A : Action, E : Event> rememberViewStore(key: Any = Unit, autoClose: Boolean = false, store: () -> Store<S, A, E>): ViewStore<S, A, E> {
    val rememberedStore = remember(key) { store() }
    val closeStoreOnDispose = remember(rememberedStore) { autoClose }

    val state = key(rememberedStore) {
        rememberedStore.state.collectAsState()
    }

    DisposableEffect(rememberedStore) {
        onDispose {
            if (closeStoreOnDispose) {
                rememberedStore.close()
            }
        }
    }

    return remember(rememberedStore) {
        ViewStore(
            stateRef = state,
            dispatch = rememberedStore::dispatch,
            eventFlow = rememberedStore.event,
        )
    }
}

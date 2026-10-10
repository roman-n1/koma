package actron.test

import actron.core.Action
import actron.core.Event
import actron.core.Plugin
import actron.core.PluginScope
import actron.core.State
import actron.core.Store

/**
 * Default in-memory recorder for tests. Implemented as a [Plugin] so it integrates with the Store
 * through the same lifecycle hooks as other plugins.
 *
 * Records the state at Store startup (which is the [actron.core.StateSaver]-restored value
 * when present, otherwise the Store's initial state), every committed state transition, and every
 * emitted event, in insertion order.
 *
 * The events can also be received one by one, in order, with [receiveEvent]: a cursor over
 * [events] that a test moves as it accounts for each event, so a test that ends with
 * [assertNoUnconsumedEvents] (or [assertNoPendingWork]) has seen every event the Store emitted
 * and none it did not expect. [events] keeps every event whether received or not.
 */
class StoreRecorder<S : State, A : Action, E : Event> internal constructor() : Plugin<S, A, E> {
    private val recordedStates = mutableListOf<S>()
    private val recordedEvents = mutableListOf<E>()
    private var cursor = 0

    /**
     * State snapshots recorded for this Store.
     */
    val states: List<S> = recordedStates

    /**
     * Events recorded for this Store.
     */
    val events: List<E> = recordedEvents

    /**
     * The recorded events not yet received with [receiveEvent], in order.
     */
    val unconsumedEvents: List<E> get() = recordedEvents.subList(cursor, recordedEvents.size).toList()

    /**
     * Clears all recorded history and the cursor.
     */
    fun clear() {
        recordedStates.clear()
        recordedEvents.clear()
        cursor = 0
    }

    /**
     * Receives the next unconsumed event, which must be an [E2]: returns it and moves the cursor.
     *
     * @throws AssertionError if every recorded event was received already, or the next one is
     * not an [E2]; the cursor does not move then
     */
    inline fun <reified E2 : E> receiveEvent(): E2 {
        val next = peekUnconsumed("an event of type ${E2::class.simpleName}")
        if (next !is E2) throw AssertionError("[Actron] Expected an event of type ${E2::class.simpleName}, but the next unconsumed event is $next; unconsumed: $unconsumedEvents")
        consumeNext()
        return next
    }

    /**
     * Receives the next unconsumed event, which must satisfy [predicate]: returns it and moves
     * the cursor.
     *
     * @throws AssertionError if every recorded event was received already, or the next one does
     * not satisfy [predicate]; the cursor does not move then
     */
    fun receiveEvent(predicate: (E) -> Boolean): E {
        val next = peekUnconsumed("an event")
        if (!predicate(next)) throw AssertionError("[Actron] The next unconsumed event $next does not satisfy the predicate; unconsumed: $unconsumedEvents")
        consumeNext()
        return next
    }

    /**
     * Fails when a recorded event was not received with [receiveEvent].
     *
     * @throws AssertionError listing the unconsumed events
     */
    fun assertNoUnconsumedEvents() {
        val left = unconsumedEvents
        if (left.isNotEmpty()) throw AssertionError("[Actron] ${left.size} recorded event(s) were not received: $left")
    }

    @PublishedApi
    internal fun peekUnconsumed(expected: String): E {
        if (cursor >= recordedEvents.size) throw AssertionError("[Actron] Expected $expected, but every recorded event was received already (${recordedEvents.size} recorded)")
        return recordedEvents[cursor]
    }

    @PublishedApi
    internal fun consumeNext() {
        cursor++
    }

    // The Store this recorder was started on; the lists are plain, so one recorder cannot serve
    // two Stores (their hook rounds are serialized per Store only).
    private var acceptsOwner: (PluginScope<S, A>) -> Boolean = { true }

    override suspend fun onStart(scope: PluginScope<S, A>, state: S) {
        check(acceptsOwner(scope)) { "[Actron] A StoreRecorder records one Store; create one per Store with createRecorder()" }
        acceptsOwner = { it === scope }
        recordedStates.add(state)
    }

    override suspend fun onState(scope: PluginScope<S, A>, prevState: S, state: S) {
        recordedStates.add(state)
    }

    override suspend fun onEvent(scope: PluginScope<S, A>, state: S, event: E) {
        recordedEvents.add(event)
    }
}

/**
 * Creates and registers a [StoreRecorder] for this Store.
 *
 * The recorder is registered as a [Plugin] via [patch], which must happen before the Store is started.
 *
 * @return The registered [StoreRecorder]
 * @throws IllegalStateException if the Store has already been started or is starting
 * @throws IllegalStateException if the Store is not backed by Actron's internal implementation
 */
fun <S : State, A : Action, E : Event> Store<S, A, E>.createRecorder(): StoreRecorder<S, A, E> {
    val recorder = StoreRecorder<S, A, E>()
    try {
        patch { plugin(recorder) }
    } catch (e: IllegalStateException) {
        throw IllegalStateException("[Actron] createRecorder() must be called before the Store is started, dispatched to or its state collected", e)
    }
    return recorder
}

/**
 * Creates a [StoreRecorder], registers it, and runs [block] with this Store as the receiver and
 * the recorder as the argument.
 *
 * Intended for tests that want to scope a recording session to a single block. Inside the block,
 * use [startAndAwait] and [dispatchAndAwait] to drive the Store, then assert against the recorder's
 * `states` and `events`.
 *
 * @param block Test body that receives the registered [StoreRecorder]
 * @throws IllegalStateException if the Store has already been started or is starting
 * @throws IllegalStateException if the Store is not backed by Actron's internal implementation
 */
suspend fun <S : State, A : Action, E : Event> Store<S, A, E>.record(
    block: suspend Store<S, A, E>.(StoreRecorder<S, A, E>) -> Unit,
) {
    val recorder = createRecorder()
    block(recorder)
}

package koma.test

import koma.core.Action
import koma.core.Event
import koma.core.InternalKomaApi
import koma.core.State
import koma.core.Store
import koma.core.StoreInternalApi
import koma.core.StorePatchBuilder
import koma.core.StorePendingWork
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Starts the Store and suspends until the startup work completes.
 *
 * Prefer this for tests that need to observe startup behavior before the first dispatched action.
 *
 * This waits for plugin `onStart` hooks and the synchronous `enter {}` chain triggered by startup.
 * It does not wait for additional work launched from `enter {}` handlers.
 * Calling it from inside a handler, plugin hook or transaction of the same Store throws
 * [IllegalStateException]: it would wait for itself. On a closed Store it returns at once and
 * nothing runs.
 *
 * This extension is available for Store instances created by the Koma DSL.
 *
 * @throws IllegalStateException if the Store is not backed by Koma's internal implementation
 */
@OptIn(InternalKomaApi::class)
suspend fun <S : State, A : Action, E : Event> Store<S, A, E>.startAndAwait() {
    requireStoreInternalApi().startAndAwait()
}

/**
 * Dispatches an action and suspends until the Store finishes the dispatch work itself.
 *
 * This waits for startup when needed, the matching action handler, and any resulting synchronous
 * state transition work triggered by that dispatch.
 * It does not wait for additional work launched from `enter {}` or `action {}` handlers.
 * It also returns normally when the action was discarded before it ran, by
 * [koma.core.PendingActionPolicy.ClearOnStateExit] or `clearPendingActions()`, and on a closed
 * Store, where nothing runs; check the state or a [StoreRecorder] rather than relying on the
 * return alone. Calling it from inside a handler, plugin hook or transaction of the same Store
 * throws [IllegalStateException]: it would wait for itself. Call it from a `launch {}` instead.
 *
 * This extension is available for Store instances created by the Koma DSL.
 *
 * @param action The action to dispatch
 * @throws IllegalStateException if the Store is not backed by Koma's internal implementation
 */
@OptIn(InternalKomaApi::class)
suspend fun <S : State, A : Action, E : Event> Store<S, A, E>.dispatchAndAwait(action: A) {
    requireStoreInternalApi().dispatchAndAwait(action)
}

/**
 * Applies a Store configuration patch before the Store is started.
 *
 * This is intended for tests that need to swap persistence, policies, exception handling,
 * plugins, or the declared initial state without rewriting the Store definition itself.
 * The patch must happen before startup is requested by `start()`, dispatch or state collection,
 * including while startup is queued or awaiting a retry. Some values must also be patched before
 * they are consumed: `initialState` and `stateSaver` before state is read, and `coroutineContext`
 * before the Store launches any coroutine.
 *
 * This extension is available for Store instances created by the Koma DSL.
 *
 * @param builder Builder lambda for a Store configuration patch
 * @throws IllegalStateException if the Store has already been started or is starting
 * @throws IllegalStateException if the patch targets a value that has already been consumed
 * @throws IllegalStateException if the Store is not backed by Koma's internal implementation
 */
@OptIn(InternalKomaApi::class)
fun <S : State, A : Action, E : Event> Store<S, A, E>.patch(
    builder: StorePatchBuilder<S, A, E>.() -> Unit,
): Store<S, A, E> {
    val patch = StorePatchBuilder<S, A, E>().apply(builder).build()
    return requireStoreInternalApi().patch(patch)
}

/**
 * Waits until the Store is idle: every accepted input (dispatches, the startup, transactions,
 * recoveries) finished or was discarded, and every coroutine started with `launch {}` from
 * `enter {}` or `action {}` ended; it iterates until nothing new appeared. A `subscribe {}` is not
 * waited for: it lives as long as its state. Neither is a plugin's launch. Timers of a
 * `koma-statechart` machine are data, not launches. Call it after [dispatchAndAwait] to see the
 * side effects of the work the handler started, however it is dispatched, without a test
 * dispatcher. Calling it from inside a handler, plugin hook or transaction of the same Store
 * throws [IllegalStateException]: it would wait for itself. [timeout] runs on the caller's
 * clock: under `runTest`'s virtual time it expires as soon as the body suspends, so a Store on a
 * real dispatcher is awaited under `withContext(Dispatchers.Default)`.
 *
 * @param timeout How long to wait before failing
 * @throws IllegalStateException if the Store is not idle after [timeout], with what is pending
 * @throws IllegalStateException if the Store is not backed by Koma's internal implementation
 */
@OptIn(InternalKomaApi::class)
suspend fun <S : State, A : Action, E : Event> Store<S, A, E>.awaitIdle(timeout: Duration = 10.seconds) {
    val pending = requireStoreInternalApi().awaitIdle(timeout)
    check(pending.isIdle) { "[Koma] The Store did not become idle within $timeout: ${pending.inputs} input(s) pending, ${pending.launches} launch(es) running" }
}

/**
 * What the Store still has to do now, without waiting: the inputs accepted and not finished, and
 * the launches from `enter {}` or `action {}` still active. Both zero means [awaitIdle] would
 * return at once.
 *
 * @throws IllegalStateException if the Store is not backed by Koma's internal implementation
 */
@OptIn(InternalKomaApi::class)
suspend fun <S : State, A : Action, E : Event> Store<S, A, E>.pendingWork(): StorePendingWork = requireStoreInternalApi().awaitIdle(Duration.ZERO)

/**
 * Fails when the Store still has work to do, or when [recorder] holds events the test did not
 * receive with [StoreRecorder.receiveEvent]: the end-of-test check of a Store that was driven
 * step by step. Nothing is waited for; call [awaitIdle] first when launches may still be running.
 *
 * @param recorder The recorder whose events must all have been received, if any
 * @throws AssertionError with what is pending and what was not received
 * @throws IllegalStateException if the Store is not backed by Koma's internal implementation
 */
suspend fun <S : State, A : Action, E : Event> Store<S, A, E>.assertNoPendingWork(recorder: StoreRecorder<S, A, E>? = null) {
    val pending = pendingWork()
    val unreceived = recorder?.unconsumedEvents.orEmpty()
    if (pending.isIdle && unreceived.isEmpty()) return
    throw AssertionError(
        "[Koma] The Store has pending work: ${pending.inputs} input(s) pending, ${pending.launches} launch(es) running" +
            (if (unreceived.isNotEmpty()) ", ${unreceived.size} recorded event(s) not received: $unreceived" else ""),
    )
}

@OptIn(InternalKomaApi::class)
internal fun <S : State, A : Action, E : Event> Store<S, A, E>.requireStoreInternalApi(): StoreInternalApi<S, A, E> {
    @Suppress("UNCHECKED_CAST")
    return this as? StoreInternalApi<S, A, E>
        ?: throw IllegalStateException("[Koma] This API is only supported for Store instances created by Koma DSL")
}

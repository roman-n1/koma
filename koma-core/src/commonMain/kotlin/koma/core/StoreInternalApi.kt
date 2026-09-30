package koma.core

import kotlin.time.Duration

/**
 * Internal bridge API exposed from `:koma-core` so companion modules such as `:koma-test`
 * can provide extensions without depending on internal implementation types.
 *
 * Most users should call extensions from `:koma-test` instead of using this interface directly.
 */
@InternalKomaApi
interface StoreInternalApi<S : State, A : Action, E : Event> {
    suspend fun startAndAwait()
    suspend fun dispatchAndAwait(action: A)

    /**
     * Enqueues an adapter action whose lifetime may end before it is processed. [isValid] is
     * checked under the Store lock before action hooks or handlers run; false discards it.
     * The predicate must be fast, non-suspending and side-effect free. A predicate that throws
     * discards the action and its failure reaches the exception handler, not `recover {}`.
     */
    fun dispatchIf(action: A, isValid: () -> Boolean)
    fun patch(patch: StorePatch<S, A, E>): Store<S, A, E>

    /**
     * Returns every registered action handler in first-match order, marking which ones match
     * [state] and [action]. No handler runs.
     */
    fun matchActionHandlers(state: S, action: A): List<ActionHandlerMatch>

    /**
     * Returns the declared types of all registered handlers. No handler runs.
     */
    fun handlerMetadata(): StoreHandlerMetadata

    /**
     * Waits until the Store is idle: no accepted input (a dispatch, the startup, a transaction,
     * a recovery) is queued or being processed, and no coroutine started with `launch {}` from
     * `enter {}` or `action {}` is active. A `subscribe {}` is not waited for; a plugin's launch is
     * not either. Iterates until nothing new appeared, at most [timeout], and returns what is still
     * pending: both counts zero when idle. Throws [IllegalStateException] from inside a handler,
     * plugin hook or transaction of the same Store. On a closed Store it returns idle once every
     * coroutine of the Store has ended.
     */
    suspend fun awaitIdle(timeout: Duration): StorePendingWork
}

/**
 * What a Store still has to do, as [StoreInternalApi.awaitIdle] found it: [inputs] accepted and
 * not finished or discarded, [launches] started from `enter {}` or `action {}` and still active.
 */
data class StorePendingWork(val inputs: Int, val launches: Int) {
    val isIdle: Boolean get() = inputs == 0 && launches == 0
}

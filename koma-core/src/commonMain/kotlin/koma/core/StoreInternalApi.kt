package koma.core

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
    fun patch(patch: StorePatch<S, A, E>): Store<S, A, E>

    /**
     * Returns every registered action handler in first-match order, marking which ones match
     * [state] and [action]. No handler runs.
     */
    fun matchActionHandlers(state: S, action: A): List<ActionHandlerMatch>
}

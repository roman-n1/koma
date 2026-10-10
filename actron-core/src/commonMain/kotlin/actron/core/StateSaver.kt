package actron.core

/**
 * Persists committed Store state snapshots and optionally restores the last saved snapshot.
 *
 * A restored snapshot replaces the declared initial state before Store startup processing begins.
 */
interface StateSaver<S : State> {
    /**
     * Persists a committed state snapshot.
     *
     * An exception thrown here is reported to the Store's [ExceptionHandler]; the state stays
     * committed and the transition continues.
     *
     * @param state The state to save
     */
    fun save(state: S)

    /**
     * Restores the snapshot to use before Store startup.
     *
     * Return [initialState] when no snapshot has been saved.
     *
     * @return The restored state, or [initialState] when no snapshot exists
     */
    fun restore(initialState: S): S

    companion object {
        /**
         * Creates a no-op implementation that never restores and never persists state.
         */
        @Suppress("FunctionName")
        fun <S : State> Noop(): StateSaver<S> = object : StateSaver<S> {
            override fun save(state: S) {}
            override fun restore(initialState: S): S {
                return initialState
            }
        }
    }
}

/**
 * Creates a [StateSaver] from save and restore lambdas.
 */
fun <S : State> StateSaver(save: (state: S) -> Unit, restore: (initialState: S) -> S) = object : StateSaver<S> {
    override fun save(state: S) {
        save.invoke(state)
    }

    override fun restore(initialState: S): S {
        return restore.invoke(initialState)
    }
}

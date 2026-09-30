package koma.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Collecting [Store.state] with an operator that stops early.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StoreStateCollectionTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    sealed interface AppState : State {
        data class Active(val value: Int = 0) : AppState
        data object Done : AppState
    }

    sealed interface AppAction : Action {
        data object Finish : AppAction
    }

    /**
     * Operators that stop collecting [Store.state] early, such as `first()`, `first {}` and
     * `take(n)`, return. They used to abort only an internal child coroutine and hang forever.
     *
     * Runs on [Dispatchers.Default] with a real timeout so a regression fails instead of hanging.
     */
    @Test
    fun stateFirst_returnsOnceThePredicateMatches() = runTest(testDispatcher) {
        val store: Store<AppState, AppAction, Nothing> = Store(AppState.Active()) {
            coroutineContext(Dispatchers.Default)
            state<AppState.Active> {
                action<AppAction.Finish> { nextState { AppState.Done } }
            }
        }

        val results = withContext(Dispatchers.Default) {
            withTimeoutOrNull(2_000) {
                val initial = store.state.first()
                val taken = store.state.take(1).toList()
                store.dispatch(AppAction.Finish)
                val done = store.state.first { it is AppState.Done }
                listOf(initial, taken.single(), done)
            }
        }

        assertEquals(listOf(AppState.Active(), AppState.Active(), AppState.Done), results)
        store.close()
    }
}

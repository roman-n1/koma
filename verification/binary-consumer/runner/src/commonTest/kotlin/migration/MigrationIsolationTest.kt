package migration

import actron.core.Action
import actron.core.State
import actron.core.Store
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class MigrationIsolationTest {
    private data class Counter(val count: Int = 0) : State
    private data object Increment : Action

    @Test
    fun frozenKomaClientRunsAlongsideActron() = runTest(timeout = 30.seconds) {
        val store = Store<Counter, Increment, Nothing>(Counter(), context = coroutineContext) {
            state<Counter> {
                action<Increment> { nextState { state.copy(count = state.count + 1) } }
            }
        }
        try {
            store.dispatch(Increment)
            runCurrent()
            assertEquals(1, store.currentState.count)
            assertEquals("stable-4.0.0: state/action/launch/transaction/recover/plugin/saver/exit passed", legacy.exercise(coroutineContext))
            store.dispatch(Increment)
            runCurrent()
            assertEquals(2, store.currentState.count)
        } finally {
            store.close()
        }
    }
}

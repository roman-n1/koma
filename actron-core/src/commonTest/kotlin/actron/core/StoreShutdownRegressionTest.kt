package actron.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class StoreShutdownRegressionTest {
    private data object Active : State
    private data object Done : State
    private data object Finish : Action

    @Test
    fun closeDuringNonCancellableExitDoesNotCommitOrSaveTheTransition() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val saved = mutableListOf<State>()
        var doneEntered = false
        val store = Store<State, Finish, Nothing>(Active, backgroundScope.coroutineContext) {
            stateSaver(StateSaver(save = { saved += it }, restore = { null }))
            state<Active> {
                action<Finish> { nextState { Done } }
                exit {
                    withContext(NonCancellable) {
                        entered.complete(Unit)
                        release.await()
                    }
                }
            }
            state<Done> { enter { doneEntered = true } }
        }

        store.dispatch(Finish)
        entered.await()
        store.close()
        release.complete(Unit)
        runCurrent()

        assertEquals(Active, store.currentState)
        assertEquals(emptyList(), saved)
        assertEquals(false, doneEntered)
    }
}

package koma.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Nothing commits, is saved, entered or recovered after close(), even when the transition was
 * past its last suspension point or finishing cleanup under NonCancellable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StoreCloseTest {

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

    private sealed interface Net : State {
        data object Idle : Net
        data class Done(val reply: String) : Net
    }

    private sealed interface NetAction : Action {
        data object Ping : NetAction
    }

    /**
     * An `exit {}` that fails after the Store was closed (its cleanup ran in `NonCancellable`)
     * does not run `recover {}`: nothing runs in a closed Store, and the state stays as it was.
     */
    @Test
    fun anExitFailingAfterClose_doesNotRunRecover() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val handled = mutableListOf<Throwable>()
        val release = CompletableDeferred<Unit>()
        var recovered = 0
        val store: Store<Net, NetAction, Nothing> = Store(Net.Idle) {
            coroutineContext(dispatcher)
            exceptionHandler(ExceptionHandler { handled += it })
            state<Net.Idle> {
                action<NetAction.Ping> { nextState { Net.Done("pong") } }
                exit {
                    withContext(NonCancellable) { release.await() }
                    throw IllegalStateException("exit failed")
                }
                recover<Exception> { recovered++ }
            }
        }

        store.startAndAwaitForTest()
        store.dispatch(NetAction.Ping)
        runCurrent()
        store.close()
        release.complete(Unit)
        runCurrent()

        assertEquals(Net.Idle, store.currentState)
        assertEquals(0, recovered)
        assertEquals(emptyList(), handled.map { it.message })
    }
}

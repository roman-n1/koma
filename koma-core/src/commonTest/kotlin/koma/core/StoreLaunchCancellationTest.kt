package koma.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class StoreLaunchCancellationTest {
    private data class Active(val value: Int = 0) : State
    private data object Off : State
    private data object Start : Action
    private data object Stop : Action
    private data object Output : Event

    @Test
    fun cancelledLaneCannotCommitFromNonCancellableCleanup() = runTest {
        val lane = LaunchLane()
        val store = Store<Active, Action, Nothing>(Active(), backgroundScope.coroutineContext) {
            state<Active> {
                action<Start> {
                    launch(control = LaunchControl.CancelPrevious(lane)) {
                        try { awaitCancellation() } finally {
                            withContext(NonCancellable) {
                                transaction { nextState { Active(99) } }
                            }
                        }
                    }
                }
                action<Stop> {
                    cancelLaunch(lane)
                    nextState { Active(2) }
                }
            }
        }
        try {
            store.dispatchAndAwaitForTest(Start)
            runCurrent()
            store.dispatchAndAwaitForTest(Stop)
            runCurrent()
            assertEquals(Active(2), store.currentState)
        } finally {
            store.close()
        }
    }

    @Test
    fun cancelledLaneCannotEmitFromNonCancellableCleanup() = runTest {
        val lane = LaunchLane()
        val events = mutableListOf<Output>()
        val store = Store<Active, Action, Output>(Active(), backgroundScope.coroutineContext) {
            plugin(Plugin(onEvent = { _, event -> events += event }))
            state<Active> {
                action<Start> {
                    launch(control = LaunchControl.CancelPrevious(lane)) {
                        try { awaitCancellation() } finally {
                            withContext(NonCancellable) { event(Output) }
                        }
                    }
                }
                action<Stop> { cancelLaunch(lane) }
            }
        }
        try {
            store.dispatchAndAwaitForTest(Start)
            runCurrent()
            store.dispatchAndAwaitForTest(Stop)
            runCurrent()
            assertEquals(emptyList(), events)
        } finally {
            store.close()
        }
    }

    @Test
    fun cancelledLaunchFailureCannotRecoverOverTheNextRequest() = runTest {
        val lane = LaunchLane()
        val reported = mutableListOf<Throwable>()
        val store = Store<Active, Action, Nothing>(Active(), backgroundScope.coroutineContext) {
            exceptionHandler(ExceptionHandler { reported += it })
            state<Active> {
                action<Start> {
                    launch(control = LaunchControl.CancelPrevious(lane)) {
                        try { awaitCancellation() } finally { error("cleanup failed") }
                    }
                }
                action<Stop> {
                    cancelLaunch(lane)
                    nextState { Active(2) }
                }
                recover<IllegalStateException> { nextState { Active(-1) } }
            }
        }
        try {
            store.dispatchAndAwaitForTest(Start)
            runCurrent()
            store.dispatchAndAwaitForTest(Stop)
            runCurrent()
            assertEquals(Active(2), store.currentState)
            assertEquals("cleanup failed", reported.single().message)
        } finally {
            store.close()
        }
    }

    @Test
    fun exitedAndClosedLaunchesCannotEmitFromNonCancellableCleanup() = runTest {
        for (close in listOf(false, true)) {
            val observed = mutableListOf<Output>()
            val recorded = mutableListOf<Output>()
            val store = Store<State, Stop, Output>(Active(), backgroundScope.coroutineContext) {
                plugin(Plugin(onEvent = { _, event -> recorded += event }))
                state<Active> {
                    enter {
                        launch {
                            try { awaitCancellation() } finally {
                                withContext(NonCancellable) { event(Output) }
                            }
                        }
                    }
                    action<Stop> { nextState { Off } }
                }
            }
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                store.event.collect { observed += it }
            }
            try {
                store.startAndAwaitForTest()
                runCurrent()
                if (close) store.close() else store.dispatchAndAwaitForTest(Stop)
                runCurrent()
                assertEquals(emptyList(), observed, "close=$close")
                assertEquals(emptyList(), recorded, "close=$close")
            } finally {
                store.close()
                collector.cancel()
            }
        }
    }

    @Test
    fun nestedTransactionsFailBeforeEnqueuingWork() = runTest {
        for (fromEnter in listOf(false, true)) {
            val outcome = CompletableDeferred<Throwable?>()
            var nestedRan = false
            val store = Store<Active, Start, Nothing>(Active(), backgroundScope.coroutineContext) {
                state<Active> {
                    if (fromEnter) enter {
                        launch {
                            val outer = this
                            transaction {
                                outcome.complete(withTimeoutOrNull(100) {
                                    runCatching { outer.transaction { nestedRan = true } }.exceptionOrNull()
                                })
                            }
                        }
                    }
                    action<Start> {
                        if (!fromEnter) launch {
                            val outer = this
                            transaction {
                                outcome.complete(withTimeoutOrNull(100) {
                                    runCatching { outer.transaction { nestedRan = true } }.exceptionOrNull()
                                })
                            }
                        }
                    }
                }
            }
            try {
                store.dispatchAndAwaitForTest(Start)
                val failure = outcome.await()
                runCurrent()
                assertIs<IllegalStateException>(failure, "fromEnter=$fromEnter")
                assertEquals(false, nestedRan)
                store.dispatchAndAwaitForTest(Start)
            } finally {
                store.close()
            }
        }
    }
}

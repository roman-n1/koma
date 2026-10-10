package actron.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class)
class StoreStartupExceptionJvmTest {
    private data object Active : State
    private data object Input : Action
    private data object Output : Event

    @Test
    fun aThrowingExceptionHandlerIsNotCalledAgainForTheInitialEnterEvent() {
        var reports = 0
        // JVM runTest rethrows uncaught coroutine failures synchronously when it finishes.
        assertFailsWith<Throwable> {
            runTest {
                val store = Store<Active, Input, Output>(Active, backgroundScope.coroutineContext) {
                    exceptionHandler(ExceptionHandler {
                        reports++
                        if (reports == 1) throw it
                    })
                    plugin(Plugin(onEvent = { _, _ -> error("event plugin failed") }))
                    state<Active> { enter { event(Output) } }
                }
                try {
                    store.startAndAwaitForTest()
                    runCurrent()
                } finally {
                    store.close()
                }
            }
        }
        assertEquals(1, reports)
    }
}

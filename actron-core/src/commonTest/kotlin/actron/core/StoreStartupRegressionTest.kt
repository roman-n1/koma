package actron.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class)
class StoreStartupRegressionTest {
    private data object Active : State
    private data object Input : Action

    @Test
    fun patchIsRejectedAsSoonAsStartupIsRequested() = runTest {
        for (dispatch in listOf(false, true)) {
            val store = Store<Active, Input, Nothing>(Active, backgroundScope.coroutineContext) { }
            try {
                if (dispatch) store.dispatch(Input) else store.start()
                assertFailsWith<IllegalStateException> { store.patchForTest { plugin(Plugin()) } }
            } finally {
                store.close()
            }
        }
    }

    @Test
    fun patchIsRejectedAfterPartialPluginStartup() = runTest {
        val store = Store<Active, Input, Nothing>(Active, backgroundScope.coroutineContext) {
            exceptionHandler(ExceptionHandler.Ignore)
            plugin(Plugin(), Plugin(onStart = { error("not started") }))
        }
        try {
            store.startAndAwaitForTest()
            assertFailsWith<IllegalStateException> { store.patchForTest { replacePlugins(Plugin()) } }
        } finally {
            store.close()
        }
    }
}

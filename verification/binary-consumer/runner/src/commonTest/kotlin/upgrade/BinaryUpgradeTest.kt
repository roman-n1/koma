package upgrade

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class BinaryUpgradeTest {
    @Test
    fun frozenStableClientRunsWithoutRecompilation() = runTest(timeout = 30.seconds) {
        assertEquals("stable-4.0.0: state/action/launch/transaction/recover/plugin/saver/exit passed", legacy.exercise(coroutineContext))
    }
}

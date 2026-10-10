package actron.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class StoreInstanceIsolationTest {
    private data class Result(val value: Int = 0) : State
    private data class Load(val value: Int) : Action
    private data object Cancel : Action

    @Test
    fun sharedLaneAndActionTypeDoNotShareCancellationAcrossStores() = runTest {
        for (lane in listOf(null, LaunchLane())) {
            fun tab() = Store<Result, Action, Nothing>(Result(), backgroundScope.coroutineContext) {
                state<Result> {
                    action<Load> {
                        launch(control = LaunchControl.CancelPrevious(lane)) {
                            delay(100)
                            transaction { nextState { Result(action.value) } }
                        }
                    }
                    action<Cancel> { if (lane != null) cancelLaunch(lane) }
                }
            }
            val left = tab()
            val right = tab()
            try {
                left.dispatchAndAwaitForTest(Load(1))
                right.dispatchAndAwaitForTest(Load(2))
                runCurrent()
                left.dispatchAndAwaitForTest(Load(3))
                left.dispatchAndAwaitForTest(Cancel)
                left.close()
                advanceTimeBy(100)
                runCurrent()
                assertEquals(Result(), left.currentState)
                assertEquals(Result(2), right.currentState)
                right.dispatchAndAwaitForTest(Load(4))
                runCurrent()
                advanceTimeBy(100)
                runCurrent()
                assertEquals(Result(4), right.currentState)
            } finally {
                left.close()
                right.close()
            }
        }
    }
}

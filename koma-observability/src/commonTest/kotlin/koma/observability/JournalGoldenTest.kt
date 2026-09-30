@file:OptIn(ExperimentalKomaApi::class, ExperimentalCoroutinesApi::class, InternalKomaApi::class, ExperimentalTime::class)

package koma.observability

import koma.core.Action
import koma.core.Event
import koma.core.ExceptionHandler
import koma.core.ExperimentalKomaApi
import koma.core.InternalKomaApi
import koma.core.State
import koma.core.Store
import koma.core.StoreInternalApi
import koma.test.dispatchAndAwait
import koma.test.startAndAwait
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime
import kotlin.time.TestTimeSource

/**
 * The text form of the journal, pinned as a golden fixture, with the initial values of the
 * identities the handoff fixes by a golden test: the startup is input #1 and processing 1, the
 * first commit is revision 1, the group's first record is #1 and a store's first record is 1.
 * A scripted Store on a test time source produces exactly these lines under the production
 * payload policy. Changing them is changing the format: bump JOURNAL_FORMAT_VERSION.
 *
 * ```
 * Loading --enter--> Ready(count)
 * Ready --Increment--> Ready(count + 1)
 * Ready --Throw--> (recover) --> Failed
 * ```
 */
class JournalGoldenTest {

    sealed interface AppState : State {
        data object Loading : AppState
        data class Ready(val count: Int = 0) : AppState
        data class Failed(val message: String) : AppState
    }

    sealed interface AppAction : Action {
        data object Increment : AppAction
        data object Noop : AppAction
        data object Throw : AppAction
        data object Unknown : AppAction
    }

    sealed interface AppEvent : Event {
        data object Ping : AppEvent
    }

    @Test
    fun theScriptedRun_producesTheGoldenLines() = runTest {
        val timeSource = TestTimeSource()
        val session = RecordingSession(backgroundScope, id = RuntimeSessionId("golden"), group = MachineGroupId("g"), timeSource = timeSource)
        val store = Store<AppState, AppAction, AppEvent>(AppState.Loading) {
            coroutineContext(UnconfinedTestDispatcher(testScheduler))
            exceptionHandler(ExceptionHandler.Ignore)
            recordTo(session, StoreInstanceId("s"), PayloadPolicy.metadataOnly())
            state<AppState.Loading> { enter { nextState { AppState.Ready() } } }
            state<AppState.Ready> {
                action<AppAction.Increment> {
                    event(AppEvent.Ping)
                    nextState { state.copy(count = state.count + 1) }
                }
                action<AppAction.Noop> { }
                action<AppAction.Throw> { throw IllegalStateException("secret message") }
                recover<IllegalStateException> { nextState { AppState.Failed("recovered") } }
            }
        }

        store.startAndAwait()
        store.dispatchAndAwait(AppAction.Increment)
        store.dispatchAndAwait(AppAction.Noop)
        (store as StoreInternalApi<AppState, AppAction, AppEvent>).dispatchIf(AppAction.Increment) { false }
        store.dispatchAndAwait(AppAction.Unknown)
        store.dispatchAndAwait(AppAction.Throw)
        store.close()
        session.close()

        val lines = session.records().map { JournalFormat.line(it) }
        assertEquals(GOLDEN, lines, "the journal's text format or initial values changed; if intended, bump JOURNAL_FORMAT_VERSION.\nACTUAL:\n${lines.joinToString("\n")}")
    }

    companion object {
        val GOLDEN: List<String> = listOf(
            "[golden g s #1/1 +0s] StoreRegistered InspectOnly",
            "[golden g s #2/2 +0s] InputAccepted #1 Startup",
            "[golden g s #3/3 +0s] ProcessingStarted #1 ordinal=1",
            "[golden g s #4/4 +0s] StateCommitted #1 revision=1 - <- -",
            "[golden g s #5/5 +0s] ProcessingFinished #1 ordinal=1 Handled commits=1 in 0s",
            "[golden g s #6/6 +0s] InputAccepted #2 Dispatch -",
            "[golden g s #7/7 +0s] ProcessingStarted #2 ordinal=2",
            "[golden g s #8/8 +0s] EventEmitted #2 -",
            "[golden g s #9/9 +0s] StateCommitted #2 revision=2 - <- -",
            "[golden g s #10/10 +0s] ProcessingFinished #2 ordinal=2 Handled commits=1 in 0s",
            "[golden g s #11/11 +0s] InputAccepted #3 Dispatch -",
            "[golden g s #12/12 +0s] ProcessingStarted #3 ordinal=3",
            "[golden g s #13/13 +0s] ProcessingFinished #3 ordinal=3 Unchanged in 0s",
            "[golden g s #14/14 +0s] InputAccepted #4 Dispatch -",
            "[golden g s #15/15 +0s] InputDiscarded #4 Stale",
            "[golden g s #16/16 +0s] InputAccepted #5 Dispatch -",
            "[golden g s #17/17 +0s] ProcessingStarted #5 ordinal=4",
            "[golden g s #18/18 +0s] ProcessingFinished #5 ordinal=4 Ignored in 0s",
            "[golden g s #19/19 +0s] InputAccepted #6 Dispatch -",
            "[golden g s #20/20 +0s] ProcessingStarted #6 ordinal=5",
            "[golden g s #21/21 +0s] StateCommitted #6 revision=3 - <- -",
            "[golden g s #22/22 +0s] ProcessingFinished #6 ordinal=5 Recovered commits=1 IllegalStateException in 0s",
            "[golden g s #23/23 +0s] StoreClosed",
            "[golden g #24 +0s] RecordingStopped",
        )
    }
}

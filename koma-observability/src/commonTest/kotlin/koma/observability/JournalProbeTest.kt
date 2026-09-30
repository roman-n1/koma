@file:OptIn(ExperimentalKomaApi::class, ExperimentalCoroutinesApi::class, InternalKomaApi::class, ExperimentalTime::class)

package koma.observability

import koma.core.Action
import koma.core.Event
import koma.core.ExceptionHandler
import koma.core.ExperimentalKomaApi
import koma.core.InputId
import koma.core.InternalKomaApi
import koma.core.State
import koma.core.Store
import koma.core.StoreInternalApi
import koma.test.createRecorder
import koma.test.dispatchAndAwait
import koma.test.startAndAwait
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlin.time.TestTimeSource

/**
 * From a running Store to journal records: the entries a recorded Store produces, the policy
 * applied before retention, durations, several Stores in one group, and that recording changes
 * nothing about what the Store does.
 *
 * ```
 * Loading --enter--> Ready(count)
 * Ready --Increment--> Ready(count + 1)
 * Ready --Throw--> (recover) --> Failed(message)
 * ```
 */
class JournalProbeTest {

    sealed interface AppState : State {
        data object Loading : AppState
        data class Ready(val count: Int = 0) : AppState
        data class Failed(val message: String) : AppState

        /** A state whose text must never reach a production journal. */
        data class Secret(val token: String) : AppState {
            override fun toString(): String = "Secret(token=$token)"
        }
    }

    sealed interface AppAction : Action {
        data object Increment : AppAction
        data object Throw : AppAction
        data object ThrowUnrecovered : AppAction
        data object Unknown : AppAction
        data object Reveal : AppAction
        data object Slow : AppAction
    }

    sealed interface AppEvent : Event {
        data object Ping : AppEvent
    }

    private val timeSource = TestTimeSource()

    private fun TestScope.session(policy: PayloadPolicy<AppState, AppAction, AppEvent>? = null, config: JournalConfig = JournalConfig()): RecordingSession =
        RecordingSession(backgroundScope, id = RuntimeSessionId("s"), group = MachineGroupId("g"), config = config, timeSource = timeSource)

    private fun createStore(
        session: RecordingSession?,
        store: StoreInstanceId,
        policy: PayloadPolicy<AppState, AppAction, AppEvent> = PayloadPolicy.metadataOnly(),
        exceptionHandler: ExceptionHandler = ExceptionHandler.Rethrow,
    ): Store<AppState, AppAction, AppEvent> = Store(AppState.Loading) {
        coroutineContext(UnconfinedTestDispatcher())
        exceptionHandler(exceptionHandler)
        if (session != null) recordTo(session, store, policy)
        state<AppState.Loading> {
            enter { nextState { AppState.Ready() } }
        }
        state<AppState.Ready> {
            action<AppAction.Increment> {
                event(AppEvent.Ping)
                nextState { state.copy(count = state.count + 1) }
            }
            action<AppAction.Throw> { throw IllegalStateException("boom") }
            action<AppAction.ThrowUnrecovered> { throw ArithmeticException("unrecovered") }
            action<AppAction.Reveal> { nextState { AppState.Secret("t0p-s3cret") } }
            action<AppAction.Slow> {
                timeSource += 7.milliseconds
                nextState { state.copy(count = state.count + 10) }
            }
            recover<IllegalStateException> { nextState { AppState.Failed(error.message ?: "?") } }
        }
    }

    private fun List<JournalRecord<*, *, *>>.entries() = map { it.entry }

    private inline fun <reified T : JournalEntry<*, *, *>> List<JournalRecord<*, *, *>>.entry(): T = map { it.entry }.filterIsInstance<T>().single()

    @Test
    fun aRecordedStore_producesTheRegistrationFirst_thenTheProcessingEntriesInOrder() = runTest {
        val session = session()
        val store = createStore(session, StoreInstanceId("chat-1"), PayloadPolicy.retainAll())

        store.startAndAwait()
        store.dispatchAndAwait(AppAction.Increment)

        val startup = InputId(1)
        val increment = InputId(2)
        assertEquals(
            listOf(
                JournalEntry.StoreRegistered(Capability.InspectOnly),
                JournalEntry.InputAccepted(startup, InputDescriptor.Startup),
                JournalEntry.ProcessingStarted(startup, 1),
                JournalEntry.StateCommitted(startup, 1, Payload.Retained(AppState.Loading), Payload.Retained(AppState.Ready())),
                JournalEntry.ProcessingFinished(startup, 1, OutcomeDescriptor(OutcomeKind.Handled, commits = 1), 0.milliseconds),
                JournalEntry.InputAccepted(increment, InputDescriptor.Dispatch(Payload.Retained(AppAction.Increment))),
                JournalEntry.ProcessingStarted(increment, 2),
                JournalEntry.EventEmitted(increment, Payload.Retained(AppEvent.Ping)),
                JournalEntry.StateCommitted(increment, 2, Payload.Retained(AppState.Ready(0)), Payload.Retained(AppState.Ready(1))),
                JournalEntry.ProcessingFinished(increment, 2, OutcomeDescriptor(OutcomeKind.Handled, commits = 1), 0.milliseconds),
            ),
            session.records().entries(),
        )
        val records = session.records()
        assertTrue(records.all { it.store == StoreInstanceId("chat-1") && it.session == RuntimeSessionId("s") && it.group == MachineGroupId("g") })
        assertEquals((1L..records.size).toList(), records.map { it.groupSeq.value })
        assertEquals((1L..records.size).toList(), records.map { it.storeSeq?.value })
        store.close()
    }

    @Test
    fun processingDuration_isMeasuredWithTheSessionTimeSource() = runTest {
        val session = session()
        val store = createStore(session, StoreInstanceId("chat-1"))
        store.startAndAwait()

        store.dispatchAndAwait(AppAction.Slow)

        val finished = session.records().entries().filterIsInstance<JournalEntry.ProcessingFinished>().last()
        assertEquals(7.milliseconds, finished.duration)
        store.close()
    }

    @Test
    fun metadataOnly_keepsNothingOfAState_soASecretNeverReachesTheJournal() = runTest {
        val session = session()
        val store = createStore(session, StoreInstanceId("chat-1"))
        store.startAndAwait()

        store.dispatchAndAwait(AppAction.Reveal)

        val committed = session.records().entries().filterIsInstance<JournalEntry.StateCommitted<*>>().last()
        assertEquals(Payload.Omitted, committed.state)
        assertEquals(Payload.Omitted, committed.previous)
        val text = session.records().joinToString("\n") { JournalFormat.line(it) }
        assertTrue("s3cret" !in text, text)
        assertTrue(session.records().none { record -> (record.entry as? JournalEntry.StateCommitted<*>)?.state is Payload.Retained<*> })
        store.close()
    }

    @Test
    fun failures_areDescribedByThePolicy_withoutMessagesByDefault() = runTest {
        val session = session()
        val handled = mutableListOf<Throwable>()
        val store = createStore(session, StoreInstanceId("chat-1"), exceptionHandler = ExceptionHandler { handled += it })
        store.startAndAwait()

        store.dispatchAndAwait(AppAction.ThrowUnrecovered)
        store.dispatchAndAwait(AppAction.Throw)
        val internal = store as StoreInternalApi<AppState, AppAction, AppEvent>
        internal.dispatchIf(AppAction.Increment) { throw IllegalArgumentException("bad predicate") }
        advanceUntilIdle()

        val finished = session.records().entries().filterIsInstance<JournalEntry.ProcessingFinished>()
        val recovered = finished.single { it.outcome.kind == OutcomeKind.Recovered }
        assertEquals(FailureDescriptor(type = "IllegalStateException"), recovered.outcome.failure)
        assertEquals(1, recovered.outcome.commits)
        val failed = finished.single { it.outcome.kind == OutcomeKind.Failed }
        assertEquals(FailureDescriptor(type = "ArithmeticException"), failed.outcome.failure)
        val reported = session.records().entries().filterIsInstance<JournalEntry.FailureReported>()
        assertEquals(listOf("ArithmeticException", "IllegalArgumentException"), reported.map { it.failure.type })
        val discarded = session.records().entry<JournalEntry.InputDiscarded>()
        assertEquals(DiscardDescriptor(DiscardKind.Rejected, FailureDescriptor(type = "IllegalArgumentException")), discarded.reason)
        assertEquals(2, handled.size)
        val text = session.records().joinToString("\n") { JournalFormat.line(it) }
        assertTrue("boom" !in text && "unrecovered" !in text && "bad predicate" !in text, text)
        store.close()
    }

    @Test
    fun aProjectingPolicy_keepsOnlyWhatItProjects() = runTest {
        val session = session()
        val policy = PayloadPolicy<AppState, AppAction, AppEvent>(
            state = { state ->
                when (state) {
                    is AppState.Ready -> Payload.Projected("Ready", mapOf("count" to state.count.toString()))
                    else -> Payload.Projected(state::class.simpleName ?: "?")
                }
            },
            action = { Payload.Projected(it::class.simpleName ?: "?") },
        )
        val store = createStore(session, StoreInstanceId("chat-1"), policy)
        store.startAndAwait()

        store.dispatchAndAwait(AppAction.Increment)

        val committed = session.records().entries().filterIsInstance<JournalEntry.StateCommitted<*>>().last()
        assertEquals(Payload.Projected("Ready", mapOf("count" to "1")), committed.state)
        val accepted = session.records().entries().filterIsInstance<JournalEntry.InputAccepted<*>>().last()
        assertEquals(InputDescriptor.Dispatch(Payload.Projected("Increment")), accepted.kind)
        assertEquals(Payload.Omitted, session.records().entry<JournalEntry.EventEmitted<*>>().event, "the event rule kept the default")
        store.close()
    }

    @Test
    fun aThrowingPolicy_makesThePayloadUnavailable_reportsTheFailure_andTheRecordIsStillPublished() = runTest {
        val failures = mutableListOf<JournalFailure>()
        val session = RecordingSession(backgroundScope, config = JournalConfig(onFailure = { failures += it }), timeSource = timeSource)
        val policy = PayloadPolicy<AppState, AppAction, AppEvent>(state = { throw IllegalStateException("codec") })
        val handled = mutableListOf<Throwable>()
        val store = createStore(session, StoreInstanceId("chat-1"), policy, exceptionHandler = ExceptionHandler { handled += it })

        store.startAndAwait()

        val committed = session.records().entry<JournalEntry.StateCommitted<*>>()
        assertEquals(Payload.Unavailable, committed.previous)
        assertEquals(Payload.Unavailable, committed.state)
        assertEquals(2, session.stats.policyFailures)
        assertEquals(2, failures.count { it is JournalFailure.PolicyFailed && it.store == StoreInstanceId("chat-1") })
        assertEquals(emptyList(), handled, "a policy failure is the journal's, not the Store's")
        assertEquals(AppState.Ready(), store.currentState)
        store.close()
    }

    @Test
    fun twoStoresInOneGroup_shareOneDenseGroupSequence_andKeepTheirOwnStoreSequences() = runTest {
        val session = session()
        val first = createStore(session, StoreInstanceId("tab-1"))
        val second = createStore(session, StoreInstanceId("tab-2"))

        first.startAndAwait()
        second.startAndAwait()
        first.dispatchAndAwait(AppAction.Increment)
        second.dispatchAndAwait(AppAction.Increment)
        second.dispatchAndAwait(AppAction.Increment)

        val records = session.records()
        assertEquals((1L..records.size).toList(), records.map { it.groupSeq.value })
        val ofFirst = records.filter { it.store == StoreInstanceId("tab-1") }
        val ofSecond = records.filter { it.store == StoreInstanceId("tab-2") }
        assertEquals((1L..ofFirst.size).toList(), ofFirst.map { it.storeSeq?.value })
        assertEquals((1L..ofSecond.size).toList(), ofSecond.map { it.storeSeq?.value })
        assertTrue(ofSecond.size > ofFirst.size)
        assertEquals(AppState.Ready(1), first.currentState)
        assertEquals(AppState.Ready(2), second.currentState)
        first.close()
        second.close()
    }

    @Test
    fun recording_doesNotChangeWhatTheStoreDoes() = runTest {
        val scenario = listOf(AppAction.Increment, AppAction.Unknown, AppAction.Throw, AppAction.Increment, AppAction.Slow)
        suspend fun run(session: RecordingSession?): List<AppState> {
            val store = createStore(session, StoreInstanceId("chat-1"), PayloadPolicy.retainAll(), exceptionHandler = ExceptionHandler.Ignore)
            val recorder = store.createRecorder()
            store.startAndAwait()
            scenario.forEach { store.dispatchAndAwait(it) }
            store.close()
            return recorder.states
        }

        val withoutJournal = run(null)
        val session = session()
        val withJournal = run(session)

        assertEquals(withoutJournal, withJournal)
        assertEquals(listOf(AppState.Loading, AppState.Ready(0), AppState.Ready(1), AppState.Failed("boom")), withoutJournal)
        assertNull(session.records().entries().filterIsInstance<JournalEntry.JournalGap>().firstOrNull())
        assertEquals(JournalEntry.StoreClosed, session.records().entries().last())
    }
}

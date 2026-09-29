@file:OptIn(ExperimentalKomaApi::class, ExperimentalCoroutinesApi::class)

package koma.logging

import koma.core.Action
import koma.core.Event
import koma.core.ExceptionHandler
import koma.core.ExperimentalKomaApi
import koma.core.State
import koma.core.Store
import koma.observability.MachineGroupId
import koma.observability.PayloadPolicy
import koma.observability.RecordingSession
import koma.observability.RuntimeSessionId
import koma.observability.StoreInstanceId
import koma.observability.recordTo
import koma.test.dispatchAndAwait
import koma.test.startAndAwait
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoggerJournalSinkTest {

    private data class Line(val severity: Logger.Severity, val tag: String, val message: String)

    private data class Account(val token: String) : State {
        override fun toString(): String = "Account(token=$token)"
    }

    private sealed interface AccountAction : Action {
        data object Refresh : AccountAction
        data object Fail : AccountAction
    }

    private class TestLogger : Logger {
        val lines = mutableListOf<Line>()

        override fun log(severity: Logger.Severity, tag: String, throwable: Throwable?, message: () -> String) {
            lines += Line(severity, tag, message())
        }
    }

    private fun createStore(policy: PayloadPolicy<Account, AccountAction, Nothing>, session: RecordingSession): Store<Account, AccountAction, Nothing> =
        Store(Account("t0p-s3cret")) {
            coroutineContext(UnconfinedTestDispatcher())
            exceptionHandler(ExceptionHandler.Ignore)
            recordTo(session, StoreInstanceId("account"), policy)
            state<Account> {
                action<AccountAction.Refresh> { nextState { state.copy(token = state.token + "!") } }
                action<AccountAction.Fail> { throw IllegalStateException("token t0p-s3cret rejected") }
            }
        }

    @Test
    fun writesOneLinePerRecord_withTheDefaultSeverities_andNoPayloadUnderMetadataOnly() = runTest {
        val logger = TestLogger()
        val session = RecordingSession(
            backgroundScope,
            id = RuntimeSessionId("s1"),
            group = MachineGroupId("chat"),
            sinks = listOf(LoggerJournalSink(logger = logger, tag = "Journal")),
        )
        val store = createStore(PayloadPolicy.metadataOnly(), session)

        store.startAndAwait()
        store.dispatchAndAwait(AccountAction.Refresh)
        store.dispatchAndAwait(AccountAction.Fail)
        store.close()
        session.close()

        assertTrue(logger.lines.all { it.tag == "Journal" })
        assertTrue(logger.lines.none { "s3cret" in it.message }, logger.lines.joinToString("\n") { it.message })
        val messages = logger.lines.map { it.message.substringAfter("] ") }
        assertEquals(
            listOf(
                "StoreRegistered InspectOnly",
                "InputAccepted #1 Startup",
                "ProcessingStarted #1 ordinal=1",
                "ProcessingFinished #1 ordinal=1 Unchanged in 0s",
                "InputAccepted #2 Dispatch -",
                "ProcessingStarted #2 ordinal=2",
                "StateCommitted #2 revision=1 - <- -",
                "ProcessingFinished #2 ordinal=2 Handled commits=1 in 0s",
                "InputAccepted #3 Dispatch -",
                "ProcessingStarted #3 ordinal=3",
                "ProcessingFinished #3 ordinal=3 Failed IllegalStateException in 0s",
                "FailureReported #3 IllegalStateException",
                "StoreClosed",
                "RecordingStopped",
            ),
            messages.map { it.replace(Regex(" in \\S+$"), " in 0s") },
        )
        assertTrue(logger.lines.first().message.startsWith("[s1 chat account #1/1 +"))
        assertEquals(Logger.Severity.Warn, logger.lines.single { it.message.contains("FailureReported") }.severity)
        assertTrue(logger.lines.filter { !it.message.contains("FailureReported") }.all { it.severity == Logger.Severity.Debug })
    }

    @Test
    fun retainAll_printsTheObjects_becauseThePolicyAllowedIt() = runTest {
        val logger = TestLogger()
        val session = RecordingSession(backgroundScope, sinks = listOf(LoggerJournalSink(logger = logger)))
        val store = createStore(PayloadPolicy.retainAll(), session)

        store.startAndAwait()
        store.dispatchAndAwait(AccountAction.Refresh)
        store.close()
        session.close()

        assertTrue(logger.lines.any { "StateCommitted #2 revision=1 Account(token=t0p-s3cret!) <- Account(token=t0p-s3cret)" in it.message })
        assertTrue(logger.lines.any { "Dispatch Refresh" in it.message })
    }
}

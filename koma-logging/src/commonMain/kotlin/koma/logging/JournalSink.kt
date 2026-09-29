package koma.logging

import koma.core.ExperimentalKomaApi
import koma.observability.JournalEntry
import koma.observability.JournalFormat
import koma.observability.JournalRecord
import koma.observability.JournalSink

/**
 * Writes the records of a `RecordingSession` to a [Logger], one line per record in
 * [JournalFormat.line] by default.
 *
 * Unlike [simpleLogging], what reaches the logger is what the session's `PayloadPolicy` kept:
 * with the production default nothing of a state, action or event, and of a failure only its
 * class names. The sink runs on the session's writer coroutine, in `GroupSeq` order, never on a
 * Store's thread.
 *
 * @param logger The logger to write to
 * @param tag The tag of every line
 * @param severity The severity of a record; by default failures and gaps are warnings, discards
 * are informational and everything else is debug
 * @param format The line to write for a record
 */
@ExperimentalKomaApi
class LoggerJournalSink(
    private val logger: Logger = DefaultLogger,
    private val tag: String = "Koma",
    private val severity: (JournalRecord<*, *, *>) -> Logger.Severity = ::defaultSeverity,
    private val format: (JournalRecord<*, *, *>) -> String = JournalFormat::line,
) : JournalSink {
    override suspend fun write(record: JournalRecord<*, *, *>) {
        logger.log(severity = severity(record), tag = tag, throwable = null) { format(record) }
    }

    companion object {
        /**
         * Warnings for [JournalEntry.FailureReported] and [JournalEntry.JournalGap], info for
         * [JournalEntry.InputDiscarded], debug for the rest.
         */
        fun defaultSeverity(record: JournalRecord<*, *, *>): Logger.Severity = when (record.entry) {
            is JournalEntry.FailureReported, is JournalEntry.JournalGap -> Logger.Severity.Warn
            is JournalEntry.InputDiscarded -> Logger.Severity.Info
            else -> Logger.Severity.Debug
        }
    }
}

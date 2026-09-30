package koma.observability

import koma.core.ExperimentalKomaApi

/**
 * One-line text rendering of records, for loggers and quick inspection. It prints only what the
 * records hold: a [Payload.Retained] payload prints its `toString()`, because the policy that
 * retained it decided the object may be shown; an omitted payload prints as `-`.
 */
@ExperimentalKomaApi
object JournalFormat {
    /**
     * `[session group store #groupSeq/storeSeq +elapsed] entry`; the store part is absent for
     * records of the session itself.
     */
    fun line(record: JournalRecord<*, *, *>): String = buildString {
        append('[').append(record.session.value).append(' ').append(record.group.value)
        record.store?.let { append(' ').append(it.value) }
        append(' ').append(record.groupSeq)
        record.storeSeq?.let { append('/').append(it.value) }
        if (record.mode != ExecutionMode.Live) append(' ').append(record.mode)
        append(" +").append(record.elapsed).append("] ")
        append(entry(record.entry))
    }

    /**
     * The entry alone, without the envelope.
     */
    fun entry(entry: JournalEntry<*, *, *>): String = when (entry) {
        is JournalEntry.StoreRegistered -> "StoreRegistered ${entry.capability}"
        is JournalEntry.InputAccepted -> "InputAccepted ${entry.input} ${input(entry.kind)}"
        is JournalEntry.InputDiscarded -> "InputDiscarded ${entry.input} ${entry.reason.kind}${entry.reason.failure?.let { " " + failure(it) } ?: ""}"
        is JournalEntry.ProcessingStarted -> "ProcessingStarted ${entry.input} ordinal=${entry.ordinal}"
        is JournalEntry.StateCommitted -> "StateCommitted ${entry.input} revision=${entry.revision} ${payload(entry.state)} <- ${payload(entry.previous)}"
        is JournalEntry.EventEmitted -> "EventEmitted ${entry.input ?: "?"} ${payload(entry.event)}"
        is JournalEntry.FailureReported -> "FailureReported ${entry.input ?: "?"} ${failure(entry.failure)}"
        is JournalEntry.ProcessingFinished -> "ProcessingFinished ${entry.input} ordinal=${entry.ordinal} ${outcome(entry.outcome)} in ${entry.duration}"
        JournalEntry.StoreClosed -> "StoreClosed"
        is JournalEntry.InputRejected -> "InputRejected ${payload(entry.action)} ${entry.reason}"
        is JournalEntry.DecisionCommitted -> buildString {
            append("DecisionCommitted ").append(entry.input ?: "?").append(" revision=").append(entry.revision)
            append(" active=").append(entry.active.joinToString(",", "[", "]"))
            if (entry.transitions.isNotEmpty()) append(" transitions=").append(entry.transitions.joinToString(",", "[", "]") { "T$it" })
            if (entry.exited.isNotEmpty()) append(" exited=").append(entry.exited.joinToString(",", "[", "]") { "${it.node}/a${it.activation}" })
            if (entry.entered.isNotEmpty()) append(" entered=").append(entry.entered.joinToString(",", "[", "]") { "${it.node}/a${it.activation}" })
            if (entry.commands.isNotEmpty()) append(" commands=").append(entry.commands.joinToString(",", "[", "]") { "c${it.id}@a${it.scope}${it.lane?.let { l -> " $l/${it.policy}" } ?: ""} ${payload(it.command)}" })
            if (entry.cancelledScopes.isNotEmpty()) append(" cancelled=").append(entry.cancelledScopes.joinToString(",", "[", "]") { "a$it" })
            if (entry.timersScheduled.isNotEmpty()) append(" timers=").append(entry.timersScheduled.joinToString(",", "[", "]") { "t${it.id}:T${it.transition}@a${it.activation}+${it.deadline}" })
            if (entry.timersCancelled.isNotEmpty()) append(" timersCancelled=").append(entry.timersCancelled.joinToString(",", "[", "]") { "t$it" })
            if (entry.effects > 0) append(" effects=").append(entry.effects)
        }
        is JournalEntry.DecisionIgnored -> "DecisionIgnored ${entry.input ?: "?"} ${entry.reason}"
        is JournalEntry.BridgeSent -> "BridgeSent ${entry.input ?: "?"} ${entry.message} -> ${entry.to}${if (entry.delivered) "" else " undelivered"}"
        is JournalEntry.BridgeReceived -> "BridgeReceived ${entry.input ?: "?"} ${entry.message}"
        is JournalEntry.EffectQueued -> "EffectQueued ${entry.input ?: "?"} e${entry.effect} ${entry.policy} ${payload(entry.event)}"
        is JournalEntry.EffectHandlingStarted -> "EffectHandlingStarted e${entry.effect} attempt=${entry.attempt}"
        is JournalEntry.EffectAcknowledged -> "EffectAcknowledged e${entry.effect}"
        is JournalEntry.EffectDiscarded -> "EffectDiscarded e${entry.effect} ${entry.reason}"
        is JournalEntry.ExternalReceived -> "ExternalReceived ${entry.input ?: "?"} from ${entry.source}"
        is JournalEntry.CheckpointCreated -> "CheckpointCreated members=${entry.members.joinToString(",", "[", "]")} sources=${entry.sources.joinToString(",", "[", "]")} inFlight=${entry.inFlight}"
        is JournalEntry.CommandsAbandoned -> "CommandsAbandoned ${entry.reason} queued=${entry.queued.joinToString(",", "[", "]") { "c$it" }} running=${entry.running.joinToString(",", "[", "]") { "c$it" }}"
        is JournalEntry.JournalGap -> "JournalGap dropped=${entry.dropped}"
        JournalEntry.RecordingStopped -> "RecordingStopped"
    }

    /**
     * A payload: the retained object's `toString()` or the text a file kept of it, a projection's
     * label and fields, `-` when omitted, `?` when unavailable.
     */
    fun payload(payload: Payload<*>): String = when (payload) {
        is Payload.Retained -> payload.value.toString()
        is Payload.Described -> payload.text
        is Payload.Projected -> if (payload.fields.isEmpty()) payload.label else payload.label + payload.fields.entries.joinToString(prefix = "(", postfix = ")") { "${it.key}=${it.value}" }
        Payload.Omitted -> "-"
        Payload.Unavailable -> "?"
    }

    /**
     * A failure: `Type: message <- Cause` with `+n suppressed` when there are any.
     */
    fun failure(failure: FailureDescriptor): String = buildString {
        append(failure.type ?: "failure")
        failure.message?.let { append(": ").append(it) }
        failure.cause?.let { append(" <- ").append(failure(it)) }
        if (failure.suppressed.isNotEmpty()) append(" +").append(failure.suppressed.size).append(" suppressed")
    }

    private fun input(kind: InputDescriptor<*>): String = when (kind) {
        InputDescriptor.Startup -> "Startup"
        is InputDescriptor.Dispatch -> "Dispatch ${payload(kind.action)}"
        is InputDescriptor.Transaction -> "Transaction origin=${kind.origin ?: "?"}"
        is InputDescriptor.Recovery -> "Recovery origin=${kind.origin ?: "?"} ${failure(kind.failure)}"
    }

    private fun outcome(outcome: OutcomeDescriptor): String = buildString {
        append(outcome.kind)
        if (outcome.commits > 0) append(" commits=").append(outcome.commits)
        outcome.failure?.let { append(' ').append(failure(it)) }
    }
}

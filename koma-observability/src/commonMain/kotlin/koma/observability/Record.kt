@file:OptIn(InternalKomaApi::class)

package koma.observability

import koma.core.Action
import koma.core.Event
import koma.core.ExperimentalKomaApi
import koma.core.InputId
import koma.core.InternalKomaApi
import koma.core.State
import kotlin.time.Duration

/**
 * Version of the record model below. Bumped when a field or a variant changes meaning; a reader
 * that meets a higher version must not guess.
 */
const val JOURNAL_FORMAT_VERSION: Int = 1

/**
 * One record of the journal: the envelope every record shares, and the typed [entry].
 *
 * @property formatVersion [JOURNAL_FORMAT_VERSION] at the time of recording
 * @property session The run this record belongs to
 * @property group The group whose [groupSeq] orders this record
 * @property store The Store this record is about; `null` for a record of the session itself
 * ([JournalEntry.JournalGap], [JournalEntry.RecordingStopped])
 * @property mode Whether the Store ran live or in a replay
 * @property groupSeq Dense position in the group; the order of records
 * @property storeSeq Dense position among the records of [store]; `null` when [store] is
 * @property elapsed Monotonic time since the session started, read under the same lock that
 * assigned [groupSeq], so it never decreases along the sequence. Diagnostic only: the order is
 * [groupSeq], not time
 */
@ExperimentalKomaApi
data class JournalRecord<out S : State, out A : Action, out E : Event>(
    val formatVersion: Int,
    val session: RuntimeSessionId,
    val group: MachineGroupId,
    val store: StoreInstanceId?,
    val mode: ExecutionMode,
    val groupSeq: GroupSeq,
    val storeSeq: StoreSeq?,
    val elapsed: Duration,
    val entry: JournalEntry<S, A, E>,
)

/**
 * What a record says. The variants mirror the processing boundaries a `StoreProbe` sees, with
 * payloads and failures already passed through the [PayloadPolicy], plus the records of the
 * recording itself.
 */
@ExperimentalKomaApi
sealed interface JournalEntry<out S : State, out A : Action, out E : Event> {
    /**
     * A Store started recording into the session, with what its recording can be used for.
     */
    data class StoreRegistered(val capability: Capability) : JournalEntry<Nothing, Nothing, Nothing>

    /**
     * The Store accepted [input]; see [InputDescriptor] for what it is.
     */
    data class InputAccepted<out A : Action>(val input: InputId, val kind: InputDescriptor<A>) : JournalEntry<Nothing, A, Nothing>

    /**
     * [input] will never be processed.
     */
    data class InputDiscarded(val input: InputId, val reason: DiscardDescriptor) : JournalEntry<Nothing, Nothing, Nothing>

    /**
     * Processing of [input] began; [ordinal] is the Store's dense processing number.
     */
    data class ProcessingStarted(val input: InputId, val ordinal: Long) : JournalEntry<Nothing, Nothing, Nothing>

    /**
     * A snapshot was committed while processing [input]; [revision] is the Store's dense commit
     * number. [previous] and [state] are what the policy kept of the two snapshots.
     */
    data class StateCommitted<out S : State>(val input: InputId, val revision: Long, val previous: Payload<S>, val state: Payload<S>) : JournalEntry<S, Nothing, Nothing>

    /**
     * An event was emitted by the processing of [input], or by a coroutine it launched.
     */
    data class EventEmitted<out E : Event>(val input: InputId?, val event: Payload<E>) : JournalEntry<Nothing, Nothing, E>

    /**
     * A failure reached the Store's exception handler while processing [input], or from a
     * coroutine it launched; `null` when the Store could not tell.
     */
    data class FailureReported(val input: InputId?, val failure: FailureDescriptor) : JournalEntry<Nothing, Nothing, Nothing>

    /**
     * Processing of [input] ended with [outcome] after [duration], measured from its
     * [ProcessingStarted] with the session's time source.
     */
    data class ProcessingFinished(val input: InputId, val ordinal: Long, val outcome: OutcomeDescriptor, val duration: Duration) : JournalEntry<Nothing, Nothing, Nothing>

    /**
     * Every coroutine of the Store has ended.
     */
    data object StoreClosed : JournalEntry<Nothing, Nothing, Nothing>

    /**
     * [dropped] published records before this one never reached the sinks: the writer's queue was
     * full. They may still be in the session's retained records. The gap record has a sequence
     * number of its own, so a sink sees the hole between the previous record and this one, and
     * the count tells whether the hole is fully explained.
     */
    data class JournalGap(val dropped: Long) : JournalEntry<Nothing, Nothing, Nothing>

    /**
     * The session stopped recording: nothing published after this reaches the sinks or the
     * retained records.
     */
    data object RecordingStopped : JournalEntry<Nothing, Nothing, Nothing>
}

/**
 * What an accepted input is, with the action or failure as the policy kept it.
 */
@ExperimentalKomaApi
sealed interface InputDescriptor<out A : Action> {
    data object Startup : InputDescriptor<Nothing>

    data class Dispatch<out A : Action>(val action: Payload<A>) : InputDescriptor<A>

    /** A `transaction {}` from a coroutine launched while processing [origin]. */
    data class Transaction(val origin: InputId?) : InputDescriptor<Nothing>

    /** The `recover {}` pass for a launched coroutine of [origin] that failed with [failure]. */
    data class Recovery(val failure: FailureDescriptor, val origin: InputId?) : InputDescriptor<Nothing>
}

/**
 * Why an input was discarded; the names are the wire vocabulary, stable across versions of the
 * core types they mirror.
 */
@ExperimentalKomaApi
enum class DiscardKind {
    StoreClosed,
    ClearedOnStateExit,
    ClearedExplicitly,
    Stale,
    Rejected,
    StartupFailed,
    LaunchInactive,
}

/**
 * A discard reason and, for [DiscardKind.Rejected] and [DiscardKind.StartupFailed], the failure
 * behind it as the policy described it.
 */
@ExperimentalKomaApi
data class DiscardDescriptor(val kind: DiscardKind, val failure: FailureDescriptor? = null)

/**
 * How a processing ended; the names are the wire vocabulary.
 */
@ExperimentalKomaApi
enum class OutcomeKind {
    Handled,
    Unchanged,
    Ignored,
    Recovered,
    Failed,
    Cancelled,
}

/**
 * An outcome with the number of snapshots it committed and, for [OutcomeKind.Recovered] and
 * [OutcomeKind.Failed], the failure as the policy described it.
 */
@ExperimentalKomaApi
data class OutcomeDescriptor(val kind: OutcomeKind, val commits: Int = 0, val failure: FailureDescriptor? = null)

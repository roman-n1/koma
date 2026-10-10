@file:OptIn(InternalActronApi::class)

package actron.observability

import actron.core.Action
import actron.core.Event
import actron.core.InputId
import actron.core.InternalActronApi
import actron.core.State
import kotlin.time.Duration

/**
 * Version of the record model below. Bumped when a field or a variant changes meaning; a reader
 * that meets a higher version must not guess.
 *
 * History: 1 had the Store's own entries and the machine's decisions; 2 added the bridge entries
 * ([JournalEntry.BridgeSent], [JournalEntry.BridgeReceived]); 3 added the effect mailbox entries
 * ([JournalEntry.EffectQueued], [JournalEntry.EffectHandlingStarted], [JournalEntry.EffectAcknowledged],
 * [JournalEntry.EffectDiscarded]); 4 added [JournalEntry.ExternalReceived] and
 * [JournalEntry.CheckpointCreated]; 5 added [JournalEntry.CommandsAbandoned]; 6 added
 * [JournalEntry.BridgeDropped]; 7 added [JournalEntry.BridgeSent.cause]. New variants only at
 * the end, under new tags, new fields only at the end of a variant; a reader of a version reads
 * every earlier one.
 */
const val JOURNAL_FORMAT_VERSION: Int = 7

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
     * An action was refused at admission, before it became an input: the store's pending queue
     * was full. Nothing of it was processed. [action] is what the policy kept of it.
     */
    data class InputRejected<out A : Action>(val action: Payload<A>, val reason: String) : JournalEntry<Nothing, A, Nothing>

    /**
     * The decision of a replay-ready machine behind the [StateCommitted] of the same [input]
     * and [revision]: which transitions were taken, which activations ended and began, which
     * commands were registered and which scopes cancelled, which timers were scheduled and
     * cancelled, and how many events were emitted. Ids are the machine's; node and lane names
     * are the chart's declared ids.
     */
    data class DecisionCommitted(
        val input: InputId?,
        val revision: Long,
        val active: List<String>,
        val transitions: List<Int>,
        val exited: List<ActivationRef>,
        val entered: List<ActivationRef>,
        val commands: List<CommandRef>,
        val cancelledScopes: List<Long>,
        val timersScheduled: List<TimerRef>,
        val timersCancelled: List<Long>,
        val effects: Int,
    ) : JournalEntry<Nothing, Nothing, Nothing>

    /**
     * A replay-ready machine ignored [input] for [reason] (the machine's `IgnoreReason` name):
     * nothing was committed.
     */
    data class DecisionIgnored(val input: InputId?, val reason: String) : JournalEntry<Nothing, Nothing, Nothing>

    /**
     * A bridge routed an effect of this Store to [to] as the message [message]; [delivered] is
     * false when [to] was not attached to the bridge, had closed or had left, so the message
     * went nowhere and the group's record is partial. [cause] is the bridge message this Store
     * was deciding when it emitted the effect, when it was one: the request a reply decided in
     * the same step replies to.
     */
    data class BridgeSent(val input: InputId?, val message: MessageRef, val to: StoreInstanceId, val delivered: Boolean, val cause: MessageRef? = null) : JournalEntry<Nothing, Nothing, Nothing>

    /**
     * This Store decided the bridge message [message] while processing [input]: the
     * `BridgeReceived` input the message became was handled, ignored or failed.
     */
    data class BridgeReceived(val input: InputId?, val message: MessageRef) : JournalEntry<Nothing, Nothing, Nothing>

    /**
     * The bridge message [message], delivered to [to] and not yet decided, never will be: the
     * store it was delivered to closed ([reason] `StoreClosed`). A record of [to]; nothing of
     * the message was processed, and it is not in flight any more.
     */
    data class BridgeDropped(val message: MessageRef, val to: StoreInstanceId, val reason: String) : JournalEntry<Nothing, Nothing, Nothing>

    /**
     * The effect [effect] of a decision made while processing [input] entered the Store's
     * mailbox under [policy] (the policy's name); [event] is what the describer kept of it.
     */
    data class EffectQueued<out E : Event>(val input: InputId?, val effect: Long, val policy: String, val event: Payload<E>) : JournalEntry<Nothing, Nothing, E>

    /** A subscriber took the effect [effect] from the mailbox, for the [attempt]th time. */
    data class EffectHandlingStarted(val effect: Long, val attempt: Int) : JournalEntry<Nothing, Nothing, Nothing>

    /** The effect [effect] was acknowledged: the UI did what it asked. */
    data class EffectAcknowledged(val effect: Long) : JournalEntry<Nothing, Nothing, Nothing>

    /** The mailbox gave up the effect [effect] for [reason] (the reason's name) without an acknowledgement. */
    data class EffectDiscarded(val effect: Long, val reason: String) : JournalEntry<Nothing, Nothing, Nothing>

    /**
     * This Store decided an input the external source [source] fed while processing [input].
     */
    data class ExternalReceived(val input: InputId?, val source: String) : JournalEntry<Nothing, Nothing, Nothing>

    /**
     * A consistent cut of the group was taken: [members] were frozen and idle, [sources] were
     * paused and snapshotted, [inFlight] bridge messages were on their way. A record of the
     * session itself, at the boundary the cut was taken at.
     */
    data class CheckpointCreated(val members: List<StoreInstanceId>, val sources: List<String>, val inFlight: Int) : JournalEntry<Nothing, Nothing, Nothing>

    /**
     * The Store closed with commands its executor had not finished, for [reason] (`StoreClosed`):
     * [queued] had been registered and never started, [running] were cancelled. Journaled once
     * by the executor as it stops, which may be before or after the Store's own [StoreClosed].
     */
    data class CommandsAbandoned(val reason: String, val queued: List<Long>, val running: List<Long>) : JournalEntry<Nothing, Nothing, Nothing>

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
data class DiscardDescriptor(val kind: DiscardKind, val failure: FailureDescriptor? = null)

/**
 * How a processing ended; the names are the wire vocabulary.
 */
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
data class OutcomeDescriptor(val kind: OutcomeKind, val commits: Int = 0, val failure: FailureDescriptor? = null)

/**
 * A node with an activation id, as a decision names them.
 */
data class ActivationRef(val node: String, val activation: Long)

/**
 * A command a decision registered: its id, the activation it belongs to, its lane and policy
 * names, and what the policy kept of the command itself.
 */
data class CommandRef(val id: Long, val scope: Long, val lane: String?, val policy: String?, val command: Payload<Any?>)

/**
 * A timer a decision scheduled: its id, the transition it fires (by position in the chart), the
 * activation of its source and when it is due on the machine's clock.
 */
data class TimerRef(val id: Long, val transition: Int, val activation: Long, val deadline: Duration)

/**
 * A bridge message as the journal names it: the Store that sent it and the id of the effect it
 * was routed from, so the same effect gives the same message in a replay.
 */
data class MessageRef(val from: StoreInstanceId, val effect: Long) {
    override fun toString(): String = "${from.value}/e$effect"
}

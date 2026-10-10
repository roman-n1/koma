package actron.core

import kotlinx.coroutines.currentCoroutineContext
import kotlin.jvm.JvmInline

/**
 * Observes the processing boundaries of one Store: every input it accepts or discards, each
 * processing of an input with its outcome, every committed state snapshot, every emitted event
 * and every failure reported to the [ExceptionHandler].
 *
 * A probe sees what a [Plugin] cannot: an input discarded before any hook ran, a handler that
 * left the state equal (no `onState` round), several commits caused by one input, the input a
 * transaction or an event belongs to, and the outcome of each processing. It is the source for
 * structured journals, inspectors and replay recordings; it is not a replacement for [Plugin],
 * which stays the extension point for reacting to the Store.
 *
 * [record] is a plain function that the Store calls inline at the boundary, mostly under its
 * lock: it must be fast, must not suspend, must not call back into the Store and must not block
 * on I/O. [StoreTrace.InputAccepted] and some [StoreTrace.InputDiscarded] traces are recorded from
 * the thread that dispatched, concurrently with traces recorded under the lock, so a probe that
 * keeps state must be thread safe. A probe that needs to keep a trace beyond the call decides
 * itself what to retain: the payloads are the live state, action, event and error objects.
 *
 * An exception thrown by [record] is reported to the Store's [ExceptionHandler] and the boundary
 * proceeds as if the probe had returned; the failure of a probe is never recorded as a trace, so a
 * failing probe cannot recurse. A non-[Exception] throwable propagates.
 *
 * Probes are registered with `StoreBuilder.probe {}` or, before startup, with a [StorePatch].
 * Registering none costs nothing on the hot path beyond the allocation of input ids.
 */
@InternalActronApi
fun interface StoreProbe<S : State, A : Action, E : Event> {
    fun record(trace: StoreTrace<S, A, E>)
}

/**
 * Identity of one input of one Store: a dispatched action, the startup, a transaction or the
 * recovery of a launch failure.
 *
 * Ids are unique within a Store and increase in allocation order, which is the order the
 * dispatching threads called the Store, not the order the inputs are processed in; see
 * [StoreTrace.ProcessingStarted.ordinal] for that. Ids are not dense: an allocation that lost a
 * race leaves a gap. An id is never reused, not even when a startup is retried.
 *
 * Unlike the rest of the probe API, the id itself is public rather than internal: journals built
 * on the probe expose it as the identity of an input, and the fork's journal is stable API.
 */
@JvmInline
value class InputId(val value: Long) {
    override fun toString(): String = "#$value"
}

/**
 * What an input is.
 */
@InternalActronApi
sealed interface InputKind<out A : Action> {
    /**
     * The startup of the Store: plugin `onStart` hooks and the initial `enter {}` chain. There is
     * one startup input per Store; it is processed again, with a new ordinal, when a plugin's
     * `onStart` failed and a later dispatch or [Store.start] retries.
     */
    data object Startup : InputKind<Nothing>

    /**
     * An action passed to [Store.dispatch] or [StoreInternalApi.dispatchIf].
     */
    data class Dispatch<out A : Action>(val action: A) : InputKind<A>

    /**
     * A `transaction {}` requested from a launched coroutine.
     *
     * @property origin The input whose handler launched the coroutine, when known
     */
    data class Transaction(val origin: InputId?) : InputKind<Nothing>

    /**
     * The `recover {}` pass for a launched coroutine that failed, run under the Store lock once
     * the failure is reported.
     *
     * @property error The failure of the launch
     * @property origin The input whose handler launched the coroutine, when known
     */
    data class Recovery(val error: Throwable, val origin: InputId?) : InputKind<Nothing>
}

/**
 * Why an accepted input was never processed.
 */
@InternalActronApi
sealed interface DiscardReason {
    /** The Store closed before the input got the lock. */
    data object StoreClosed : DiscardReason

    /** [PendingActionPolicy.ClearOnStateExit] cleared the queue when a state variant was exited. */
    data object ClearedOnStateExit : DiscardReason

    /** A handler or transaction called `clearPendingActions()`. */
    data object ClearedExplicitly : DiscardReason

    /** The `isValid` predicate of [StoreInternalApi.dispatchIf] returned false under the lock. */
    data object Stale : DiscardReason

    /** The `isValid` predicate of [StoreInternalApi.dispatchIf] threw; [error] reaches the exception handler. */
    data class Rejected(val error: Throwable) : DiscardReason

    /**
     * The startup this dispatch triggered failed in a plugin's `onStart`, so the action was
     * dropped; the next dispatch or [Store.start] retries the startup.
     */
    data class StartupFailed(val error: Throwable) : DiscardReason

    /**
     * A transaction was skipped because, by the time it got the lock, its state had exited or the
     * launch that requested it was cancelled.
     */
    data object LaunchInactive : DiscardReason
}

/**
 * How the processing of an input ended.
 */
@InternalActronApi
sealed interface ProcessingOutcome {
    /** A handler ran and committed [commits] state snapshots (at least one). */
    data class Handled(val commits: Int) : ProcessingOutcome {
        init {
            require(commits >= 1) { "[Actron] Handled needs at least one commit; use Unchanged" }
        }
    }

    /** A handler ran and left the state equal; it may still have emitted events or launched work. */
    data object Unchanged : ProcessingOutcome

    /** No `action {}` handler matched the state and the action; nothing ran but the plugins' `onAction`. */
    data object Ignored : ProcessingOutcome

    /** A handler failed with [error] and a `recover {}` handled it, committing [commits] snapshots. */
    data class Recovered(val error: Throwable, val commits: Int) : ProcessingOutcome

    /**
     * The processing failed with [error] and no `recover {}` handled it: the error reaches the
     * exception handler. State committed before the failure stays committed.
     */
    data class Failed(val error: Throwable) : ProcessingOutcome

    /** The Store closed while the input was being processed; nothing after that point was committed. */
    data object Cancelled : ProcessingOutcome
}

/**
 * One observation at a processing boundary of a Store, delivered to a [StoreProbe].
 *
 * For one processed input the Store records, in this order: [InputAccepted] (from the caller's
 * thread), [ProcessingStarted], zero or more [StateCommitted], [EventEmitted] and
 * [FailureReported], then exactly one [ProcessingFinished]. An input that never gets processed
 * has [InputAccepted] and [InputDiscarded] only. Processing is serialized by the Store lock, so
 * the traces of two inputs never interleave, except for [InputAccepted], the discards of queued
 * inputs, and the [EventEmitted] and [FailureReported] of launched coroutines, which happen
 * outside the lock and carry the input they belong to.
 */
@InternalActronApi
sealed interface StoreTrace<out S : State, out A : Action, out E : Event> {
    /**
     * The Store accepted [input]: it was enqueued (a dispatch), requested (a transaction) or
     * created (the startup, the recovery of a launch failure). Acceptance is not processing.
     */
    data class InputAccepted<out A : Action>(val input: InputId, val kind: InputKind<A>) : StoreTrace<Nothing, A, Nothing>

    /**
     * [input] will not be processed, for [reason]. No handler, hook or commit ran for it.
     */
    data class InputDiscarded(val input: InputId, val reason: DiscardReason) : StoreTrace<Nothing, Nothing, Nothing>

    /**
     * Processing of [input] began under the Store lock.
     *
     * @property ordinal The processing number of this Store: 1 for the first processed input, then
     * dense and increasing in processing order. A retried startup gets a new ordinal.
     */
    data class ProcessingStarted(val input: InputId, val ordinal: Long) : StoreTrace<Nothing, Nothing, Nothing>

    /**
     * A new state snapshot was committed while processing [input]: it is already visible to
     * [Store.currentState] and collectors; the [StateSaver] and the plugins' `onState` run after
     * this trace. Equal snapshots are never committed, so [previous] and [state] differ.
     *
     * @property revision The commit number of this Store: 1 for the first commit, then dense and
     * increasing. The restored or initial state a Store starts with has revision 0.
     */
    data class StateCommitted<out S : State>(val input: InputId, val revision: Long, val previous: S, val state: S) : StoreTrace<S, Nothing, Nothing>

    /**
     * [event] was emitted to collectors; the plugins' `onEvent` runs after this trace.
     *
     * @property input The input being processed, or the input whose handler launched the coroutine
     * that emitted the event; `null` when unknown
     */
    data class EventEmitted<out E : Event>(val input: InputId?, val event: E) : StoreTrace<Nothing, Nothing, E>

    /**
     * [error] is about to be passed to the Store's [ExceptionHandler]. It is recorded once per
     * report, before the handler runs, so a throwing handler (such as [ExceptionHandler.Rethrow])
     * does not lose it.
     *
     * @property input The input being processed, or the input whose handler launched the failing
     * coroutine; `null` when unknown, for example a failing [StateSaver.restore]
     */
    data class FailureReported(val input: InputId?, val error: Throwable) : StoreTrace<Nothing, Nothing, Nothing>

    /**
     * Processing of [input] ended with [outcome]; [ordinal] matches its [ProcessingStarted].
     */
    data class ProcessingFinished(val input: InputId, val ordinal: Long, val outcome: ProcessingOutcome) : StoreTrace<Nothing, Nothing, Nothing>

    /**
     * The Store's root coroutine scope completed: [Store.close] was called or a parent scope was
     * cancelled, and every coroutine of the Store has ended. Queued inputs were discarded with
     * [DiscardReason.StoreClosed] before this trace. A dispatch that arrives after the close is
     * accepted and discarded from its own cancelled coroutine, so its traces may follow this one.
     */
    data object StoreClosed : StoreTrace<Nothing, Nothing, Nothing>
}

/**
 * The [InputId] the current coroutine works for, or `null` outside a Store.
 *
 * Inside a handler, hook or transaction it is the input being processed; inside a coroutine a
 * handler or plugin launched, the input that was being processed when it was launched. One
 * imprecision: a startup that the first dispatch triggered is processed in that dispatch's
 * coroutine, so its handlers see the dispatch's id. Journals built on the probe use it to relate
 * their own records to the Store's [StoreTrace]s.
 */
@InternalActronApi
suspend fun currentInputId(): InputId? = currentCoroutineContext()[InputOrigin]?.input

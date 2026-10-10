@file:OptIn(InternalActronApi::class)

package actron.observability

import actron.core.Action
import actron.core.DiscardReason
import actron.core.Event
import actron.core.InputKind
import actron.core.InternalActronApi
import actron.core.ProcessingOutcome
import actron.core.State
import actron.core.StoreBuilder
import actron.core.StorePatchBuilder
import actron.core.StoreProbe
import actron.core.StoreTrace
import kotlin.time.TimeMark

/**
 * Records this Store into [session] as [store], keeping of each payload what [policy] allows.
 *
 * @param session The session that orders and keeps the records
 * @param store This Store instance; unique within the session
 * @param policy What to keep of states, actions, events and failures; the production default keeps
 * metadata only
 */
fun <S : State, A : Action, E : Event> StoreBuilder<S, A, E>.recordTo(
    session: RecordingSession,
    store: StoreInstanceId,
    policy: PayloadPolicy<S, A, E> = PayloadPolicy.metadataOnly(),
) {
    probe(session.probe(store, policy))
}

/**
 * Records this Store into [session] as [store] from a test patch; see the [StoreBuilder] overload.
 */
fun <S : State, A : Action, E : Event> StorePatchBuilder<S, A, E>.recordTo(
    session: RecordingSession,
    store: StoreInstanceId,
    policy: PayloadPolicy<S, A, E> = PayloadPolicy.metadataOnly(),
) {
    probe(session.probe(store, policy))
}

/**
 * Turns the traces of one Store into journal entries and publishes them. The policy runs here,
 * on the Store's thread, so nothing of a payload is retained before it decided.
 */
internal class JournalProbe<S : State, A : Action, E : Event>(
    private val session: RecordingSession,
    private val store: StoreInstanceId,
    private val policy: PayloadPolicy<S, A, E>,
) : StoreProbe<S, A, E> {
    // ProcessingStarted and ProcessingFinished come under the Store's lock, one processing at a time.
    private var processingStartedAt: TimeMark? = null

    override fun record(trace: StoreTrace<S, A, E>) {
        val entry: JournalEntry<S, A, E> = when (trace) {
            is StoreTrace.InputAccepted -> JournalEntry.InputAccepted(trace.input, describe(trace.kind))
            is StoreTrace.InputDiscarded -> JournalEntry.InputDiscarded(trace.input, describe(trace.reason))
            is StoreTrace.ProcessingStarted -> {
                processingStartedAt = session.now()
                JournalEntry.ProcessingStarted(trace.input, trace.ordinal)
            }
            is StoreTrace.StateCommitted -> JournalEntry.StateCommitted(
                input = trace.input,
                revision = trace.revision,
                previous = guarded { policy.state(trace.previous) },
                state = guarded { policy.state(trace.state) },
            )
            is StoreTrace.EventEmitted -> JournalEntry.EventEmitted(trace.input, guarded { policy.event(trace.event) })
            is StoreTrace.FailureReported -> JournalEntry.FailureReported(trace.input, failure(trace.error))
            is StoreTrace.ProcessingFinished -> {
                val duration = processingStartedAt.elapsedOrZero()
                processingStartedAt = null
                JournalEntry.ProcessingFinished(trace.input, trace.ordinal, describe(trace.outcome), duration)
            }
            StoreTrace.StoreClosed -> JournalEntry.StoreClosed
        }
        session.publish(store, entry)
    }

    private fun describe(kind: InputKind<A>): InputDescriptor<A> = when (kind) {
        InputKind.Startup -> InputDescriptor.Startup
        is InputKind.Dispatch -> InputDescriptor.Dispatch(guarded { policy.action(kind.action) })
        is InputKind.Transaction -> InputDescriptor.Transaction(kind.origin)
        is InputKind.Recovery -> InputDescriptor.Recovery(failure(kind.error), kind.origin)
    }

    private fun describe(reason: DiscardReason): DiscardDescriptor = when (reason) {
        DiscardReason.StoreClosed -> DiscardDescriptor(DiscardKind.StoreClosed)
        DiscardReason.ClearedOnStateExit -> DiscardDescriptor(DiscardKind.ClearedOnStateExit)
        DiscardReason.ClearedExplicitly -> DiscardDescriptor(DiscardKind.ClearedExplicitly)
        DiscardReason.Stale -> DiscardDescriptor(DiscardKind.Stale)
        is DiscardReason.Rejected -> DiscardDescriptor(DiscardKind.Rejected, failure(reason.error))
        is DiscardReason.StartupFailed -> DiscardDescriptor(DiscardKind.StartupFailed, failure(reason.error))
        DiscardReason.LaunchInactive -> DiscardDescriptor(DiscardKind.LaunchInactive)
    }

    private fun describe(outcome: ProcessingOutcome): OutcomeDescriptor = when (outcome) {
        is ProcessingOutcome.Handled -> OutcomeDescriptor(OutcomeKind.Handled, commits = outcome.commits)
        ProcessingOutcome.Unchanged -> OutcomeDescriptor(OutcomeKind.Unchanged)
        ProcessingOutcome.Ignored -> OutcomeDescriptor(OutcomeKind.Ignored)
        is ProcessingOutcome.Recovered -> OutcomeDescriptor(OutcomeKind.Recovered, commits = outcome.commits, failure = failure(outcome.error))
        is ProcessingOutcome.Failed -> OutcomeDescriptor(OutcomeKind.Failed, failure = failure(outcome.error))
        ProcessingOutcome.Cancelled -> OutcomeDescriptor(OutcomeKind.Cancelled)
    }

    private fun failure(error: Throwable): FailureDescriptor = try {
        policy.failure(error)
    } catch (e: Exception) {
        session.reportPolicyFailure(store, e)
        FailureDescriptor.Unavailable
    }

    private inline fun <T> guarded(describe: () -> Payload<T>): Payload<T> = try {
        describe()
    } catch (e: Exception) {
        session.reportPolicyFailure(store, e)
        Payload.Unavailable
    }
}

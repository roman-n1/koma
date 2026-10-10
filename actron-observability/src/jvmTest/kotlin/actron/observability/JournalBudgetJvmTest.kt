@file:OptIn(ExperimentalActronApi::class, InternalActronApi::class)

package actron.observability

import actron.core.Action
import actron.core.DiscardReason
import actron.core.ExperimentalActronApi
import actron.core.InputId
import actron.core.InternalActronApi
import actron.core.State
import actron.core.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Measurements behind the budgets of [JournalConfig], on the JVM: how much memory a retained
 * record costs under the production policy, how fast the session publishes, and what the
 * journal costs a Store's dispatch. The numbers are printed and only sanity-checked, since a
 * CI machine is not a target device; the ADR records the values measured when the budgets were
 * set, and a change here should update them.
 */
class JournalBudgetJvmTest {

    data class Counter(val count: Int = 0) : State

    data object Increment : Action

    private fun entryAt(i: Int): JournalEntry<Nothing, Nothing, Nothing> =
        when (i % 5) {
            0 -> JournalEntry.InputAccepted(InputId(i.toLong()), InputDescriptor.Dispatch(Payload.Omitted))
            1 -> JournalEntry.ProcessingStarted(InputId(i.toLong()), i.toLong())
            2 -> JournalEntry.StateCommitted(InputId(i.toLong()), i.toLong(), Payload.Omitted, Payload.Omitted)
            3 -> JournalEntry.ProcessingFinished(InputId(i.toLong()), i.toLong(), OutcomeDescriptor(OutcomeKind.Handled, 1), 812.milliseconds)
            else -> JournalEntry.InputDiscarded(InputId(i.toLong()), DiscardDescriptor(DiscardKind.Stale))
        }

    private fun usedMemory(): Long {
        repeat(3) {
            System.gc()
            Thread.sleep(50)
        }
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    @Test
    fun retainedRecord_costsAFewHundredBytes_underTheProductionPolicy() = runBlocking {
        val n = 20_000
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val session = RecordingSession(scope, config = JournalConfig(retainedRecords = n, writerQueueCapacity = 1))
        val store = StoreInstanceId("m")
        val before = usedMemory()

        // The entries are built inside the measured region: a retained record is the envelope and
        // the entry, with the descriptors a production policy leaves in it.
        repeat(n) { session.publish(store, entryAt(it)) }
        val records = session.records()
        val after = usedMemory()

        val perRecord = (after - before) / n
        println("[JournalBudget] retained record: ~$perRecord bytes (n=$n, envelope and entry, payloads omitted)")
        assertTrue(records.size == n)
        assertTrue(perRecord in 64..2_048, "a retained record costs $perRecord bytes; expected a few hundred")
        scope.cancel()
    }

    @Test
    fun publish_takesMicroseconds() = runBlocking {
        val n = 200_000
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val session = RecordingSession(scope, config = JournalConfig(retainedRecords = 2_000, writerQueueCapacity = 4_096))
        val store = StoreInstanceId("m")
        val entry = JournalEntry.ProcessingStarted(InputId(1), 1)
        repeat(10_000) { session.publish(store, entry) } // warm-up

        val mark = TimeSource.Monotonic.markNow()
        repeat(n) { session.publish(store, entry) }
        val elapsed = mark.elapsedNow()

        val perPublish = elapsed / n
        println("[JournalBudget] publish: $perPublish per record, ${(n / elapsed.inWholeMilliseconds.coerceAtLeast(1)) * 1000} records/s (retained ring 2000, queue 4096, no sinks)")
        assertTrue(perPublish < 50.milliseconds / 1000, "a publish takes $perPublish; expected microseconds")
        scope.cancel()
    }

    @Test
    fun dispatch_withTheJournal_staysWithinAFewTimesOfWithout() = runBlocking {
        val n = 30_000
        fun store(session: RecordingSession?): Store<Counter, Increment, Nothing> = Store(Counter()) {
            coroutineContext(Dispatchers.Default)
            if (session != null) recordTo(session, StoreInstanceId("m"), PayloadPolicy.metadataOnly())
            state<Counter> {
                action<Increment> { nextState { state.copy(count = state.count + 1) } }
            }
        }
        suspend fun measure(session: RecordingSession?): Long {
            val store = store(session)
            store.dispatch(Increment) // startup
            withTimeout(30_000) { store.state.first { it.count == 1 } }
            val mark = TimeSource.Monotonic.markNow()
            repeat(n) { store.dispatch(Increment) }
            withTimeout(60_000) { store.state.first { it.count == n + 1 } }
            val elapsed = mark.elapsedNow()
            store.close()
            return elapsed.inWholeMicroseconds
        }

        measure(null) // warm-up
        val without = measure(null)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val session = RecordingSession(scope, config = JournalConfig(retainedRecords = 2_000, writerQueueCapacity = 4_096))
        val with = measure(session)
        val ratio = with.toDouble() / without.coerceAtLeast(1)
        println("[JournalBudget] dispatch: ${without / n} us without the journal, ${with / n} us with it (metadata only), ratio %.2f; session stats ${session.stats}".format(ratio))
        assertTrue(ratio < 5.0, "the journal made dispatch $ratio times slower; expected a few times at most")
        session.close()
        scope.cancel()
    }
}

@file:OptIn(ExperimentalKomaApi::class)

package koma.statechart.machine

import koma.core.Action
import koma.core.Event
import koma.core.ExceptionHandler
import koma.core.ExperimentalKomaApi
import koma.observability.JournalConfig
import koma.observability.JournalEntry
import koma.observability.RecordingSession
import koma.observability.StoreInstanceId
import koma.statechart.ActionMatcher
import koma.statechart.AtomicState
import koma.statechart.CompoundState
import koma.statechart.StateChartDefinition
import koma.statechart.StateId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Retained effects under a storm on [Dispatchers.Default]: four threads dispatch, three
 * subscribers come and go (each lives a few milliseconds, acknowledges some of what it takes
 * and abandons the rest) until every effect is acknowledged. Every effect is acknowledged
 * exactly once, none is lost, the journal has one queue and one acknowledgement per effect
 * with its attempts counting up, and the mailbox ends empty. What no hand times: an effect
 * abandoned by a dying subscriber while the next one is already taking it.
 *
 * ```
 * [*] --> Idle      onAction Navigate(n): event Navigate(n) (Retained)
 * ```
 */
class EffectMailboxStormTest {

    data class Navigate(val n: Int) : Action

    data class Route(val n: Int) : Event

    private val root = StateId("Root")
    private val idle = StateId("Idle")
    private val chart = StateChartDefinition(root, listOf(CompoundState(root, initial = idle), AtomicState(idle, parent = root)), emptyList())

    private val machine = Machine<Unit, Navigate, Nothing, Route>(DefinitionId("mailbox-storm"), DefinitionVersion("1"), chart) {
        onAction(root, ActionMatcher.of<Navigate>("Navigate")) { event(Route((action as Navigate).n)) }
    }

    @Test
    fun everyRetainedEffect_isAcknowledgedExactlyOnce_whateverTheSubscribersDo() = runTest {
        val senders = 4
        val perSender = 100
        val total = senders * perSender
        val executionScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val session = RecordingSession(executionScope, config = JournalConfig(retainedRecords = 50_000, writerQueueCapacity = 50_000))
        val id = StoreInstanceId("s")
        val reported = Channel<Throwable>(Channel.UNLIMITED)
        val store = MachineStore(
            machine, Unit, CommandHandler<Nothing, Navigate> { _, _ -> }, executionScope, coroutineContext = Dispatchers.Default,
            mailbox = MailboxConfig({ EffectPolicy.Retained() }, maxRetained = total, listeners = listOf(session.effectsOf(id))),
        ) { exceptionHandler(ExceptionHandler { reported.trySend(it) }) }
        store.start()
        val acknowledged = Channel<EffectId>(Channel.UNLIMITED)
        val acknowledgedCount = MutableStateFlow(0)
        val deliveries = MutableStateFlow(0)

        withContext(Dispatchers.Default) {
            coroutineScope {
                repeat(senders) { sender ->
                    launch {
                        repeat(perSender) { index ->
                            store.dispatch(Navigate(sender * perSender + index))
                            if (index % 10 == 0) delay(1.milliseconds)
                        }
                    }
                }
                repeat(3) {
                    launch {
                        while (acknowledgedCount.value < total) {
                            // A subscriber lives a few milliseconds: it takes effects, acknowledges some, and dies with the rest in hand.
                            withTimeoutOrNull(Random.nextLong(3, 15).milliseconds) {
                                store.mailbox.subscribe().collect { delivery ->
                                    deliveries.update { it + 1 }
                                    delay(Random.nextLong(0, 2).milliseconds)
                                    if (Random.nextInt(10) < 7 && delivery.acknowledge()) {
                                        acknowledged.send(delivery.id)
                                        acknowledgedCount.update { it + 1 }
                                    }
                                }
                            }
                        }
                    }
                }
                withTimeout(60_000) { acknowledgedCount.first { it == total } }
            }
        }
        val pending = store.mailbox.pending
        store.close()
        session.close()
        executionScope.cancel()

        val ids = generateSequence { acknowledged.tryReceive().getOrNull() }.toList()
        assertEquals(total, ids.size)
        assertEquals(total, ids.toSet().size, "each effect acknowledged once")
        assertTrue(pending.isEmpty(), "nothing left: $pending")
        assertTrue(deliveries.value >= total, "at least one delivery per effect: ${deliveries.value}")
        val entries = session.records().map { it.entry }
        val queued = entries.filterIsInstance<JournalEntry.EffectQueued<*>>().map { it.effect }
        val acked = entries.filterIsInstance<JournalEntry.EffectAcknowledged>().map { it.effect }
        assertEquals(total, queued.size)
        assertEquals(queued.toSet(), acked.toSet(), "every queued effect was acknowledged in the journal")
        assertEquals(acked.size, acked.toSet().size)
        assertTrue(entries.none { it is JournalEntry.EffectDiscarded }, "nothing was discarded")
        val attempts = entries.filterIsInstance<JournalEntry.EffectHandlingStarted>().groupBy({ it.effect }, { it.attempt })
        assertTrue(attempts.values.all { it == (1..it.size).toList() }, "attempts count up per effect")
        assertEquals(null, reported.tryReceive().getOrNull(), "nothing was reported")
        println("deliveries ${deliveries.value} for $total effects; effects redelivered: ${attempts.values.count { it.size > 1 }}")
    }
}

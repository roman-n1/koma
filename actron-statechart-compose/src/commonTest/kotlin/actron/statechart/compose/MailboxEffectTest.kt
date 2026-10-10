@file:OptIn(ExperimentalActronApi::class, ExperimentalCoroutinesApi::class)

package actron.statechart.compose

import androidx.compose.runtime.Composable
import actron.core.ExperimentalActronApi
import actron.statechart.compose.MailboxFixture.Act
import actron.statechart.compose.MailboxFixture.Ev
import actron.statechart.machine.Delivery
import actron.statechart.machine.EffectId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `MailboxEffect` ties a mailbox subscription to a composition: a delivery reaches the block and
 * leaves the mailbox on acknowledgement, what was queued before the composition entered is
 * delivered when it enters, a composition that leaves mid-handling hands the effect to the next
 * one as attempt 2, the latest block is used, a throwing block fails the composition and the
 * effect waits, and a storm of recreations loses and duplicates nothing.
 */
class MailboxEffectTest {

    private val testDispatcher = StandardTestDispatcher()

    private inner class Fixture(scope: TestScope, maxAttempts: Int? = null, maxRetained: Int = 64) {
        val executionScope = CoroutineScope(testDispatcher + SupervisorJob())
        val store = MailboxFixture.store(executionScope, testDispatcher, maxRetained = maxRetained, maxAttempts = maxAttempts)

        init {
            scope.testScheduler.runCurrent()
        }
    }

    @Test
    fun aDelivery_reachesTheBlock_andItsAcknowledgementLeavesTheMailbox() = runTest(testDispatcher) {
        val f = Fixture(this)
        val received = mutableListOf<Delivery<Ev>>()

        withComposeHarness {
            compose { MailboxEffect(f.store.mailbox) { received += it; it.acknowledge() } }
            pumpFrame()
            assertTrue(received.isEmpty())

            f.store.dispatch(Act.Navigate("chat/7"))
            pumpFrame()

            assertEquals(listOf<Ev>(Ev.Navigate("chat/7")), received.map { it.event })
            assertEquals(1, received.single().attempt)
            assertTrue(f.store.mailbox.pending.isEmpty(), "acknowledged: ${f.store.mailbox.pending}")
        }
        f.store.close()
    }

    @Test
    fun anEffectQueuedBeforeTheCompositionEntered_isDeliveredWhenItEnters() = runTest(testDispatcher) {
        val f = Fixture(this)
        f.store.dispatch(Act.Navigate("chat/7"))
        f.store.dispatch(Act.Navigate("chat/8"))
        runCurrent()
        assertEquals(2, f.store.mailbox.pending.size, "the mailbox keeps what no one collects yet")

        val received = mutableListOf<Delivery<Ev>>()
        withComposeHarness {
            compose { MailboxEffect(f.store.mailbox) { received += it; it.acknowledge() } }
            pumpFrame()

            assertEquals(listOf<Ev>(Ev.Navigate("chat/7"), Ev.Navigate("chat/8")), received.map { it.event }, "oldest first")
            assertTrue(f.store.mailbox.pending.isEmpty())
        }
        f.store.close()
    }

    @Test
    fun aCompositionThatLeavesMidHandling_handsTheEffectToTheNextOne_asAttempt2() = runTest(testDispatcher) {
        val f = Fixture(this)
        val first = mutableListOf<Delivery<Ev>>()
        val second = mutableListOf<Delivery<Ev>>()

        withComposeHarness {
            compose { MailboxEffect(f.store.mailbox) { first += it; awaitCancellation() } }
            pumpFrame()
            f.store.dispatch(Act.Navigate("chat/7"))
            pumpFrame()
            assertEquals(1, first.size)
            assertEquals(1, first.single().attempt)
            assertTrue(f.store.mailbox.pending.single().handling, "the first composition holds it")

            // The screen is recreated while the effect is being handled: a new composition, a
            // new subscription; the old one acknowledged nothing.
            compose { MailboxEffect(f.store.mailbox) { second += it; it.acknowledge() } }
            pumpFrame()

            assertEquals(1, first.size, "the old block was cancelled, not called again")
            assertEquals(listOf(first.single().id), second.map { it.id }, "the same effect")
            assertEquals(2, second.single().attempt)
            assertTrue(f.store.mailbox.pending.isEmpty())
        }
        f.store.close()
    }

    @Test
    fun theBudgetOfThePolicy_endsTheRedeliveries() = runTest(testDispatcher) {
        val f = Fixture(this, maxAttempts = 2)
        val taken = mutableListOf<Delivery<Ev>>()

        withComposeHarness {
            f.store.dispatch(Act.Navigate("chat/7"))
            repeat(3) {
                compose { MailboxEffect(f.store.mailbox) { taken += it; awaitCancellation() } }
                pumpFrame()
            }
            assertEquals(listOf(1, 2), taken.map { it.attempt }, "two subscribers took it and left; the third got nothing")
            assertTrue(f.store.mailbox.pending.isEmpty(), "discarded as Exhausted")
        }
        f.store.close()
    }

    @Test
    fun theLatestBlock_isUsed() = runTest(testDispatcher) {
        val f = Fixture(this)
        val handledBy = mutableListOf<String>()

        withComposeHarness {
            compose { MailboxEffect(f.store.mailbox) { handledBy += "first"; it.acknowledge() } }
            pumpFrame()
            recompose { MailboxEffect(f.store.mailbox) { handledBy += "second"; it.acknowledge() } }
            pumpFrame()

            f.store.dispatch(Act.Navigate("chat/7"))
            pumpFrame()

            assertEquals(listOf("second"), handledBy)
        }
        f.store.close()
    }

    @Test
    fun aBlockThatThrows_failsTheComposition_andTheEffectWaitsForTheNextSubscriber() = runTest(testDispatcher) {
        val f = Fixture(this)
        f.store.dispatch(Act.Navigate("chat/7"))
        runCurrent()

        val failure = assertFailsWith<IllegalStateException> {
            withComposeHarness {
                compose { MailboxEffect(f.store.mailbox) { error("the screen could not navigate") } }
                pumpFrame()
            }
        }
        assertEquals("the screen could not navigate", failure.message)

        val pending = f.store.mailbox.pending.single()
        assertEquals(1, pending.attempts, "the attempt counted against the budget")
        assertFalse(pending.handling, "released for the next subscriber")
        f.store.close()
    }

    /**
     * 400 retained effects through 200 recreations of the composition, each recreation while a
     * delivery is being handled: every effect is acknowledged exactly once, the 200 interrupted
     * ones on their second attempt, and the mailbox ends empty. The block waits at a gate the
     * test opens, so what is mid-handling at each recreation is known.
     */
    @Test
    fun aStormOfRecreations_acknowledgesEveryEffectOnce_andLosesNone() = runTest(testDispatcher) {
        val effects = 400
        val recreations = 200
        val f = Fixture(this, maxRetained = effects)
        val gates = mutableMapOf<EffectId, CompletableDeferred<Unit>>()
        val taken = mutableListOf<Delivery<Ev>>()
        val acknowledged = mutableListOf<Boolean>()
        val content: @Composable () -> Unit = {
            MailboxEffect(f.store.mailbox) { delivery ->
                taken += delivery
                gates.getOrPut(delivery.id) { CompletableDeferred() }.await()
                acknowledged += delivery.acknowledge()
            }
        }

        withComposeHarness {
            repeat(effects) { f.store.dispatch(Act.Navigate("chat/$it")) }
            compose(content)
            var recreated = 0
            var rounds = 0
            while (acknowledged.size < effects) {
                check(rounds++ < effects * 2) { "no progress: ${acknowledged.size} acknowledged, ${taken.size} taken, pending ${f.store.mailbox.pending.size}" }
                pumpFrame()                                          // the subscriber takes the next effect and waits at its gate
                val handling = taken.last()
                gates.getValue(handling.id).complete(Unit)
                pumpFrame()                                          // acknowledged; the next one taken and waiting
                if (recreated < recreations) {
                    compose(content)                                 // the screen recreated mid-handling
                    recreated++
                }
            }
        }

        assertEquals(effects, acknowledged.size)
        assertTrue(acknowledged.all { it }, "an acknowledgement of an effect no longer pending: ${acknowledged.count { !it }}")
        assertEquals(effects, taken.map { it.id }.toSet().size, "every effect reached a block")
        assertEquals(recreations, taken.count { it.attempt == 2 }, "every recreation interrupted one delivery, redelivered once")
        assertTrue(taken.none { it.attempt > 2 })
        assertTrue(f.store.mailbox.pending.isEmpty(), "left over: ${f.store.mailbox.pending.size}")
        f.store.close()
    }
}

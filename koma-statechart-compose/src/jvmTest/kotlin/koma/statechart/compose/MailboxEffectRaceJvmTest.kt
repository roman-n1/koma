@file:OptIn(ExperimentalKomaApi::class, ExperimentalCoroutinesApi::class)

package koma.statechart.compose

import androidx.compose.runtime.Composable
import koma.core.ExperimentalKomaApi
import koma.statechart.compose.MailboxFixture.Act
import koma.statechart.compose.MailboxFixture.Ev
import koma.statechart.machine.Delivery
import koma.statechart.machine.EffectId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The storm of `MailboxEffectTest` with the store on real threads: its executor queues the
 * effects and rings the mailbox from `Dispatchers.Default` while the composition, on the test
 * dispatcher, is recreated mid-handling. What is mid-handling at each recreation is no longer
 * known; that every effect is acknowledged exactly once and none is left over still is.
 */
class MailboxEffectRaceJvmTest {

    private val testDispatcher = StandardTestDispatcher()

    @Test
    fun aStormOfRecreations_withTheStoreOnRealThreads_acknowledgesEveryEffectOnce() = runTest(testDispatcher) {
        val effects = 400
        val recreations = 200
        val storeScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val store = MailboxFixture.store(storeScope, coroutineContext = null, maxRetained = effects)
        val gates = mutableMapOf<EffectId, CompletableDeferred<Unit>>()
        val taken = mutableListOf<Delivery<Ev>>()
        val acknowledged = mutableListOf<Boolean>()
        val content: @Composable () -> Unit = {
            MailboxEffect(store.mailbox) { delivery ->
                taken += delivery
                gates.getOrPut(delivery.id) { CompletableDeferred() }.await()
                acknowledged += delivery.acknowledge()
            }
        }

        withComposeHarness {
            val producer = storeScope.launch { repeat(effects) { store.dispatch(Act.Navigate("chat/$it")) } }
            compose(content)
            var recreated = 0
            var opened = 0
            val deadline = TimeSource.Monotonic.markNow() + 60.seconds
            while (acknowledged.size < effects) {
                check(deadline.hasNotPassedNow()) { "no progress: ${acknowledged.size} acknowledged, ${taken.size} taken, pending ${store.mailbox.pending.size}" }
                pumpFrame()                                          // whatever the executor queued so far is taken, one at a time
                if (taken.size > opened) {
                    gates.getValue(taken.last().id).complete(Unit)
                    opened = taken.size
                    pumpFrame()
                    if (recreated < recreations) {
                        compose(content)
                        recreated++
                    }
                }
            }
            producer.join()
        }

        assertEquals(effects, acknowledged.size)
        assertTrue(acknowledged.all { it }, "an acknowledgement of an effect no longer pending: ${acknowledged.count { !it }}")
        assertEquals(effects, taken.map { it.id }.toSet().size, "every effect reached a block")
        assertTrue(taken.any { it.attempt == 2 }, "no recreation interrupted a delivery: ${taken.size} deliveries")
        assertTrue(store.mailbox.pending.isEmpty(), "left over: ${store.mailbox.pending.size}")
        store.close()
        storeScope.cancel()
    }
}

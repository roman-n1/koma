@file:OptIn(ExperimentalKomaApi::class, ExperimentalCoroutinesApi::class, ExperimentalTime::class)

package koma.statechart.machine

import koma.core.Action
import koma.core.Event
import koma.core.ExceptionHandler
import koma.core.ExperimentalKomaApi
import koma.observability.JournalEntry
import koma.observability.MachineGroupId
import koma.observability.Payload
import koma.observability.RecordingSession
import koma.observability.RuntimeSessionId
import koma.observability.StoreInstanceId
import koma.statechart.ActionMatcher
import koma.statechart.AtomicState
import koma.statechart.CompoundState
import koma.statechart.StateChartDefinition
import koma.statechart.StateId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.TestTimeSource

/**
 * The retained mailbox of effects: transient effects go to the event flow and never wait, a
 * retained effect waits for a subscriber and leaves on acknowledgement, a subscriber that goes
 * away hands its effects to the next one, `Latest` keeps only the newest of its key, the
 * mailbox is bounded, closing discards, the checkpoint carries what is pending, and the
 * journal tells the story.
 *
 * ```
 * [*] --> Idle      onAction Toast(text): event Toast; onAction Navigate(route): event Navigate; onAction Badge(n): event Badge
 * ```
 */
class EffectMailboxTest {

    sealed interface Act : Action {
        data class Toast(val text: String) : Act
        data class Navigate(val route: String) : Act
        data class Badge(val count: Int) : Act
    }

    sealed interface Ev : Event {
        data class Toast(val text: String) : Ev
        data class Navigate(val route: String) : Ev
        data class Badge(val count: Int) : Ev
    }

    private val root = StateId("Root")
    private val idle = StateId("Idle")
    private val chart = StateChartDefinition(root, listOf(CompoundState(root, initial = idle), AtomicState(idle, parent = root)), emptyList())

    private val machine = Machine<Unit, Act, Nothing, Ev>(DefinitionId("mailbox"), DefinitionVersion("1"), chart) {
        onAction(root, ActionMatcher.of<Act.Toast>("Toast")) { event(Ev.Toast((action as Act.Toast).text)) }
        onAction(root, ActionMatcher.of<Act.Navigate>("Navigate")) { event(Ev.Navigate((action as Act.Navigate).route)) }
        onAction(root, ActionMatcher.of<Act.Badge>("Badge")) { event(Ev.Badge((action as Act.Badge).count)) }
    }

    private val policy: (Ev) -> EffectPolicy = { event ->
        when (event) {
            is Ev.Toast -> EffectPolicy.Transient
            is Ev.Navigate -> EffectPolicy.Retained
            is Ev.Badge -> EffectPolicy.Latest("badge")
        }
    }

    private inner class Fixture(scope: TestScope, maxRetained: Int = 64, policy: (Ev) -> EffectPolicy = this.policy) {
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val executionScope = CoroutineScope(dispatcher + SupervisorJob())
        val session = RecordingSession(scope.backgroundScope, id = RuntimeSessionId("m"), group = MachineGroupId("g"), timeSource = TestTimeSource())
        val id = StoreInstanceId("s")
        val reported = mutableListOf<Throwable>()
        val store = MachineStore(
            machine, Unit, CommandHandler<Nothing, Act> { _, _ -> }, executionScope, coroutineContext = dispatcher,
            mailbox = MailboxConfig(policy, maxRetained, listeners = listOf(session.effectsOf(id) { Payload.Projected(it.toString()) })),
        ) { exceptionHandler(ExceptionHandler { reported += it }) }
        val events = mutableListOf<Ev>()
        val deliveries = mutableListOf<Delivery<Ev>>()

        init {
            executionScope.launch { store.event.collect { events += it } }
            store.start()
        }

        fun subscribe(ack: Boolean = false): Job = executionScope.launch {
            store.mailbox.subscribe().collect { delivery ->
                deliveries += delivery
                if (ack) delivery.acknowledge()
            }
        }

        fun effectEntries(): List<JournalEntry<*, *, *>> = session.records().map { it.entry }.filter {
            it is JournalEntry.EffectQueued<*> || it is JournalEntry.EffectHandlingStarted || it is JournalEntry.EffectAcknowledged || it is JournalEntry.EffectDiscarded
        }
    }

    @Test
    fun transientEffects_goToTheEventFlow_andNeverWait() = runTest {
        val f = Fixture(this)
        runCurrent()

        f.store.dispatch(Act.Toast("hi"))
        runCurrent()

        assertEquals(listOf<Ev>(Ev.Toast("hi")), f.events)
        assertTrue(f.store.mailbox.pending.isEmpty())
        assertTrue(f.effectEntries().isEmpty(), "a transient effect is not a mailbox matter")
        f.store.close()
    }

    @Test
    fun aRetainedEffect_waitsForASubscriber_andLeavesOnAcknowledgement() = runTest {
        val f = Fixture(this)
        runCurrent()
        f.store.dispatch(Act.Navigate("chat"))
        runCurrent()

        val waiting = f.store.mailbox.pending.single()
        assertEquals(Ev.Navigate("chat"), waiting.event)
        assertEquals(EffectPolicy.Retained, waiting.policy)
        assertFalse(waiting.handling)
        assertEquals(0, waiting.attempts)
        assertTrue(f.events.isEmpty(), "not on the event flow")

        val subscriber = f.subscribe()
        runCurrent()
        val delivery = f.deliveries.single()
        assertEquals(waiting.id, delivery.id)
        assertEquals(1, delivery.attempt)
        assertTrue(f.store.mailbox.pending.single().handling)

        assertTrue(delivery.acknowledge())
        assertFalse(delivery.acknowledge(), "acknowledged once")
        assertTrue(f.store.mailbox.pending.isEmpty())
        assertEquals(
            listOf<JournalEntry<*, *, *>>(
                JournalEntry.EffectQueued(f.effectEntries().filterIsInstance<JournalEntry.EffectQueued<*>>().single().input, waiting.id.value, "Retained", Payload.Projected("Navigate(route=chat)")),
                JournalEntry.EffectHandlingStarted(waiting.id.value, 1),
                JournalEntry.EffectAcknowledged(waiting.id.value),
            ),
            f.effectEntries(),
        )
        subscriber.cancel()
        f.store.close()
    }

    @Test
    fun aSubscriberThatGoesAway_handsItsEffectToTheNextOne() = runTest {
        val f = Fixture(this)
        runCurrent()
        f.store.dispatch(Act.Navigate("chat"))
        val first = f.subscribe()
        runCurrent()
        assertEquals(1, f.deliveries.single().attempt)

        first.cancel()
        runCurrent()
        assertFalse(f.store.mailbox.pending.single().handling, "back to waiting")
        val second = f.subscribe(ack = true)
        runCurrent()

        assertEquals(listOf(1, 2), f.deliveries.map { it.attempt })
        assertTrue(f.store.mailbox.pending.isEmpty())
        assertEquals(listOf(1, 2), f.effectEntries().filterIsInstance<JournalEntry.EffectHandlingStarted>().map { it.attempt })
        second.cancel()
        f.store.close()
    }

    @Test
    fun latest_keepsOnlyTheNewestOfItsKey_whileWaiting() = runTest {
        val f = Fixture(this)
        runCurrent()
        f.store.dispatch(Act.Badge(1))
        f.store.dispatch(Act.Navigate("chat"))
        f.store.dispatch(Act.Badge(2))
        f.store.dispatch(Act.Badge(3))
        runCurrent()

        assertEquals(listOf<Ev>(Ev.Navigate("chat"), Ev.Badge(3)), f.store.mailbox.pending.map { it.event })
        val discarded = f.effectEntries().filterIsInstance<JournalEntry.EffectDiscarded>()
        assertEquals(listOf("Superseded", "Superseded"), discarded.map { it.reason })
        assertEquals(EffectPolicy.Latest("badge"), f.store.mailbox.pending.last().policy)
        assertEquals("Latest(badge)", f.store.mailbox.pending.last().policy.name)
        f.store.close()
    }

    @Test
    fun theMailboxIsBounded_theOldestWaitingMakesRoom_andANewOneIsDroppedWhenAllAreHandled() = runTest {
        val f = Fixture(this, maxRetained = 2)
        runCurrent()
        f.store.dispatch(Act.Navigate("a"))
        f.store.dispatch(Act.Navigate("b"))
        f.store.dispatch(Act.Navigate("c"))
        runCurrent()

        assertEquals(listOf<Ev>(Ev.Navigate("b"), Ev.Navigate("c")), f.store.mailbox.pending.map { it.event })
        assertEquals(listOf("Overflow"), f.effectEntries().filterIsInstance<JournalEntry.EffectDiscarded>().map { it.reason })

        // Both are being handled without acknowledgement: nothing waiting can make room.
        val subscriber = f.subscribe()
        runCurrent()
        assertEquals(2, f.deliveries.size)
        f.store.dispatch(Act.Navigate("d"))
        runCurrent()
        assertEquals(listOf<Ev>(Ev.Navigate("b"), Ev.Navigate("c")), f.store.mailbox.pending.map { it.event })
        assertEquals(2, f.effectEntries().filterIsInstance<JournalEntry.EffectDiscarded>().count { it.reason == "Overflow" })
        subscriber.cancel()
        f.store.close()
    }

    @Test
    fun closing_discardsWhatIsPending_andEndsTheSubscribers() = runTest {
        val f = Fixture(this)
        runCurrent()
        f.store.dispatch(Act.Navigate("a"))
        f.store.dispatch(Act.Navigate("b"))
        val subscriber = f.subscribe()
        runCurrent()
        assertEquals(2, f.deliveries.size)
        f.deliveries.first().acknowledge()

        f.store.close()
        runCurrent()

        assertTrue(f.store.mailbox.pending.isEmpty())
        assertEquals(listOf("StoreClosed"), f.effectEntries().filterIsInstance<JournalEntry.EffectDiscarded>().map { it.reason })
        assertTrue(subscriber.isCompleted, "the subscriber's flow ended")
        assertFalse(f.deliveries.last().acknowledge(), "nothing to acknowledge after the close")
    }

    @Test
    fun theCheckpoint_carriesThePendingEffects_withTheirDeliveryState() = runTest {
        val f = Fixture(this)
        runCurrent()
        f.store.dispatch(Act.Navigate("a"))
        f.store.dispatch(Act.Badge(5))
        runCurrent()
        val subscriber = f.subscribe()
        runCurrent()
        f.deliveries.first().acknowledge()

        val checkpoint = f.store.checkpoint()

        assertEquals(f.store.mailbox.pending, checkpoint.effects)
        assertEquals(listOf(Ev.Badge(5)), checkpoint.effects.map { it.event })
        assertTrue(checkpoint.effects.single().handling && checkpoint.effects.single().attempts == 1)
        f.deliveries.last().acknowledge()
        assertTrue(f.store.checkpoint().effects.isEmpty())
        subscriber.cancel()
        f.store.close()
    }

    @Test
    fun aPolicyThatThrows_isReported_andTheEffectIsTransient() = runTest {
        val f = Fixture(this, policy = { throw IllegalStateException("no policy for $it") })
        runCurrent()

        f.store.dispatch(Act.Navigate("a"))
        runCurrent()

        assertEquals(listOf<Ev>(Ev.Navigate("a")), f.events)
        assertTrue(f.store.mailbox.pending.isEmpty())
        assertEquals(1, f.reported.size)
        f.store.close()
    }
}

@file:OptIn(ExperimentalKomaApi::class, ExperimentalCoroutinesApi::class)

package koma.statechart.test

import koma.core.Action
import koma.core.Event
import koma.core.ExperimentalKomaApi
import koma.statechart.ActionMatcher
import koma.statechart.AtomicState
import koma.statechart.CompoundState
import koma.statechart.StateChartDefinition
import koma.statechart.StateId
import koma.statechart.Transition
import koma.statechart.Trigger
import koma.statechart.machine.Admission
import koma.statechart.machine.AdmissionPolicy
import koma.statechart.machine.CommandHandler
import koma.statechart.machine.ConcurrencyPolicy
import koma.statechart.machine.DefinitionId
import koma.statechart.machine.DefinitionVersion
import koma.statechart.machine.EffectPolicy
import koma.statechart.machine.LaneId
import koma.statechart.machine.Machine
import koma.statechart.machine.MachineStore
import koma.statechart.machine.MailboxConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The driver over a small loader: a send decides and registers a command the test answers;
 * effects are received one by one; a timer fires when the clock is advanced; the end-of-test
 * check names what is left; and `settle` under a storm always returns a checkpoint at the store's
 * revision.
 *
 * ```
 * [*] --> Root
 * state Root {
 *     [*] --> Idle
 *     Idle --Load--> Loading            onEnter: command Fetch in lane "load", Latest; event Started; after 10s --> Idle
 *     Loading --Loaded--> Content       onEnter: event Done (retained)
 *     Content --Load--> Loading
 * }
 * Root: onAction Tick / ticks++
 * ```
 */
class MachineTestDriverTest {

    data class Ctx(val loaded: Int = 0, val ticks: Int = 0)

    sealed interface Act : Action {
        data object Load : Act
        data object Loaded : Act
        data object Tick : Act
    }

    sealed interface Ev : Event {
        data object Started : Ev
        data object Done : Ev
    }

    data object Fetch

    private val root = StateId("Root")
    private val idle = StateId("Idle")
    private val loading = StateId("Loading")
    private val content = StateId("Content")
    private val chart = StateChartDefinition(
        root,
        listOf(CompoundState(root, initial = idle), AtomicState(idle, parent = root), AtomicState(loading, parent = root), AtomicState(content, parent = root)),
        listOf(
            Transition(idle, loading, ActionMatcher.of<Act.Load>("Load")),
            Transition(loading, content, ActionMatcher.of<Act.Loaded>("Loaded"), effect = "count"),
            Transition(loading, idle, Trigger.After(10.seconds)),
            Transition(content, loading, ActionMatcher.of<Act.Load>("Load")),
        ),
    )
    private val machine = Machine<Ctx, Act, Fetch, Ev>(DefinitionId("driven"), DefinitionVersion("1"), chart) {
        effect("count") { c, _ -> c.copy(loaded = c.loaded + 1) }
        onEnter(loading) {
            command(Fetch, LaneId("load"), ConcurrencyPolicy.Latest)
            event(Ev.Started)
        }
        onEnter(content) { event(Ev.Done) }
        onAction(root, ActionMatcher.of<Act.Tick>("Tick")) { context = context.copy(ticks = context.ticks + 1) }
    }
    private val mailbox = MailboxConfig<Ev>({ if (it is Ev.Done) EffectPolicy.Retained() else EffectPolicy.Transient })

    @Test
    fun aSend_decidesAndRegistersACommand_theTestAnswersIt_andReceivesTheEffects() = runTest {
        val driver = MachineTestDriver(machine, Ctx(), this, mailbox = mailbox)
        driver.start()
        driver.assertActive(setOf(root, idle))

        val afterLoad = driver.send(Act.Load)

        driver.assertActive(setOf(root, loading))
        val fetch = driver.runningCommands.single()
        assertEquals(Fetch, fetch.command)
        assertEquals(listOf(fetch.id), afterLoad.lanes.running.keys.toList(), "the checkpoint holds the running command")
        assertEquals(Ev.Started, driver.receiveEvent<Ev.Started>())

        driver.answer(fetch.id, Act.Loaded)

        driver.assertActive(setOf(root, content))
        driver.assertContext(Ctx(loaded = 1))
        assertTrue(driver.runningCommands.isEmpty(), "the transition exited the command's activation, which cancelled it")
        assertEquals(listOf(fetch.id), driver.handler.cancelled)
        assertEquals(Ev.Done, driver.receiveEvent<Ev.Done>())
        assertEquals(listOf<Ev>(Ev.Done), driver.pendingEffects.map { it.event }, "retained until acknowledged")
        assertTrue(driver.acknowledge(driver.pendingEffects.single().id))

        driver.assertNoPendingWork()
        assertTrue(driver.failures.isEmpty(), driver.failures.toString())
        driver.close()
    }

    @Test
    fun assertNoPendingWork_namesARunningCommand_ATimer_AnEffect_andAnUnreceivedEvent() = runTest {
        val driver = MachineTestDriver(machine, Ctx(), this, mailbox = mailbox)
        driver.start()
        driver.send(Act.Load)

        val failure = assertFailsWith<AssertionError> { driver.assertNoPendingWork() }

        val message = failure.message.orEmpty()
        assertTrue("running [c1 Fetch]" in message, message)
        assertTrue("timers [t1 remaining 10s]" in message, message)
        assertTrue("1 recorded event(s) not received: [Started]" in message, message)
        driver.receiveEvent<Ev.Started>()
        driver.complete(driver.runningCommands.single().id)
        driver.advanceBy(10.seconds)
        driver.assertActive(setOf(root, idle))
        driver.assertNoPendingWork()
        driver.close()
    }

    @Test
    fun advanceBy_firesTheTimer_andTheCommandIsCancelledWithItsNode() = runTest {
        val driver = MachineTestDriver(machine, Ctx(), this, mailbox = mailbox)
        driver.start()
        driver.send(Act.Load)
        val fetch = driver.runningCommands.single()

        val before = driver.advanceBy(9.seconds)
        assertEquals(1.seconds, before.remaining(before.snapshot.timers.keys.single()))
        driver.assertActive(setOf(root, loading))

        driver.advanceBy(1.seconds)

        driver.assertActive(setOf(root, idle))
        assertEquals(listOf(fetch.id), driver.handler.cancelled)
        driver.receiveEvent<Ev.Started>()
        driver.assertNoPendingWork()
        driver.close()
    }

    @Test
    fun aFailedCommand_isFedAsAFailure_andARejectedSend_isReported() = runTest {
        val driver = MachineTestDriver(machine, Ctx(), this, admission = AdmissionPolicy.Bounded(1), mailbox = mailbox)
        driver.start()
        driver.send(Act.Load)

        driver.fail(driver.runningCommands.single().id)

        driver.assertActive(setOf(root, loading), )
        assertTrue(driver.runningCommands.isEmpty())
        assertTrue(driver.failures.isNotEmpty(), "the command's failure reached the exception handler")
        val first = driver.sendLater(Act.Tick)
        val second = driver.sendLater(Act.Tick)
        assertEquals(Admission.Accepted, first)
        assertTrue(second is Admission.Rejected, second.toString())
        driver.settle()
        driver.assertContext(Ctx(ticks = 1))
        driver.close()
    }

    @Test
    fun settle_underAStorm_alwaysReturnsACheckpointAtTheStoresRevision() = runTest {
        val executionScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val store = MachineStore(machine, Ctx(), CommandHandler<Fetch, Act> { _, results -> results.result(Act.Loaded) }, executionScope, coroutineContext = Dispatchers.Default)
        store.start()
        val senders = 4
        val perSender = 50
        val settles = MutableStateFlow(0)

        withContext(Dispatchers.Default) {
            coroutineScope {
                repeat(senders) {
                    launch { repeat(perSender) { store.dispatch(Act.Tick); delay(Random.nextLong(0, 2).milliseconds) } }
                }
                repeat(100) {
                    launch {
                        val checkpoint = store.settle(10.seconds)
                        // Idle means the store's revision is the checkpoint's: nothing was decided between the two.
                        check(checkpoint.snapshot.revision <= store.currentState.revision)
                        settles.update { it + 1 }
                    }
                }
            }
            val last = store.settle(10.seconds)
            assertEquals(store.currentState.revision, last.snapshot.revision)
            assertEquals(senders * perSender, store.currentState.context.ticks)
        }
        assertEquals(100, settles.value)
        store.close()
        executionScope.cancel()
    }
}

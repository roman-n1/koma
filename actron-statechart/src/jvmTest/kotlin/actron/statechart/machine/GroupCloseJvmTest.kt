@file:OptIn(actron.core.InternalActronApi::class, actron.core.ExperimentalActronApi::class)

package actron.statechart.machine

import actron.core.InputAttribution

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import actron.core.Action
import actron.core.Event
import actron.core.InputId
import actron.core.StoreProbe
import actron.core.StoreTrace
import actron.observability.JournalEntry
import actron.observability.RecordingSession
import actron.observability.StoreInstanceId
import actron.statechart.ActionMatcher
import actron.statechart.AtomicState
import actron.statechart.CompoundState
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.Transition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GroupCloseJvmTest {
    private data object Apply : Action
    private data object Applied : Event

    @Test
    fun closeWaitsForTheCommittedObserverBeforeDroppingMessages() = runTest {
        val root = StateId("root")
        val idle = StateId("idle")
        val chart = StateChartDefinition(root, listOf(CompoundState(root, initial = idle), AtomicState(idle, parent = root)), listOf(Transition(idle, idle, ActionMatcher.of<Apply>("apply"), effect = actron.statechart.EffectKey("increment"))))
        val machine = Machine<Int, Apply, Nothing, Applied>(DefinitionId("counter"), DefinitionVersion("1"), chart) {
            effect("increment") { count, _ -> count + 1 }
            onEnter(idle) { if (context > 0) event(Applied) }
        }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val session = RecordingSession(backgroundScope)
        val group = MachineGroup(session)
        val sender = group.member<Int, Apply, Nothing, Applied>(StoreInstanceId("sender"))
        val receiver = group.member<Int, Apply, Nothing, Applied>(StoreInstanceId("receiver"))
        group.route(sender, receiver) { _: Applied, carry -> carry(Apply) }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CompletableDeferred<Unit>()
        val observer = object : DecisionObserver<Int, Apply, Nothing, Applied> {
            override fun onCommitted(input: InputAttribution, machineInput: MachineInput<Apply>, decision: Decision<Int, Nothing, Applied>) {
                if (machineInput is MachineInput.BridgeReceived) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            }
        }
        val from = MachineStore(machine, 0, CommandHandler<Nothing, Apply> { _, _ -> }, scope, observers = listOf(sender))
        val to = MachineStore(machine, 0, CommandHandler<Nothing, Apply> { _, _ -> }, scope, observers = listOf(observer, receiver)) {
            probe(StoreProbe { if (it === StoreTrace.StoreClosed) finished.complete(Unit) })
        }
        try {
            sender.attach(from)
            receiver.attach(to)
            from.dispatch(Apply)
            withContext(Dispatchers.Default) {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                to.close()
                assertEquals(1, group.inFlight.size, "the committed decision's observer has not finished")
                release.countDown()
                withTimeout(10_000) { finished.await() }
            }
            val entries = session.records().map { it.entry }
            assertEquals(1, entries.filterIsInstance<JournalEntry.BridgeReceived>().size)
            assertTrue(entries.none { it is JournalEntry.BridgeDropped })
            assertTrue(group.inFlight.isEmpty())
            assertEquals(1, to.currentState.context)
        } finally {
            release.countDown()
            from.close()
            to.close()
            scope.cancel()
        }
    }
}

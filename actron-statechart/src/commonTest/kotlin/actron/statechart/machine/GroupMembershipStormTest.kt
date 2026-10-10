@file:OptIn(ExperimentalActronApi::class, InternalActronApi::class, ExperimentalTime::class)

package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import actron.core.ExceptionHandler
import actron.core.ExperimentalActronApi
import actron.core.InternalActronApi
import actron.core.StoreProbe
import actron.core.StoreTrace
import actron.observability.JournalEntry
import actron.observability.MachineGroupId
import actron.observability.RecordingSession
import actron.observability.RuntimeSessionId
import actron.observability.StoreInstanceId
import actron.statechart.ActionMatcher
import actron.statechart.AtomicState
import actron.statechart.CompoundState
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.Transition
import actron.test.awaitIdle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.TestTimeSource

/**
 * A receiver that closes and comes back, twenty times, while four threads send it messages and
 * cuts are taken: every message the bridge delivered was received by one of the receiver's
 * stores or dropped when that store closed, exactly once, whichever way the store went (the
 * member left first, then the store closed, or the store closed while attached); nothing is
 * left in flight, and what the stores hold is exactly what was received. (Dropping on detach
 * was tried first: the store kept deciding between detach and close, and the journal called
 * dropped what the store had applied.)
 *
 * ```
 * picker: [*] --> Idle; Idle --Pick(name) / remember--> Idle   onEnter(Idle): event Picked(last) when there is one
 * root:   [*] --> Idle; Idle --Apply(name) / add--> Idle
 * route:  picker.Picked(name) -> root.Apply(name)
 * ```
 */
class GroupMembershipStormTest {

    data class PickerCtx(val last: String? = null)

    sealed interface PickerAct : Action {
        data class Pick(val name: String) : PickerAct
    }

    sealed interface PickerEv : Event {
        data class Picked(val name: String) : PickerEv
    }

    data class RootCtx(val names: List<String> = emptyList())

    sealed interface RootAct : Action {
        data class Apply(val name: String) : RootAct
    }

    private val root = StateId("Root")
    private val idle = StateId("Idle")
    private fun chart(matcher: ActionMatcher) = StateChartDefinition(root, listOf(CompoundState(root, initial = idle), AtomicState(idle, parent = root)), listOf(Transition(idle, idle, matcher, effect = "remember")))

    private val picker = Machine<PickerCtx, PickerAct, Nothing, PickerEv>(DefinitionId("picker"), DefinitionVersion("1"), chart(ActionMatcher.of<PickerAct.Pick>("Pick"))) {
        effect("remember") { c, a -> c.copy(last = (a as PickerAct.Pick).name) }
        onEnter(idle) { context.last?.let { event(PickerEv.Picked(it)) } }
    }

    private val rootMachine = Machine<RootCtx, RootAct, Nothing, Nothing>(DefinitionId("root"), DefinitionVersion("1"), chart(ActionMatcher.of<RootAct.Apply>("Apply"))) {
        effect("remember") { c, a -> c.copy(names = c.names + (a as RootAct.Apply).name) }
    }

    private val pickerId = StoreInstanceId("picker-1")
    private val rootId = StoreInstanceId("root-1")

    @Test
    fun aReceiverThatClosesAndComesBack_underAStorm_takesOrDropsEveryDeliveredMessage_exactlyOnce() = runTest {
        val executionScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val session = RecordingSession(backgroundScope, id = RuntimeSessionId("storm"), group = MachineGroupId("g"), timeSource = TestTimeSource())
        val group = MachineGroup(session)
        val pickerMember = group.member<PickerCtx, PickerAct, Nothing, PickerEv>(pickerId)
        val rootMember = group.member<RootCtx, RootAct, Nothing, Nothing>(rootId)
        group.route(pickerMember, rootMember) { picked: PickerEv -> (picked as? PickerEv.Picked)?.let { RootAct.Apply(it.name) } }
        val pickerStore = MachineStore(picker, PickerCtx(), CommandHandler<Nothing, PickerAct> { _, _ -> }, executionScope, coroutineContext = Dispatchers.Default, observers = listOf(pickerMember)) {
            exceptionHandler(ExceptionHandler.Ignore)
        }
        pickerMember.attach(pickerStore)
        pickerStore.start()
        val roots = mutableListOf<MachineStore<RootCtx, RootAct, Nothing, Nothing>>()
        val closedRoots = mutableListOf<CompletableDeferred<Unit>>()
        fun newRoot() {
            val closed = CompletableDeferred<Unit>()
            closedRoots += closed
            val store = MachineStore(rootMachine, RootCtx(), CommandHandler<Nothing, RootAct> { _, _ -> }, executionScope, coroutineContext = Dispatchers.Default, observers = listOf(rootMember)) {
                exceptionHandler(ExceptionHandler.Ignore)
                probe(StoreProbe { if (it === StoreTrace.StoreClosed) closed.complete(Unit) })
            }
            rootMember.attach(store)
            store.start()
            roots += store
        }
        newRoot()
        val senders = 4
        val perSender = 60
        val recreations = 20
        var cuts = 0
        var failedCuts = 0

        withContext(Dispatchers.Default) {
            coroutineScope {
                repeat(senders) { sender ->
                    launch {
                        repeat(perSender) { index ->
                            pickerStore.dispatch(PickerAct.Pick("$sender-$index"))
                            if (index % 5 == 0) delay(1.milliseconds)
                        }
                    }
                }
                launch {
                    repeat(recreations) { round ->
                        delay(Random.nextLong(0, 4).milliseconds)
                        val current = roots.last()
                        // Every other time the member leaves first, and its store decides what
                        // it holds meanwhile; otherwise the store closes while attached.
                        if (round % 2 == 0) rootMember.detach()
                        current.close()
                        newRoot()
                    }
                }
                launch {
                    repeat(10) {
                        if (group.checkpoint(5.seconds) == null) failedCuts++ else cuts++
                        delay(Random.nextLong(0, 3).milliseconds)
                    }
                }
            }
            pickerStore.awaitIdle(30.seconds)
            roots.last().awaitIdle(30.seconds)
            // Old stores may still be finishing their last observer after close returned.
            for (closed in closedRoots.dropLast(1)) closed.await()
        }

        val entries = session.records().map { it.entry }
        val sent = entries.filterIsInstance<JournalEntry.BridgeSent>()
        val delivered = sent.filter { it.delivered }.map { it.message }
        val received = entries.filterIsInstance<JournalEntry.BridgeReceived>().map { it.message }
        val dropped = entries.filterIsInstance<JournalEntry.BridgeDropped>().map { it.message }
        assertEquals(senders * perSender, sent.size, "every pick was sent once")
        assertEquals(delivered.size, received.size + dropped.size, "every delivered message was received or dropped, once: ${received.size} received, ${dropped.size} dropped")
        assertEquals(delivered.toSet(), (received + dropped).toSet())
        assertEquals(received.size, received.toSet().size, "no message received twice")
        assertEquals(dropped.size, dropped.toSet().size, "no message dropped twice")
        assertTrue(received.none { it in dropped }, "no message both received and dropped")
        assertTrue(group.inFlight.isEmpty(), "left in flight: ${group.inFlight}")
        assertEquals(received.size, roots.sumOf { it.currentState.context.names.size }, "what the stores hold is what the group counts as received")
        assertEquals(10, cuts + failedCuts)
        println("received ${received.size}, dropped ${dropped.size}, undelivered ${sent.size - delivered.size}, cuts $cuts, failed cuts $failedCuts")
        for (store in roots) store.close()
        pickerStore.close()
        executionScope.cancel()
    }
}

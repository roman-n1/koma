@file:OptIn(ExperimentalKomaApi::class, ExperimentalCoroutinesApi::class, ExperimentalTime::class)

package koma.statechart.machine

import koma.core.Action
import koma.core.Event
import koma.core.ExceptionHandler
import koma.core.ExperimentalKomaApi
import koma.core.Plugin
import koma.core.PluginScope
import koma.observability.JournalEntry
import koma.observability.MachineGroupId
import koma.observability.MessageRef
import koma.observability.RecordingSession
import koma.observability.RuntimeSessionId
import koma.observability.StoreInstanceId
import koma.statechart.ActionMatcher
import koma.statechart.AtomicState
import koma.statechart.CompoundState
import koma.statechart.StateChartDefinition
import koma.statechart.StateId
import koma.statechart.Transition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.TestTimeSource

/**
 * A group of two machines talking through the bridge: a picker whose picks are routed to a root
 * as applies, whose acknowledgements are routed back. Deliveries carry the sender's message id
 * and are decided once, go nowhere when the receiver is not attached, and a consistent cut
 * freezes the members' queues, waits for what they had accepted, and lets everything held in
 * afterwards, in order and once.
 *
 * ```
 * picker: [*] --> Idle; Idle --Pick(name) / remember--> Idle   onEnter(Idle): event Picked(last) when there is one; command Fetch(last)
 *         onAction Ack(n) / count acks; onAction Fetched(name) / count fetched
 * root:   [*] --> Idle; Idle --Apply(name) / add--> Idle       onEnter(Idle): event Applied(size) when not empty
 * routes: picker.Picked(name) -> root.Apply(name); root.Applied(n) -> picker.Ack(n)
 * ```
 */
class MachineGroupTest {

    data class PickerCtx(val last: String? = null, val acks: List<Int> = emptyList(), val fetched: List<String> = emptyList())

    sealed interface PickerAct : Action {
        data class Pick(val name: String) : PickerAct
        data class Ack(val applied: Int) : PickerAct
        data class Fetched(val name: String) : PickerAct
    }

    sealed interface PickerEv : Event {
        data class Picked(val name: String) : PickerEv
    }

    data class Fetch(val name: String)

    data class RootCtx(val names: List<String> = emptyList())

    sealed interface RootAct : Action {
        data class Apply(val name: String) : RootAct
    }

    sealed interface RootEv : Event {
        data class Applied(val count: Int) : RootEv
    }

    private val root = StateId("Root")
    private val idle = StateId("Idle")
    private fun chart(matcher: ActionMatcher) = StateChartDefinition(root, listOf(CompoundState(root, initial = idle), AtomicState(idle, parent = root)), listOf(Transition(idle, idle, matcher, effect = "remember")))

    private val picker = Machine<PickerCtx, PickerAct, Fetch, PickerEv>(DefinitionId("picker"), DefinitionVersion("1"), chart(ActionMatcher.of<PickerAct.Pick>("Pick"))) {
        effect("remember") { c, a -> c.copy(last = (a as PickerAct.Pick).name) }
        onEnter(idle) {
            context.last?.let { last ->
                event(PickerEv.Picked(last))
                command(Fetch(last))
            }
        }
        onAction(root, ActionMatcher.of<PickerAct.Ack>("Ack")) { context = context.copy(acks = context.acks + (action as PickerAct.Ack).applied) }
        onAction(root, ActionMatcher.of<PickerAct.Fetched>("Fetched")) { context = context.copy(fetched = context.fetched + (action as PickerAct.Fetched).name) }
    }

    private val rootMachine = Machine<RootCtx, RootAct, Nothing, RootEv>(DefinitionId("root"), DefinitionVersion("1"), chart(ActionMatcher.of<RootAct.Apply>("Apply"))) {
        effect("remember") { c, a -> c.copy(names = c.names + (a as RootAct.Apply).name) }
        onEnter(idle) { if (context.names.isNotEmpty()) event(RootEv.Applied(context.names.size)) }
    }

    private val pickerId = StoreInstanceId("picker-1")
    private val rootId = StoreInstanceId("root-1")

    private class TestClock(private val scheduler: TestCoroutineScheduler) : MachineClock {
        override fun now(): MachineTime = MachineTime(scheduler.currentTime.milliseconds)

        override suspend fun delayUntil(deadline: MachineTime) {
            val remaining = deadline - now()
            if (remaining.isPositive()) delay(remaining)
        }
    }

    /** The group with both members attached and routed, on a journal. */
    private inner class Fixture(scope: TestScope, attachRoot: Boolean = true, pickerPlugin: Plugin<MachineSnapshot<PickerCtx>, MachineInput<PickerAct>, PickerEv>? = null, routed: Boolean = true) {
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val executionScope = CoroutineScope(dispatcher + SupervisorJob())
        val session = RecordingSession(scope.backgroundScope, id = RuntimeSessionId("g"), group = MachineGroupId("picker"), timeSource = TestTimeSource())
        val group = MachineGroup(session)
        val pickerMember = group.member<PickerCtx, PickerAct, Fetch, PickerEv>(pickerId)
        val rootMember = group.member<RootCtx, RootAct, Nothing, RootEv>(rootId)
        val fetches = mutableListOf<String>()
        val pickerStore = MachineStore(
            picker, PickerCtx(), CommandHandler<Fetch, PickerAct> { command, results ->
                delay(50.milliseconds)
                fetches += command.command.name
                results.result(PickerAct.Fetched(command.command.name))
            },
            executionScope, TestClock(scope.testScheduler), dispatcher, admission = AdmissionPolicy.Bounded(1), observers = listOf(pickerMember),
        ) {
            exceptionHandler(ExceptionHandler.Ignore)
            pickerPlugin?.let { plugin(it) }
        }
        val rootStore = MachineStore(rootMachine, RootCtx(), CommandHandler<Nothing, RootAct> { _, _ -> }, executionScope, TestClock(scope.testScheduler), dispatcher, observers = listOf(rootMember)) {
            exceptionHandler(ExceptionHandler.Ignore)
        }

        init {
            if (routed) {
                group.route<PickerEv, RootAct>(pickerId, rootId) { (it as? PickerEv.Picked)?.let { picked -> RootAct.Apply(picked.name) } }
                group.route<RootEv, PickerAct>(rootId, pickerId) { (it as? RootEv.Applied)?.let { applied -> PickerAct.Ack(applied.count) } }
            }
            pickerMember.attach(pickerStore)
            if (attachRoot) rootMember.attach(rootStore)
            pickerStore.start()
            rootStore.start()
        }

        fun close() {
            pickerStore.close()
            rootStore.close()
        }
    }

    @Test
    fun aRoutedEffect_isDeliveredWithTheSendersMessageId_andDecidedOnce() = runTest {
        val f = Fixture(this)
        runCurrent()

        f.pickerStore.dispatch(PickerAct.Pick("tom"))
        runCurrent()

        assertEquals(listOf("tom"), f.rootStore.currentState.context.names, "the pick reached the root")
        assertEquals(listOf(1), f.pickerStore.currentState.context.acks, "the root's acknowledgement came back")
        assertTrue(f.group.inFlight.isEmpty(), "every message was decided: ${f.group.inFlight}")
        val entries = f.session.records().map { it.entry }
        val sent = entries.filterIsInstance<JournalEntry.BridgeSent>()
        val received = entries.filterIsInstance<JournalEntry.BridgeReceived>()
        assertEquals(listOf(MessageRef(pickerId, 1) to rootId, MessageRef(rootId, 1) to pickerId), sent.map { it.message to it.to })
        assertTrue(sent.all { it.delivered })
        assertEquals(sent.map { it.message }, received.map { it.message }, "each message was received exactly once")
        assertEquals(listOf(pickerId, rootId), f.group.memberIds)
        assertEquals(2, f.group.routes.size)
        f.close()
    }

    @Test
    fun aMessageToAnUnattachedMember_goesNowhere_andIsJournaledUndelivered() = runTest {
        val f = Fixture(this, attachRoot = false)
        runCurrent()

        f.pickerStore.dispatch(PickerAct.Pick("tom"))
        runCurrent()

        assertTrue(f.rootStore.currentState.context.names.isEmpty())
        val sent = f.session.records().map { it.entry }.filterIsInstance<JournalEntry.BridgeSent>().single()
        assertEquals(false, sent.delivered)
        assertEquals(rootId, sent.to)
        assertTrue(f.group.inFlight.isEmpty(), "nothing undelivered is in flight")
        f.close()
    }

    @Test
    fun aTypedRoute_carriesLikeAnIdRoute_andARemovedRoute_carriesNothingMore_butStaysInTheHistory() = runTest {
        val f = Fixture(this, routed = false)
        // The receiver's action type is checked where the route is written: RootAct for the root.
        val toRoot = f.group.route(f.pickerMember, f.rootMember) { picked: PickerEv -> (picked as? PickerEv.Picked)?.let { RootAct.Apply(it.name) } }
        runCurrent()

        f.pickerStore.dispatch(PickerAct.Pick("tom"))
        runCurrent()
        assertEquals(listOf("tom"), f.rootStore.currentState.context.names)

        assertTrue(f.group.removeRoute(toRoot))
        assertFalse(f.group.removeRoute(toRoot), "removed once")
        f.pickerStore.dispatch(PickerAct.Pick("ann"))
        runCurrent()

        assertEquals(listOf("tom"), f.rootStore.currentState.context.names, "the removed route carries nothing")
        assertEquals(emptyList(), f.group.routes)
        assertEquals(listOf(toRoot), f.group.routeHistory, "a recording still knows the route existed")
        val sent = f.session.records().map { it.entry }.filterIsInstance<JournalEntry.BridgeSent>()
        assertEquals(listOf(MessageRef(pickerId, 1)), sent.map { it.message }, "nothing was sent for ann: no route, no message")
        f.close()
    }

    @Test
    fun aRequestReplyPair_isTwoRoutes_andAReplyDecidedFromTheRequest_namesIt() = runTest {
        val f = Fixture(this, routed = false)
        val pair = f.group.requestReply(
            f.pickerMember, f.rootMember, "apply",
            request = { picked: PickerEv -> (picked as? PickerEv.Picked)?.let { RootAct.Apply(it.name) } },
            reply = { applied: RootEv -> (applied as? RootEv.Applied)?.let { PickerAct.Ack(it.count) } },
        )
        runCurrent()
        assertEquals(listOf(pair.request, pair.reply), f.group.routes)
        assertEquals(RoutePair("apply", PairRole.Request), pair.request.pair)
        assertEquals(RoutePair("apply", PairRole.Reply), pair.reply.pair)
        assertEquals("$pickerId -> $rootId (apply Request)", pair.request.toString())

        f.pickerStore.dispatch(PickerAct.Pick("tom"))
        runCurrent()

        assertEquals(listOf("tom"), f.rootStore.currentState.context.names)
        assertEquals(listOf(1), f.pickerStore.currentState.context.acks)
        val sent = f.session.records().map { it.entry }.filterIsInstance<JournalEntry.BridgeSent>()
        assertEquals(listOf(MessageRef(pickerId, 1) to rootId, MessageRef(rootId, 1) to pickerId), sent.map { it.message to it.to })
        assertEquals(listOf<MessageRef?>(null, MessageRef(pickerId, 1)), sent.map { it.cause }, "the request replies to nothing; the reply, decided from the request, names it")
        f.close()
    }

    @Test
    fun aMemberWhoseStoreClosed_getsNoMoreMessages_andWhatWasInFlightToIt_isDropped() = runTest {
        val f = Fixture(this)
        runCurrent()
        // The root holds its inputs: the message is delivered, in flight, and not decided.
        (f.rootStore as MachineStoreImpl).freeze()
        f.pickerStore.dispatch(PickerAct.Pick("tom"))
        runCurrent()
        assertEquals(listOf(BridgeMessage(MessageId(pickerId, EffectId(1)), rootId)), f.group.inFlight)

        f.rootStore.close()

        assertTrue(f.group.inFlight.isEmpty(), "a closed store decides nothing more: dropped")
        assertFalse(f.rootMember.isAttached)
        val entries = f.session.records().map { it.entry }
        assertEquals(listOf(JournalEntry.BridgeDropped(MessageRef(pickerId, 1), rootId, "StoreClosed")), entries.filterIsInstance<JournalEntry.BridgeDropped>())
        assertTrue(entries.filterIsInstance<JournalEntry.BridgeReceived>().none { it.message.from == pickerId }, "never received")

        f.pickerStore.dispatch(PickerAct.Pick("ann"))
        runCurrent()
        val toClosed = f.session.records().map { it.entry }.filterIsInstance<JournalEntry.BridgeSent>().last()
        assertEquals(MessageRef(pickerId, 2) to false, toClosed.message to toClosed.delivered, "a message to a closed member goes nowhere")
        assertTrue(f.group.inFlight.isEmpty())

        // A cut does not wait for the closed member.
        val cut = async { f.group.checkpoint(1.seconds) }
        runCurrent()
        assertEquals(setOf(pickerId), cut.await()?.members?.keys)
        f.pickerStore.close()
    }

    @Test
    fun aDetachedMember_leavesTheGroup_andTakesPartAgainWhenAttached() = runTest {
        val f = Fixture(this)
        runCurrent()
        (f.rootStore as MachineStoreImpl).freeze()
        f.pickerStore.dispatch(PickerAct.Pick("tom"))
        runCurrent()
        assertEquals(1, f.group.inFlight.size)

        f.rootMember.detach()

        assertEquals(1, f.group.inFlight.size, "delivered before it left: in flight until its store decides it or closes")
        assertFalse(f.rootMember.isAttached)

        // The store goes on and decides what it held: received, as any delivered message; its
        // own effects are not routed any more.
        (f.rootStore as MachineStoreImpl).thaw()
        runCurrent()
        assertEquals(listOf("tom"), f.rootStore.currentState.context.names)
        assertTrue(f.group.inFlight.isEmpty())
        assertEquals(listOf(MessageRef(pickerId, 1)), f.session.records().map { it.entry }.filterIsInstance<JournalEntry.BridgeReceived>().filter { it.message.from == pickerId }.map { it.message })
        assertTrue(f.session.records().map { it.entry }.none { it is JournalEntry.BridgeDropped })
        assertTrue(f.pickerStore.currentState.context.acks.isEmpty(), "a detached member's effects are not routed")

        f.pickerStore.dispatch(PickerAct.Pick("ann"))
        runCurrent()
        assertEquals(false, f.session.records().map { it.entry }.filterIsInstance<JournalEntry.BridgeSent>().last().delivered, "a message to a detached member goes nowhere")
        val cut = async { f.group.checkpoint(1.seconds) }
        runCurrent()
        assertEquals(setOf(pickerId), cut.await()?.members?.keys, "a cut without the detached member")

        f.rootMember.attach(f.rootStore)
        assertTrue(f.rootMember.isAttached)
        f.pickerStore.dispatch(PickerAct.Pick("bob"))
        runCurrent()
        assertEquals(listOf("tom", "bob"), f.rootStore.currentState.context.names, "attached again, it takes part again")
        assertTrue(f.pickerStore.currentState.context.acks.isNotEmpty(), "and its effects are routed")
        f.close()
    }

    @Test
    fun aDetachedMemberWhoseStoreThenCloses_dropsWhatItHeld() = runTest {
        val f = Fixture(this)
        runCurrent()
        (f.rootStore as MachineStoreImpl).freeze()
        f.pickerStore.dispatch(PickerAct.Pick("tom"))
        runCurrent()
        f.rootMember.detach()
        assertEquals(1, f.group.inFlight.size)

        f.rootStore.close()

        assertTrue(f.group.inFlight.isEmpty())
        assertEquals(listOf(JournalEntry.BridgeDropped(MessageRef(pickerId, 1), rootId, "StoreClosed")), f.session.records().map { it.entry }.filterIsInstance<JournalEntry.BridgeDropped>())
        f.pickerStore.close()
    }

    @Test
    fun deliver_bypassesTheAdmissionBound() = runTest {
        val f = Fixture(this)
        runCurrent()
        // One dispatched action fills the picker's bound of one; a delivery still gets in.
        f.pickerStore.dispatch(PickerAct.Pick("a"))
        assertEquals(Admission.Rejected(1, 1), f.pickerStore.admit(PickerAct.Pick("b")))
        f.pickerStore.deliver(MessageId(rootId, EffectId(99)), PickerAct.Ack(42))
        runCurrent()

        assertTrue(42 in f.pickerStore.currentState.context.acks)
        f.close()
    }

    @Test
    fun aCut_freezesTheMembers_andLetsWhatArrivedIn_afterwardsInOrder() = runTest {
        val f = Fixture(this)
        runCurrent()
        f.pickerStore.dispatch(PickerAct.Pick("a"))
        runCurrent()

        val cut = f.group.checkpoint(1.seconds)

        assertTrue(cut != null)
        assertEquals(setOf(pickerId, rootId), cut.members.keys)
        assertEquals(listOf("a"), (cut.members.getValue(rootId).snapshot.context as RootCtx).names)
        assertTrue(cut.inFlight.isEmpty())
        assertEquals(mapOf(pickerId to 0, rootId to 0), cut.held)
        assertTrue(cut.boundary != null && cut.boundary!!.value > 0)

        // Frozen by hand: inputs from every source wait, in arrival order, and enter after the thaw.
        val pickerImpl = f.pickerStore as MachineStoreImpl<PickerCtx, PickerAct, Fetch, PickerEv>
        pickerImpl.freeze()
        f.pickerStore.dispatch(PickerAct.Pick("b"))
        f.pickerStore.deliver(MessageId(rootId, EffectId(7)), PickerAct.Ack(7))
        runCurrent()
        assertEquals("a", f.pickerStore.currentState.context.last, "nothing entered while frozen")
        assertEquals(2, pickerImpl.heldInputs)
        pickerImpl.thaw()
        runCurrent()
        assertEquals("b", f.pickerStore.currentState.context.last)
        assertTrue(7 in f.pickerStore.currentState.context.acks)
        assertEquals(listOf("a", "b"), f.rootStore.currentState.context.names, "the held pick reached the root after the thaw")
        f.close()
    }

    @Test
    fun aResultArrivingDuringTheCut_isAppliedOnce_afterIt() = runTest {
        val f = Fixture(this)
        runCurrent()
        f.pickerStore.dispatch(PickerAct.Pick("a"))
        runCurrent()
        val pickerImpl = f.pickerStore as MachineStoreImpl<PickerCtx, PickerAct, Fetch, PickerEv>

        pickerImpl.freeze()
        advanceTimeBy(60.milliseconds)
        runCurrent()

        assertEquals(listOf("a"), f.fetches, "the handler ran during the cut")
        assertTrue(f.pickerStore.currentState.context.fetched.isEmpty(), "its result waited")
        pickerImpl.thaw()
        runCurrent()
        assertEquals(listOf("a"), f.pickerStore.currentState.context.fetched, "applied once, after the cut")
        f.close()
    }

    @Test
    fun aMemberThatDoesNotSettle_abortsTheCut_andTheGroupResumes() = runTest {
        val gate = CompletableDeferred<Unit>()
        var stuck = false
        val slow = object : Plugin<MachineSnapshot<PickerCtx>, MachineInput<PickerAct>, PickerEv> {
            override suspend fun onState(scope: PluginScope<MachineSnapshot<PickerCtx>, MachineInput<PickerAct>>, prevState: MachineSnapshot<PickerCtx>, state: MachineSnapshot<PickerCtx>) {
                if (state.context.last == "slow" && !stuck) {
                    stuck = true
                    gate.await()
                }
            }
        }
        val f = Fixture(this, pickerPlugin = slow)
        runCurrent()
        f.pickerStore.dispatch(PickerAct.Pick("slow"))
        runCurrent()
        assertTrue(stuck, "the picker is mid-processing")

        val cut = async { f.group.checkpoint(100.milliseconds) }
        f.rootStore.dispatch(RootAct.Apply("during"))
        advanceTimeBy(200.milliseconds)
        runCurrent()

        assertNull(cut.await(), "the cut timed out")
        // "slow" reached the root before the picker got stuck (plugins run in parallel, the
        // group's observer ran); "during" was held by the failed cut and entered after it.
        assertEquals(listOf("slow", "during"), f.rootStore.currentState.context.names, "the group resumed and the input held during the failed cut entered")
        gate.complete(Unit)
        runCurrent()
        assertTrue(f.pickerStore.currentState.context.acks.isNotEmpty(), "the picker finished its processing and got the root's acknowledgements")
        val again = f.group.checkpoint(1.seconds)
        assertTrue(again != null && again.inFlight.isEmpty())
        f.close()
    }

    @Test
    fun aCutListener_seesTheCut_whileTheMembersAreFrozen_andAThrowingOne_failsTheCut_butTheGroupResumes() = runTest {
        val f = Fixture(this)
        runCurrent()
        f.pickerStore.dispatch(PickerAct.Pick("a"))
        runCurrent()
        val seen = mutableListOf<GroupCheckpoint>()
        val heldDuring = mutableListOf<Int>()
        val pickerImpl = f.pickerStore as MachineStoreImpl<PickerCtx, PickerAct, Fetch, PickerEv>
        val listener = CutListener { checkpoint ->
            seen += checkpoint
            // Inside the cut: an input dispatched now waits in the frozen queue.
            f.pickerStore.dispatch(PickerAct.Pick("inside"))
            heldDuring += pickerImpl.heldInputs
        }
        f.group.onCut(listener)

        val cut = f.group.checkpoint(1.seconds)

        assertEquals(listOf(cut), seen, "the listener saw the cut the caller got")
        assertEquals(listOf(1), heldDuring, "the members were frozen while the listener ran")
        runCurrent()
        assertEquals("inside", f.pickerStore.currentState.context.last, "what the listener dispatched entered after the thaw")

        f.group.removeCutListener(listener)
        val failing = CutListener { throw IllegalStateException("the listener failed") }
        f.group.onCut(failing)
        val failure = assertFailsWith<IllegalStateException> { f.group.checkpoint(1.seconds) }
        assertEquals("the listener failed", failure.message)
        assertEquals(1, seen.size, "a removed listener is not called")
        f.group.removeCutListener(failing)
        f.pickerStore.dispatch(PickerAct.Pick("after"))
        runCurrent()
        assertEquals("after", f.pickerStore.currentState.context.last, "the group resumed after the failed cut")
        val again = f.group.checkpoint(1.seconds)
        assertTrue(again != null, "a cut without listeners succeeds again")
        f.close()
    }
}

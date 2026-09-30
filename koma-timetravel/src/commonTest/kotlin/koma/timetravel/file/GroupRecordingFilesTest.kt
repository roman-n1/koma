@file:OptIn(ExperimentalKomaApi::class, ExperimentalCoroutinesApi::class)

package koma.timetravel.file

import koma.core.Action
import koma.core.Event
import koma.core.ExceptionHandler
import koma.core.ExperimentalKomaApi
import koma.observability.MachineGroupId
import koma.observability.StoreInstanceId
import koma.observability.file.InMemorySegmentStorage
import koma.statechart.ActionMatcher
import koma.statechart.AtomicState
import koma.statechart.CompoundState
import koma.statechart.StateChartDefinition
import koma.statechart.StateId
import koma.statechart.Transition
import koma.statechart.machine.CommandHandler
import koma.statechart.machine.DefinitionId
import koma.statechart.machine.DefinitionVersion
import koma.statechart.machine.Machine
import koma.statechart.machine.MachineGroup
import koma.statechart.machine.MachineStore
import koma.timetravel.GroupRecorder
import koma.timetravel.GroupReplaySession
import koma.timetravel.RecordingCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A group recorded to files as it runs: the order file and the members' files read back as the
 * in-memory recorder saw the run and replay; a ring that dropped a member's early segments
 * yields the range every file still covers, with the messages in flight at its start; an order
 * entry the writer had no room for starts a new range.
 *
 * ```
 * ping: [*] --> Idle; Idle --Kick(n) / remember--> Idle     onEnter(Idle): event Ping(last) when there is one;  onAction Acked(n) / acks += n
 * pong: [*] --> Idle; Idle --Pong(n) / remember--> Idle     onEnter(Idle): event Ack(last) when there is one
 * routes: ping.Ping(n) -> pong.Pong(n); pong.Ack(n) -> ping.Acked(n)
 * ```
 */
class GroupRecordingFilesTest {

    @Serializable
    data class PingCtx(val last: Int? = null, val acks: List<Int> = emptyList())

    @Serializable
    sealed interface PingAct : Action {
        @Serializable
        @SerialName("Kick")
        data class Kick(val n: Int) : PingAct

        @Serializable
        @SerialName("Acked")
        data class Acked(val n: Int) : PingAct
    }

    @Serializable
    sealed interface PingEv : Event {
        @Serializable
        @SerialName("Ping")
        data class Ping(val n: Int) : PingEv
    }

    @Serializable
    data class PongCtx(val last: Int? = null, val count: Int = 0)

    @Serializable
    sealed interface PongAct : Action {
        @Serializable
        @SerialName("Pong")
        data class Pong(val n: Int) : PongAct
    }

    @Serializable
    sealed interface PongEv : Event {
        @Serializable
        @SerialName("Ack")
        data class Ack(val n: Int) : PongEv
    }

    @Serializable
    data object NoCommand

    private val root = StateId("Root")
    private val idle = StateId("Idle")
    private fun chart(matcher: ActionMatcher) = StateChartDefinition(root, listOf(CompoundState(root, initial = idle), AtomicState(idle, parent = root)), listOf(Transition(idle, idle, matcher, effect = "remember")))

    private val ping = Machine<PingCtx, PingAct, NoCommand, PingEv>(DefinitionId("ping"), DefinitionVersion("1"), chart(ActionMatcher.of<PingAct.Kick>("Kick"))) {
        effect("remember") { c, a -> c.copy(last = (a as PingAct.Kick).n) }
        onEnter(idle) { context.last?.let { event(PingEv.Ping(it)) } }
        onAction(root, ActionMatcher.of<PingAct.Acked>("Acked")) { context = context.copy(acks = context.acks + (action as PingAct.Acked).n) }
    }

    private val pong = Machine<PongCtx, PongAct, NoCommand, PongEv>(DefinitionId("pong"), DefinitionVersion("1"), chart(ActionMatcher.of<PongAct.Pong>("Pong"))) {
        effect("remember") { c, a -> c.copy(last = (a as PongAct.Pong).n, count = c.count + 1) }
        onEnter(idle) { context.last?.let { event(PongEv.Ack(it)) } }
    }

    private val pingId = StoreInstanceId("ping-1")
    private val pongId = StoreInstanceId("pong-1")
    private val groupId = MachineGroupId("pingpong")
    private val pingCodec = RecordingCodec(PingCtx.serializer(), PingAct.serializer(), NoCommand.serializer(), PingEv.serializer())
    private val pongCodec = RecordingCodec(PongCtx.serializer(), PongAct.serializer(), NoCommand.serializer(), PongEv.serializer())
    private val codecs: Map<StoreInstanceId, RecordingCodec<*, *, *, *>> = mapOf(pingId to pingCodec, pongId to pongCodec)
    private val machines: Map<StoreInstanceId, Machine<*, *, *, *>> = mapOf(pingId to ping, pongId to pong)

    private inner class Live(scope: TestScope, config: RecordingFileConfig = RecordingFileConfig()) {
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val executionScope = CoroutineScope(dispatcher + SupervisorJob())
        val storage = InMemorySegmentStorage()
        val group = MachineGroup()
        val recorder = GroupRecorder(group)
        val files = GroupRecordingFileSink(group, groupId, storage, executionScope, config)
        val pingMember = group.member<PingCtx, PingAct, NoCommand, PingEv>(pingId)
        val pongMember = group.member<PongCtx, PongAct, NoCommand, PongEv>(pongId)
        val pingStore = MachineStore(ping, PingCtx(), CommandHandler<NoCommand, PingAct> { _, _ -> }, executionScope, coroutineContext = dispatcher, observers = listOf(pingMember, recorder.member(pingId, ping, PingCtx()), files.member(pingId, ping, PingCtx(), pingCodec))) { exceptionHandler(ExceptionHandler.Ignore) }
        val pongStore = MachineStore(pong, PongCtx(), CommandHandler<NoCommand, PongAct> { _, _ -> }, executionScope, coroutineContext = dispatcher, observers = listOf(pongMember, recorder.member(pongId, pong, PongCtx()), files.member(pongId, pong, PongCtx(), pongCodec))) { exceptionHandler(ExceptionHandler.Ignore) }

        init {
            group.route<PingEv, PongAct>(pingId, pongId) { (it as? PingEv.Ping)?.let { p -> PongAct.Pong(p.n) } }
            group.route<PongEv, PingAct>(pongId, pingId) { (it as? PongEv.Ack)?.let { a -> PingAct.Acked(a.n) } }
            pingMember.attach(pingStore)
            pongMember.attach(pongStore)
            pingStore.start()
            pongStore.start()
        }

        suspend fun close() {
            pingStore.close()
            pongStore.close()
            files.close()
        }
    }

    private fun TestScope.kicks(live: Live, count: Int) {
        runCurrent()
        for (n in 1..count) {
            live.pingStore.dispatch(PingAct.Kick(n))
            runCurrent()
        }
    }

    @Test
    fun aRecordedGroup_readsBackAsTheRecorderSawIt_andReplays() = runTest {
        val live = Live(this)
        kicks(live, 3)
        live.close()
        val expected = live.recorder.recording()

        val contents = GroupRecordingFiles(live.storage).read(groupId, codecs)

        assertTrue(contents.marks.isEmpty(), contents.marks.toString())
        assertEquals(0, contents.position)
        val read = checkNotNull(contents.recording)
        assertEquals(expected.order, read.order)
        assertEquals(expected.members.getValue(pingId).steps, read.members.getValue(pingId).steps)
        assertEquals(expected.members.getValue(pongId).steps, read.members.getValue(pongId).steps)
        assertEquals(expected.routes, read.routes)
        assertTrue(read.inFlight.isEmpty())
        assertEquals(emptyList(), GroupReplaySession(machines, read).verify())
        assertEquals(listOf(groupId), GroupRecordingFiles(live.storage).groups())
        assertEquals(setOf(pingId, pongId), RecordingFiles(live.storage).stores().toSet())
        assertEquals(0L, live.files.dropped)
    }

    @Test
    fun aRingThatDroppedEarlySegments_yieldsTheRangeEveryFileCovers_withItsMessagesInFlight() = runTest {
        val live = Live(this, RecordingFileConfig(maxSegmentBytes = 900, maxSegments = 2))
        kicks(live, 8)
        live.close()
        val whole = live.recorder.recording()

        val contents = GroupRecordingFiles(live.storage).read(groupId, codecs)

        assertTrue(contents.marks.any { it is RecordingFileMark.MissingSegments }, contents.marks.toString())
        val read = checkNotNull(contents.recording)
        val position = checkNotNull(contents.position)
        assertTrue(position > 0 && read.length < whole.length, "position $position, ${read.length} of ${whole.length}")
        assertEquals(whole.order.drop(position).map { it.store }, read.order.map { it.store }, "the order's tail, member by member")
        for ((member, recording) in read.members) {
            val offset = whole.order.drop(position).firstOrNull { it.store == member }?.step ?: whole.members.getValue(member).length
            assertEquals(whole.members.getValue(member).steps.drop(offset), recording.steps, "$member's steps from the range on")
            assertEquals(whole.members.getValue(member).checkpointAt(offset).snapshot, recording.start.snapshot, "$member begins at its checkpoint there")
        }
        assertEquals(emptyList(), GroupReplaySession(machines, read).verify(), "replays, the messages in flight at the range's start known")
    }

    @Test
    fun anOrderEntryTheWriterHadNoRoomFor_startsANewRange() = runTest {
        val live = Live(this, RecordingFileConfig(queueCapacity = 1))
        runCurrent()
        // Three kicks decided before the writers run: the order file and the members drop entries.
        live.pingStore.dispatch(PingAct.Kick(1))
        live.pingStore.dispatch(PingAct.Kick(2))
        live.pingStore.dispatch(PingAct.Kick(3))
        runCurrent()
        live.pingStore.dispatch(PingAct.Kick(4))
        runCurrent()
        live.close()
        val whole = live.recorder.recording()

        val contents = GroupRecordingFiles(live.storage).read(groupId, codecs)

        assertTrue(live.files.dropped > 0)
        assertTrue(contents.marks.any { it is RecordingFileMark.StepsMissing }, contents.marks.toString())
        val read = checkNotNull(contents.recording)
        val position = checkNotNull(contents.position)
        assertTrue(position > 0)
        assertEquals(whole.order.drop(position).map { it.store }, read.order.map { it.store })
        assertEquals(emptyList(), GroupReplaySession(machines, read).verify())
    }

    @Test
    fun anOrderSegment_roundTrips_andEveryCutIsAPrefix() {
        val header = GroupSegmentHeader(RECORDING_FILE_FORMAT_VERSION, groupId, listOf(pingId, pongId), listOf(koma.timetravel.GroupRoute(pingId, pongId)), setOf(koma.statechart.machine.SourceId("paging:x")), 3, 40, listOf(koma.statechart.machine.MessageId(pingId, koma.statechart.machine.EffectId(7))))
        val entries = listOf(
            GroupOrderEntry(pingId, 5, listOf(koma.statechart.machine.MessageId(pingId, koma.statechart.machine.EffectId(8)))),
            GroupOrderEntry(pongId, 4, received = koma.statechart.machine.MessageId(pingId, koma.statechart.machine.EffectId(8))),
            GroupOrderEntry(pingId, 6),
        )
        var bytes = GroupRecordingFileFormat.header(header)
        for (entry in entries) bytes += GroupRecordingFileFormat.entryFrame(entry)
        bytes += koma.observability.file.Framing.END

        val decoded = GroupRecordingFileFormat.decodeSegment("pingpong-000003.group", bytes)

        assertEquals(header, decoded.header)
        assertEquals(entries, decoded.entries)
        assertTrue(decoded.finished && decoded.mark == null)
        for (length in 0 until bytes.size) {
            val cut = GroupRecordingFileFormat.decodeSegment("cut", bytes.copyOf(length))
            assertEquals(entries.take(cut.entries.size), cut.entries, "cut at $length")
            assertTrue(cut.mark != null, "cut at $length")
        }
        assertEquals(groupId to 3, GroupRecordingFileFormat.parseSegmentName("pingpong-000003.group"))
        assertIs<koma.observability.file.SegmentMark.UnsupportedFormat>(GroupRecordingFileFormat.decodeSegment("n", GroupRecordingFileFormat.header(header.copy(fileFormatVersion = RECORDING_FILE_FORMAT_VERSION + 1)) + koma.observability.file.Framing.END).mark)
    }
}

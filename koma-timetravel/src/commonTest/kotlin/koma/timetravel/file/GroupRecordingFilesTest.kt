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
import koma.statechart.machine.EffectId
import koma.statechart.machine.ExternalSource
import koma.statechart.machine.Machine
import koma.statechart.machine.MachineGroup
import koma.statechart.machine.MachineStore
import koma.statechart.machine.MessageId
import koma.statechart.machine.PairRole
import koma.statechart.machine.RoutePair
import koma.statechart.machine.SourceId
import koma.statechart.machine.SourceSnapshot
import koma.observability.file.Framing
import koma.observability.file.SegmentMark
import koma.timetravel.GroupRecorder
import koma.timetravel.GroupReplaySession
import koma.timetravel.GroupRoute
import koma.timetravel.Recording
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
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A group recorded to files as it runs: the order file and the members' files read back as the
 * in-memory recorder saw the run and replay; a ring that dropped a member's early segments
 * yields the range every file still covers, with the messages in flight at its start; an order
 * entry the writer had no room for starts a new range; a cut of the group begins a segment in
 * every file and the range since it knows the sources' snapshots, like the in-memory recording
 * since the same cut; pruning by total size deletes the oldest segments first.
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

    /** Kicks the ping store from outside: an external source whose snapshot counts what it fed. */
    private class KickSource(private val store: () -> MachineStore<PingCtx, PingAct, NoCommand, PingEv>) : ExternalSource {
        override val id = SourceId("kicks:1")
        override val kind: String get() = "kicks"
        var fed = 0
            private set

        fun kick(n: Int) {
            store().feed(id, PingAct.Kick(n))
            fed++
        }

        override suspend fun pause() = Unit

        override fun snapshot(): SourceSnapshot = SourceSnapshot(id, kind, 1, mapOf("fed" to fed.toString()))

        override fun resume() = Unit
    }

    private inner class Live(scope: TestScope, config: RecordingFileConfig = RecordingFileConfig()) {
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val executionScope = CoroutineScope(dispatcher + SupervisorJob())
        val storage = InMemorySegmentStorage()
        val group = MachineGroup()
        val recorder = GroupRecorder(group)
        val files = GroupRecordingFileSink(group, groupId, storage, executionScope, config)
        val source = KickSource { pingStore }
        val pingMember = group.member<PingCtx, PingAct, NoCommand, PingEv>(pingId)
        val pongMember = group.member<PongCtx, PongAct, NoCommand, PongEv>(pongId)
        val pingStore = MachineStore(ping, PingCtx(), CommandHandler<NoCommand, PingAct> { _, _ -> }, executionScope, coroutineContext = dispatcher, observers = listOf(pingMember, recorder.member(pingId, ping, PingCtx()), files.member(pingId, ping, PingCtx(), pingCodec))) { exceptionHandler(ExceptionHandler.Ignore) }
        val pongStore = MachineStore(pong, PongCtx(), CommandHandler<NoCommand, PongAct> { _, _ -> }, executionScope, coroutineContext = dispatcher, observers = listOf(pongMember, recorder.member(pongId, pong, PongCtx()), files.member(pongId, pong, PongCtx(), pongCodec))) { exceptionHandler(ExceptionHandler.Ignore) }

        init {
            group.route<PingEv, PongAct>(pingId, pongId) { (it as? PingEv.Ping)?.let { p -> PongAct.Pong(p.n) } }
            group.route<PongEv, PingAct>(pongId, pingId) { (it as? PongEv.Ack)?.let { a -> PingAct.Acked(a.n) } }
            pingMember.attach(pingStore)
            pongMember.attach(pongStore)
            group.source(source)
            pingStore.start()
            pongStore.start()
        }

        suspend fun close() {
            pingStore.close()
            pongStore.close()
            files.close()
        }
    }

    private fun TestScope.kicks(live: Live, count: Int, from: Int = 1, fed: Boolean = false) {
        runCurrent()
        for (n in from until from + count) {
            if (fed) live.source.kick(n) else live.pingStore.dispatch(PingAct.Kick(n))
            runCurrent()
        }
    }

    private fun assertSameRun(expected: koma.timetravel.GroupRecording, read: koma.timetravel.GroupRecording, what: String) {
        assertEquals(expected.order, read.order, "$what: the order")
        for ((member, recording) in expected.members) {
            assertEquals(recording.steps, read.members.getValue(member).steps, "$what: $member's steps")
            assertEquals(recording.start.snapshot, read.members.getValue(member).start.snapshot, "$what: $member's start")
        }
        assertEquals(expected.inFlight.toSet(), read.inFlight.toSet(), "$what: the messages in flight")
        assertEquals(expected.sourceIds, read.sourceIds, "$what: the sources")
        assertEquals(expected.sourceSnapshots, read.sourceSnapshots, "$what: the sources' snapshots")
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
    fun memberFilesAheadOfTheOrder_areTrimmedToEverySurvivingPrefix() = runTest {
        val live = Live(this)
        kicks(live, 3)
        live.close()
        val whole = live.recorder.recording()
        val name = GroupRecordingFileFormat.segmentName(groupId, 0)
        val bytes = live.storage.read(name)
        val decoded = GroupRecordingFileFormat.decodeSegment(name, bytes)
        val header = checkNotNull(decoded.header)
        for (count in 1 until decoded.entries.size) {
            val prefix = GroupRecordingFileFormat.header(header) +
                decoded.entries.take(count).fold(byteArrayOf()) { acc, entry -> acc + GroupRecordingFileFormat.entryFrame(entry) }
            live.storage.delete(name)
            live.storage.append(name).use { it.write(prefix) } // no END: order writer died first
            val contents = GroupRecordingFiles(live.storage).read(groupId, codecs)
            val read = checkNotNull(contents.recording)
            assertEquals(whole.order.take(count), read.order, "prefix $count")
            for ((member, recording) in read.members) {
                val covered = read.order.count { it.store == member }
                assertEquals(whole.members.getValue(member).steps.take(covered), recording.steps)
                assertEquals(whole.members.getValue(member).start, recording.start, "prefix $count: $member checkpoint")
            }
            assertTrue(contents.marks.any { it is RecordingFileMark.Damaged && it.mark is SegmentMark.Unfinished })
            assertEquals(emptyList(), GroupReplaySession(machines, read).verify(), "prefix $count")
        }
    }

    @Test
    fun rotatedMemberAheadOfTheOnlyOrderPrefix_reportsThatItsOlderCheckpointIsMissing() = runTest {
        val live = Live(this)
        kicks(live, 3)
        live.close()
        val whole = live.recorder.recording()
        val orderName = GroupRecordingFileFormat.segmentName(groupId, 0)
        val order = GroupRecordingFileFormat.decodeSegment(orderName, live.storage.read(orderName))
        // Keep both Start entries and the first ping Kick; pong's file now begins after step 1.
        val entries = order.entries.take(3)
        assertEquals(listOf(pingId, pongId, pingId), entries.map { it.store })
        live.storage.delete(orderName)
        live.storage.append(orderName).use { output ->
            output.write(GroupRecordingFileFormat.header(checkNotNull(order.header)))
            entries.forEach { output.write(GroupRecordingFileFormat.entryFrame(it)) }
        }
        val pongRecording = whole.members.getValue(pongId) as Recording<PongCtx, PongAct, NoCommand, PongEv>
        live.storage.delete(RecordingFileFormat.segmentName(pongId, 0))
        live.storage.append(RecordingFileFormat.segmentName(pongId, 1)).use { output ->
            output.write(RecordingFileFormat.header(pong.id, pong.version, pongId, 1, 2))
            output.write(RecordingFileFormat.checkpointFrame(pongCodec.encodeCheckpoint(pongRecording.checkpointAt(2))))
            pongRecording.steps.drop(2).forEach { output.write(RecordingFileFormat.stepFrame(pongCodec.encodeStep(it))) }
            output.write(Framing.END)
        }
        val contents = GroupRecordingFiles(live.storage).read(groupId, codecs)
        assertEquals(null, contents.recording, "An unavailable checkpoint must not become a future state")
        assertTrue(contents.marks.any { it is RecordingFileMark.StartMismatch && it.store == pongId })
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
    fun aCutOfTheGroup_beginsASegmentInEveryFile_andTheRunSinceIt_isTheInMemoryOne_withTheSourcesSnapshots() = runTest {
        val live = Live(this)
        kicks(live, 2, fed = true)
        val cut = checkNotNull(live.group.checkpoint())
        runCurrent()
        kicks(live, 2, from = 3, fed = true)
        live.close()
        val whole = live.recorder.recording()

        val contents = GroupRecordingFiles(live.storage).read(groupId, codecs)

        assertTrue(contents.marks.isEmpty(), contents.marks.toString())
        assertEquals(0L, live.files.droppedCuts)
        assertEquals(2, RecordingFiles(live.storage).read(pingId, pingCodec).segments.size, "the cut began ping's second segment")
        assertEquals(2, RecordingFiles(live.storage).read(pongId, pongCodec).segments.size, "the cut began pong's second segment")
        val recorded = contents.cuts.single()
        assertEquals(mapOf(live.source.id to SourceSnapshot(live.source.id, "kicks", 1, mapOf("fed" to "2"))), recorded.sources, "the sources' snapshots at the cut")
        assertEquals(mapOf(pingId to whole.stepsBefore(recorded.position, pingId), pongId to whole.stepsBefore(recorded.position, pongId)), recorded.counts, "the members' step counts at the cut")
        val read = checkNotNull(contents.recording)
        assertSameRun(whole, read, "the whole run")
        assertTrue(read.sourceSnapshots.isEmpty(), "the whole run does not begin at the cut")
        assertSameRun(whole.since(cut), contents.since(recorded), "the run since the cut")
        assertEquals(emptyList(), GroupReplaySession(machines, contents.since(recorded)).verify(), "the run since the cut replays, its external inputs from a known source")
        assertFailsWith<IllegalArgumentException> { contents.since(recorded.copy(position = recorded.position + 1)) }
    }

    @Test
    fun aRingThatKeptOnlyTheSegmentsSinceTheCut_readsTheRunFromIt_withTheSourcesSnapshots() = runTest {
        val live = Live(this, RecordingFileConfig(maxSegments = 1))
        kicks(live, 3, fed = true)
        val cut = checkNotNull(live.group.checkpoint())
        runCurrent()
        kicks(live, 2, from = 4, fed = true)
        live.close()
        val whole = live.recorder.recording()

        val contents = GroupRecordingFiles(live.storage).read(groupId, codecs)

        assertTrue(contents.marks.all { it is RecordingFileMark.MissingSegments }, contents.marks.toString())
        val recorded = contents.cuts.single()
        assertEquals(recorded.position, contents.position, "the range begins at the cut")
        val read = checkNotNull(contents.recording)
        assertSameRun(whole.since(cut), read, "the range since the cut")
        assertEquals("3", read.sourceSnapshots.getValue(live.source.id).fields["fed"])
        assertEquals(emptyList(), GroupReplaySession(machines, read).verify())
    }

    @Test
    fun prune_deletesTheOldestSegmentsFirst_neverTheNewest_andWhatRemainsStillReads() = runTest {
        val live = Live(this, RecordingFileConfig(maxSegmentBytes = 900))
        kicks(live, 6)
        live.close()
        val whole = live.recorder.recording()
        val files = RecordingFiles(live.storage)
        val before = live.storage.list().sortedWith(compareBy({ it.modified }, { it.name }))
        assertTrue(before.size >= 4, "several segments: ${before.map { it.name }}")
        val total = before.sumOf { it.size }

        val deleted = files.prune(total - before.first().size)

        assertEquals(listOf(before.first().name), deleted, "exactly the oldest segment went")
        val contents = GroupRecordingFiles(live.storage).read(groupId, codecs)
        val read = checkNotNull(contents.recording)
        assertTrue(contents.marks.any { it is RecordingFileMark.MissingSegments }, contents.marks.toString())
        assertEquals(whole.order.drop(checkNotNull(contents.position)).map { it.store }, read.order.map { it.store })
        assertEquals(emptyList(), GroupReplaySession(machines, read).verify())

        val rest = files.prune(1)
        assertEquals(before.drop(1).dropLast(1).map { it.name }, rest, "all but the newest, oldest first")
        assertEquals(listOf(before.last().name), live.storage.list().map { it.name })
        assertTrue(files.prune(1).isEmpty())
    }

    @Test
    fun anOrderSegment_roundTrips_andEveryCutIsAPrefix() {
        val header = GroupSegmentHeader(
            GroupRecordingFileFormat.VERSION, groupId, listOf(pingId, pongId), listOf(GroupRoute(pingId, pongId, RoutePair("ping", PairRole.Request)), GroupRoute(pongId, pingId, RoutePair("ping", PairRole.Reply)), GroupRoute(pongId, pingId)), setOf(SourceId("paging:x")), 3, 40, listOf(MessageId(pingId, EffectId(7))),
            RecordedCut(40, mapOf(pingId to 21, pongId to 19), mapOf(SourceId("paging:x") to SourceSnapshot(SourceId("paging:x"), "paging", 2, mapOf("loaded" to "3", "generation" to "1")))),
        )
        val entries = listOf(
            GroupOrderEntry(pingId, 5, listOf(MessageId(pingId, EffectId(8)))),
            GroupOrderEntry(pongId, 4, received = MessageId(pingId, EffectId(8))),
            GroupOrderEntry(pingId, 6),
        )
        var bytes = GroupRecordingFileFormat.header(header)
        for (entry in entries) bytes += GroupRecordingFileFormat.entryFrame(entry)
        bytes += Framing.END

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
        assertIs<SegmentMark.UnsupportedFormat>(GroupRecordingFileFormat.decodeSegment("n", GroupRecordingFileFormat.header(header.copy(fileFormatVersion = GroupRecordingFileFormat.VERSION + 1)) + Framing.END).mark)
        // A format 1 segment, written before cuts existed, reads as one without a cut; a format 2
        // segment, written before pairs, as routes without pairs.
        val unpaired = listOf(GroupRoute(pingId, pongId))
        val old = GroupRecordingFileFormat.decodeSegment("old", GroupRecordingFileFormat.header(header.copy(fileFormatVersion = 1, routes = unpaired, cut = null)) + GroupRecordingFileFormat.entryFrame(entries[0]) + Framing.END)
        assertEquals(header.copy(fileFormatVersion = 1, routes = unpaired, cut = null), old.header)
        assertEquals(entries.take(1), old.entries)
        assertTrue(old.finished && old.mark == null)
        val v2 = GroupRecordingFileFormat.decodeSegment("v2", GroupRecordingFileFormat.header(header.copy(fileFormatVersion = 2, routes = unpaired)) + Framing.END)
        assertEquals(header.copy(fileFormatVersion = 2, routes = unpaired), v2.header)
    }
}

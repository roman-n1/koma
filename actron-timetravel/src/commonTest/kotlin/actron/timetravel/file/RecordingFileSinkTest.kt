@file:OptIn(ExperimentalActronApi::class, ExperimentalCoroutinesApi::class)

package actron.timetravel.file

import actron.timetravel.verify

import actron.core.Action
import actron.core.Event
import actron.core.ExceptionHandler
import actron.core.ExperimentalActronApi
import actron.observability.StoreInstanceId
import actron.observability.file.InMemorySegmentStorage
import actron.observability.file.SegmentMark
import actron.statechart.ActionMatcher
import actron.statechart.AtomicState
import actron.statechart.CompoundState
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.Transition
import actron.statechart.Trigger
import actron.statechart.machine.CommandHandler
import actron.statechart.machine.ConcurrencyPolicy
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.LaneId
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineClock
import actron.statechart.machine.MachineStore
import actron.statechart.machine.MachineTime
import actron.timetravel.MachineRecorder
import actron.timetravel.RecordingCodec
import actron.timetravel.ReplaySession
import actron.statechart.test.VirtualMachineClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A live run recorded to files as it happens: read back, it is the recorder's recording and
 * replays; segments rotate and each begins with a checkpoint, so a ring that dropped the first
 * ones still replays from what remains; a step the writer had no room for leaves a hole that is
 * marked and a range after it that replays; a segment cut short by a crash or damaged in the
 * middle is marked and never crossed.
 *
 * ```
 * [*] --> Idle
 * Idle --Load(query)--> Loading            onEnter: command Fetch(query) in lane "load", Latest; timer 10s to Idle
 * Loading --Loaded / store--> Content      onEnter: event Shown
 * Loading --Load / remember--> Loading
 * Content --Refresh--> Loading
 * ```
 */
class RecordingFileSinkTest {

    @Serializable
    data class Ctx(val query: String = "", val items: List<String> = emptyList(), val timeouts: Int = 0)

    @Serializable
    sealed interface Act : Action {
        @Serializable
        @SerialName("Load")
        data class Load(val query: String) : Act

        @Serializable
        @SerialName("Loaded")
        data class Loaded(val items: List<String>) : Act

        @Serializable
        @SerialName("Refresh")
        data object Refresh : Act
    }

    @Serializable
    sealed interface Ev : Event {
        @Serializable
        @SerialName("Shown")
        data object Shown : Ev
    }

    @Serializable
    data class Fetch(val query: String)

    private val root = StateId("Root")
    private val idle = StateId("Idle")
    private val loading = StateId("Loading")
    private val content = StateId("Content")

    private val chart = StateChartDefinition(
        root,
        listOf(CompoundState(root, initial = idle), AtomicState(idle, parent = root), AtomicState(loading, parent = root), AtomicState(content, parent = root)),
        listOf(
            Transition(idle, loading, ActionMatcher.of<Act.Load>("Load"), effect = actron.statechart.EffectKey("remember")),
            Transition(loading, content, ActionMatcher.of<Act.Loaded>("Loaded"), effect = actron.statechart.EffectKey("store")),
            Transition(loading, loading, ActionMatcher.of<Act.Load>("Load"), effect = actron.statechart.EffectKey("remember")),
            Transition(loading, idle, Trigger.After(10.seconds), effect = actron.statechart.EffectKey("timeout")),
            Transition(content, loading, ActionMatcher.of<Act.Refresh>("Refresh")),
        ),
    )

    private val machine = Machine<Ctx, Act, Fetch, Ev>(DefinitionId("files"), DefinitionVersion("1"), chart) {
        effect("remember") { c, a -> c.copy(query = (a as Act.Load).query) }
        effect("store") { c, a -> c.copy(items = (a as Act.Loaded).items) }
        effect("timeout") { c, _ -> c.copy(timeouts = c.timeouts + 1) }
        onEnter(loading) { command(Fetch(context.query), LaneId("load"), ConcurrencyPolicy.Latest) }
        onEnter(content) { event(Ev.Shown) }
    }

    private val codec = RecordingCodec(Ctx.serializer(), Act.serializer(), Fetch.serializer(), Ev.serializer())
    private val store = StoreInstanceId("files-1")

    private inner class Live(scope: TestScope, config: RecordingFileConfig = RecordingFileConfig()) {
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val executionScope = CoroutineScope(dispatcher + SupervisorJob())
        val storage = InMemorySegmentStorage()
        val recorder = MachineRecorder(machine, Ctx())
        val sink = RecordingFileSink(store, machine, Ctx(), codec, storage, executionScope, config)
        val machineStore = MachineStore(
            machine, Ctx(), CommandHandler<Fetch, Act> { command, results ->
                delay(100.milliseconds)
                results.result(Act.Loaded(listOf(command.command.query + "!")))
            },
            executionScope, VirtualMachineClock(scope.testScheduler), dispatcher, observers = listOf(recorder, sink),
        ) { exceptionHandler(ExceptionHandler.Ignore) }
    }

    /** Loads, answers, refreshes and times out: a run with commands, timers, effects, ignored and handled inputs. */
    private fun TestScope.script(live: Live, rounds: Int = 2) {
        live.machineStore.start()
        runCurrent()
        repeat(rounds) { round ->
            live.machineStore.dispatch(Act.Load("q$round"))
            advanceTimeBy(150.milliseconds)
            runCurrent()
            live.machineStore.dispatch(Act.Refresh)
            runCurrent()
            advanceTimeBy(11.seconds)
            runCurrent()
        }
    }

    @Test
    fun aRunRecordedToFiles_readsBackAsTheRecorderSawIt_andReplays() = runTest {
        val live = Live(this)
        script(live)
        live.machineStore.close()
        live.sink.close()
        val recording = live.recorder.recording()

        val contents = RecordingFiles(live.storage).read(store, codec)

        assertNull(live.sink.problem)
        assertTrue(contents.isComplete, contents.marks.toString())
        val read = checkNotNull(contents.recording)
        assertEquals(recording.steps, read.steps)
        assertEquals(recording.start, read.start)
        assertEquals(0, contents.firstStep)
        assertNull(ReplaySession(machine, read).verify())
        assertEquals(listOf(store), RecordingFiles(live.storage).stores())
        assertEquals(RecordingFileStats(recording.length.toLong(), recording.length.toLong(), 0, 1), live.sink.stats)
        assertTrue(RecordingFileFormat.decodeSegment("x", live.storage.read(live.sink.segments.single()), codec).finished, "closed: the segment has its end frame")
    }

    @Test
    fun segmentsRotate_eachBeginningWithACheckpoint_andTheRingStillReplaysFromWhatRemains() = runTest {
        val live = Live(this, RecordingFileConfig(maxSegmentBytes = 1500, maxSegments = 3))
        script(live, rounds = 4)
        live.machineStore.close()
        live.sink.close()
        val recording = live.recorder.recording()
        val segments = live.sink.segments
        assertEquals(3, segments.size, segments.toString())

        val contents = RecordingFiles(live.storage).read(store, codec)

        val missing = assertIs<RecordingFileMark.MissingSegments>(contents.marks.single())
        assertEquals(0, missing.fromIndex)
        val read = checkNotNull(contents.recording)
        val firstStep = checkNotNull(contents.firstStep)
        assertTrue(firstStep > 0)
        assertEquals(recording.steps.drop(firstStep), read.steps, "the range is the tail of the run")
        assertEquals(recording.checkpointAt(firstStep), read.start, "it begins with the checkpoint the segment was written with")
        assertNull(ReplaySession(machine, read).verify())
        for (name in segments) {
            val decoded = RecordingFileFormat.decodeSegment(name, live.storage.read(name), codec)
            assertTrue(decoded.start != null && decoded.finished, "$name begins with a checkpoint and was finished")
        }
    }

    @Test
    fun aStepTheWriterHadNoRoomFor_leavesAMarkedHole_andARangeAfterItThatReplays() = runTest {
        // The writer runs only when the dispatcher does: three steps before it does, a queue of one.
        val live = Live(this, RecordingFileConfig(queueCapacity = 1))
        live.machineStore.start()
        runCurrent()
        live.machineStore.dispatch(Act.Load("a"))
        live.machineStore.dispatch(Act.Refresh)
        live.machineStore.dispatch(Act.Load("b"))
        // The three dispatches are decided in one drain of the store; the writer wakes after.
        runCurrent()
        advanceTimeBy(150.milliseconds)
        runCurrent()
        live.machineStore.close()
        live.sink.close()
        val recording = live.recorder.recording()

        val stats = live.sink.stats
        assertTrue(stats.dropped > 0, "$stats")
        val contents = RecordingFiles(live.storage).read(store, codec)
        val hole = assertIs<RecordingFileMark.StepsMissing>(contents.marks.single())
        assertTrue(hole.found > hole.expected)
        val read = checkNotNull(contents.recording)
        val firstStep = checkNotNull(contents.firstStep)
        assertEquals(hole.found, firstStep, "the range begins where the hole ends")
        assertEquals(recording.steps.drop(firstStep), read.steps)
        assertEquals(recording.checkpointAt(firstStep), read.start, "the checkpoint includes the dropped steps")
        assertNull(ReplaySession(machine, read).verify())
        assertTrue(contents.segments.size >= 1 && live.sink.segments.size == 2, "a new segment began after the hole: ${live.sink.segments}")
    }

    @Test
    fun aCrashMidWrite_keepsWhatWasWritten_andDamageIsNeverCrossed() = runTest {
        val live = Live(this, RecordingFileConfig(maxSegmentBytes = 1500, maxSegments = 10, flushEveryFrames = 1))
        script(live, rounds = 3)
        runCurrent()
        val recording = live.recorder.recording()
        val segments = live.sink.segments
        assertTrue(segments.size >= 3, segments.toString())
        // Died three bytes into the last segment's last frame: the previous frames stay.
        val last = segments.last()
        live.storage.truncate(last, live.storage.list().first { it.name == last }.size.toInt() - 3)

        val cut = RecordingFiles(live.storage).read(store, codec)

        val truncated = assertIs<RecordingFileMark.Damaged>(cut.marks.single())
        assertIs<SegmentMark.TruncatedTail>(truncated.mark)
        val read = checkNotNull(cut.recording)
        assertEquals(recording.steps.take(read.steps.size), read.steps, "a prefix of the run, one step short")
        assertNull(ReplaySession(machine, read).verify())

        // A damaged byte in the middle of the first segment: the range starts over at the next one.
        live.storage.corrupt(segments[0], live.storage.list().first { it.name == segments[0] }.size.toInt() / 2)
        val damaged = RecordingFiles(live.storage).read(store, codec)
        assertTrue(damaged.marks.any { it is RecordingFileMark.Damaged && (it as RecordingFileMark.Damaged).mark is SegmentMark.Corrupt })
        val after = checkNotNull(damaged.recording)
        val firstStep = checkNotNull(damaged.firstStep)
        assertTrue(firstStep > 0, "the range does not cross the damage")
        assertEquals(recording.checkpointAt(firstStep), after.start)
        assertNull(ReplaySession(machine, after).verify())
        assertEquals(segments.drop(1).dropLast(0), damaged.segments.let { it.take(segments.size - 1) }, "read from the segment after the damaged one")
    }
}

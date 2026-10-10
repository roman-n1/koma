@file:OptIn(ExperimentalActronApi::class)

package actron.timetravel.file

import actron.core.Action
import actron.core.Event
import actron.core.ExceptionHandler
import actron.core.ExperimentalActronApi
import actron.observability.StoreInstanceId
import actron.observability.file.InMemorySegmentStorage
import actron.statechart.ActionMatcher
import actron.statechart.AtomicState
import actron.statechart.CompoundState
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.Transition
import actron.statechart.machine.CommandHandler
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineStore
import actron.timetravel.MachineRecorder
import actron.timetravel.RecordingCodec
import actron.test.awaitIdle
import actron.timetravel.ReplaySession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A recording written to files under a storm on [Dispatchers.Default], with a writer queue too
 * small for the storm and a ring of a few small segments: whatever was dropped or rotated
 * away, what the files hold is a continuous piece of the run the recorder saw, beginning at
 * the checkpoint the recorder carries there and reaching the run's end unless its last steps
 * were dropped, and it replays.
 *
 * The recorder and the sink are read only once the store is idle (`awaitIdle` of actron-test):
 * the state settles before the observers of its last commit run, so a snapshot of an observer
 * taken as soon as the state settles may miss the last step.
 *
 * ```
 * [*] --> Idle
 * Idle --Load(n)--> Loading                onEnter: command Fetch(n)
 * Loading --Load(n)--> Loading
 * Loading --Loaded(n) / store--> Idle
 * Root: onAction Tick / count
 * ```
 */
class RecordingFileStormTest {

    @Serializable
    data class Ctx(val ticks: Int = 0, val loaded: Int = -1, val loads: Int = 0)

    @Serializable
    sealed interface Act : Action {
        @Serializable
        @SerialName("Load")
        data class Load(val n: Int) : Act

        @Serializable
        @SerialName("Loaded")
        data class Loaded(val n: Int) : Act

        @Serializable
        @SerialName("Tick")
        data object Tick : Act
    }

    @Serializable
    sealed interface Ev : Event

    @Serializable
    data class Fetch(val n: Int)

    private val root = StateId("Root")
    private val idle = StateId("Idle")
    private val loading = StateId("Loading")

    private val chart = StateChartDefinition(
        root,
        listOf(CompoundState(root, initial = idle), AtomicState(idle, parent = root), AtomicState(loading, parent = root)),
        listOf(
            Transition(idle, loading, ActionMatcher.of<Act.Load>("Load"), effect = "countLoad"),
            Transition(loading, loading, ActionMatcher.of<Act.Load>("Load"), effect = "countLoad"),
            Transition(loading, idle, ActionMatcher.of<Act.Loaded>("Loaded"), effect = "store"),
        ),
    )

    private val machine = Machine<Ctx, Act, Fetch, Ev>(DefinitionId("files-storm"), DefinitionVersion("1"), chart) {
        effect("countLoad") { c, _ -> c.copy(loads = c.loads + 1) }
        effect("store") { c, a -> c.copy(loaded = (a as Act.Loaded).n) }
        onEnter(loading) { command(Fetch((action as Act.Load).n)) }
        onAction(root, ActionMatcher.of<Act.Tick>("Tick")) { context = context.copy(ticks = context.ticks + 1) }
    }

    @Test
    fun whatTheFilesHold_isTheTailOfTheRun_fromItsCheckpoint_andReplays() = runTest {
        val senders = 6
        val perSender = 120
        val store = StoreInstanceId("storm-1")
        val codec = RecordingCodec(Ctx.serializer(), Act.serializer(), Fetch.serializer(), Ev.serializer())
        val executionScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val storage = InMemorySegmentStorage()
        val recorder = MachineRecorder(machine, Ctx())
        val sink = RecordingFileSink(store, machine, Ctx(), codec, storage, executionScope, RecordingFileConfig(maxSegmentBytes = 16 * 1024, maxSegments = 4, queueCapacity = 32))
        val machineStore = MachineStore(
            machine, Ctx(), CommandHandler<Fetch, Act> { command, results ->
                delay(Random.nextLong(0, 3).milliseconds)
                results.result(Act.Loaded(command.command.n))
            },
            executionScope, coroutineContext = Dispatchers.Default, observers = listOf(recorder, sink),
        ) { exceptionHandler(ExceptionHandler.Ignore) }
        val ticks = senders * perSender / 2

        withContext(Dispatchers.Default) {
            coroutineScope {
                repeat(senders) { sender ->
                    launch {
                        repeat(perSender) { index -> machineStore.dispatch(if (index % 2 == 0) Act.Tick else Act.Load(sender * perSender + index)) }
                    }
                }
            }
            withTimeout(30_000) { machineStore.state.first { it.context.ticks == ticks && it.commands.isEmpty() && it.isActive(idle) } }
            machineStore.awaitIdle(timeout = 30.seconds)
        }
        val recording = recorder.recording()
        machineStore.close()
        sink.close()
        executionScope.cancel()

        val contents = RecordingFiles(storage).read(store, codec)
        val read = checkNotNull(contents.recording)
        val firstStep = checkNotNull(contents.firstStep)
        val end = firstStep + read.length
        assertTrue(end <= recording.length, "the files hold $end steps of ${recording.length}")
        assertEquals(recording.steps.subList(firstStep, end), read.steps, "a piece of the run from $firstStep to $end of ${recording.length}")
        assertEquals(recording.checkpointAt(firstStep), read.start)
        assertNull(ReplaySession(machine, read).verify())
        assertNull(sink.problem)
        assertTrue(sink.stats.written + sink.stats.dropped == recording.length.toLong(), "${sink.stats} of ${recording.length}")
        // A step dropped with no step after it (the run's last ones) is absent from the files: only the counters tell.
        if (end < recording.length) assertTrue(recording.length - end <= sink.stats.dropped, "the run's last ${recording.length - end} steps are absent but only ${sink.stats.dropped} were dropped")
        assertTrue(sink.stats.dropped > 0 || end == recording.length, "nothing dropped, so the files reach the run's end")
        println("recorded ${recording.length}, dropped ${sink.stats.dropped}, files hold ${read.length} from step $firstStep to $end in ${contents.segments.size} segments; marks ${contents.marks.map { it::class.simpleName }}")
    }
}

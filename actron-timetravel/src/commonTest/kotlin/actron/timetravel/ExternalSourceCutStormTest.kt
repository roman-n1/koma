@file:OptIn(ExperimentalActronApi::class)

package actron.timetravel

import actron.core.Action
import actron.core.Event
import actron.core.ExceptionHandler
import actron.core.ExperimentalActronApi
import actron.observability.StoreInstanceId
import actron.statechart.ActionMatcher
import actron.statechart.AtomicState
import actron.statechart.CompoundState
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.machine.Admission
import actron.statechart.machine.CommandHandler
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.ExternalSource
import actron.statechart.machine.GroupCheckpoint
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineGroup
import actron.statechart.machine.MachineStore
import actron.statechart.machine.SourceId
import actron.statechart.machine.SourceSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A paged source feeds a member from its own thread while four threads dispatch and thirty
 * cuts are taken on [Dispatchers.Default]. At every cut the source's snapshot equals what the
 * member had decided, and the group's recording since the cut replays without a mismatch: the
 * pause before the freeze and the snapshot after the members settle line up under real
 * interleavings.
 *
 * ```
 * [*] --> Idle      onAction PageLoaded(index) / pages++; onAction Tick / ticks++
 * ```
 */
class ExternalSourceCutStormTest {

    data class Ctx(val pages: Int = 0, val ticks: Int = 0)

    sealed interface Act : Action {
        data class PageLoaded(val index: Int) : Act
        data object Tick : Act
    }

    data object Nothing : Event

    private val root = StateId("Root")
    private val idle = StateId("Idle")
    private val chart = StateChartDefinition(root, listOf(CompoundState(root, initial = idle), AtomicState(idle, parent = root)), emptyList())

    private val machine = Machine<Ctx, Act, Nothing, Nothing>(DefinitionId("pages-storm"), DefinitionVersion("1"), chart) {
        onAction(root, ActionMatcher.of<Act.PageLoaded>("PageLoaded")) { context = context.copy(pages = context.pages + 1) }
        onAction(root, ActionMatcher.of<Act.Tick>("Tick")) { context = context.copy(ticks = context.ticks + 1) }
    }

    private class PagedSource(override val id: SourceId, private val store: MachineStore<Ctx, Act, Nothing, Nothing>) : ExternalSource {
        override val kind: String get() = "paging"
        private val feeding = Mutex()
        var loaded = 0
            private set

        suspend fun load(index: Int): Admission = feeding.withLock {
            store.feed(id, Act.PageLoaded(index)).also { if (it == Admission.Accepted) loaded = index + 1 }
        }

        override suspend fun pause() = feeding.lock()

        override fun snapshot(): SourceSnapshot = SourceSnapshot(id, kind, 1, mapOf("loaded" to loaded.toString()))

        override fun resume() = feeding.unlock()
    }

    @Test
    fun everyCut_hasTheSourceWhereTheMemberHadIt_andReplaysFromThere() = runTest {
        val listId = StoreInstanceId("list-1")
        val pagingId = SourceId("paging:list-1")
        val executionScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val group = MachineGroup()
        val recorder = GroupRecorder(group)
        val member = group.member<Ctx, Act, Nothing, Nothing>(listId)
        val store = MachineStore(
            machine, Ctx(), CommandHandler<Nothing, Act> { _, _ -> }, executionScope, coroutineContext = Dispatchers.Default,
            observers = listOf(member, recorder.member(listId, machine, Ctx())),
        ) { exceptionHandler(ExceptionHandler.Ignore) }
        member.attach(store)
        val source = PagedSource(pagingId, store)
        group.source(source)
        store.start()
        val pages = 300
        val senders = 4
        val perSender = 80
        val cuts = mutableListOf<GroupCheckpoint>()
        var failedCuts = 0

        withContext(Dispatchers.Default) {
            coroutineScope {
                launch {
                    var index = 0
                    while (index < pages) {
                        if (source.load(index) == Admission.Accepted) index++
                        if (index % 20 == 0) delay(1.milliseconds)
                    }
                }
                repeat(senders) {
                    launch {
                        repeat(perSender) { i ->
                            store.dispatch(Act.Tick)
                            if (i % 10 == 0) delay(1.milliseconds)
                        }
                    }
                }
                launch {
                    repeat(30) {
                        val cut = group.checkpoint(5.seconds)
                        if (cut == null) failedCuts++ else cuts += cut
                        delay(Random.nextLong(0, 3).milliseconds)
                    }
                }
            }
            withTimeout(30_000) { store.state.first { it.context.pages == pages && it.context.ticks == senders * perSender } }
        }
        val recording = recorder.recording()
        store.close()
        executionScope.cancel()

        assertEquals(0, failedCuts)
        assertEquals(30, cuts.size)
        assertEquals(setOf(pagingId), recording.sourceIds)
        for ((index, cut) in cuts.withIndex()) {
            val snapshot = cut.sources.getValue(pagingId)
            val decided = (cut.members.getValue(listId).snapshot.context as Ctx).pages
            assertEquals(decided, snapshot.fields.getValue("loaded").toInt(), "cut $index: the source and the member disagree on the pages")
            val since = recording.since(cut)
            assertEquals(cut.sources, since.sourceSnapshots)
            assertEquals(emptyList(), GroupReplaySession(mapOf(listId to machine), since).verify(), "cut $index")
        }
        assertEquals(emptyList(), GroupReplaySession(mapOf(listId to machine), recording).verify())
        println("cuts taken mid-run: ${cuts.count { (it.members.getValue(listId).snapshot.context as Ctx).pages in 1 until pages }} of ${cuts.size}")
    }
}

@file:OptIn(ExperimentalActronApi::class, ExperimentalCoroutinesApi::class, ExperimentalTime::class)

package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import actron.core.ExceptionHandler
import actron.core.ExperimentalActronApi
import actron.core.InputId
import actron.core.Plugin
import actron.core.PluginScope
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.TestTimeSource

/**
 * An external source feeding a member: its inputs are decided like dispatches and known as its
 * own, admission applies to it, and in a cut it is paused before the members are frozen and
 * snapshotted after they settle, so its snapshot is what the member decided; a source that does
 * not pause aborts the cut and the group resumes.
 *
 * ```
 * [*] --> Idle      onAction PageLoaded(index) / pages += index; onAction Tick / ticks++
 * ```
 */
class ExternalSourceTest {

    data class Ctx(val pages: List<Int> = emptyList(), val ticks: Int = 0)

    sealed interface Act : Action {
        data class PageLoaded(val index: Int) : Act
        data object Tick : Act
    }

    data object Nothing : Event

    private val root = StateId("Root")
    private val idle = StateId("Idle")
    private val chart = StateChartDefinition(root, listOf(CompoundState(root, initial = idle), AtomicState(idle, parent = root)), emptyList())

    private val machine = Machine<Ctx, Act, Nothing, Nothing>(DefinitionId("pages"), DefinitionVersion("1"), chart) {
        onAction(root, ActionMatcher.of<Act.PageLoaded>("PageLoaded")) { context = context.copy(pages = context.pages + (action as Act.PageLoaded).index) }
        onAction(root, ActionMatcher.of<Act.Tick>("Tick")) { context = context.copy(ticks = context.ticks + 1) }
    }

    /** A paging engine's adapter: feeds pages, pauses by holding its feed lock, snapshots what it fed. */
    class PagedSource(override val id: SourceId, private val store: MachineStore<Ctx, Act, Nothing, Nothing>) : ExternalSource {
        override val kind: String get() = "paging"
        private val feeding = Mutex()
        var loaded = 0
            private set

        suspend fun load(index: Int): Admission = feeding.withLock {
            store.feed(id, Act.PageLoaded(index)).also { if (it == Admission.Accepted) loaded = index + 1 }
        }

        override suspend fun pause() = feeding.lock()

        override fun snapshot(): SourceSnapshot = SourceSnapshot(id, kind, 1, mapOf("loaded" to loaded.toString(), "generation" to "1"))

        override fun resume() = feeding.unlock()
    }

    private val listId = StoreInstanceId("list-1")
    private val pagingId = SourceId("paging:list-1")

    private inner class Fixture(scope: TestScope, admission: AdmissionPolicy = AdmissionPolicy.Unbounded, plugin: Plugin<MachineSnapshot<Ctx>, MachineInput<Act>, Nothing>? = null) {
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val executionScope = CoroutineScope(dispatcher + SupervisorJob())
        val session = RecordingSession(scope.backgroundScope, id = RuntimeSessionId("e"), group = MachineGroupId("list"), timeSource = TestTimeSource())
        val group = MachineGroup(session)
        val member = group.member<Ctx, Act, Nothing, Nothing>(listId)
        val inputs = mutableListOf<MachineInput<Act>>()
        val store = MachineStore(
            machine, Ctx(), CommandHandler<Nothing, Act> { _, _ -> }, executionScope, coroutineContext = dispatcher, admission = admission,
            observers = listOf(member, session.decisionsOf(listId), object : DecisionObserver<Ctx, Act, Nothing, Nothing> {
                override fun onCommitted(input: InputId?, machineInput: MachineInput<Act>, decision: Decision<Ctx, Nothing, Nothing>) {
                    inputs += machineInput
                }
            }),
        ) {
            exceptionHandler(ExceptionHandler.Ignore)
            plugin?.let { plugin(it) }
        }
        val source = PagedSource(pagingId, store)

        init {
            member.attach(store)
            group.source(source)
            store.start()
        }
    }

    @Test
    fun anInputOfASource_isDecidedLikeADispatch_andKnownAsItsOwn() = runTest {
        val f = Fixture(this)
        runCurrent()

        assertEquals(Admission.Accepted, f.source.load(0))
        assertEquals(Admission.Accepted, f.source.load(1))
        runCurrent()

        assertEquals(listOf(0, 1), f.store.currentState.context.pages)
        assertEquals(listOf(pagingId, pagingId), f.inputs.filterIsInstance<MachineInput.External<Act>>().map { it.source })
        val received = f.session.records().map { it.entry }.filterIsInstance<JournalEntry.ExternalReceived>()
        assertEquals(listOf("paging:list-1", "paging:list-1"), received.map { it.source })
        assertTrue(received.all { it.input != null })
        assertEquals(listOf(pagingId), f.group.sourceIds)
        f.store.close()
    }

    @Test
    fun admission_appliesToASource_whichDecidesToRetry() = runTest {
        val f = Fixture(this, admission = AdmissionPolicy.Bounded(1))
        runCurrent()

        assertEquals(Admission.Accepted, f.source.load(0))
        assertEquals(Admission.Rejected(1, 1), f.source.load(1))
        runCurrent()
        assertEquals(Admission.Accepted, f.source.load(1), "the source retried once the queue had room")
        runCurrent()

        assertEquals(listOf(0, 1), f.store.currentState.context.pages)
        assertEquals(2, f.source.loaded)
        f.store.close()
    }

    @Test
    fun aCut_pausesTheSourceBeforeTheMembers_snapshotsWhatTheMemberDecided_andResumesItAfter() = runTest {
        val gate = CompletableDeferred<Unit>()
        var stuck = false
        val slow = object : Plugin<MachineSnapshot<Ctx>, MachineInput<Act>, Nothing> {
            override suspend fun onState(scope: PluginScope<MachineSnapshot<Ctx>, MachineInput<Act>>, prevState: MachineSnapshot<Ctx>, state: MachineSnapshot<Ctx>) {
                if (state.context.ticks == 1 && !stuck) {
                    stuck = true
                    gate.await()
                }
            }
        }
        val f = Fixture(this, plugin = slow)
        runCurrent()
        f.source.load(0)
        runCurrent()
        f.store.dispatch(Act.Tick)
        runCurrent()
        assertTrue(stuck, "the member is mid-processing")

        val cut = async { f.group.checkpoint(1.seconds) }
        runCurrent()
        // The source is paused while the cut waits for the member: a load waits with it.
        val late = launch { f.source.load(1) }
        runCurrent()
        assertTrue(late.isActive && f.source.loaded == 1, "the load waits for the source to resume")
        gate.complete(Unit)
        runCurrent()

        val checkpoint = checkNotNull(cut.await())
        assertEquals(SourceSnapshot(pagingId, "paging", 1, mapOf("loaded" to "1", "generation" to "1")), checkpoint.sources.getValue(pagingId))
        assertEquals(listOf(0), (checkpoint.members.getValue(listId).snapshot.context as Ctx).pages, "the snapshot counts what the member decided")
        assertTrue(late.isCompleted && f.source.loaded == 2, "resumed after the cut: the load went through")
        assertEquals(listOf(0, 1), f.store.currentState.context.pages)
        val created = f.session.records().map { it.entry }.filterIsInstance<JournalEntry.CheckpointCreated>().single()
        assertEquals(JournalEntry.CheckpointCreated(listOf(listId), listOf("paging:list-1"), 0), created)
        assertNull(f.session.records().first { it.entry is JournalEntry.CheckpointCreated }.store, "a record of the session itself")
        f.store.close()
    }

    @Test
    fun aSourceThatDoesNotPause_abortsTheCut_andTheGroupResumes() = runTest {
        val f = Fixture(this)
        runCurrent()
        val hanging = object : ExternalSource {
            override val id = SourceId("socket:hanging")
            override val kind get() = "socket"
            val pausing = CompletableDeferred<Unit>()
            override suspend fun pause() = pausing.await()
            override fun snapshot() = SourceSnapshot(id, kind, 1, emptyMap())
            override fun resume() = Unit
        }
        f.group.source(hanging)

        val cut = async { f.group.checkpoint(100.milliseconds) }
        f.store.dispatch(Act.Tick)
        advanceTimeBy(200.milliseconds)
        runCurrent()

        assertNull(cut.await(), "the cut gave up on the source")
        assertEquals(1, f.store.currentState.context.ticks, "the members were never frozen; the group went on")
        assertEquals(Admission.Accepted, f.source.load(0), "the paged source was resumed")
        runCurrent()
        assertEquals(listOf(0), f.store.currentState.context.pages)
        assertTrue(f.session.records().none { it.entry is JournalEntry.CheckpointCreated })
        f.store.close()
    }
}

@file:OptIn(actron.core.ExperimentalActronApi::class)

package actron.example.timetravel

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import actron.core.Action
import actron.core.Event
import actron.core.InputId
import actron.core.InputAttribution
import actron.observability.MachineGroupId
import actron.observability.JournalSink
import actron.observability.RecordingSession
import actron.observability.RuntimeSessionId
import actron.observability.StoreInstanceId
import actron.observability.file.FileSegmentStorage
import actron.observability.file.JournalFileContents
import actron.observability.file.JournalFileSink
import actron.observability.file.JournalFiles
import actron.observability.recordTo
import actron.statechart.recordTo
import actron.statechart.ActionMatcher
import actron.statechart.AtomicState
import actron.statechart.CompoundState
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.Transition
import actron.statechart.Trigger
import actron.statechart.machine.CommandHandler
import actron.statechart.machine.Decision
import actron.statechart.machine.DecisionObserver
import actron.statechart.machine.MachineInput
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineGroup
import actron.statechart.machine.MachineStore
import actron.statechart.machine.decisionsOf
import actron.timetravel.GroupBranch
import actron.timetravel.GroupRecording
import actron.timetravel.GroupReplaySession
import actron.timetravel.RecordingCodec
import actron.timetravel.compose.BranchInput
import actron.timetravel.compose.GroupReplayControls
import actron.timetravel.compose.GroupTimeTravelControls
import actron.timetravel.file.GroupRecordingFileSink
import actron.timetravel.file.GroupRecordingFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.seconds

@Serializable
data class CartContext(val discount: Int = 20, val price: Int = 0, val total: Int = 0)

@Serializable
sealed interface CartAction : Action {
    @Serializable @SerialName("Start") data object Start : CartAction
    @Serializable @SerialName("Priced") data class Priced(val price: Int) : CartAction
}

@Serializable
data object LookupPrice

@Serializable
data class TotalChanged(val total: Int) : Event

@Serializable
data class SummaryContext(val total: Int = 0)

@Serializable
data class UpdateTotal(val total: Int) : Action

@Serializable
data object NoCommand

@Serializable
data object NoEvent : Event

/** Synthetic data only: a live command runs once; replay and experiments never call it. */
object CheckoutDemo {
    val cartId = StoreInstanceId("cart-1")
    val summaryId = StoreInstanceId("summary-1")
    private val groupId = MachineGroupId("checkout-demo")
    private val sessionId = RuntimeSessionId("checkout-demo-run")
    val loading = StateId("Loading")
    private val idle = StateId("Idle")
    private val ready = StateId("Ready")
    private val timedOut = StateId("TimedOut")
    private val root = StateId("Cart")

    fun cart(fixed: Boolean) = Machine<CartContext, CartAction, LookupPrice, TotalChanged>(
        DefinitionId("demo-cart"), DefinitionVersion("1"),
        StateChartDefinition(root, listOf(
            CompoundState(root, initial = idle), AtomicState(idle, root), AtomicState(loading, root),
            AtomicState(ready, root), AtomicState(timedOut, root),
        ), listOf(
            Transition(idle, loading, ActionMatcher.of<CartAction.Start>("Start")),
            Transition(loading, ready, ActionMatcher.of<CartAction.Priced>("Priced"), effect = actron.statechart.EffectKey("calculate")),
            Transition(loading, timedOut, Trigger.After(5.seconds)),
        )),
    ) {
        onEnter(loading) { command(LookupPrice) }
        effect("calculate") { context, action ->
            val price = (action as CartAction.Priced).price
            context.copy(price = price, total = if (fixed) (price - context.discount).coerceAtLeast(0) else price)
        }
        onEnter(ready) { event(TotalChanged(context.total)) }
    }

    private fun summary(): Machine<SummaryContext, UpdateTotal, NoCommand, NoEvent> {
        val root = StateId("Summary")
        val showing = StateId("Showing")
        return Machine(DefinitionId("demo-summary"), DefinitionVersion("1"), StateChartDefinition(
            root, listOf(CompoundState(root, initial = showing), AtomicState(showing, root)),
            listOf(Transition(showing, showing, ActionMatcher.of<UpdateTotal>("UpdateTotal"), effect = actron.statechart.EffectKey("show"))),
        )) { effect("show") { _, action -> SummaryContext((action as UpdateTotal).total) } }
    }

    private val cartCodec = RecordingCodec(CartContext.serializer(), CartAction.serializer(), LookupPrice.serializer(), TotalChanged.serializer())
    private val summaryCodec = RecordingCodec(SummaryContext.serializer(), UpdateTotal.serializer(), NoCommand.serializer(), NoEvent.serializer())
    private val codecs = mapOf(cartId to cartCodec, summaryId to summaryCodec)

    val routes: List<GroupBranch.Route> = listOf(GroupBranch.Route(cartId, summaryId) { event, carry ->
        if (event is TotalChanged) carry(UpdateTotal(event.total))
    })
    val answers = listOf(BranchInput.Answer(cartId, "Price 100", CartAction.Priced(100)), BranchInput.Answer(cartId, "Price 60", CartAction.Priced(60)))

    data class Recorded(val directory: Path, val commandCalls: Int)
    data class Loaded(val directory: Path, val recording: GroupRecording, val journal: JournalFileContents) {
        fun replay(fixed: Boolean) = GroupReplayControls(GroupReplaySession(mapOf(cartId to cart(fixed), summaryId to summary()), recording))
        fun controls(fixed: Boolean) = GroupTimeTravelControls(replay(fixed), routes, answers)
    }

    /** Writes a fresh directory every time; no existing recording is overwritten. */
    suspend fun record(parent: Path, calls: AtomicInteger = AtomicInteger()): Recorded =
        record(parent, calls, beforeSummaryRecorded = null)

    // The example's test seam holds a real committed observer before the file observer runs.
    internal suspend fun record(
        parent: Path,
        calls: AtomicInteger,
        beforeSummaryStartupRecorded: (() -> Unit)? = null,
        onJournalFlushed: (() -> Unit)? = null,
        beforeSummaryRecorded: (() -> Unit)? = null,
    ): Recorded = withContext(Dispatchers.IO) {
        Files.createDirectories(parent)
        val directory = Files.createTempDirectory(parent, "checkout-")
        val storage = FileSegmentStorage(directory.toString())
        val engineJob = SupervisorJob()
        val writerJob = SupervisorJob()
        val engine = CoroutineScope(Dispatchers.Default + engineJob)
        val writers = CoroutineScope(Dispatchers.IO + writerJob)
        val journalSink = JournalFileSink(storage)
        // Tests can observe a completed writer flush while a Store observer is held. Reading
        // the buffered file alone cannot establish that the independent writer has caught up.
        val diagnosticSink = if (onJournalFlushed == null) journalSink else JournalSink { record ->
            journalSink.write(record)
            journalSink.flush()
            onJournalFlushed()
        }
        val session = RecordingSession(writers, sessionId, groupId, sinks = listOf(diagnosticSink))
        val group = MachineGroup(session)
        val files = GroupRecordingFileSink(group, groupId, storage, writers)
        val cartMachine = cart(fixed = false)
        val summaryMachine = summary()
        val cartMember = group.member<CartContext, CartAction, LookupPrice, TotalChanged>(cartId)
        val summaryMember = group.member<SummaryContext, UpdateTotal, NoCommand, NoEvent>(summaryId)
        val cartStore = MachineStore(cartMachine, CartContext(), CommandHandler<LookupPrice, CartAction> { _, results ->
            calls.incrementAndGet()
            results.result(CartAction.Priced(100))
        }, engine, coroutineContext = engine.coroutineContext, observers = listOf(cartMember, session.decisionsOf(cartId), files.member(cartId, cartMachine, CartContext(), cartCodec))) {
            recordTo(session, cartId)
        }
        val summaryObserver = object : DecisionObserver<SummaryContext, UpdateTotal, NoCommand, NoEvent> {
            override fun onCommitted(input: InputAttribution, machineInput: MachineInput<UpdateTotal>, decision: Decision<SummaryContext, NoCommand, NoEvent>) {
                if (decision.snapshot.context.total == 100) beforeSummaryRecorded?.invoke()
            }
        }
        val summaryStartupObserver = object : DecisionObserver<SummaryContext, UpdateTotal, NoCommand, NoEvent> {
            override fun onCommitted(input: InputAttribution, machineInput: MachineInput<UpdateTotal>, decision: Decision<SummaryContext, NoCommand, NoEvent>) {
                if (machineInput is MachineInput.Start) beforeSummaryStartupRecorded?.invoke()
            }
        }
        val summaryStore = MachineStore(summaryMachine, SummaryContext(), CommandHandler<NoCommand, UpdateTotal> { _, _ ->
            error("The summary has no commands")
        }, engine, coroutineContext = engine.coroutineContext, observers = listOf(summaryStartupObserver, summaryMember, session.decisionsOf(summaryId), summaryObserver, files.member(summaryId, summaryMachine, SummaryContext(), summaryCodec))) {
            recordTo(session, summaryId)
        }
        try {
            group.route<TotalChanged, UpdateTotal>(cartId, summaryId) { event, carry -> carry(UpdateTotal(event.total)) }
            cartMember.attach(cartStore)
            summaryMember.attach(summaryStore)
            withTimeout(10.seconds) {
                cartStore.start()
                summaryStore.start()
                cartStore.state.first { it.isActive(idle) }
                summaryStore.state.first { it.configuration.active.isNotEmpty() }
                // Active snapshots publish before startup observers. Drain startup before
                // issuing inputs, so a branch at the first price mismatch has both members
                // started in its true recorded prefix, not just in the live StateFlows.
                check(group.checkpoint(5.seconds) is actron.statechart.machine.GroupCut.Ready) { "Checkout startup did not reach a consistent recording boundary" }
                cartStore.dispatch(CartAction.Start)
                summaryStore.state.first { it.context.total == 100 }
                // StateFlow publishes before the committed plugins/observers finish. A group
                // cut drains both members and their executors before cancellation can stop them.
                check(group.checkpoint(5.seconds) is actron.statechart.machine.GroupCut.Ready) { "Checkout did not reach a consistent recording boundary" }
            }
        } finally {
            // Join Store shutdown before draining file writers: no late observer can append
            // after the files' end frames. Writers have a separate lifetime from the Stores.
            withContext(NonCancellable) {
                cartStore.close()
                summaryStore.close()
                engineJob.cancelAndJoin()
                try {
                    files.close()
                    session.close()
                    check(files.dropped == 0L && files.members.values.all { it.stats.dropped == 0L }) { "Recording queue dropped inputs" }
                } finally {
                    journalSink.close()
                    writerJob.cancelAndJoin()
                }
            }
        }
        Recorded(directory, calls.get())
    }

    /** Only file readers and codecs: neither this method nor Loaded constructs live Stores. */
    fun load(directory: Path): Loaded {
        require(Files.isDirectory(directory)) { "Choose a checkout recording directory: $directory" }
        val storage = FileSegmentStorage(directory.toString())
        val read = GroupRecordingFiles(storage).read(groupId, codecs)
        require(read.marks.isEmpty()) { "The example requires a complete recording: ${read.marks}" }
        require(read is actron.timetravel.file.GroupRecordingFileContents.Readable) { "No checkout recording found in $directory" }
        val recording = read.recording
        val journal = JournalFiles(storage).read(sessionId)
        require(journal.isComplete && journal.records.isNotEmpty()) { "Missing or incomplete diagnostic journal: ${journal.marks}" }
        return Loaded(directory, recording, journal)
    }
}

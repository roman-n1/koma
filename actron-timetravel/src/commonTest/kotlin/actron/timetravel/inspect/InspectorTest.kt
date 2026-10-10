@file:OptIn(ExperimentalActronApi::class, ExperimentalCoroutinesApi::class, ExperimentalTime::class, InternalActronApi::class)

package actron.timetravel.inspect

import actron.core.Action
import actron.core.Event
import actron.core.ExceptionHandler
import actron.core.ExperimentalActronApi
import actron.core.InputId
import actron.core.InternalActronApi
import actron.observability.Capability
import actron.observability.ExecutionMode
import actron.observability.FailureDescriptor
import actron.observability.GroupSeq
import actron.observability.InputDescriptor
import actron.observability.JOURNAL_FORMAT_VERSION
import actron.observability.JournalEntry
import actron.observability.JournalRecord
import actron.observability.MachineGroupId
import actron.observability.MessageRef
import actron.observability.OutcomeDescriptor
import actron.observability.OutcomeKind
import actron.observability.Payload
import actron.observability.PayloadPolicy
import actron.observability.RecordingSession
import actron.observability.RuntimeSessionId
import actron.observability.StoreInstanceId
import actron.observability.StoreSeq
import actron.observability.file.InMemorySegmentStorage
import actron.observability.file.JournalFileSink
import actron.observability.file.JournalFiles
import actron.observability.file.SegmentMark
import actron.observability.recordTo
import actron.statechart.recordTo
import actron.statechart.ActionMatcher
import actron.statechart.AtomicState
import actron.statechart.CompoundState
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.Transition
import actron.statechart.Trigger
import actron.statechart.machine.AdmissionPolicy
import actron.statechart.machine.CommandHandler
import actron.statechart.machine.ConcurrencyPolicy
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.LaneId
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineClock
import actron.statechart.machine.MachineStore
import actron.statechart.machine.MachineTime
import actron.statechart.machine.decisionsOf
import actron.statechart.machine.effectsOf
import actron.timetravel.MachineRecorder
import actron.timetravel.Recording
import actron.statechart.test.VirtualMachineClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
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
 * The inspector over a journaled run: the Stores with their capabilities, the timeline with
 * every kind of position, the snapshots and their difference when a recording is attached,
 * and the completeness that tells a full record from a partial one, with the reason.
 *
 * ```
 * [*] --> Idle
 * Idle --Load(query)--> Loading            onEnter: command Fetch(query) in lane "load", Latest; timer 10s to Idle
 * Loading --Loaded / store--> Content      onEnter: event Shown
 * Loading --Load / remember--> Loading
 * Content --Refresh--> Loading
 * Idle --Boom [boom]--> Idle
 * ```
 */
class InspectorTest {

    data class Ctx(val query: String = "", val items: List<String> = emptyList(), val timeouts: Int = 0)

    sealed interface Act : Action {
        data class Load(val query: String) : Act
        data class Loaded(val items: List<String>) : Act
        data object Refresh : Act
        data object Boom : Act
    }

    sealed interface Ev : Event {
        data object Shown : Ev
    }

    data class Fetch(val query: String)

    private val root = StateId("Root")
    private val idle = StateId("Idle")
    private val loading = StateId("Loading")
    private val content = StateId("Content")

    private val chart = StateChartDefinition(
        root,
        listOf(CompoundState(root, initial = idle), AtomicState(idle, parent = root), AtomicState(loading, parent = root), AtomicState(content, parent = root)),
        listOf(
            Transition(idle, loading, ActionMatcher.of<Act.Load>("Load"), effect = "remember"),
            Transition(loading, content, ActionMatcher.of<Act.Loaded>("Loaded"), effect = "store"),
            Transition(loading, loading, ActionMatcher.of<Act.Load>("Load"), effect = "remember"),
            Transition(loading, idle, Trigger.After(10.seconds), effect = "timeout"),
            Transition(content, loading, ActionMatcher.of<Act.Refresh>("Refresh")),
            Transition(idle, idle, ActionMatcher.of<Act.Boom>("Boom"), guard = "boom"),
        ),
    )

    private val machine = Machine<Ctx, Act, Fetch, Ev>(DefinitionId("inspected"), DefinitionVersion("1"), chart) {
        guard("boom") { _, _ -> throw IllegalStateException("boom") }
        effect("remember") { c, a -> c.copy(query = (a as Act.Load).query) }
        effect("store") { c, a -> c.copy(items = (a as Act.Loaded).items) }
        effect("timeout") { c, _ -> c.copy(timeouts = c.timeouts + 1) }
        onEnter(loading) { command(Fetch(context.query), LaneId("load"), ConcurrencyPolicy.Latest) }
        onEnter(content) { event(Ev.Shown) }
    }

    private val tab1 = StoreInstanceId("search-tab-1")
    private val tab2 = StoreInstanceId("search-tab-2")

    /** A journaled, recorded run of one or two tabs: a failing guard, a load answered and its stale completion, a refresh, an unanswered load, an ignored and a refused refresh, a timeout. */
    private class Run(val session: RecordingSession, val recordings: Map<StoreInstanceId, Recording<Ctx, Act, Fetch, Ev>>)

    private suspend fun TestScope.run(policy: PayloadPolicy<actron.statechart.machine.MachineSnapshot<Ctx>, actron.statechart.machine.MachineInput<Act>, Ev>, tabs: Int = 1, sinks: List<actron.observability.JournalSink> = emptyList()): Run {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        val session = RecordingSession(backgroundScope, id = RuntimeSessionId("run-1"), group = MachineGroupId("picker"), timeSource = TestTimeSource(), sinks = sinks)
        val recordings = mutableMapOf<StoreInstanceId, Recording<Ctx, Act, Fetch, Ev>>()
        val ids = listOf(tab1, tab2).take(tabs)
        val stores = ids.map { id ->
            val handler = CommandHandler<Fetch, Act> { command, results ->
                if (command.command.query == "cats") {
                    delay(100.milliseconds)
                    results.result(Act.Loaded(listOf("tom", "felix")))
                } else {
                    awaitCancellation()
                }
            }
            val recorder = MachineRecorder(machine, Ctx())
            val store = MachineStore(
                machine, Ctx(), handler, scope, VirtualMachineClock(testScheduler), dispatcher,
                admission = AdmissionPolicy.Bounded(1),
                observers = listOf(recorder, session.decisionsOf(id, command = { Payload.Projected("Fetch", mapOf("query" to it.query)) }, action = { Payload.Projected(it.toString()) })),
            ) {
                exceptionHandler(ExceptionHandler.Ignore)
                recordTo(session, id, policy)
            }
            recorder to store
        }
        for ((_, store) in stores) store.start()
        runCurrent()
        // A failing guard in Idle; a load answered, whose completion comes after Content was
        // entered (stale); a refresh; a load of "dogs" that is never answered; a refresh in
        // Loading that matches no transition, with a second one refused by the bounded admission
        // while the first waits; then the 10 s timeout fires.
        for ((_, store) in stores) {
            store.dispatch(Act.Boom)
            runCurrent()
            store.dispatch(Act.Load("cats"))
            advanceTimeBy(150.milliseconds)
            runCurrent()
            store.dispatch(Act.Refresh)
            runCurrent()
            store.dispatch(Act.Load("dogs"))
            runCurrent()
            store.dispatch(Act.Refresh)
            store.dispatch(Act.Refresh)
            runCurrent()
        }
        advanceTimeBy(11.seconds)
        runCurrent()
        for ((index, pair) in stores.withIndex()) {
            val (recorder, store) = pair
            recordings[ids[index]] = recorder.recording()
            store.close()
            runCurrent()
        }
        session.close()
        return Run(session, recordings)
    }

    @Test
    fun theStoresOfTheGroup_withCapabilitiesCountsAndCompleteness() = runTest {
        val run = run(PayloadPolicy.metadataOnly(), tabs = 2)

        val inspector = Inspector.of(run.session, run.recordings)

        assertEquals(RuntimeSessionId("run-1"), inspector.session)
        assertEquals(MachineGroupId("picker"), inspector.group)
        assertEquals(ExecutionMode.Live, inspector.mode)
        assertEquals(listOf(tab1, tab2), inspector.stores.map { it.id })
        for (store in inspector.stores) {
            assertEquals(Capability.InspectOnly, store.capability)
            assertTrue(store.closed)
            assertTrue(store.processings >= 7, "$store")
            assertEquals(RecordingStatus.Attached(run.recordings.getValue(store.id).length), store.recording)
            assertTrue(store.completeness.isComplete, "with a matching recording the omitted payloads are supplied: ${store.completeness}")
            assertEquals(Availability.Available, inspector.replayability(store.id))
        }
        assertTrue(inspector.completeness.isComplete, inspector.completeness.reasons.toString())
        assertEquals(inspector.timeline.size, inspector.timelineOf(tab1).size + inspector.timelineOf(tab2).size - inspector.timeline.count { it.store == null })
    }

    @Test
    fun theTimeline_hasEveryKindOfPosition_inTheGroupsOrder() = runTest {
        val run = run(PayloadPolicy.metadataOnly())

        val inspector = Inspector.of(run.session, run.recordings)
        val timeline = inspector.timeline

        assertIs<TimelineItem.Registered>(timeline.first())
        assertIs<TimelineItem.Stopped>(timeline.last())
        assertTrue(timeline.zipWithNext().all { (a, b) -> (a.groupSeq?.value ?: 0) <= (b.groupSeq?.value ?: Long.MAX_VALUE) }, "in order")
        val processings = timeline.filterIsInstance<TimelineItem.Processing>()
        val startup = processings.first()
        assertEquals(InputDescriptor.Startup, startup.kind)
        assertEquals(1L, startup.revision)
        assertEquals(listOf("Idle", "Root"), startup.activeNodes)
        assertTrue(startup.isFinished && startup.outcome?.kind == OutcomeKind.Handled)
        val load = processings.first { it.decision?.commands?.isNotEmpty() == true }
        assertEquals(listOf("Fetch(query=cats)"), load.decision!!.commands.map { actron.observability.JournalFormat.payload(it.command) })
        assertEquals(1, load.decision!!.timersScheduled.size)
        // The answered Fetch completes after Content was entered: its completion is stale; the
        // Refresh in Loading finds no transition.
        assertEquals("StaleCommand", processings.first { it.ignored != null }.ignored)
        val stale = processings.first { it.ignored == "NoTransition" }
        assertNull(stale.revision)
        val rejected = timeline.filterIsInstance<TimelineItem.Rejected>()
        assertEquals(1, rejected.size, "the second Refresh was refused by the bounded admission while the first waited: $rejected")
        val failed = processings.first { it.failures.isNotEmpty() }
        assertEquals(OutcomeKind.Failed, failed.outcome?.kind)
        assertEquals("IllegalStateException", failed.failures.single().type)
        val timeout = processings.first { it.decision?.transitions == listOf(3) }
        assertTrue(timeout.revision!! > load.revision!!)
        assertTrue(timeline.any { it is TimelineItem.Closed && it.store == tab1 })
        assertTrue(timeline.none { it is TimelineItem.Unattributed }, timeline.filterIsInstance<TimelineItem.Unattributed>().toString())
        assertTrue(timeline.none { it is TimelineItem.Pending })
    }

    @Test
    fun withARecording_everyDecisionHasItsSnapshots_andTheirDifference() = runTest {
        val run = run(PayloadPolicy.metadataOnly())

        val inspector = Inspector.of(run.session, run.recordings)
        val decisions = inspector.timeline.filterIsInstance<TimelineItem.Processing>().filter { it.isDecision }

        assertTrue(decisions.all { it.recorded != null && it.before != null && it.after != null && it.diff != null }, "every decision is enriched")
        val load = decisions.first { it.decision?.commands?.isNotEmpty() == true }
        val diff = load.diff!!
        assertTrue(diff.revisionAdvanced && diff.contextChanged)
        assertEquals(setOf(loading), diff.entered)
        assertEquals(setOf(idle), diff.exited)
        assertEquals(1, diff.commandsRegistered.size)
        assertEquals(1, diff.timersScheduled.size)
        assertEquals(Ctx(query = "cats"), load.after!!.context)
        assertEquals(Ctx(), load.before!!.context)
        val stale = decisions.first { it.ignored != null }
        assertTrue(stale.diff!!.isEmpty)
        assertEquals(stale.before, stale.after)
        val answer = decisions.first { it.after?.configuration?.active?.contains(content) == true }
        assertEquals(listOf("tom", "felix"), (answer.after!!.context as Ctx).items)
        val detail = InspectorText.detail(load)
        assertTrue(detail.any { it.startsWith("  before: revision=1") } && detail.any { it.startsWith("  after:  revision=2") }, detail.joinToString("\n"))
        assertTrue(detail.any { it.startsWith("  changed: context; entered Loading; exited Idle; commands +c1; timers +t1") }, detail.joinToString("\n"))
    }

    @Test
    fun withoutARecording_theJournalIsPartial_andSaysWhy() = runTest {
        val run = run(PayloadPolicy.metadataOnly())

        val inspector = Inspector.of(run.session)
        val store = inspector.stores.single()

        assertEquals(RecordingStatus.None, store.recording)
        assertTrue(!store.completeness.isComplete)
        val omitted = assertIs<Incompleteness.PayloadsOmitted>(store.completeness.reasons.single())
        assertTrue(omitted.count > 10, omitted.toString())
        val replay = assertIs<Availability.Unavailable>(inspector.replayability(tab1))
        assertTrue("InspectOnly" in replay.reasons.single(), replay.reasons.toString())
        val load = inspector.timeline.filterIsInstance<TimelineItem.Processing>().first { it.decision?.commands?.isNotEmpty() == true }
        assertNull(load.before)
        assertEquals(Payload.Omitted, load.commits.single().after)
        assertEquals(listOf("Idle", "Loading"), load.decision!!.exited.map { it.node } + load.decision!!.entered.map { it.node })
        val detail = InspectorText.detail(load)
        assertTrue(detail.any { it.startsWith("  commit revision=2: - <- -") }, detail.joinToString("\n"))
        val overview = InspectorText.overview(inspector)
        assertTrue(overview.any { it.startsWith("  $tab1 InspectOnly") && "partial (1)" in it }, overview.joinToString("\n"))
        assertTrue(overview.any { it.trim().startsWith("! $tab1:") && "payloads omitted" in it }, overview.joinToString("\n"))

        val retained = run(PayloadPolicy.retainAll())
        val complete = Inspector.of(retained.session)
        assertTrue(complete.stores.single().completeness.isComplete, complete.stores.single().completeness.toString())
        assertTrue(complete.completeness.isComplete)
    }

    @Test
    fun aRecordingOfAnotherRun_isReportedAsAMismatch_andNotUsed() = runTest {
        val run = run(PayloadPolicy.metadataOnly())
        val other = run(PayloadPolicy.metadataOnly(), tabs = 2)

        val inspector = Inspector.of(run.session, mapOf(tab1 to other.recordings.getValue(tab2), tab2 to run.recordings.getValue(tab1)))
        val store = inspector.stores.single()

        // The other run decided the same inputs: same revisions, so it matches by content; a
        // checkpoint-trimmed recording that starts past the journal does not.
        assertIs<RecordingStatus.Attached>(store.recording)
        // A recording trimmed to its last checkpoint begins at the last revision: only the steps
        // after that revision (the failing Boom, which committed nothing) are aligned.
        val trimmed = run.recordings.getValue(tab1).let { r -> r.since(r.checkpointAt(r.length)) }
        val past = Inspector.of(run.session, mapOf(tab1 to trimmed))
        assertEquals(RecordingStatus.Attached(trimmed.length), past.stores.single().recording)
        assertEquals(trimmed.length, past.timeline.filterIsInstance<TimelineItem.Processing>().count { it.recorded != null })
        val foreign = Recording(machine.id, machine.version, machine.initialSnapshot(Ctx(query = "other")), run.recordings.getValue(tab1).steps.drop(1))
        val mismatched = Inspector.of(run.session, mapOf(tab1 to foreign))
        val status = assertIs<RecordingStatus.Mismatch>(mismatched.stores.single().recording)
        assertTrue("step 0" in status.reason, status.reason)
        assertTrue(mismatched.stores.single().completeness.reasons.any { it is Incompleteness.RecordingMismatch })
        assertIs<Availability.Unavailable>(mismatched.replayability(tab1))
        assertTrue(mismatched.timeline.filterIsInstance<TimelineItem.Processing>().none { it.recorded != null }, "nothing enriched from a mismatching recording")
        val nobody = assertIs<Availability.Unavailable>(Inspector.of(run.session, mapOf(StoreInstanceId("nobody") to foreign)).replayability(StoreInstanceId("nobody")))
        assertTrue("no records of nobody" in nobody.reasons.single(), nobody.reasons.toString())
    }

    @Test
    fun fromFiles_damageAndHolesAreInTheTimeline_andInTheCompleteness() = runTest {
        val storage = InMemorySegmentStorage()
        val sink = JournalFileSink(storage, actron.observability.file.JournalFileConfig(maxSegmentBytes = 600, maxSegments = 8))
        val run = run(PayloadPolicy.metadataOnly(), sinks = listOf(sink))
        sink.close()
        val segments = sink.segments
        assertTrue(segments.size >= 3, segments.toString())
        storage.corrupt(segments[1], storage.list().first { it.name == segments[1] }.size.toInt() / 2)
        storage.delete(segments[0])

        val inspector = Inspector.of(JournalFiles(storage), run.session.id, run.recordings)

        val damage = inspector.timeline.filterIsInstance<TimelineItem.Damage>().map { it.mark }
        assertIs<SegmentMark.MissingSegments>(damage[0])
        assertIs<SegmentMark.Corrupt>(damage[1])
        assertIs<SegmentMark.SequenceHole>(damage[2])
        assertTrue(!inspector.completeness.isComplete)
        assertEquals(3, inspector.completeness.reasons.count { it is Incompleteness.Damaged }, inspector.completeness.reasons.toString())
        val store = inspector.stores.single()
        assertTrue(store.completeness.reasons.any { it is Incompleteness.NotRegistered }, "the registration was in the deleted segment: ${store.completeness}")
        assertTrue(inspector.timeline.first() is TimelineItem.Damage)
        val lines = InspectorText.timeline(inspector)
        assertTrue(lines.any { it.startsWith("- ! segments 0..") }, "the sink rotated the first segments away and the test deleted one more: ${lines.take(3).joinToString("\n")}")
    }

    @Test
    fun theMailboxsStory_isInTheTimeline() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        val session = RecordingSession(backgroundScope, id = RuntimeSessionId("run-2"), group = MachineGroupId("picker"), timeSource = TestTimeSource())
        val store = MachineStore(
            machine, Ctx(), CommandHandler<Fetch, Act> { _, _ -> awaitCancellation() }, scope, VirtualMachineClock(testScheduler), dispatcher,
            mailbox = actron.statechart.machine.MailboxConfig({ actron.statechart.machine.EffectPolicy.Retained() }, listeners = listOf(session.effectsOf(tab1) { Payload.Projected(it.toString()) })),
        ) { recordTo(session, tab1, PayloadPolicy.metadataOnly()) }
        store.start()
        store.dispatch(Act.Load("cats"))
        runCurrent()
        store.dispatch(Act.Loaded(listOf("tom")))
        runCurrent()
        val job = scope.launch { store.mailbox.subscribe().collect { it.acknowledge() } }
        runCurrent()
        job.cancel()
        store.close()
        session.close()

        val inspector = Inspector.of(session)
        val effects = inspector.timeline.filterIsInstance<TimelineItem.Effect>()

        assertEquals(listOf("EffectQueued", "EffectHandlingStarted", "EffectAcknowledged"), effects.map { it.entry::class.simpleName })
        assertTrue(effects.all { it.effect == 1L && it.store == tab1 })
        assertTrue(InspectorText.timeline(inspector).any { "EffectQueued" in it && "Retained" in it && "Shown" in it }, InspectorText.timeline(inspector).joinToString("\n"))
    }

    @Test
    fun handMadeRecords_showGapsPendingInputsAndUnattributedRecords() {
        val session = RuntimeSessionId("hand")
        val store = StoreInstanceId("s")
        var seq = 0L
        fun record(entry: JournalEntry<*, *, *>, forStore: StoreInstanceId? = store) =
            JournalRecord(JOURNAL_FORMAT_VERSION, session, MachineGroupId("g"), forStore, ExecutionMode.Live, GroupSeq(++seq), forStore?.let { StoreSeq(seq) }, seq.milliseconds, entry)
        val records = listOf(
            record(JournalEntry.StoreRegistered(Capability.InspectOnly)),
            record(JournalEntry.InputAccepted(InputId(1), InputDescriptor.Startup)),
            record(JournalEntry.ProcessingStarted(InputId(1), 1)),
            record(JournalEntry.StateCommitted(InputId(1), 1, Payload.Projected("Loading"), Payload.Projected("Ready"))),
            record(JournalEntry.ProcessingFinished(InputId(1), 1, OutcomeDescriptor(OutcomeKind.Handled, 1), 1.milliseconds)),
            record(JournalEntry.InputAccepted(InputId(2), InputDescriptor.Dispatch(Payload.Projected("Tap")))),
            record(JournalEntry.InputAccepted(InputId(3), InputDescriptor.Transaction(InputId(1)))),
            record(JournalEntry.EventEmitted(InputId(1), Payload.Projected("Ping"))),
            record(JournalEntry.EventEmitted(InputId(9), Payload.Projected("Orphan"))),
            record(JournalEntry.JournalGap(4), forStore = null),
            record(JournalEntry.ProcessingStarted(InputId(3), 2)),
            record(JournalEntry.CommandsAbandoned("StoreClosed", listOf(3), listOf(2))),
            record(JournalEntry.BridgeDropped(MessageRef(StoreInstanceId("p"), 7), store, "StoreClosed")),
            record(JournalEntry.BridgeSent(InputId(1), MessageRef(store, 3), StoreInstanceId("p"), delivered = true, cause = MessageRef(StoreInstanceId("p"), 7))),
            record(JournalEntry.RecordingStopped, forStore = null),
        )

        val inspector = Inspector.of(records)

        val items = inspector.timeline
        val startup = assertIs<TimelineItem.Processing>(items[1])
        assertEquals(listOf<Payload<*>>(Payload.Projected("Ping")), startup.events, "a late event of a finished processing is attributed to it")
        val pending = items.filterIsInstance<TimelineItem.Pending>().single()
        assertEquals(InputId(2), pending.input)
        val transaction = items.filterIsInstance<TimelineItem.Processing>().last()
        assertEquals(InputId(1), transaction.cause)
        assertTrue(!transaction.isFinished)
        assertEquals(1, items.count { it is TimelineItem.Unattributed })
        assertEquals(1, items.count { it is TimelineItem.Gap })
        val reasons = inspector.completeness.reasons
        assertTrue(reasons.any { it == Incompleteness.RecordsDropped(1, 4) }, reasons.toString())
        assertTrue(reasons.any { it == Incompleteness.StoppedWhileOpen(listOf(store)) }, reasons.toString())
        assertTrue(reasons.any { it == Incompleteness.InputsPending(store, 2) }, reasons.toString())
        assertTrue(reasons.any { it == Incompleteness.Unattributed(store, 1) }, reasons.toString())
        assertTrue(reasons.none { it is Incompleteness.PayloadsOmitted })
        assertTrue(InspectorText.overview(inspector).size >= 6)
        assertEquals(items.size, InspectorText.timeline(inspector).size)
        assertTrue(InspectorText.line(pending).endsWith("(no end in the journal)"))
        val abandoned = items.filterIsInstance<TimelineItem.Abandoned>().single()
        assertEquals(listOf(3L) to listOf(2L), abandoned.queued to abandoned.running)
        assertTrue(InspectorText.line(abandoned).endsWith("Abandoned StoreClosed queued=[c3] running=[c2]"), InspectorText.line(abandoned))
        val dropped = items.filterIsInstance<TimelineItem.Dropped>().single()
        assertEquals(MessageRef(StoreInstanceId("p"), 7) to "StoreClosed", dropped.message to dropped.reason)
        assertTrue(InspectorText.line(dropped).endsWith("Dropped p/e7 StoreClosed"), InspectorText.line(dropped))
        assertTrue(reasons.none { it is Incompleteness.MessagesUndelivered }, "a drop is a fact of the record, not a hole in it")
        val sent = items.filterIsInstance<TimelineItem.Sent>().single()
        assertEquals(MessageRef(StoreInstanceId("p"), 7), sent.cause)
        assertTrue(InspectorText.line(sent).endsWith("Sent s/e3 -> p reply-to=p/e7"), InspectorText.line(sent))
    }
}

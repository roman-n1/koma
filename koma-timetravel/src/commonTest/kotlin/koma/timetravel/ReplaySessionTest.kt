@file:OptIn(ExperimentalKomaApi::class, ExperimentalCoroutinesApi::class, InternalKomaApi::class)

package koma.timetravel

import koma.core.Action
import koma.core.Event
import koma.core.ExceptionHandler
import koma.core.ExperimentalKomaApi
import koma.core.InternalKomaApi
import koma.observability.FailureDescriptor
import koma.statechart.ActionMatcher
import koma.statechart.AtomicState
import koma.statechart.CompoundState
import koma.statechart.StateChartDefinition
import koma.statechart.StateId
import koma.statechart.Transition
import koma.statechart.Trigger
import koma.statechart.machine.CommandHandler
import koma.statechart.machine.ConcurrencyPolicy
import koma.statechart.machine.Decision
import koma.statechart.machine.DecisionOutcome
import koma.statechart.machine.DefinitionId
import koma.statechart.machine.DefinitionVersion
import koma.statechart.machine.IgnoreReason
import koma.statechart.machine.LaneId
import koma.statechart.machine.Machine
import koma.statechart.machine.MachineBuilder
import koma.statechart.machine.MachineClock
import koma.statechart.machine.MachineInput
import koma.statechart.machine.MachineSnapshot
import koma.statechart.machine.MachineStore
import koma.statechart.machine.MachineTime
import koma.statechart.machine.TimerId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Stage 5, single-store replay: a run of a `MachineStore` is recorded, decided again by the pure
 * machine and compared step by step; the cursor moves back and forward over checkpoints; a
 * changed machine diverges at the first affected step; a foreign version is inspectable only.
 *
 * ```
 * [*] --> Idle
 * Idle --Load(query)--> Loading            onEnter: command Fetch(query) in lane "load", Latest; timer 10s to Idle
 * Loading --Loaded / store--> Content
 * Loading --Load / remember--> Loading
 * Content --Refresh--> Loading
 * Idle --Boom [boom]--> Idle
 * ```
 */
class ReplaySessionTest {

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

    private fun machine(version: String = "1", tweak: MachineBuilder<Ctx, Act, Fetch, Ev>.() -> Unit = {}) =
        Machine<Ctx, Act, Fetch, Ev>(DefinitionId("replayable"), DefinitionVersion(version), chart) {
            guard("boom") { _, _ -> throw IllegalStateException("boom") }
            effect("remember") { c, a -> c.copy(query = (a as Act.Load).query) }
            effect("store") { c, a -> c.copy(items = (a as Act.Loaded).items) }
            effect("timeout") { c, _ -> c.copy(timeouts = c.timeouts + 1) }
            onEnter(loading) { command(Fetch(context.query), LaneId("load"), ConcurrencyPolicy.Latest) }
            onEnter(content) { event(Ev.Shown) }
            tweak()
        }

    private class TestClock(private val scheduler: TestCoroutineScheduler) : MachineClock {
        private val origin = scheduler.currentTime

        override fun now(): MachineTime = MachineTime((scheduler.currentTime - origin).milliseconds)

        override suspend fun delayUntil(deadline: MachineTime) {
            val remaining = deadline - now()
            if (remaining.isPositive()) delay(remaining)
        }
    }

    /** Records a live run: a load, a stale answer, a refresh, a timeout, a failing guard. */
    private class Run(val recording: Recording<Ctx, Act, Fetch, Ev>, val final: MachineSnapshot<Ctx>, val handlerCalls: Int)

    private suspend fun TestScope.record(machine: Machine<Ctx, Act, Fetch, Ev>): Run {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        var handlerCalls = 0
        // A handler that answers even when cancelled, as a careless one would: its late answers
        // are stale results for the machine.
        val handler = CommandHandler<Fetch, Act> { command, results ->
            handlerCalls++
            try {
                delay(if (command.command.query == "slow") 30.seconds else 100.milliseconds)
                results.result(Act.Loaded(listOf("${command.command.query}-item")))
            } catch (e: kotlinx.coroutines.CancellationException) {
                results.result(Act.Loaded(listOf("late-${command.command.query}")))
                throw e
            }
        }
        val recorder = MachineRecorder(machine, Ctx())
        val store = MachineStore(machine, Ctx(), handler, scope, TestClock(testScheduler), dispatcher, observers = listOf(recorder)) {
            exceptionHandler(ExceptionHandler.Ignore)
        }
        store.start()
        runCurrent()
        store.dispatch(Act.Load("cats"))
        advanceTimeBy(50.milliseconds); runCurrent()
        store.dispatch(Act.Load("dogs")) // supersedes cats; the cats handler answers on cancellation: stale
        advanceTimeBy(150.milliseconds); runCurrent()
        assertTrue(store.currentState.isActive(content))
        store.dispatch(Act.Refresh)
        runCurrent()
        store.dispatch(Act.Load("slow"))
        advanceTimeBy(11.seconds); runCurrent() // the 10s timer fires before the slow answer
        assertTrue(store.currentState.isActive(idle))
        store.dispatch(Act.Boom) // the guard throws: a Failed step
        runCurrent()
        advanceTimeBy(30.seconds); runCurrent() // the slow answer arrives for a cancelled command
        val final = store.currentState
        store.close()
        scope.cancel()
        return Run(recorder.recording(), final, handlerCalls)
    }

    @Test
    fun aRecordedRun_replaysToTheSameSnapshots_andNoHandlerRuns() = runTest {
        val machine = machine()
        val run = record(machine)
        val recording = run.recording
        assertTrue(recording.steps.any { it is RecordedStep.Ignored && it.reason == IgnoreReason.StaleCommand })
        assertTrue(recording.steps.any { it is RecordedStep.Failed })
        assertTrue(recording.steps.any { it.input is MachineInput.TimerFired })

        val session = ReplaySession(machine, recording)
        assertNull(session.verify())
        var matched = 0
        while (true) {
            val step = session.stepForward() ?: break
            assertIs<ReplayStep.Matched<Ctx, Act, Fetch, Ev>>(step)
            matched++
        }
        assertEquals(recording.length, matched)
        assertEquals(run.final, session.snapshot)
        assertEquals(4, run.handlerCalls, "the live run called the handler for cats, dogs, dogs again after Refresh and slow; the replay called nothing")
    }

    @Test
    fun seekAndSteppingBack_landOnCheckpoints_thatSequentialReplayReproduces() = runTest {
        val machine = machine()
        val recording = record(machine).recording
        val session = ReplaySession(machine, recording)

        // Every position, reached by seek, equals the snapshot a sequential replay produces.
        var sequential = recording.initial
        for (position in 0..recording.length) {
            session.seek(position)
            assertEquals(sequential, session.snapshot, "position $position")
            recording.steps.getOrNull(position)?.let { step ->
                val decision = machine.decide(sequential, step.input)
                if (decision.isHandled) sequential = decision.snapshot
            }
        }
        // Back and forward from the end.
        session.seek(recording.length)
        repeat(3) { assertTrue(session.stepBackward()) }
        assertEquals(recording.snapshotAt(recording.length - 3), session.snapshot)
        repeat(3) { assertIs<ReplayStep.Matched<Ctx, Act, Fetch, Ev>>(session.stepForward()) }
        assertEquals(recording.snapshotAt(recording.length), session.snapshot)
        assertNull(session.stepForward())
        session.seek(0)
        assertTrue(!session.stepBackward())
        assertFailsWith<IllegalArgumentException> { session.seek(recording.length + 1) }
    }

    @Test
    fun aChangedMachine_divergesAtTheFirstAffectedStep_andTheSessionStaysThere() = runTest {
        val recorded = machine()
        val recording = record(recorded).recording
        // The same version string, but the store effect keeps only the first item: a rule changed without a new version.
        val changed = Machine<Ctx, Act, Fetch, Ev>(DefinitionId("replayable"), DefinitionVersion("1"), chart) {
            guard("boom") { _, _ -> throw IllegalStateException("boom") }
            effect("remember") { c, a -> c.copy(query = (a as Act.Load).query) }
            effect("store") { c, a -> c.copy(items = (a as Act.Loaded).items.take(0)) }
            effect("timeout") { c, _ -> c.copy(timeouts = c.timeouts + 1) }
            onEnter(loading) { command(Fetch(context.query), LaneId("load"), ConcurrencyPolicy.Latest) }
            onEnter(content) { event(Ev.Shown) }
        }

        val session = ReplaySession(changed, recording)
        val mismatch = session.verify()
        assertTrue(mismatch != null, "the changed effect must show")
        val firstLoaded = recording.steps.indexOfFirst { it.input is MachineInput.CommandResult<*> && it is RecordedStep.Committed }
        assertEquals(firstLoaded, mismatch.position)
        assertTrue(mismatch.differences.any { it.startsWith("context:") }, mismatch.describe())
        assertTrue(mismatch.describe().startsWith("Replay diverged at step $firstLoaded"))

        session.seek(firstLoaded)
        val step = session.stepForward()
        assertIs<ReplayStep.Diverged<Ctx, Act, Fetch, Ev>>(step)
        assertEquals(firstLoaded, session.position, "a divergence does not advance")
        assertIs<ReplayStep.Diverged<Ctx, Act, Fetch, Ev>>(session.stepForward())
        assertNull(session.verify(0, firstLoaded), "everything before it still matches")
    }

    @Test
    fun aTimerRecordedAsFiringBeforeItsDeadline_diverges() = runTest {
        val machine = machine()
        val recording = record(machine).recording
        val timerIndex = recording.steps.indexOfFirst { it.input is MachineInput.TimerFired }
        val timerStep = recording.steps[timerIndex] as RecordedStep.Committed
        val fired = timerStep.input as MachineInput.TimerFired
        val early = MachineInput.TimerFired(fired.timer, MachineTime(fired.now.sinceStart - 5.seconds))
        val tampered = Recording(recording.definition, recording.version, recording.initial, recording.steps.toMutableList().also { it[timerIndex] = RecordedStep.Committed(early, timerStep.decision) })

        val mismatch = ReplaySession(machine, tampered).verify()

        assertTrue(mismatch != null && mismatch.position == timerIndex)
        assertTrue(mismatch.differences.any { "before its deadline" in it }, mismatch.describe())
    }

    @Test
    fun compatibility_versionIsInspectableOnly_andAnotherDefinitionIsUnsupported() = runTest {
        val recording = record(machine("1")).recording

        assertEquals(Compatibility.Replayable, recording.compatibilityWith(machine("1")))
        assertIs<Compatibility.InspectableOnly>(recording.compatibilityWith(machine("2")))
        val other = Machine<Ctx, Act, Fetch, Ev>(DefinitionId("other"), DefinitionVersion("1"), chart) {
            guard("boom") { _, _ -> false }
            effect("remember") { c, _ -> c }
            effect("store") { c, _ -> c }
            effect("timeout") { c, _ -> c }
        }
        assertIs<Compatibility.Unsupported>(recording.compatibilityWith(other))
        assertFailsWith<IllegalArgumentException> { ReplaySession(machine("2"), recording) }
        // Inspectable: the snapshots are there without deciding anything.
        assertTrue(recording.snapshotAt(recording.length).revision > 0)
    }

    @Test
    fun aRunThatDidNotStartFromTheRecorderInitial_isFlagged() = runTest {
        val machine = machine()
        val recorder = MachineRecorder(machine, Ctx(query = "other"))
        val start = machine.decide(machine.initialSnapshot(Ctx()), MachineInput.Start(MachineTime.Zero))

        recorder.onCommitted(null, MachineInput.Start(MachineTime.Zero), start)

        assertTrue(recorder.problem != null)
    }

    @Test
    fun recording_refusesAnInitialSnapshotOfAnotherMachineOrRevision() {
        val machine = machine()
        val started = machine.decide(machine.initialSnapshot(Ctx()), MachineInput.Start(MachineTime.Zero)).snapshot
        assertFailsWith<IllegalArgumentException> { Recording<Ctx, Act, Fetch, Ev>(machine.id, machine.version, started, emptyList()) }
        assertFailsWith<IllegalArgumentException> { Recording<Ctx, Act, Fetch, Ev>(DefinitionId("other"), machine.version, machine.initialSnapshot(Ctx()), emptyList()) }
    }

    @Test
    fun replayOfARecordedFailure_matchesByFailureType() = runTest {
        val machine = machine()
        val recording = record(machine).recording
        val failed = recording.steps.indexOfFirst { it is RecordedStep.Failed }
        val session = ReplaySession(machine, recording)
        session.seek(failed)
        val step = session.stepForward()
        val matched = assertIs<ReplayStep.Matched<Ctx, Act, Fetch, Ev>>(step)
        assertIs<DecisionOutcome.Failed>(matched.decision.outcome)
        assertEquals(FailureDescriptor(type = "IllegalStateException"), (recording.steps[failed] as RecordedStep.Failed).failure)
    }
}

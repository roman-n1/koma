@file:OptIn(ExperimentalKomaApi::class, ExperimentalCoroutinesApi::class, InternalKomaApi::class)

package koma.statechart.machine

import koma.core.Action
import koma.core.Event
import koma.core.ExceptionHandler
import koma.core.ExperimentalKomaApi
import koma.core.InputId
import koma.core.InternalKomaApi
import koma.observability.ActivationRef
import koma.observability.CommandRef
import koma.observability.JournalEntry
import koma.observability.JournalFormat
import koma.observability.JournalRecord
import koma.observability.MachineGroupId
import koma.observability.Payload
import koma.observability.RecordingSession
import koma.observability.RuntimeSessionId
import koma.observability.StoreInstanceId
import koma.observability.TimerRef
import koma.observability.recordTo
import koma.statechart.ActionMatcher
import koma.statechart.AtomicState
import koma.statechart.CompoundState
import koma.statechart.StateChartDefinition
import koma.statechart.StateId
import koma.statechart.Transition
import koma.statechart.Trigger
import koma.test.startAndAwait
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The decisions of a [MachineStore] in the journal: `DecisionCommitted` right after the
 * `StateCommitted` of the same input and revision, `DecisionIgnored` with the reason,
 * `InputRejected` for actions refused at admission.
 *
 * ```
 * [*] --> Root
 * state Root {
 *     [*] --> Idle
 *     Idle --Load--> Loading            onEnter: command Fetch in lane "load", Latest; timer 10s
 *     Loading --Loaded--> Content
 *     Loading --after 10s--> Idle
 * }
 * ```
 */
class MachineJournalTest {

    sealed interface Act : Action {
        data object Load : Act
        data object Loaded : Act
    }

    data object Fetch

    data object Nothing : Event

    private val root = StateId("Root")
    private val idle = StateId("Idle")
    private val loading = StateId("Loading")
    private val content = StateId("Content")

    private val chart = StateChartDefinition(
        root,
        listOf(CompoundState(root, initial = idle), AtomicState(idle, parent = root), AtomicState(loading, parent = root), AtomicState(content, parent = root)),
        listOf(
            Transition(idle, loading, ActionMatcher.of<Act.Load>("Load")),
            Transition(loading, content, ActionMatcher.of<Act.Loaded>("Loaded")),
            Transition(loading, idle, Trigger.After(10.seconds)),
        ),
    )

    private val machine = Machine<Unit, Act, Fetch, Nothing>(DefinitionId("journaled"), DefinitionVersion("1"), chart) {
        onEnter(loading) { command(Fetch, LaneId("load"), ConcurrencyPolicy.Latest) }
    }

    private class TestClock(private val scheduler: TestCoroutineScheduler) : MachineClock {
        override fun now(): MachineTime = MachineTime(scheduler.currentTime.milliseconds)

        override suspend fun delayUntil(deadline: MachineTime) {
            val remaining = deadline - now()
            if (remaining.isPositive()) delay(remaining)
        }
    }

    private class Harness(scope: TestScope, machine: Machine<Unit, Act, Fetch, Nothing>, admission: AdmissionPolicy, describeCommand: (Fetch) -> Payload<Fetch>, extraObserver: DecisionObserver<Unit, Act, Fetch, Nothing>?) {
        val handled = mutableListOf<Throwable>()
        val session = RecordingSession(scope.backgroundScope, id = RuntimeSessionId("s"), group = MachineGroupId("g"))
        val id = StoreInstanceId("m")
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val executionScope = CoroutineScope(dispatcher + SupervisorJob())
        val store = MachineStore(
            machine,
            Unit,
            CommandHandler<Fetch, Act> { _, _ -> awaitCancellation() },
            executionScope,
            TestClock(scope.testScheduler),
            dispatcher,
            admission = admission,
            observers = listOfNotNull(session.decisionsOf(id, command = describeCommand), extraObserver),
        ) {
            exceptionHandler(ExceptionHandler { handled += it })
            recordTo(session, id)
        }
        val inner get() = (store as MachineStoreImpl<Unit, Act, Fetch, Nothing>).inner

        fun records() = session.records()

        fun close() {
            store.close()
            executionScope.cancel()
        }
    }

    private suspend fun TestScope.harness(
        admission: AdmissionPolicy = AdmissionPolicy.Unbounded,
        describeCommand: (Fetch) -> Payload<Fetch> = { Payload.Omitted },
        observer: DecisionObserver<Unit, Act, Fetch, Nothing>? = null,
    ): Harness {
        val h = Harness(this, machine, admission, describeCommand, observer)
        h.store.startAndAwait()
        runCurrent()
        return h
    }

    private fun List<JournalRecord<*, *, *>>.entries() = map { it.entry }

    private fun List<JournalRecord<*, *, *>>.of(input: InputId) = entries().filter { entry ->
        when (entry) {
            is JournalEntry.InputAccepted<*> -> entry.input == input
            is JournalEntry.ProcessingStarted -> entry.input == input
            is JournalEntry.StateCommitted<*> -> entry.input == input
            is JournalEntry.DecisionCommitted -> entry.input == input
            is JournalEntry.DecisionIgnored -> entry.input == input
            is JournalEntry.ProcessingFinished -> entry.input == input
            else -> false
        }
    }

    @Test
    fun aDecision_followsItsCommit_withTheSameInputAndRevision() = runTest {
        val h = harness()

        h.store.dispatch(Act.Load)
        runCurrent()

        val startup = h.records().of(InputId(1))
        assertEquals(
            listOf("InputAccepted", "ProcessingStarted", "StateCommitted", "DecisionCommitted", "ProcessingFinished"),
            startup.map { it::class.simpleName },
        )
        val started = assertIs<JournalEntry.DecisionCommitted>(startup[3])
        assertEquals(1, started.revision)
        assertEquals(listOf(ActivationRef("Root", 1), ActivationRef("Idle", 2)), started.entered)
        assertEquals(listOf("Idle", "Root"), started.active)

        val load = h.records().of(InputId(2))
        assertEquals(
            listOf("InputAccepted", "ProcessingStarted", "StateCommitted", "DecisionCommitted", "ProcessingFinished"),
            load.map { it::class.simpleName },
        )
        val committed = assertIs<JournalEntry.StateCommitted<*>>(load[2])
        val decision = assertIs<JournalEntry.DecisionCommitted>(load[3])
        assertEquals(committed.revision, decision.revision)
        assertEquals(
            JournalEntry.DecisionCommitted(
                input = InputId(2),
                revision = 2,
                active = listOf("Loading", "Root"),
                transitions = listOf(0),
                exited = listOf(ActivationRef("Idle", 2)),
                entered = listOf(ActivationRef("Loading", 3)),
                commands = listOf(CommandRef(1, 3, "load", "Latest", Payload.Omitted)),
                cancelledScopes = listOf(2),
                timersScheduled = listOf(TimerRef(1, 2, 3, 10.seconds)),
                timersCancelled = emptyList(),
                effects = 0,
            ),
            decision,
        )
        assertTrue(h.records().zipWithNext().all { (a, b) -> a.groupSeq.value + 1 == b.groupSeq.value }, "one dense sequence")
        h.close()
    }

    @Test
    fun theCommandPayload_isWhatTheDescriberKeeps() = runTest {
        val h = harness(describeCommand = { Payload.Retained(it) })

        h.store.dispatch(Act.Load)
        runCurrent()

        val decision = h.records().entries().filterIsInstance<JournalEntry.DecisionCommitted>().last()
        assertEquals(Payload.Retained(Fetch), decision.commands.single().command)
        h.close()
    }

    @Test
    fun ignoredInputs_areJournaledWithTheirReason() = runTest {
        val h = harness()

        h.store.dispatch(Act.Loaded)
        runCurrent()
        h.inner.dispatch(MachineInput.CommandResult(CommandId(42), Act.Loaded, MachineTime.Zero))
        runCurrent()

        val ignored = h.records().entries().filterIsInstance<JournalEntry.DecisionIgnored>()
        assertEquals(listOf(InputId(2) to "NoTransition", InputId(3) to "StaleCommand"), ignored.map { it.input to it.reason })
        val outcomes = h.records().entries().filterIsInstance<JournalEntry.ProcessingFinished>().map { it.outcome.kind.name }
        assertEquals(listOf("Handled", "Unchanged", "Unchanged"), outcomes, "the store saw an unchanged processing; the journal knows why")
        h.close()
    }

    @Test
    fun rejectedActions_areJournaled_andNeverBecomeInputs() = runTest {
        val h = harness(admission = AdmissionPolicy.Bounded(1))

        assertEquals(Admission.Accepted, h.store.admit(Act.Load))
        assertEquals(Admission.Rejected(pending = 1, limit = 1), h.store.admit(Act.Load))
        runCurrent()
        assertEquals(Admission.Accepted, h.store.admit(Act.Loaded), "processed inputs free the queue")
        runCurrent()

        val rejected = h.records().entries().filterIsInstance<JournalEntry.InputRejected<*>>()
        assertEquals(listOf(JournalEntry.InputRejected(Payload.Omitted, "QueueFull(pending=1, limit=1)")), rejected)
        val accepted = h.records().entries().filterIsInstance<JournalEntry.InputAccepted<*>>()
        assertEquals(3, accepted.size, "startup, Load, Loaded; the refused Load never became an input")
        h.close()
    }

    @Test
    fun closing_journalsTheCommandsNeverStarted_andThoseCancelled() = runTest {
        // Two Fetches in one sequential lane: the first runs, the second waits behind it.
        val twoInALane = Machine<Unit, Act, Fetch, Nothing>(DefinitionId("journaled"), DefinitionVersion("1"), chart) {
            onEnter(loading) {
                command(Fetch, LaneId("load"), ConcurrencyPolicy.Sequential)
                command(Fetch, LaneId("load"), ConcurrencyPolicy.Sequential)
            }
        }
        val h = Harness(this, twoInALane, AdmissionPolicy.Unbounded, { Payload.Omitted }, null)
        h.store.startAndAwait()
        runCurrent()
        h.store.dispatch(Act.Load)
        runCurrent()
        assertEquals(2, h.store.currentState.commands.size)

        h.store.close()
        runCurrent()

        val abandoned = h.records().entries().filterIsInstance<JournalEntry.CommandsAbandoned>().single()
        assertEquals(JournalEntry.CommandsAbandoned("StoreClosed", queued = listOf(2), running = listOf(1)), abandoned)
        assertTrue(JournalFormat.line(h.records().first { it.entry == abandoned }).endsWith("CommandsAbandoned StoreClosed queued=[c2] running=[c1]"))
        assertTrue(h.records().entries().any { it == JournalEntry.StoreClosed }, "the store's own close is journaled too")
        h.executionScope.cancel()
    }

    @Test
    fun journalFormat_rendersDecisions() = runTest {
        val h = harness()
        h.store.dispatch(Act.Load)
        runCurrent()

        val lines = h.records().map { JournalFormat.line(it) }
        assertTrue(lines.any { it.endsWith("DecisionCommitted #2 revision=2 active=[Loading,Root] transitions=[T0] exited=[Idle/a2] entered=[Loading/a3] commands=[c1@a3 load/Latest -] cancelled=[a2] timers=[t1:T2@a3+10s]") }, lines.joinToString("\n"))
        h.close()
    }

    @Test
    fun anObserverThatThrows_isReported_andTheStoreContinues() = runTest {
        val boom = IllegalStateException("observer")
        val h = harness(observer = object : DecisionObserver<Unit, Act, Fetch, Nothing> {
            override fun onCommitted(input: InputId?, machineInput: MachineInput<Act>, decision: Decision<Unit, Fetch, Nothing>) = throw boom
        })

        h.store.dispatch(Act.Load)
        runCurrent()

        assertTrue(h.store.currentState.isActive(loading))
        assertTrue(h.handled.count { it === boom } >= 1)
        assertTrue(h.records().entries().any { it is JournalEntry.DecisionCommitted && it.revision == 2L }, "the journal observer still ran")
        h.close()
    }
}

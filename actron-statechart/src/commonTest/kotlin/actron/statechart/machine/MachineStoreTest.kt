@file:OptIn(ExperimentalActronApi::class, ExperimentalCoroutinesApi::class)

package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import actron.core.ExceptionHandler
import actron.core.ExperimentalActronApi
import actron.core.PluginExecutionPolicy
import actron.core.Plugin
import actron.core.StateSaver
import actron.observability.FailureDescriptor
import actron.statechart.ActionMatcher
import actron.statechart.AtomicState
import actron.statechart.CompoundState
import actron.statechart.ParallelState
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.Transition
import actron.statechart.Trigger
import actron.test.startAndAwait
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
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
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The executor of stage 2b on the list machine of [MachineTest]: decisions are committed under
 * the lock, commands run after the commit and outside it, results feed back as inputs, lane
 * policies, timers on the clock, failures, close, events, stale results, recovery and restore.
 *
 * ```
 * [*] --> Session                                     onEnter: command Connect (lane "net", Latest)
 * state Session {
 *     [*] --> Idle
 *     Idle --Load--> Loading                          onEnter: command Fetch(query, attempt)
 *     Loading --Loaded / storeItems--> Content        onEnter: event ItemsShown
 *     Loading --Failed [canRetry] / countAttempt--> Loading
 *     Loading --Failed--> Error                       onEnter: event GaveUp
 *     Loading --after 10s--> Error
 *     Error --Load / resetAttempts--> Loading
 *     Content --Refresh--> Loading
 * }
 * ```
 */
class MachineStoreTest {

    sealed interface ListAction : Action {
        data class Load(val query: String) : ListAction
        data class Loaded(val items: List<String>) : ListAction
        data object Failed : ListAction
        data object Refresh : ListAction
        data object Boom : ListAction
    }

    sealed interface ListEvent : Event {
        data object ItemsShown : ListEvent
        data object GaveUp : ListEvent
    }

    sealed interface ListCommand {
        data object Connect : ListCommand
        data class Fetch(val query: String, val attempt: Int) : ListCommand
        data class Ping(val region: String) : ListCommand
    }

    data class ListContext(val query: String = "", val items: List<String> = emptyList(), val attempts: Int = 0)

    private val session = StateId("Session")
    private val idle = StateId("Idle")
    private val loading = StateId("Loading")
    private val content = StateId("Content")
    private val error = StateId("Error")
    private val net = LaneId("net")

    private val chart = StateChartDefinition(
        initial = session,
        states = listOf(
            CompoundState(session, initial = idle),
            AtomicState(idle, parent = session),
            AtomicState(loading, parent = session),
            AtomicState(content, parent = session),
            AtomicState(error, parent = session),
        ),
        transitions = listOf(
            Transition(idle, loading, ActionMatcher.of<ListAction.Load>("Load"), effect = actron.statechart.EffectKey("rememberQuery")),
            Transition(loading, content, ActionMatcher.of<ListAction.Loaded>("Loaded"), effect = actron.statechart.EffectKey("storeItems")),
            Transition(loading, loading, ActionMatcher.of<ListAction.Failed>("Failed"), guard = actron.statechart.GuardKey("canRetry"), effect = actron.statechart.EffectKey("countAttempt")),
            Transition(loading, error, ActionMatcher.of<ListAction.Failed>("Failed")),
            Transition(loading, error, Trigger.After(10.seconds)),
            Transition(error, loading, ActionMatcher.of<ListAction.Load>("Load"), effect = actron.statechart.EffectKey("resetAttempts")),
            Transition(content, loading, ActionMatcher.of<ListAction.Refresh>("Refresh")),
            Transition(idle, idle, ActionMatcher.of<ListAction.Boom>("Boom"), guard = actron.statechart.GuardKey("boom")),
        ),
    )

    private fun listMachine(guardBoom: (MachineSnapshot<ListContext>) -> Boolean = { false }, version: String = "1") =
        Machine<ListContext, ListAction, ListCommand, ListEvent>(DefinitionId("list"), DefinitionVersion(version), chart) {
            guard("canRetry") { snapshot, _ -> snapshot.context.attempts < 3 }
            guard("boom") { snapshot, _ -> guardBoom(snapshot) }
            effect("rememberQuery") { context, action -> context.copy(query = (action as ListAction.Load).query) }
            effect("storeItems") { context, action -> context.copy(items = (action as ListAction.Loaded).items) }
            effect("countAttempt") { context, _ -> context.copy(attempts = context.attempts + 1) }
            effect("resetAttempts") { context, _ -> context.copy(attempts = 0) }
            onEnter(session) { command(ListCommand.Connect, net, ConcurrencyPolicy.Latest) }
            onEnter(loading) { command(ListCommand.Fetch(context.query, context.attempts)) }
            onEnter(content) { event(ListEvent.ItemsShown) }
            onEnter(error) { event(ListEvent.GaveUp) }
        }

    /** Two regions that each register a Ping in the same lane on entry. */
    private fun regionsMachine(policy: ConcurrencyPolicy): Machine<ListContext, ListAction, ListCommand, ListEvent> {
        val root = StateId("Root")
        val a = StateId("A")
        val b = StateId("B")
        val regions = StateChartDefinition(root, listOf(ParallelState(root), AtomicState(a, parent = root), AtomicState(b, parent = root)), emptyList())
        return Machine(DefinitionId("regions"), DefinitionVersion("1"), regions) {
            onEnter(a) { command(ListCommand.Ping("A"), net, policy) }
            onEnter(b) { command(ListCommand.Ping("B"), net, policy) }
        }
    }

    private class TestClock(private val scheduler: TestCoroutineScheduler) : MachineClock {
        override fun now(): MachineTime = MachineTime(scheduler.currentTime.milliseconds)

        override suspend fun delayUntil(deadline: MachineTime) {
            val remaining = deadline - now()
            if (remaining.isPositive()) delay(remaining)
        }
    }

    /** Records every command it was asked to run and how each ended; behaviour is set per test. */
    private class Handler : CommandHandler<ListCommand, ListAction> {
        val started = mutableListOf<CommandEnvelope<ListCommand>>()
        val cancelled = mutableListOf<CommandId>()
        val sinks = mutableMapOf<CommandId, ResultSink<ListAction>>()
        var snapshotSeen: MachineSnapshot<ListContext>? = null
        var behaviour: suspend (CommandEnvelope<ListCommand>, ResultSink<ListAction>) -> Unit = { _, _ -> awaitCancellation() }

        override suspend fun execute(command: CommandEnvelope<ListCommand>, results: ResultSink<ListAction>) {
            started += command
            sinks[command.id] = results
            try {
                behaviour(command, results)
            } catch (e: CancellationException) {
                cancelled += command.id
                throw e
            }
        }
    }

    private class Harness(scope: TestScope, machine: Machine<ListContext, ListAction, ListCommand, ListEvent>, configure: actron.statechart.StoreConfiguration<MachineSnapshot<ListContext>, MachineInput<ListAction>, ListEvent>.() -> Unit = {}) {
        val handler = Handler()
        val handled = mutableListOf<Throwable>()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val executionScope = CoroutineScope(dispatcher + SupervisorJob())
        val clock = TestClock(scope.testScheduler)
        val store = MachineStore(machine, ListContext(), handler, executionScope, clock, dispatcher) {
            exceptionHandler(ExceptionHandler { handled += it })
            configure()
        }
        val inner get() = (store as MachineStoreImpl<ListContext, ListAction, ListCommand, ListEvent>).inner

        fun close() {
            store.close()
            executionScope.cancel()
        }
    }

    private fun TestScope.harness(
        machine: Machine<ListContext, ListAction, ListCommand, ListEvent> = listMachine(),
        configure: actron.statechart.StoreConfiguration<MachineSnapshot<ListContext>, MachineInput<ListAction>, ListEvent>.() -> Unit = {},
    ) = Harness(this, machine, configure)

    // --- commit protocol ---

    @Test
    fun startup_decidesStart_andRunsTheEnterCommandsAfterTheCommit() = runTest {
        val h = harness()
        h.handler.behaviour = { _, _ -> h.handler.snapshotSeen = h.store.currentState; awaitCancellation() }

        h.store.startAndAwait()
        assertTrue(h.handler.started.isEmpty(), "nothing runs under the lock; the scheduler has not had its turn")
        runCurrent()

        assertEquals(listOf(CommandEnvelope<ListCommand>(CommandId(1), ListCommand.Connect, ActivationId(1), net)), h.handler.started)
        assertEquals(1, h.store.currentState.revision)
        assertTrue(h.handler.snapshotSeen!!.isStarted, "the handler sees the committed snapshot")
        h.close()
    }

    @Test
    fun results_feedBackAsInputs_andCompletionDeregistersTheCommand() = runTest {
        val h = harness()
        h.handler.behaviour = { command, results ->
            when (command.command) {
                is ListCommand.Fetch -> results.result(ListAction.Loaded(listOf("tom", "jerry")))
                else -> awaitCancellation()
            }
        }
        h.store.startAndAwait()
        runCurrent()

        h.store.dispatch(ListAction.Load("cats"))
        runCurrent()

        val snapshot = h.store.currentState
        assertTrue(snapshot.isActive(content))
        assertEquals(listOf("tom", "jerry"), snapshot.context.items)
        assertEquals(setOf(CommandId(1)), snapshot.commands.keys, "Fetch left with the Loading activation; Connect stays")
        assertEquals(listOf(ListCommand.Connect, ListCommand.Fetch("cats", 0)), h.handler.started.map { it.command })
        // Start, Load, CommandResult(Loaded): three handled decisions. The CommandCompleted that
        // followed found its command gone with the exited activation and was ignored as stale.
        assertEquals(3, snapshot.revision)
        h.close()
    }

    @Test
    fun exitingTheActivation_cancelsItsCommand() = runTest {
        val h = harness()
        h.store.startAndAwait()
        h.store.dispatch(ListAction.Load("cats"))
        runCurrent()
        assertEquals(2, h.handler.started.size)

        h.inner.dispatch(MachineInput.CommandResult(CommandId(2), ListAction.Failed, h.clock.now()))
        runCurrent()

        assertEquals(listOf(CommandId(2)), h.handler.cancelled, "the first Fetch was cancelled with its activation")
        assertEquals(ListCommand.Fetch("cats", 1), h.handler.started.last().command)
        assertEquals(setOf(CommandId(1), CommandId(3)), h.store.currentState.commands.keys)
        h.close()
    }

    // --- lanes ---

    @Test
    fun laneLatest_supersedesTheRunningCommand() = runTest {
        val h = harness(regionsMachine(ConcurrencyPolicy.Latest))
        h.store.startAndAwait()
        runCurrent()

        // Both are admitted in one turn: B supersedes A before A ever ran.
        assertEquals(listOf("B"), h.handler.started.map { (it.command as ListCommand.Ping).region })
        assertTrue(h.handler.cancelled.isEmpty(), "A never started, so it had nothing to cancel")
        assertEquals(setOf(CommandId(2)), h.store.currentState.commands.keys, "the superseded command left the snapshot")
        h.close()
    }

    @Test
    fun laneSequential_runsOneCommandAtATime() = runTest {
        val h = harness(regionsMachine(ConcurrencyPolicy.Sequential))
        val gate = CompletableDeferred<Unit>()
        h.handler.behaviour = { command, _ -> if ((command.command as ListCommand.Ping).region == "A") gate.await() else awaitCancellation() }
        h.store.startAndAwait()
        runCurrent()

        assertEquals(listOf("A"), h.handler.started.map { (it.command as ListCommand.Ping).region }, "B waits for A")
        gate.complete(Unit)
        runCurrent()

        assertEquals(listOf("A", "B"), h.handler.started.map { (it.command as ListCommand.Ping).region })
        assertEquals(setOf(CommandId(2)), h.store.currentState.commands.keys, "A completed, B runs")
        h.close()
    }

    @Test
    fun laneDropIfRunning_dropsTheSecondCommand() = runTest {
        val h = harness(regionsMachine(ConcurrencyPolicy.DropIfRunning))
        h.store.startAndAwait()
        runCurrent()

        assertEquals(listOf("A"), h.handler.started.map { (it.command as ListCommand.Ping).region })
        assertEquals(setOf(CommandId(1)), h.store.currentState.commands.keys, "the dropped command left the snapshot")
        h.close()
    }

    // --- lanes and activations (stabilization round) ---

    /** Regions a and b, plus a region whose node moves on a Refresh; commands are given by [rules]. */
    private fun threeRegions(rules: MachineBuilder<ListContext, ListAction, ListCommand, ListEvent>.(a: StateId, b: StateId, c1: StateId) -> Unit): Machine<ListContext, ListAction, ListCommand, ListEvent> {
        val root = StateId("Root")
        val a = StateId("A")
        val bRegion = StateId("BRegion")
        val b = StateId("B")
        val b2 = StateId("B2")
        val cRegion = StateId("CRegion")
        val c0 = StateId("C0")
        val c1 = StateId("C1")
        val chart = StateChartDefinition(
            root,
            listOf(
                ParallelState(root),
                AtomicState(a, parent = root),
                CompoundState(bRegion, initial = b, parent = root),
                AtomicState(b, parent = bRegion),
                AtomicState(b2, parent = bRegion),
                CompoundState(cRegion, initial = c0, parent = root),
                AtomicState(c0, parent = cRegion),
                AtomicState(c1, parent = cRegion),
            ),
            listOf(
                Transition(b, b2, ActionMatcher.of<ListAction.Refresh>("Refresh")),
                Transition(c0, c1, ActionMatcher.of<ListAction.Boom>("Boom")),
            ),
        )
        return Machine(DefinitionId("three"), DefinitionVersion("1"), chart) { rules(a, b, c1) }
    }

    @Test
    fun aQueuedCommand_whoseActivationExited_neverStarts() = runTest {
        val machine = threeRegions { a, b, _ ->
            onEnter(a) { command(ListCommand.Ping("A"), net, ConcurrencyPolicy.Sequential) }
            onEnter(b) { command(ListCommand.Ping("B"), net, ConcurrencyPolicy.Sequential) }
        }
        val h = harness(machine)
        val gate = CompletableDeferred<Unit>()
        h.handler.behaviour = { command, _ -> if ((command.command as ListCommand.Ping).region == "A") gate.await() else awaitCancellation() }
        h.store.startAndAwait()
        runCurrent()
        assertEquals(listOf("A"), h.handler.started.map { (it.command as ListCommand.Ping).region }, "B waits in the lane")

        // B's node moves on: the decision deregisters B, and the executor must forget the queued entry.
        h.store.dispatch(ListAction.Refresh)
        runCurrent()
        assertEquals(setOf(CommandId(1)), h.store.currentState.commands.keys)
        gate.complete(Unit)
        runCurrent()

        assertEquals(listOf("A"), h.handler.started.map { (it.command as ListCommand.Ping).region }, "a command of an exited activation never starts")
        h.close()
    }

    @Test
    fun laneLatest_supersedesQueuedCommandsToo() = runTest {
        val machine = threeRegions { a, b, c1 ->
            onEnter(a) { command(ListCommand.Ping("A"), net, ConcurrencyPolicy.Sequential) }
            onEnter(b) { command(ListCommand.Ping("B"), net, ConcurrencyPolicy.Sequential) }
            onEnter(c1) { command(ListCommand.Ping("C"), net, ConcurrencyPolicy.Latest) }
        }
        val h = harness(machine)
        h.store.startAndAwait()
        runCurrent()
        assertEquals(listOf("A"), h.handler.started.map { (it.command as ListCommand.Ping).region })

        h.store.dispatch(ListAction.Boom)
        runCurrent()

        assertEquals(listOf("A", "C"), h.handler.started.map { (it.command as ListCommand.Ping).region }, "C started at once; B never did")
        assertEquals(listOf(CommandId(1)), h.handler.cancelled, "A was superseded")
        assertEquals(setOf(CommandId(3)), h.store.currentState.commands.keys, "A and the queued B were abandoned in the snapshot too")
        h.close()
    }

    @Test
    fun aRestoredSnapshotOfAnotherVersion_startsOver_andIsReported() = runTest {
        val first = harness(listMachine(version = "1"))
        first.store.startAndAwait()
        first.store.dispatch(ListAction.Load("cats"))
        runCurrent()
        val saved = first.store.currentState
        first.close()

        val second = harness(listMachine(version = "2")) { stateSaver(StateSaver(save = {}, restore = { saved })) }
        second.store.startAndAwait()
        runCurrent()

        val restored = second.store.currentState
        assertEquals(DefinitionVersion("2"), restored.version)
        assertEquals(1, restored.revision)
        assertTrue(restored.isActive(idle))
        assertEquals(saved.context, restored.context)
        val reported = assertIs<IllegalStateException>(second.handled.single())
        assertTrue("version" in reported.message.orEmpty(), reported.message)
        second.store.dispatch(ListAction.Load("dogs"))
        runCurrent()
        assertTrue(second.store.currentState.isActive(loading), "the store works after the reset")
        second.close()
    }

    // --- timers ---

    @Test
    fun timer_firesThroughTheClock_andIsCancelledWhenItsSourceExits() = runTest {
        val h = harness()
        h.store.startAndAwait()
        h.store.dispatch(ListAction.Load("cats"))
        runCurrent()
        assertEquals(1, h.store.currentState.timers.size)

        advanceTimeBy(9.seconds)
        runCurrent()
        assertTrue(h.store.currentState.isActive(loading), "not due yet")
        advanceTimeBy(2.seconds)
        runCurrent()

        assertTrue(h.store.currentState.isActive(error), "the 10s timer fired")
        assertTrue(h.store.currentState.timers.isEmpty())

        // A load that finishes first exits Loading: the new timer is cancelled and never fires.
        h.store.dispatch(ListAction.Load("dogs"))
        runCurrent()
        h.inner.dispatch(MachineInput.CommandResult(CommandId(3), ListAction.Loaded(emptyList()), h.clock.now()))
        runCurrent()
        assertTrue(h.store.currentState.isActive(content))
        advanceTimeBy(20.seconds)
        runCurrent()
        assertTrue(h.store.currentState.isActive(content), "the cancelled timer did not fire")
        h.close()
    }

    // --- failures ---

    @Test
    fun handlerFailure_becomesCommandFailed_andIsReported() = runTest {
        val h = harness()
        val boom = IllegalStateException("socket")
        h.handler.behaviour = { command, _ -> if (command.command is ListCommand.Fetch) throw boom else awaitCancellation() }
        h.store.startAndAwait()
        h.store.dispatch(ListAction.Load("cats"))
        runCurrent()

        assertEquals(setOf(CommandId(1)), h.store.currentState.commands.keys, "the failed Fetch was deregistered")
        assertTrue(h.store.currentState.isActive(loading), "no transition declared for CommandFailure")
        assertEquals(listOf<Throwable>(boom), h.handled)
        h.close()
    }

    @Test
    fun failedDecision_commitsNothing_reportsTheCause_andLeavesRunningCommandsAlone() = runTest {
        val boom = IllegalArgumentException("guard")
        val h = harness(listMachine(guardBoom = { throw boom }))
        h.store.startAndAwait()
        runCurrent()
        val before = h.store.currentState

        h.store.dispatch(ListAction.Boom)
        runCurrent()

        assertEquals(before, h.store.currentState)
        assertEquals(listOf<Throwable>(boom), h.handled)
        assertTrue(h.handler.cancelled.isEmpty(), "Connect keeps running")
        h.close()
    }

    // --- close, events, stale results ---

    @Test
    fun closeBetweenCommitAndScheduling_startsNothing() = runTest {
        lateinit var h: Harness
        h = harness {
            pluginExecutionPolicy(PluginExecutionPolicy.InRegistrationOrder)
            plugin(Plugin(onState = { _, state -> if (state.revision == 2L) h.store.close() }))
        }
        h.store.startAndAwait()
        runCurrent()
        assertEquals(1, h.handler.started.size)

        h.store.dispatch(ListAction.Load("cats"))
        runCurrent()

        assertEquals(2, h.store.currentState.revision, "the decision was committed")
        assertEquals(1, h.handler.started.size, "the Fetch it registered never started")
        assertEquals(listOf(CommandId(1)), h.handler.cancelled, "close cancelled Connect")
        h.close()
    }

    @Test
    fun events_areDeliveredAfterTheCommit_inDecisionOrder() = runTest {
        val h = harness()
        val seen = mutableListOf<Pair<ListEvent, Long>>()
        val collector = launch(StandardTestDispatcher(testScheduler)) { h.store.event.collect { seen += it to h.store.currentState.revision } }
        h.handler.behaviour = { command, results ->
            if (command.command is ListCommand.Fetch) results.result(ListAction.Loaded(listOf("x"))) else awaitCancellation()
        }
        h.store.startAndAwait()
        h.store.dispatch(ListAction.Load("cats"))
        runCurrent()
        h.store.dispatch(ListAction.Refresh)
        runCurrent()

        assertEquals(listOf(ListEvent.ItemsShown, ListEvent.ItemsShown), seen.map { it.first })
        assertTrue(seen.all { (_, revision) -> revision >= 3 }, "each event arrived after the commit that emitted it: $seen")
        collector.cancel()
        h.close()
    }

    @Test
    fun aLateResult_ofACompletedCommand_isIgnored() = runTest {
        val h = harness()
        h.handler.behaviour = { command, results ->
            if (command.command is ListCommand.Fetch) results.result(ListAction.Loaded(listOf("first"))) else awaitCancellation()
        }
        h.store.startAndAwait()
        h.store.dispatch(ListAction.Load("cats"))
        runCurrent()
        val settled = h.store.currentState
        assertEquals(listOf("first"), settled.context.items)

        h.handler.sinks.getValue(CommandId(2)).result(ListAction.Loaded(listOf("late")))
        runCurrent()

        assertEquals(settled, h.store.currentState, "a result of a command the machine no longer holds changes nothing")
        h.close()
    }

    // --- restore and configuration ---

    @Test
    fun aRestoredStartedSnapshot_startsOverWithItsContext() = runTest {
        val first = harness()
        first.store.startAndAwait()
        first.store.dispatch(ListAction.Load("cats"))
        runCurrent()
        val saved = first.store.currentState
        assertTrue(saved.isActive(loading) && saved.commands.isNotEmpty())
        first.close()

        val second = harness { stateSaver(StateSaver(save = {}, restore = { saved })) }
        second.store.startAndAwait()
        runCurrent()

        val restored = second.store.currentState
        assertEquals(1, restored.revision)
        assertTrue(restored.isActive(idle), "the initial configuration, not the saved one")
        assertEquals(saved.context, restored.context, "the context survives")
        assertEquals(listOf(ListCommand.Connect), second.handler.started.map { it.command })
        second.close()
    }

    @Test
    fun anUnconfinedExecutionScope_isRejected() = runTest {
        assertFailsWith<IllegalArgumentException> {
            MachineStore(listMachine(), ListContext(), Handler(), CoroutineScope(Dispatchers.Unconfined + SupervisorJob()))
        }
    }

    @Test
    fun commandFailure_withADeclaredTransition_isTaken() = runTest {
        val a = StateId("A")
        val b = StateId("B")
        val reacting = StateChartDefinition(a, listOf(AtomicState(a), AtomicState(b)), listOf(Transition(a, b, ActionMatcher.of<CommandFailure>("CommandFailure"))))
        val machine = Machine<ListContext, ListAction, ListCommand, ListEvent>(DefinitionId("reacting"), DefinitionVersion("1"), reacting) {
            onEnter(a) { command(ListCommand.Ping("A")) }
        }
        val h = harness(machine)
        h.handler.behaviour = { _, _ -> throw IllegalStateException("down") }

        h.store.startAndAwait()
        runCurrent()

        assertTrue(h.store.currentState.isActive(b))
        assertEquals(1, h.handled.size)
        assertEquals(FailureDescriptor(type = "IllegalStateException"), FailureDescriptor.of(h.handled.single()))
        h.close()
    }
}

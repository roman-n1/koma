@file:OptIn(ExperimentalActronApi::class)

package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import actron.core.ExperimentalActronApi
import actron.observability.FailureDescriptor
import actron.statechart.ActionMatcher
import actron.statechart.AtomicState
import actron.statechart.CompoundState
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.TimerFired
import actron.statechart.Transition
import actron.statechart.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The pure decision machine on a list screen that loads, retries a failed load up to three times
 * and then shows an error; a session node above it owns the connection command.
 *
 * ```
 * [*] --> Session                                     onEnter: command Connect (lane "net", Latest)
 * state Session {
 *     [*] --> Idle
 *     Idle --Load--> Loading                          onEnter: command Fetch(query)
 *     Loading --Loaded / storeItems--> Content        onEnter: event ItemsShown
 *     Loading --Failed [canRetry] / countAttempt--> Loading
 *     Loading --Failed--> Error                       onEnter: event GaveUp
 *     Loading --after 10s--> Error
 *     Error --Load / resetAttempts--> Loading
 *     Content --Refresh--> Loading
 * }
 * ```
 */
class MachineTest {

    sealed interface ListAction : Action {
        data class Load(val query: String) : ListAction
        data class Loaded(val items: List<String>) : ListAction
        data object Failed : ListAction
        data object Refresh : ListAction
        data object Nothing : ListAction
    }

    sealed interface ListEvent : Event {
        data object ItemsShown : ListEvent
        data object GaveUp : ListEvent
    }

    sealed interface ListCommand {
        data object Connect : ListCommand
        data class Fetch(val query: String, val attempt: Int) : ListCommand
    }

    data class ListContext(val query: String = "", val items: List<String> = emptyList(), val attempts: Int = 0)

    private val session = StateId("Session")
    private val idle = StateId("Idle")
    private val loading = StateId("Loading")
    private val content = StateId("Content")
    private val error = StateId("Error")

    private val load = ActionMatcher.of<ListAction.Load>("Load")
    private val loaded = ActionMatcher.of<ListAction.Loaded>("Loaded")
    private val failed = ActionMatcher.of<ListAction.Failed>("Failed")
    private val refresh = ActionMatcher.of<ListAction.Refresh>("Refresh")

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
            Transition(idle, loading, load, effect = "rememberQuery"), // T0
            Transition(loading, content, loaded, effect = "storeItems"), // T1
            Transition(loading, loading, failed, guard = "canRetry", effect = "countAttempt"), // T2
            Transition(loading, error, failed), // T3
            Transition(loading, error, Trigger.After(10.seconds)), // T4
            Transition(error, loading, load, effect = "resetAttempts"), // T5
            Transition(content, loading, refresh), // T6
        ),
    )

    private val net = LaneId("net")

    private fun machine(version: String = "1") = Machine<ListContext, ListAction, ListCommand, ListEvent>(DefinitionId("list"), DefinitionVersion(version), chart) {
        guard("canRetry") { snapshot, _ -> snapshot.context.attempts < 3 }
        effect("rememberQuery") { context, action -> context.copy(query = (action as ListAction.Load).query) }
        effect("storeItems") { context, action -> context.copy(items = (action as ListAction.Loaded).items) }
        effect("countAttempt") { context, _ -> context.copy(attempts = context.attempts + 1) }
        effect("resetAttempts") { context, _ -> context.copy(attempts = 0) }
        onEnter(session) { command(ListCommand.Connect, net, ConcurrencyPolicy.Latest) }
        onEnter(loading) { command(ListCommand.Fetch(context.query, context.attempts)) }
        onEnter(content) { event(ListEvent.ItemsShown) }
        onEnter(error) { event(ListEvent.GaveUp) }
    }

    private val t0 = MachineTime.Zero
    private fun at(seconds: Int) = MachineTime(seconds.seconds)

    private fun started(machine: Machine<ListContext, ListAction, ListCommand, ListEvent> = machine()): MachineSnapshot<ListContext> =
        machine.decide(machine.initialSnapshot(ListContext()), MachineInput.Start(t0)).snapshot

    private fun <C, CMD, E : Event> Decision<C, CMD, E>.handled(): Decision<C, CMD, E> = also { assertEquals(DecisionOutcome.Handled, it.outcome) }

    private fun <C, CMD, E : Event> Decision<C, CMD, E>.ignored(reason: IgnoreReason): Decision<C, CMD, E> = also { assertEquals(DecisionOutcome.Ignored(reason), it.outcome) }

    // --- start ---

    @Test
    fun initialSnapshot_isNotStarted_andRefusesEveryInputButStart() {
        val machine = machine()
        val initial = machine.initialSnapshot(ListContext())

        assertEquals(0, initial.revision)
        assertTrue(!initial.isStarted)
        assertTrue(initial.configuration.active.isEmpty())
        machine.decide(initial, MachineInput.Dispatch(ListAction.Load("a"), t0)).ignored(IgnoreReason.NotStarted)
        machine.decide(initial, MachineInput.TimerFired(TimerId(1), t0)).ignored(IgnoreReason.NotStarted)
        machine.decide(initial, MachineInput.CommandCompleted(CommandId(1), t0)).ignored(IgnoreReason.NotStarted)
    }

    @Test
    fun start_entersTheInitialConfiguration_activatesEveryNode_andRegistersTheEnterCommands() {
        val machine = machine()

        val decision = machine.decide(machine.initialSnapshot(ListContext()), MachineInput.Start(t0)).handled()

        val snapshot = decision.snapshot
        assertEquals(1, snapshot.revision)
        assertEquals(setOf(session, idle), snapshot.configuration.active)
        assertEquals(listOf(Activation(session, ActivationId(1)), Activation(idle, ActivationId(2))), decision.entered)
        assertEquals(mapOf(session to ActivationId(1), idle to ActivationId(2)), snapshot.activations)
        assertEquals(
            listOf(CommandRegistration(CommandId(1), ListCommand.Connect, scope = ActivationId(1), lane = net, policy = ConcurrencyPolicy.Latest)),
            decision.commands,
        )
        assertEquals(mapOf(CommandId(1) to CommandRecord(ActivationId(1), net)), snapshot.commands)
        assertTrue(decision.exited.isEmpty() && decision.transitions.isEmpty() && decision.timersScheduled.isEmpty() && decision.effects.isEmpty())
        machine.decide(snapshot, MachineInput.Start(at(1))).ignored(IgnoreReason.AlreadyStarted)
    }

    // --- steps ---

    @Test
    fun dispatch_runsEffectsAndEnterRules_scopesCommandsToTheNewActivation_andSchedulesTimers() {
        val machine = machine()
        val snapshot = started(machine)

        val decision = machine.decide(snapshot, MachineInput.Dispatch(ListAction.Load("cats"), at(3))).handled()

        assertEquals(listOf(TransitionId(0)), decision.transitions)
        assertEquals(listOf(Activation(idle, ActivationId(2))), decision.exited)
        assertEquals(listOf(ActivationId(2)), decision.cancelledScopes)
        assertEquals(listOf(Activation(loading, ActivationId(3))), decision.entered)
        assertEquals(ListContext(query = "cats"), decision.snapshot.context)
        assertEquals(listOf(CommandRegistration(CommandId(2), ListCommand.Fetch("cats", 0), scope = ActivationId(3))), decision.commands)
        assertEquals(listOf(TimerSchedule(TimerId(1), TransitionId(4), ActivationId(3), deadline = at(13))), decision.timersScheduled)
        assertEquals(mapOf(TimerId(1) to TimerRecord(TransitionId(4), ActivationId(3), at(13))), decision.snapshot.timers)
        assertEquals(setOf(CommandId(1), CommandId(2)), decision.snapshot.commands.keys, "the session's Connect stays registered")
        assertEquals(2, decision.snapshot.revision)
    }

    @Test
    fun dispatch_withoutATransition_isIgnored_andTheSnapshotIsTheSameObject() {
        val machine = machine()
        val snapshot = started(machine)

        val decision = machine.decide(snapshot, MachineInput.Dispatch(ListAction.Nothing, at(1))).ignored(IgnoreReason.NoTransition)

        assertSame(snapshot, decision.snapshot)
        assertTrue(decision.commands.isEmpty() && decision.effects.isEmpty() && decision.timersScheduled.isEmpty())
    }

    @Test
    fun selfLoop_endsTheActivation_cancelsItsCommandAndTimer_andStartsNewOnes() {
        val machine = machine()
        val loadingSnapshot = machine.decide(started(machine), MachineInput.Dispatch(ListAction.Load("cats"), at(3))).snapshot

        val decision = machine.decide(loadingSnapshot, MachineInput.CommandResult(CommandId(2), ListAction.Failed, at(5))).handled()

        assertEquals(listOf(TransitionId(2)), decision.transitions)
        assertEquals(listOf(Activation(loading, ActivationId(3))), decision.exited)
        assertEquals(listOf(Activation(loading, ActivationId(4))), decision.entered)
        assertEquals(listOf(TimerId(1)), decision.timersCancelled)
        assertEquals(listOf(TimerSchedule(TimerId(2), TransitionId(4), ActivationId(4), deadline = at(15))), decision.timersScheduled)
        assertEquals(listOf(CommandRegistration(CommandId(3), ListCommand.Fetch("cats", 1), scope = ActivationId(4))), decision.commands)
        assertEquals(setOf(CommandId(1), CommandId(3)), decision.snapshot.commands.keys, "the old Fetch left with its activation")
        assertEquals(1, decision.snapshot.context.attempts)
    }

    @Test
    fun equalBusinessData_isStillHandled_withANewRevision() {
        val machine = Machine<ListContext, ListAction, ListCommand, ListEvent>(DefinitionId("loop"), DefinitionVersion("1"), chart) {
            guard("canRetry") { _, _ -> true }
            effect("rememberQuery") { context, _ -> context }
            effect("storeItems") { context, _ -> context }
            effect("countAttempt") { context, _ -> context }
            effect("resetAttempts") { context, _ -> context }
            onEnter(loading) { command(ListCommand.Fetch("", 0)) }
        }
        val loadingSnapshot = machine.decide(started(machine), MachineInput.Dispatch(ListAction.Load("x"), t0)).snapshot

        val decision = machine.decide(loadingSnapshot, MachineInput.Dispatch(ListAction.Failed, at(1))).handled()

        assertEquals(loadingSnapshot.context, decision.snapshot.context)
        assertEquals(loadingSnapshot.configuration, decision.snapshot.configuration)
        assertEquals(loadingSnapshot.revision + 1, decision.snapshot.revision)
        assertEquals(1, decision.commands.size, "a command was registered although nothing visible changed")
    }

    @Test
    fun effects_areEmittedInOrder_withIds() {
        val machine = machine()
        val loadingSnapshot = machine.decide(started(machine), MachineInput.Dispatch(ListAction.Load("cats"), at(3))).snapshot

        val decision = machine.decide(loadingSnapshot, MachineInput.CommandResult(CommandId(2), ListAction.Loaded(listOf("tom")), at(4))).handled()

        assertEquals(listOf(EffectEnvelope(EffectId(1), ListEvent.ItemsShown)), decision.effects)
        assertEquals(listOf("tom"), decision.snapshot.context.items)
        assertEquals(1, decision.snapshot.counters.effects)
    }

    // --- commands ---

    @Test
    fun lateResult_ofACommandWhoseScopeExited_isStale() {
        val machine = machine()
        val loadingSnapshot = machine.decide(started(machine), MachineInput.Dispatch(ListAction.Load("cats"), at(3))).snapshot
        val retried = machine.decide(loadingSnapshot, MachineInput.CommandResult(CommandId(2), ListAction.Failed, at(5))).snapshot

        val late = machine.decide(retried, MachineInput.CommandResult(CommandId(2), ListAction.Loaded(listOf("old")), at(6))).ignored(IgnoreReason.StaleCommand)

        assertSame(retried, late.snapshot)
        machine.decide(retried, MachineInput.CommandResult(CommandId(3), ListAction.Loaded(listOf("new")), at(7))).handled()
    }

    @Test
    fun commandCompleted_deregistersIt_withoutATransition() {
        val machine = machine()
        val snapshot = started(machine)

        val decision = machine.decide(snapshot, MachineInput.CommandCompleted(CommandId(1), at(1))).handled()

        assertTrue(decision.transitions.isEmpty())
        assertTrue(decision.snapshot.commands.isEmpty())
        assertEquals(snapshot.revision + 1, decision.snapshot.revision)
        machine.decide(decision.snapshot, MachineInput.CommandCompleted(CommandId(1), at(2))).ignored(IgnoreReason.StaleCommand)
    }

    @Test
    fun commandAbandoned_deregistersIt_andIsStaleAfterwards() {
        val machine = machine()
        val snapshot = started(machine)

        val decision = machine.decide(snapshot, MachineInput.CommandAbandoned(CommandId(1), AbandonReason.Superseded, at(1))).handled()

        assertTrue(decision.transitions.isEmpty())
        assertTrue(decision.snapshot.commands.isEmpty())
        assertEquals(snapshot.revision + 1, decision.snapshot.revision)
        machine.decide(decision.snapshot, MachineInput.CommandAbandoned(CommandId(1), AbandonReason.Dropped, at(2))).ignored(IgnoreReason.StaleCommand)
    }

    @Test
    fun commandFailed_deregistersIt_andStepsWithCommandFailure() {
        val failure = FailureDescriptor(type = "IOException")
        val fetchFailed = ActionMatcher.of<CommandFailure>("CommandFailure")
        val reacting = StateChartDefinition(
            initial = loading,
            states = listOf(AtomicState(loading), AtomicState(error)),
            transitions = listOf(Transition(loading, error, fetchFailed)),
        )
        val machine = Machine<ListContext, ListAction, ListCommand, ListEvent>(DefinitionId("reacting"), DefinitionVersion("1"), reacting) {
            onEnter(loading) { command(ListCommand.Fetch("q", 0)) }
            onEnter(error) { event(ListEvent.GaveUp) }
        }
        val snapshot = started(machine)

        val decision = machine.decide(snapshot, MachineInput.CommandFailed(CommandId(1), failure, at(1))).handled()

        assertEquals(listOf(TransitionId(0)), decision.transitions)
        assertEquals(setOf(error), decision.snapshot.configuration.active)
        assertTrue(decision.snapshot.commands.isEmpty())
        assertEquals(listOf(EffectEnvelope(EffectId(1), ListEvent.GaveUp)), decision.effects)

        // Without a matching transition the command is only deregistered.
        val plain = machine()
        val plainSnapshot = plain.decide(started(plain), MachineInput.Dispatch(ListAction.Load("q"), t0)).snapshot
        val unmatched = plain.decide(plainSnapshot, MachineInput.CommandFailed(CommandId(2), failure, at(1))).handled()
        assertTrue(unmatched.transitions.isEmpty())
        assertEquals(setOf(CommandId(1)), unmatched.snapshot.commands.keys)
        assertEquals(plainSnapshot.revision + 1, unmatched.snapshot.revision)
    }

    // --- timers ---

    @Test
    fun timerFired_takesItsTransition_andIsSpent() {
        val machine = machine()
        val loadingSnapshot = machine.decide(started(machine), MachineInput.Dispatch(ListAction.Load("cats"), at(3))).snapshot

        val decision = machine.decide(loadingSnapshot, MachineInput.TimerFired(TimerId(1), at(13))).handled()

        assertEquals(listOf(TransitionId(4)), decision.transitions)
        assertEquals(setOf(session, error), decision.snapshot.configuration.active)
        assertEquals(listOf(TimerId(1)), decision.timersCancelled)
        assertTrue(decision.snapshot.timers.isEmpty())
        assertEquals(listOf(EffectEnvelope(EffectId(1), ListEvent.GaveUp)), decision.effects)
        machine.decide(decision.snapshot, MachineInput.TimerFired(TimerId(1), at(14))).ignored(IgnoreReason.UnknownTimer)
    }

    @Test
    fun timerWhoseGuardIsFalse_isSpentWithoutATransition() {
        val guarded = StateChartDefinition(
            initial = loading,
            states = listOf(AtomicState(loading), AtomicState(error)),
            transitions = listOf(Transition(loading, error, Trigger.After(1.seconds), guard = "never")),
        )
        val machine = Machine<ListContext, ListAction, ListCommand, ListEvent>(DefinitionId("guarded"), DefinitionVersion("1"), guarded) {
            guard("never") { _, action -> assertIs<TimerFired>(action); false }
        }
        val snapshot = started(machine)
        assertEquals(1, snapshot.timers.size)

        val decision = machine.decide(snapshot, MachineInput.TimerFired(TimerId(1), at(1))).handled()

        assertTrue(decision.transitions.isEmpty())
        assertEquals(listOf(TimerId(1)), decision.timersCancelled)
        assertTrue(decision.snapshot.timers.isEmpty())
        assertEquals(setOf(loading), decision.snapshot.configuration.active)
    }

    @Test
    fun exitingTheTimerSource_cancelsItsTimer() {
        val machine = machine()
        val loadingSnapshot = machine.decide(started(machine), MachineInput.Dispatch(ListAction.Load("cats"), at(3))).snapshot

        val decision = machine.decide(loadingSnapshot, MachineInput.CommandResult(CommandId(2), ListAction.Loaded(emptyList()), at(4))).handled()

        assertEquals(listOf(TimerId(1)), decision.timersCancelled)
        assertTrue(decision.snapshot.timers.isEmpty())
        machine.decide(decision.snapshot, MachineInput.TimerFired(TimerId(1), at(13))).ignored(IgnoreReason.UnknownTimer)
    }

    // --- failures and purity ---

    @Test
    fun aThrowingGuard_failsTheDecision_andLeavesTheSnapshotUntouched() {
        val boom = IllegalStateException("boom")
        val machine = Machine<ListContext, ListAction, ListCommand, ListEvent>(DefinitionId("throwing"), DefinitionVersion("1"), chart) {
            guard("canRetry") { _, _ -> throw boom }
            effect("rememberQuery") { context, _ -> context }
            effect("storeItems") { context, _ -> context }
            effect("countAttempt") { context, _ -> context }
            effect("resetAttempts") { context, _ -> context }
            onEnter(loading) { command(ListCommand.Fetch("", 0)) }
        }
        val loadingSnapshot = machine.decide(started(machine), MachineInput.Dispatch(ListAction.Load("x"), t0)).snapshot

        val decision = machine.decide(loadingSnapshot, MachineInput.Dispatch(ListAction.Failed, at(1)))

        val failed = assertIs<DecisionOutcome.Failed>(decision.outcome)
        assertSame(boom, failed.cause)
        assertEquals(FailureDescriptor(type = "IllegalStateException"), failed.failure)
        assertSame(loadingSnapshot, decision.snapshot)
        assertTrue(decision.commands.isEmpty() && decision.timersCancelled.isEmpty() && decision.effects.isEmpty())
    }

    @Test
    fun aThrowingEnterRule_failsTheDecision_withoutPartialRegistrations() {
        val machine = Machine<ListContext, ListAction, ListCommand, ListEvent>(DefinitionId("throwing"), DefinitionVersion("1"), chart) {
            guard("canRetry") { _, _ -> true }
            effect("rememberQuery") { context, _ -> context }
            effect("storeItems") { context, _ -> context }
            effect("countAttempt") { context, _ -> context }
            effect("resetAttempts") { context, _ -> context }
            onEnter(loading) {
                command(ListCommand.Fetch("", 0))
                throw IllegalArgumentException("rule")
            }
        }
        val snapshot = started(machine)

        val decision = machine.decide(snapshot, MachineInput.Dispatch(ListAction.Load("x"), t0))

        assertIs<DecisionOutcome.Failed>(decision.outcome)
        assertSame(snapshot, decision.snapshot)
        assertTrue(decision.commands.isEmpty())
    }

    @Test
    fun decide_isDeterministic_andIdsComeFromTheSnapshot() {
        val machine = machine()
        val inputs = listOf(
            MachineInput.Start(t0),
            MachineInput.Dispatch(ListAction.Load("cats"), at(1)),
            MachineInput.CommandResult(CommandId(2), ListAction.Failed, at(2)),
            MachineInput.CommandResult(CommandId(3), ListAction.Failed, at(3)),
            MachineInput.CommandResult(CommandId(4), ListAction.Loaded(listOf("a")), at(4)),
            MachineInput.Dispatch(ListAction.Refresh, at(5)),
            MachineInput.TimerFired(TimerId(4), at(15)),
            MachineInput.Dispatch(ListAction.Load("dogs"), at(16)),
        )
        fun run(): List<Decision<ListContext, ListCommand, ListEvent>> {
            var snapshot = machine.initialSnapshot(ListContext())
            return inputs.map { input -> machine.decide(snapshot, input).also { snapshot = it.snapshot } }
        }

        val first = run()
        val second = run()

        assertEquals(first, second)
        assertTrue(first.all { it.isHandled }, first.map { it.outcome }.toString())
        assertEquals((1L..inputs.size).toList(), first.map { it.snapshot.revision })
        val commandIds = first.flatMap { it.commands }.map { it.id.value }
        assertEquals(commandIds.sorted(), commandIds)
        assertEquals(commandIds.toSet().size, commandIds.size, "command ids are never reused")
    }

    // --- action handlers ---

    private fun machineWithHandlers(extra: MachineBuilder<ListContext, ListAction, ListCommand, ListEvent>.() -> Unit) =
        Machine<ListContext, ListAction, ListCommand, ListEvent>(DefinitionId("handlers"), DefinitionVersion("1"), chart) {
            guard("canRetry") { snapshot, _ -> snapshot.context.attempts < 3 }
            effect("rememberQuery") { context, action -> context.copy(query = (action as ListAction.Load).query) }
            effect("storeItems") { context, _ -> context }
            effect("countAttempt") { context, _ -> context.copy(attempts = context.attempts + 1) }
            effect("resetAttempts") { context, _ -> context.copy(attempts = 0) }
            onEnter(session) { command(ListCommand.Connect, net, ConcurrencyPolicy.Latest) }
            extra()
        }

    private val nothing = ActionMatcher.of<ListAction.Nothing>("Nothing")

    @Test
    fun actionHandler_updatesTheContext_withoutLeavingAnyNode_orCancellingAnything() {
        val machine = machineWithHandlers {
            onAction(session, nothing) {
                context = context.copy(attempts = 42)
                command(ListCommand.Fetch("side", 0))
                event(ListEvent.ItemsShown)
            }
        }
        val snapshot = started(machine)

        val decision = machine.decide(snapshot, MachineInput.Dispatch(ListAction.Nothing, at(1))).handled()

        assertTrue(decision.transitions.isEmpty() && decision.exited.isEmpty() && decision.entered.isEmpty() && decision.cancelledScopes.isEmpty())
        assertEquals(snapshot.configuration, decision.snapshot.configuration)
        assertEquals(snapshot.activations, decision.snapshot.activations)
        assertEquals(42, decision.snapshot.context.attempts)
        assertEquals(listOf(CommandRegistration(CommandId(2), ListCommand.Fetch("side", 0), scope = ActivationId(1))), decision.commands, "scoped to Session's current activation")
        assertEquals(setOf(CommandId(1), CommandId(2)), decision.snapshot.commands.keys)
        assertEquals(listOf(EffectEnvelope(EffectId(1), ListEvent.ItemsShown)), decision.effects)
        assertEquals(snapshot.revision + 1, decision.snapshot.revision)
    }

    @Test
    fun aTransition_winsOverAnActionHandler() {
        val machine = machineWithHandlers {
            onAction(session, load) { context = context.copy(attempts = 99) }
        }

        val decision = machine.decide(started(machine), MachineInput.Dispatch(ListAction.Load("cats"), at(1))).handled()

        assertEquals(listOf(TransitionId(0)), decision.transitions)
        assertEquals(ListContext(query = "cats"), decision.snapshot.context, "the handler did not run")
    }

    @Test
    fun theInnermostActiveNodeWithAMatchingHandler_wins() {
        val machine = machineWithHandlers {
            onAction(session, nothing) { context = context.copy(attempts = 1) }
            onAction(idle, nothing) { context = context.copy(attempts = 2) }
        }

        val decision = machine.decide(started(machine), MachineInput.Dispatch(ListAction.Nothing, at(1))).handled()

        assertEquals(2, decision.snapshot.context.attempts)
    }

    @Test
    fun aHandlerOnAnInactiveNode_isNotConsulted() {
        val machine = machineWithHandlers {
            onAction(content, nothing) { context = context.copy(attempts = 7) }
        }

        machine.decide(started(machine), MachineInput.Dispatch(ListAction.Nothing, at(1))).ignored(IgnoreReason.NoTransition)
    }

    @Test
    fun aHandlerForAnUndeclaredNode_isRejected() {
        assertFailsWith<IllegalArgumentException> {
            machineWithHandlers { onAction(StateId("Nowhere"), nothing) { } }
        }
    }

    // --- construction ---

    @Test
    fun construction_rejectsMissingImplementations_andRulesForUndeclaredStates() {
        assertFailsWith<IllegalArgumentException> {
            Machine<ListContext, ListAction, ListCommand, ListEvent>(DefinitionId("list"), DefinitionVersion("1"), chart) {
                guard("canRetry") { _, _ -> true }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            Machine<ListContext, ListAction, ListCommand, ListEvent>(DefinitionId("list"), DefinitionVersion("1"), chart) {
                guard("canRetry") { _, _ -> true }
                effect("rememberQuery") { context, _ -> context }
                effect("storeItems") { context, _ -> context }
                effect("countAttempt") { context, _ -> context }
                effect("resetAttempts") { context, _ -> context }
                onEnter(StateId("Nowhere")) { }
            }
        }
    }

    @Test
    fun decide_rejectsASnapshotOfAnotherVersion() {
        val v1 = machine("1")
        val v2 = machine("2")
        val snapshot = started(v1)

        assertFailsWith<IllegalArgumentException> { v2.decide(snapshot, MachineInput.Dispatch(ListAction.Load("x"), t0)) }
        assertNull(snapshot.commands[CommandId(99)])
    }
}

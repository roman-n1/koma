@file:OptIn(ExperimentalKomaApi::class, InternalKomaApi::class)

package koma.statechart.machine

import koma.core.Action
import koma.core.Event
import koma.core.ExceptionHandler
import koma.core.ExperimentalKomaApi
import koma.core.InputId
import koma.core.InternalKomaApi
import koma.statechart.ActionMatcher
import koma.statechart.AtomicState
import koma.statechart.CompoundState
import koma.statechart.StateChartDefinition
import koma.statechart.StateId
import koma.statechart.Transition
import koma.statechart.Trigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The first replay: a [MachineStore] runs under a multi-threaded storm of loads, ticks, results
 * and timers on [Dispatchers.Default], while a [DecisionObserver] records every machine input
 * in processing order with the snapshot it produced. Afterwards the recorded inputs are
 * decided again from the initial snapshot by the pure machine, and every snapshot must come out
 * equal, ignored inputs included. Nothing about the thread interleaving, the real clock or the
 * handler latencies may leak into a decision; if it does, this is where it shows.
 *
 * ```
 * [*] --> Idle
 * Idle --Load--> Loading                onEnter: command Fetch(n); timer 3ms to Idle
 * Loading --Load--> Loading
 * Loading --Loaded / store--> Idle
 * Root: onAction Tick / count
 * ```
 */
class ReplayDeterminismTest {

    data class Ctx(val ticks: Int = 0, val loaded: Int = -1, val timeouts: Int = 0)

    sealed interface Act : Action {
        data class Load(val n: Int) : Act
        data class Loaded(val n: Int) : Act
        data object Tick : Act
    }

    data object Nothing : Event

    data class Fetch(val n: Int)

    private val root = StateId("Root")
    private val idle = StateId("Idle")
    private val loading = StateId("Loading")

    private val chart = StateChartDefinition(
        root,
        listOf(CompoundState(root, initial = idle), AtomicState(idle, parent = root), AtomicState(loading, parent = root)),
        listOf(
            Transition(idle, loading, ActionMatcher.of<Act.Load>("Load")),
            Transition(loading, loading, ActionMatcher.of<Act.Load>("Load")),
            Transition(loading, idle, ActionMatcher.of<Act.Loaded>("Loaded"), effect = "store"),
            Transition(loading, idle, Trigger.After(3.milliseconds), effect = "timeout"),
        ),
    )

    private val machine = Machine<Ctx, Act, Fetch, Nothing>(DefinitionId("replay"), DefinitionVersion("1"), chart) {
        effect("store") { c, a -> c.copy(loaded = (a as Act.Loaded).n) }
        effect("timeout") { c, _ -> c.copy(timeouts = c.timeouts + 1) }
        onEnter(loading) { command(Fetch((action as Act.Load).n)) }
        onAction(root, ActionMatcher.of<Act.Tick>("Tick")) { context = context.copy(ticks = context.ticks + 1) }
    }

    private class Recorded(val input: MachineInput<Act>, val snapshot: MachineSnapshot<Ctx>?)

    @Test
    fun aRecordedRun_decidesAgainToTheSameSnapshots() = runTest {
        val senders = 6
        val perSender = 120
        val recorded = Channel<Recorded>(Channel.UNLIMITED)
        val handled = Channel<Throwable>(Channel.UNLIMITED)
        val executionScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val handler = CommandHandler<Fetch, Act> { command, results ->
            delay(Random.nextLong(0, 5).milliseconds)
            results.result(Act.Loaded(command.command.n))
        }
        val recorder = object : DecisionObserver<Ctx, Act, Fetch, Nothing> {
            override fun onCommitted(input: InputId?, machineInput: MachineInput<Act>, decision: Decision<Ctx, Fetch, Nothing>) {
                recorded.trySend(Recorded(machineInput, decision.snapshot))
            }

            override fun onIgnored(input: InputId?, machineInput: MachineInput<Act>, reason: IgnoreReason) {
                recorded.trySend(Recorded(machineInput, null))
            }
        }
        val store = MachineStore(machine, Ctx(), handler, executionScope, coroutineContext = Dispatchers.Default, observers = listOf(recorder)) {
            exceptionHandler(ExceptionHandler { handled.trySend(it) })
        }
        val ticks = senders * perSender / 2

        withContext(Dispatchers.Default) {
            coroutineScope {
                repeat(senders) { sender ->
                    launch {
                        repeat(perSender) { index ->
                            store.dispatch(if (index % 2 == 0) Act.Tick else Act.Load(sender * perSender + index))
                            if (index % 17 == 0) delay(1.milliseconds)
                        }
                    }
                }
            }
            withTimeout(20_000) { store.state.first { it.context.ticks == ticks && it.commands.isEmpty() && it.isActive(idle) } }
        }
        val final = store.currentState
        store.close()
        executionScope.cancel()
        val run = generateSequence { recorded.tryReceive().getOrNull() }.toList()

        // Replay: the same inputs, in processing order, from the initial snapshot, by the pure machine.
        var snapshot = machine.initialSnapshot(Ctx())
        var replayed = 0
        run.forEachIndexed { index, step ->
            val decision = machine.decide(snapshot, step.input)
            if (step.snapshot != null) {
                assertEquals(DecisionOutcome.Handled, decision.outcome, "step $index: ${step.input} was handled live")
                assertEquals(step.snapshot, decision.snapshot, "step $index: ${step.input} decided differently on replay")
                snapshot = decision.snapshot
                replayed++
            } else {
                assertTrue(decision.outcome is DecisionOutcome.Ignored, "step $index: ${step.input} was ignored live, replay says ${decision.outcome}")
            }
        }
        assertEquals(final, snapshot, "the replay ends where the live run ended")
        assertEquals(final.revision, replayed.toLong())
        assertEquals(null, handled.tryReceive().getOrNull(), "nothing was reported")
        // How rich the storm was depends on the platform's threads and timers; it is reported, not required.
        val timers = run.count { it.input is MachineInput.TimerFired && it.snapshot != null }
        val stale = run.count { it.input is MachineInput.CommandResult<*> && it.snapshot == null }
        println("[Replay] ${run.size} steps replayed: ${final.revision} commits, $timers timers fired, $stale stale results ignored")
    }
}

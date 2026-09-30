@file:OptIn(ExperimentalKomaApi::class, ExperimentalCoroutinesApi::class, InternalKomaApi::class)

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
import koma.test.startAndAwait
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * A timer firing at the very instant its source is exited by a result, in both orders the
 * scheduler can pick. Exactly one of the two takes effect, the other is ignored with its reason,
 * the snapshot holds neither a spent timer nor a finished command, and a second firing of the
 * same timer changes nothing.
 *
 * ```
 * [*] --> Idle
 * Idle --Load--> Loading            onEnter: command Fetch; timer 100ms
 * Loading --Loaded--> Content
 * Loading --after 100ms--> Timeout
 * ```
 */
class TimerExitRaceTest {

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
    private val timeout = StateId("Timeout")

    private val chart = StateChartDefinition(
        root,
        listOf(CompoundState(root, initial = idle), AtomicState(idle, parent = root), AtomicState(loading, parent = root), AtomicState(content, parent = root), AtomicState(timeout, parent = root)),
        listOf(
            Transition(idle, loading, ActionMatcher.of<Act.Load>("Load")),
            Transition(loading, content, ActionMatcher.of<Act.Loaded>("Loaded")),
            Transition(loading, timeout, Trigger.After(100.milliseconds)),
        ),
    )

    private val machine = Machine<Unit, Act, Fetch, Nothing>(DefinitionId("timer-race"), DefinitionVersion("1"), chart) {
        onEnter(loading) { command(Fetch) }
    }

    private class TestClock(private val scheduler: TestCoroutineScheduler) : MachineClock {
        override fun now(): MachineTime = MachineTime(scheduler.currentTime.milliseconds)

        override suspend fun delayUntil(deadline: MachineTime) {
            val remaining = deadline - now()
            if (remaining.isPositive()) delay(remaining)
        }
    }

    private fun run(resultFirst: Boolean) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val executionScope = CoroutineScope(dispatcher + SupervisorJob())
        val gate = CompletableDeferred<Unit>()
        var sink: ResultSink<Act>? = null
        val ignored = mutableListOf<Pair<MachineInput<Act>, IgnoreReason>>()
        val handled = mutableListOf<Throwable>()
        val store = MachineStore(
            machine, Unit,
            CommandHandler<Fetch, Act> { _, results -> sink = results; gate.await(); awaitCancellation() },
            executionScope, TestClock(testScheduler), dispatcher,
            observers = listOf(object : DecisionObserver<Unit, Act, Fetch, Nothing> {
                override fun onIgnored(input: InputId?, machineInput: MachineInput<Act>, reason: IgnoreReason) {
                    ignored += machineInput to reason
                }
            }),
        ) { exceptionHandler(ExceptionHandler { handled += it }) }
        (store as MachineStoreImpl<Unit, Act, Fetch, Nothing>).inner.startAndAwait()
        store.dispatch(Act.Load)
        runCurrent()
        val timer = store.currentState.timers.keys.single()
        val command = store.currentState.commands.keys.single()

        // Both events land on the same virtual instant; only their order differs.
        advanceTimeBy(100.milliseconds)
        if (resultFirst) {
            sink!!.result(Act.Loaded)
            runCurrent()
        } else {
            runCurrent()
            sink!!.result(Act.Loaded)
            runCurrent()
        }

        val snapshot = store.currentState
        val won = if (resultFirst) content else timeout
        val lost = if (resultFirst) timeout else content
        assertTrue(snapshot.isActive(won) && !snapshot.isActive(lost), "resultFirst=$resultFirst: ${snapshot.configuration.active}")
        assertTrue(snapshot.timers.isEmpty(), "no timer is left, spent or cancelled")
        assertTrue(snapshot.commands.isEmpty(), "the command left with Loading")
        val reason = ignored.single().second
        assertEquals(if (resultFirst) IgnoreReason.UnknownTimer else IgnoreReason.StaleCommand, reason)
        // A second firing of the same timer, or a second result, changes nothing more.
        store.inner.dispatch(MachineInput.TimerFired(timer, MachineTime(100.milliseconds)))
        store.inner.dispatch(MachineInput.CommandResult(command, Act.Loaded, MachineTime(100.milliseconds)))
        runCurrent()
        assertEquals(snapshot, store.currentState)
        assertTrue(handled.isEmpty())
        store.close()
        executionScope.cancel()
    }

    @Test
    fun theResultArrivesFirst_andTheTimerIsUnknown() = run(resultFirst = true)

    @Test
    fun theTimerFiresFirst_andTheResultIsStale() = run(resultFirst = false)
}

@file:OptIn(ExperimentalKomaApi::class, ExperimentalCoroutinesApi::class, InternalKomaApi::class)

package koma.statechart.machine

import koma.core.Action
import koma.core.Event
import koma.core.ExceptionHandler
import koma.core.ExperimentalKomaApi
import koma.core.InternalKomaApi
import koma.core.StoreProbe
import koma.observability.JournalConfig
import koma.observability.JournalSink
import koma.observability.Payload
import koma.observability.PayloadPolicy
import koma.observability.RecordingSession
import koma.observability.StoreInstanceId
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Faults injected into every diagnostic boundary at random, over several seeds: a probe that
 * throws, a payload policy that throws, a journal sink that throws, a decision observer that
 * throws. The committed snapshots of the run must equal those of a run without any journal,
 * step for step; the faults are counted and reported, and nothing else changes.
 *
 * ```
 * [*] --> Idle
 * Idle --Load--> Loading            onEnter: command Fetch(n); timer 50ms to Idle
 * Loading --Loaded / store--> Idle
 * Loading --Load--> Loading
 * ```
 */
class FaultInjectionTest {

    data class Ctx(val loaded: List<Int> = emptyList(), val timeouts: Int = 0)

    sealed interface Act : Action {
        data class Load(val n: Int) : Act
        data class Loaded(val n: Int) : Act
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
            Transition(loading, idle, ActionMatcher.of<Act.Loaded>("Loaded"), effect = "store"),
            Transition(loading, loading, ActionMatcher.of<Act.Load>("Load")),
            Transition(loading, idle, Trigger.After(50.milliseconds), effect = "timeout"),
        ),
    )

    private val machine = Machine<Ctx, Act, Fetch, Nothing>(DefinitionId("faults"), DefinitionVersion("1"), chart) {
        effect("store") { c, a -> c.copy(loaded = c.loaded + (a as Act.Loaded).n) }
        effect("timeout") { c, _ -> c.copy(timeouts = c.timeouts + 1) }
        onEnter(loading) { command(Fetch((action as Act.Load).n)) }
    }

    /** Relative to its creation: several stores run one after another on one virtual clock. */
    private class TestClock(private val scheduler: TestCoroutineScheduler) : MachineClock {
        private val origin = scheduler.currentTime

        override fun now(): MachineTime = MachineTime((scheduler.currentTime - origin).milliseconds)

        override suspend fun delayUntil(deadline: MachineTime) {
            val remaining = deadline - now()
            if (remaining.isPositive()) delay(remaining)
        }
    }

    /** The scripted scenario: loads with latencies that let some time out and some answer. */
    private suspend fun TestScope.scenario(store: MachineStore<Ctx, Act, Nothing>, snapshots: MutableList<MachineSnapshot<Ctx>>) {
        (store as MachineStoreImpl<Ctx, Act, Fetch, Nothing>).inner.startAndAwait()
        runCurrent()
        val random = Random(99)
        repeat(40) { i ->
            store.dispatch(Act.Load(i))
            advanceTimeBy(random.nextLong(5, 80).milliseconds)
            runCurrent()
        }
        advanceTimeBy(200.milliseconds)
        runCurrent()
        snapshots += store.currentState
    }

    private fun TestScope.newStore(faults: Random?, session: RecordingSession?, handled: MutableList<Throwable>, committed: MutableList<MachineSnapshot<Ctx>>): Pair<MachineStore<Ctx, Act, Nothing>, CoroutineScope> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val executionScope = CoroutineScope(dispatcher + SupervisorJob())
        val handler = CommandHandler<Fetch, Act> { command, results ->
            delay(((command.command.n * 37) % 90 + 5).milliseconds)
            results.result(Act.Loaded(command.command.n))
        }
        val recorder = object : DecisionObserver<Ctx, Act, Fetch, Nothing> {
            override fun onCommitted(input: koma.core.InputId?, machineInput: MachineInput<Act>, decision: Decision<Ctx, Fetch, Nothing>) {
                committed += decision.snapshot
            }
        }
        val faulty = faults?.let { random ->
            object : DecisionObserver<Ctx, Act, Fetch, Nothing> {
                override fun onCommitted(input: koma.core.InputId?, machineInput: MachineInput<Act>, decision: Decision<Ctx, Fetch, Nothing>) {
                    if (random.nextInt(4) == 0) throw IllegalStateException("observer fault")
                }
            }
        }
        val store = MachineStore(machine, Ctx(), handler, executionScope, TestClock(testScheduler), dispatcher, observers = listOfNotNull(recorder, faulty)) {
            exceptionHandler(ExceptionHandler { handled += it })
            if (faults != null) {
                probe(StoreProbe { if (faults.nextInt(5) == 0) throw IllegalStateException("probe fault") })
            }
            if (session != null) {
                val policy = PayloadPolicy<MachineSnapshot<Ctx>, MachineInput<Act>, Nothing>(
                    state = { if (faults != null && faults.nextInt(3) == 0) throw IllegalStateException("policy fault") else Payload.Omitted },
                )
                recordTo(session, StoreInstanceId("faulty"), policy)
            }
        }
        return store to executionScope
    }

    @Test
    fun faultsInEveryDiagnosticBoundary_changeNothingAboutTheRun() = runTest {
        val clean = mutableListOf<MachineSnapshot<Ctx>>()
        val cleanHandled = mutableListOf<Throwable>()
        val cleanFinal = mutableListOf<MachineSnapshot<Ctx>>()
        val (cleanStore, cleanScope) = newStore(faults = null, session = null, handled = cleanHandled, committed = clean)
        scenario(cleanStore, cleanFinal)
        cleanStore.close()
        cleanScope.cancel()
        assertTrue(clean.size > 40 && cleanHandled.isEmpty(), "the clean run is meaningful: ${clean.size} commits")

        for (seed in listOf(3, 11, 2024)) {
            val faults = Random(seed)
            val handled = mutableListOf<Throwable>()
            val committed = mutableListOf<MachineSnapshot<Ctx>>()
            val journalFailures = mutableListOf<Any>()
            val sink = JournalSink { if (faults.nextInt(6) == 0) throw IllegalStateException("sink fault") }
            val session = RecordingSession(backgroundScope, config = JournalConfig(onFailure = { journalFailures += it }), sinks = listOf(sink))
            val final = mutableListOf<MachineSnapshot<Ctx>>()
            val (store, scope) = newStore(faults, session, handled, committed)
            scenario(store, final)
            store.close()
            session.close()
            scope.cancel()

            val divergence = (0 until maxOf(clean.size, committed.size)).firstOrNull { clean.getOrNull(it) != committed.getOrNull(it) }
            assertTrue(divergence == null, "seed $seed: commit $divergence differs (clean ${clean.size}, faulty ${committed.size}):\n  clean  ${clean.getOrNull(divergence ?: 0)}\n  faulty ${committed.getOrNull(divergence ?: 0)}")
            assertEquals(cleanFinal, final, "seed $seed")
            val messages = handled.map { it.message }.toSet()
            assertTrue(messages.all { it == "probe fault" || it == "observer fault" }, "seed $seed: unexpected report $messages")
            assertTrue(handled.isNotEmpty(), "seed $seed: no fault was reported, so none was injected")
            assertTrue(journalFailures.isNotEmpty(), "seed $seed: no policy or sink fault reached the session")
            assertTrue(session.stats.policyFailures + session.stats.sinkFailures > 0, "seed $seed")
        }
    }
}

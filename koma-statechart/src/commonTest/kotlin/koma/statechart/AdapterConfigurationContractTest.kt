@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package koma.statechart

import koma.core.Action
import koma.core.AutoStartPolicy
import koma.core.Event
import koma.core.ExceptionHandler
import koma.core.InternalKomaApi
import koma.core.Plugin
import koma.core.PluginExecutionPolicy
import koma.core.RecoverScope
import koma.core.StateSaver
import koma.core.StoreProbe
import koma.core.StoreTrace
import koma.observability.JournalEntry
import koma.observability.RecordingSession
import koma.observability.StoreInstanceId
import koma.statechart.machine.CommandHandler
import koma.statechart.machine.DefinitionId
import koma.statechart.machine.DefinitionVersion
import koma.statechart.machine.Machine
import koma.statechart.machine.MachineStore
import koma.test.dispatchAndAwait
import koma.test.startAndAwait
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Contract tests of the constrained live adapter settings and chart recovery DSL.
 *
 * A --Go / increment--> A; A --Fail / throw--> A --recover(context only)--> A.
 * Recovery events are delivered before its single context commit, even when recovery fails.
 */
class AdapterConfigurationContractTest {
    private data object Go : Action
    private data object Fail : Action
    private data object Recovered : Event
    private val a = StateId("A")

    private val failingChart = StateChartDefinition(
        a, listOf(AtomicState(a)),
        listOf(Transition(a, a, ActionMatcher.of<Fail>("fail"), effect = "fail")),
    )

    @Test
    fun successfulRecoveryEmitsBeforeItsSingleCommitAndSave() = runTest {
        val session = RecordingSession(backgroundScope)
        val id = StoreInstanceId("recovery-success")
        val saved = mutableListOf<ChartState<Int>>()
        val committed = mutableListOf<ChartState<Int>>()
        val observed = mutableListOf<Pair<Event, Int>>()
        val chronology = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        val store = StateChartStore<Int, Action, Event>(failingChart, 0, backgroundScope.coroutineContext) {
            effect("fail") { _, _ -> error("operation failed") }
            store {
                recordTo(session, id)
                stateSaver(StateSaver(save = { saved += it; chronology += "save:${it.context}" }, restore = { null }))
                exceptionHandler(ExceptionHandler { failures += it })
                plugin(Plugin(
                    onEvent = { state, event -> observed += event to state.context; chronology += "event:${state.context}" },
                    onState = { _, state -> committed += state; chronology += "commit:${state.context}" },
                ))
                recover<IllegalStateException> {
                    assertIs<RecoverScope<*, *, *, *>>(store)
                    assertEquals("operation failed", error.message)
                    context = 7
                    event(Recovered)
                    context += 1
                }
            }
        }
        try {
            store.startAndAwait()
            val original = store.currentState
            store.dispatchAndAwait(Fail)

            assertEquals(listOf<Pair<Event, Int>>(Recovered to 0), observed)
            assertEquals(listOf("event:0", "save:8", "commit:8"), chronology)
            assertEquals(listOf(store.currentState), saved)
            assertEquals(saved, committed)
            assertEquals(8, store.currentState.context)
            assertEquals(original.configuration, store.currentState.configuration)
            assertEquals(original.timers, store.currentState.timers)
            assertTrue(failures.isEmpty())
            val entries = session.records().filter { it.store == id }.map { it.entry }
            val event = entries.single { it is JournalEntry.EventEmitted<*> }
            val commit = entries.single { it is JournalEntry.StateCommitted<*> }
            assertTrue(entries.indexOf(event) < entries.indexOf(commit))
        } finally {
            store.close()
            session.close()
        }
    }

    @Test
    fun throwingRecoveryKeepsItsEmittedEventButRollsBackContextAndPreservesOriginalCause() = runTest {
        val originalFailure = IllegalStateException("operation failed")
        val recoveryFailure = IllegalArgumentException("recovery failed")
        val saved = mutableListOf<ChartState<Int>>()
        val committed = mutableListOf<ChartState<Int>>()
        val observed = mutableListOf<Pair<Event, Int>>()
        val failures = mutableListOf<Throwable>()
        val store = StateChartStore<Int, Action, Event>(failingChart, 0, backgroundScope.coroutineContext) {
            effect("fail") { _, _ -> throw originalFailure }
            store {
                stateSaver(StateSaver(save = { saved += it }, restore = { null }))
                exceptionHandler(ExceptionHandler { failures += it })
                plugin(Plugin(
                    onEvent = { state, event -> observed += event to state.context },
                    onState = { _, state -> committed += state },
                ))
                recover<IllegalStateException> {
                    assertIs<RecoverScope<*, *, *, *>>(store)
                    assertSame(originalFailure, error)
                    context = 99
                    event(Recovered)
                    throw recoveryFailure
                }
            }
        }
        try {
            store.startAndAwait()
            val original = store.currentState
            store.dispatchAndAwait(Fail)

            assertEquals(original, store.currentState)
            assertEquals(listOf<Pair<Event, Int>>(Recovered to 0), observed, "An already emitted event cannot be rolled back")
            assertTrue(saved.isEmpty(), "Failed recovery persists nothing")
            assertTrue(committed.isEmpty(), "Failed recovery commits nothing")
            assertSame(recoveryFailure, failures.single())
            assertEquals(listOf(originalFailure), recoveryFailure.suppressedExceptions)
        } finally {
            store.close()
        }
    }

    @OptIn(InternalKomaApi::class)
    @Test
    fun chartSettingsUseTheLastValueAndAppendPluginsAndProbesAcrossBlocks() = runTest {
        val definition = StateChartDefinition(a, listOf(AtomicState(a)), listOf(
            Transition(a, a, ActionMatcher.of<Go>("go"), effect = "increment"),
            Transition(a, a, ActionMatcher.of<Fail>("fail"), effect = "fail"),
        ))
        val dispatcher = StandardTestDispatcher(testScheduler, "configured")
        val session = RecordingSession(backgroundScope)
        val id = StoreInstanceId("configured-chart")
        val firstSaved = mutableListOf<ChartState<Int>>()
        val lastSaved = mutableListOf<ChartState<Int>>()
        val firstFailures = mutableListOf<Throwable>()
        val lastFailures = mutableListOf<Throwable>()
        val plugins = mutableListOf<String>()
        val probes = mutableListOf<String>()
        var firstRestores = 0
        var lastRestores = 0
        var enters = 0
        val store = StateChartStore<Int, Action, Event>(definition, 0, backgroundScope.coroutineContext) {
            effect("increment") { context, _ -> context + 1 }
            effect("fail") { _, _ -> error("operation failed") }
            onEnter(a) {
                enters++
                assertSame(dispatcher, currentCoroutineContext()[ContinuationInterceptor])
            }
            store {
                coroutineContext(Dispatchers.Default)
                autoStartPolicy(AutoStartPolicy.OnDispatchOrStateCollection)
                pluginExecutionPolicy(PluginExecutionPolicy.Concurrent)
                stateSaver(StateSaver(save = { firstSaved += it }, restore = { firstRestores++; null }))
                exceptionHandler(ExceptionHandler { firstFailures += it })
                plugin(Plugin(onStart = { plugins += "first:start"; delay(1); plugins += "first:end" }))
                probe(StoreProbe { if (it is StoreTrace.StateCommitted) probes += "first" })
            }
            store {
                coroutineContext(dispatcher)
                autoStartPolicy(AutoStartPolicy.OnDispatch)
                pluginExecutionPolicy(PluginExecutionPolicy.InRegistrationOrder)
                stateSaver(StateSaver(save = { lastSaved += it }, restore = { lastRestores++; null }))
                exceptionHandler(ExceptionHandler { lastFailures += it })
                plugin(Plugin(onStart = { plugins += "second" }))
                probe(StoreProbe { if (it is StoreTrace.StateCommitted) probes += "second" })
                recordTo(session, id)
            }
        }
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.state.collect {} }
        try {
            runCurrent()
            assertEquals(0, enters, "The final OnDispatch policy prevents startup from collection")
            assertTrue(plugins.isEmpty())
            assertEquals(0, firstRestores)
            assertEquals(1, lastRestores)

            store.startAndAwait()
            assertEquals(1, enters)
            assertEquals(listOf("first:start", "first:end", "second"), plugins)
            store.dispatchAndAwait(Go)
            store.dispatchAndAwait(Fail)

            assertEquals(1, store.currentState.context)
            assertTrue(firstSaved.isEmpty())
            assertEquals(listOf(store.currentState), lastSaved)
            assertEquals(listOf("first", "second"), probes)
            assertTrue(firstFailures.isEmpty())
            assertEquals("operation failed", lastFailures.single().message)
            val entries = session.records().filter { it.store == id }.map { it.entry }
            assertEquals(1, entries.count { it is JournalEntry.StoreRegistered })
            assertEquals(1, entries.count { it is JournalEntry.StateCommitted<*> })
        } finally {
            collector.cancel()
            store.close()
            session.close()
        }
    }

    @Test
    fun machineSettingsJournalTheStartupAndSubsequentCommittedDecision() = runTest {
        val definition = StateChartDefinition(a, listOf(AtomicState(a)), listOf(
            Transition(a, a, ActionMatcher.of<Go>("go"), effect = "increment"),
        ))
        val machine = Machine<Int, Action, Nothing, Event>(DefinitionId("configured"), DefinitionVersion("1"), definition) {
            effect("increment") { context, _ -> context + 1 }
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        val session = RecordingSession(backgroundScope)
        val id = StoreInstanceId("configured-machine")
        val saved = mutableListOf<Int>()
        val store = MachineStore(machine, 0, CommandHandler<Nothing, Action> { _, _ -> }, scope, coroutineContext = dispatcher) {
            stateSaver(StateSaver(save = { saved += it.context }, restore = { null }))
            autoStartPolicy(AutoStartPolicy.OnDispatch)
            recordTo(session, id)
        }
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.state.collect {} }
        try {
            runCurrent()
            assertEquals(0, store.currentState.revision)
            assertTrue(saved.isEmpty())

            store.startAndAwait()
            store.dispatchAndAwait(Go)
            assertEquals(1, store.currentState.context)
            assertEquals(2, store.currentState.revision)
            assertEquals(listOf(0, 1), saved)
            val entries = session.records().filter { it.store == id }.map { it.entry }
            assertEquals(1, entries.count { it is JournalEntry.StoreRegistered })
            assertEquals(2, entries.count { it is JournalEntry.StateCommitted<*> })
        } finally {
            collector.cancel()
            store.close()
            runCurrent()
            scope.cancel()
            session.close()
        }
    }
}

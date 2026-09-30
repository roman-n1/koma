@file:OptIn(InternalKomaApi::class, ExperimentalKomaApi::class, ExperimentalCoroutinesApi::class)

package koma.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The boundaries a [StoreProbe] must see and a [Plugin] cannot: the startup as an input, equal
 * states, dropped inputs, failures with and without recovery, several commits for one input,
 * transactions and events correlated to their input.
 *
 * ```
 * Loading --enter--> Ready(count)
 * Ready --Save--> Saving --enter--> Done         // one input, two commits
 * Ready --Throw--> (recover) --> Failed          // one input, one commit, Recovered
 * ```
 */
class StoreProbeTest {

    sealed interface AppState : State {
        data object Loading : AppState
        data class Ready(val count: Int = 0) : AppState
        data class Saving(val count: Int) : AppState
        data class Done(val count: Int) : AppState
        data class Failed(val message: String) : AppState
    }

    sealed interface AppAction : Action {
        data object Increment : AppAction
        data object Noop : AppAction
        data object Emit : AppAction
        data object Save : AppAction
        data object Throw : AppAction
        data object ThrowUnrecovered : AppAction
        data object LaunchIncrement : AppAction
        data object LaunchIncrementAfterGate : AppAction
        data object LaunchEmitAfterGate : AppAction
        data object LaunchThrow : AppAction
        data object AwaitGate : AppAction
        data object AwaitGateThenSave : AppAction
        data object Clear : AppAction
        data object Unknown : AppAction
    }

    sealed interface AppEvent : Event {
        data class Ping(val from: String) : AppEvent
    }

    private class RecordingProbe<S : State, A : Action, E : Event> : StoreProbe<S, A, E> {
        val traces = mutableListOf<StoreTrace<S, A, E>>()

        override fun record(trace: StoreTrace<S, A, E>) {
            traces += trace
        }

        fun clear() = traces.clear()
    }

    private class Gates {
        val handler = CompletableDeferred<Unit>()
        val launch = CompletableDeferred<Unit>()
    }

    private fun createStore(
        probe: StoreProbe<AppState, AppAction, AppEvent>,
        context: CoroutineContext,
        gates: Gates = Gates(),
        exceptionHandler: ExceptionHandler = ExceptionHandler.Rethrow,
        stateSaver: StateSaver<AppState> = StateSaver.Noop(),
        plugins: List<Plugin<AppState, AppAction, AppEvent>> = emptyList(),
        extraProbes: List<StoreProbe<AppState, AppAction, AppEvent>> = emptyList(),
    ): Store<AppState, AppAction, AppEvent> = Store(AppState.Loading) {
        coroutineContext(context)
        exceptionHandler(exceptionHandler)
        stateSaver(stateSaver)
        plugins.forEach { plugin(it) }
        probe(probe, *extraProbes.toTypedArray())

        state<AppState.Loading> {
            enter { nextState { AppState.Ready() } }
        }
        state<AppState.Ready> {
            action<AppAction.Increment> { nextState { state.copy(count = state.count + 1) } }
            action<AppAction.Noop> { }
            action<AppAction.Emit> { event(AppEvent.Ping("handler")) }
            action<AppAction.Save> { nextState { AppState.Saving(state.count) } }
            action<AppAction.Throw> { throw IllegalStateException("boom") }
            action<AppAction.ThrowUnrecovered> { throw ArithmeticException("unrecovered") }
            action<AppAction.LaunchIncrement> {
                launch { transaction { nextState { state.copy(count = state.count + 1) } } }
            }
            action<AppAction.LaunchIncrementAfterGate> {
                launch {
                    gates.launch.await()
                    transaction { nextState { state.copy(count = state.count + 1) } }
                }
            }
            action<AppAction.LaunchEmitAfterGate> {
                launch {
                    gates.launch.await()
                    event(AppEvent.Ping("launch"))
                }
            }
            action<AppAction.LaunchThrow> {
                launch { throw IllegalStateException("late") }
            }
            action<AppAction.AwaitGate> { gates.handler.await() }
            action<AppAction.AwaitGateThenSave> {
                gates.handler.await()
                nextState { AppState.Saving(state.count) }
            }
            action<AppAction.Clear> { clearPendingActions() }
            recover<IllegalStateException> { nextState { AppState.Failed(error.message ?: "?") } }
        }
        state<AppState.Saving> {
            enter { nextState { AppState.Done(state.count) } }
        }
    }

    private val StoreTrace<*, *, *>.input: InputId?
        get() = when (this) {
            is StoreTrace.InputAccepted -> input
            is StoreTrace.InputDiscarded -> input
            is StoreTrace.ProcessingStarted -> input
            is StoreTrace.StateCommitted -> input
            is StoreTrace.EventEmitted -> input
            is StoreTrace.FailureReported -> input
            is StoreTrace.ProcessingFinished -> input
            StoreTrace.StoreClosed -> null
        }

    private fun <S : State, A : Action, E : Event> List<StoreTrace<S, A, E>>.of(input: InputId) = filter { it.input == input }

    private inline fun <reified T : StoreTrace<*, *, *>> List<StoreTrace<*, *, *>>.singleOf(): T = filterIsInstance<T>().single()

    private fun assertTraces(expected: List<StoreTrace<AppState, AppAction, AppEvent>>, actual: List<StoreTrace<AppState, AppAction, AppEvent>>) = assertEquals(expected, actual)

    private fun <S : State, A : Action, E : Event> List<StoreTrace<S, A, E>>.finished(input: InputId): StoreTrace.ProcessingFinished =
        filterIsInstance<StoreTrace.ProcessingFinished>().single { it.input == input }

    // --- startup, equal state, unmatched action, several commits ---

    @Test
    fun startup_isAnInputOfItsOwn_withOneProcessingAndOneCommit() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val probe = RecordingProbe<AppState, AppAction, AppEvent>()
        val store = createStore(probe, dispatcher)

        store.startAndAwaitForTest()

        val startup = InputId(1)
        assertTraces(
            listOf(
                StoreTrace.InputAccepted(startup, InputKind.Startup),
                StoreTrace.ProcessingStarted(startup, ordinal = 1),
                StoreTrace.StateCommitted(startup, revision = 1, previous = AppState.Loading, state = AppState.Ready()),
                StoreTrace.ProcessingFinished(startup, ordinal = 1, outcome = ProcessingOutcome.Handled(commits = 1)),
            ),
            probe.traces,
        )
        store.close()
    }

    @Test
    fun handlerThatLeavesTheStateEqual_isUnchanged_whilePluginsSeeNoState() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val probe = RecordingProbe<AppState, AppAction, AppEvent>()
        var onStateRounds = 0
        val store = createStore(probe, dispatcher, plugins = listOf(Plugin(onState = { _, _ -> onStateRounds++ })))
        store.startAndAwaitForTest()
        probe.clear()

        store.dispatchAndAwaitForTest(AppAction.Noop)

        val input = InputId(2)
        assertTraces(
            listOf(
                StoreTrace.InputAccepted(input, InputKind.Dispatch(AppAction.Noop)),
                StoreTrace.ProcessingStarted(input, ordinal = 2),
                StoreTrace.ProcessingFinished(input, ordinal = 2, outcome = ProcessingOutcome.Unchanged),
            ),
            probe.traces,
        )
        assertEquals(1, onStateRounds, "only the startup transition reached onState")
        store.close()
    }

    @Test
    fun actionWithoutMatchingHandler_isIgnored() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val probe = RecordingProbe<AppState, AppAction, AppEvent>()
        val store = createStore(probe, dispatcher)
        store.startAndAwaitForTest()
        probe.clear()

        store.dispatchAndAwaitForTest(AppAction.Unknown)

        assertEquals(ProcessingOutcome.Ignored, probe.traces.finished(InputId(2)).outcome)
        assertEquals(AppState.Ready(), store.currentState)
        store.close()
    }

    @Test
    fun oneDispatch_thatChainsThroughEnter_commitsTwiceUnderTheSameInput() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val probe = RecordingProbe<AppState, AppAction, AppEvent>()
        val store = createStore(probe, dispatcher)
        store.startAndAwaitForTest()
        store.dispatchAndAwaitForTest(AppAction.Increment)
        probe.clear()

        store.dispatchAndAwaitForTest(AppAction.Save)

        val input = InputId(3)
        assertTraces(
            listOf(
                StoreTrace.InputAccepted(input, InputKind.Dispatch(AppAction.Save)),
                StoreTrace.ProcessingStarted(input, ordinal = 3),
                StoreTrace.StateCommitted(input, revision = 3, previous = AppState.Ready(1), state = AppState.Saving(1)),
                StoreTrace.StateCommitted(input, revision = 4, previous = AppState.Saving(1), state = AppState.Done(1)),
                StoreTrace.ProcessingFinished(input, ordinal = 3, outcome = ProcessingOutcome.Handled(commits = 2)),
            ),
            probe.traces,
        )
        store.close()
    }

    // --- failures ---

    @Test
    fun failureHandledByRecover_isRecovered_withItsCommit() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val probe = RecordingProbe<AppState, AppAction, AppEvent>()
        val store = createStore(probe, dispatcher)
        store.startAndAwaitForTest()
        probe.clear()

        store.dispatchAndAwaitForTest(AppAction.Throw)

        val input = InputId(2)
        val committed = probe.traces.singleOf<StoreTrace.StateCommitted<AppState>>()
        assertEquals(StoreTrace.StateCommitted(input, revision = 2, previous = AppState.Ready(), state = AppState.Failed("boom")), committed)
        val outcome = assertIs<ProcessingOutcome.Recovered>(probe.traces.finished(input).outcome)
        assertIs<IllegalStateException>(outcome.error)
        assertEquals("boom", outcome.error.message)
        assertEquals(1, outcome.commits)
        assertTrue(probe.traces.none { it is StoreTrace.FailureReported }, "a recovered failure is not reported")
        store.close()
    }

    @Test
    fun failureWithoutRecover_isFailed_thenReportedWithItsInput() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val probe = RecordingProbe<AppState, AppAction, AppEvent>()
        val handled = mutableListOf<Throwable>()
        val store = createStore(probe, dispatcher, exceptionHandler = ExceptionHandler { handled += it })
        store.startAndAwaitForTest()
        probe.clear()

        store.dispatchAndAwaitForTest(AppAction.ThrowUnrecovered)

        val input = InputId(2)
        val error = assertIs<ArithmeticException>(handled.single())
        assertTraces(
            listOf(
                StoreTrace.InputAccepted(input, InputKind.Dispatch(AppAction.ThrowUnrecovered)),
                StoreTrace.ProcessingStarted(input, ordinal = 2),
                StoreTrace.ProcessingFinished(input, ordinal = 2, outcome = ProcessingOutcome.Failed(error)),
                StoreTrace.FailureReported(input, error),
            ),
            probe.traces,
        )
        assertEquals(AppState.Ready(), store.currentState)
        store.close()
    }

    @Test
    fun saverFailure_isReportedUnderTheInput_andTheOutcomeStaysHandled() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val probe = RecordingProbe<AppState, AppAction, AppEvent>()
        val saverError = IllegalArgumentException("disk full")
        val handled = mutableListOf<Throwable>()
        val store = createStore(
            probe,
            dispatcher,
            exceptionHandler = ExceptionHandler { handled += it },
            stateSaver = StateSaver(save = { if (it is AppState.Ready && it.count > 0) throw saverError }, restore = { null }),
        )
        store.startAndAwaitForTest()
        probe.clear()

        store.dispatchAndAwaitForTest(AppAction.Increment)

        val input = InputId(2)
        assertTraces(
            listOf(
                StoreTrace.InputAccepted(input, InputKind.Dispatch(AppAction.Increment)),
                StoreTrace.ProcessingStarted(input, ordinal = 2),
                StoreTrace.StateCommitted(input, revision = 2, previous = AppState.Ready(0), state = AppState.Ready(1)),
                StoreTrace.FailureReported(input, saverError),
                StoreTrace.ProcessingFinished(input, ordinal = 2, outcome = ProcessingOutcome.Handled(commits = 1)),
            ),
            probe.traces,
        )
        assertEquals(listOf<Throwable>(saverError), handled)
        store.close()
    }

    @Test
    fun launchFailure_isRecoveredThroughARecoveryInput_thatNamesItsOrigin() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val probe = RecordingProbe<AppState, AppAction, AppEvent>()
        val store = createStore(probe, dispatcher)
        store.startAndAwaitForTest()
        probe.clear()

        store.dispatchAndAwaitForTest(AppAction.LaunchThrow)
        advanceUntilIdle()

        val dispatch = InputId(2)
        val recovery = InputId(3)
        assertEquals(ProcessingOutcome.Unchanged, probe.traces.finished(dispatch).outcome)
        val accepted = probe.traces.of(recovery).first()
        val kind = assertIs<InputKind.Recovery>(assertIs<StoreTrace.InputAccepted<AppAction>>(accepted).kind)
        assertEquals(dispatch, kind.origin)
        assertEquals("late", kind.error.message)
        assertTraces(
            listOf(
                StoreTrace.ProcessingStarted(recovery, ordinal = 3),
                StoreTrace.StateCommitted(recovery, revision = 2, previous = AppState.Ready(), state = AppState.Failed("late")),
                StoreTrace.ProcessingFinished(recovery, ordinal = 3, outcome = ProcessingOutcome.Recovered(kind.error, commits = 1)),
            ),
            probe.traces.of(recovery).drop(1),
        )
        store.close()
    }

    @Test
    fun pluginStartFailure_failsTheStartup_dropsTheDispatch_andRetriesTheSameStartupInput() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val probe = RecordingProbe<AppState, AppAction, AppEvent>()
        val startError = IllegalStateException("not yet")
        var attempts = 0
        val handled = mutableListOf<Throwable>()
        val plugin = Plugin<AppState, AppAction, AppEvent>(onStart = { if (attempts++ == 0) throw startError })
        val store = createStore(probe, dispatcher, exceptionHandler = ExceptionHandler { handled += it }, plugins = listOf(plugin))

        store.dispatchAndAwaitForTest(AppAction.Increment)

        val startup = InputId(1)
        val first = InputId(2)
        assertTraces(
            listOf(
                StoreTrace.InputAccepted(startup, InputKind.Startup),
                StoreTrace.InputAccepted(first, InputKind.Dispatch(AppAction.Increment)),
                StoreTrace.ProcessingStarted(startup, ordinal = 1),
                StoreTrace.ProcessingFinished(startup, ordinal = 1, outcome = ProcessingOutcome.Failed(startError)),
                StoreTrace.InputDiscarded(first, DiscardReason.StartupFailed(startError)),
                StoreTrace.FailureReported(first, startError),
            ),
            probe.traces,
        )
        assertEquals(AppState.Loading, store.currentState)
        probe.clear()

        store.dispatchAndAwaitForTest(AppAction.Increment)

        val second = InputId(3)
        assertTraces(
            listOf(
                StoreTrace.InputAccepted(second, InputKind.Dispatch(AppAction.Increment)),
                StoreTrace.ProcessingStarted(startup, ordinal = 2),
                StoreTrace.StateCommitted(startup, revision = 1, previous = AppState.Loading, state = AppState.Ready(0)),
                StoreTrace.ProcessingFinished(startup, ordinal = 2, outcome = ProcessingOutcome.Handled(commits = 1)),
                StoreTrace.ProcessingStarted(second, ordinal = 3),
                StoreTrace.StateCommitted(second, revision = 2, previous = AppState.Ready(0), state = AppState.Ready(1)),
                StoreTrace.ProcessingFinished(second, ordinal = 3, outcome = ProcessingOutcome.Handled(commits = 1)),
            ),
            probe.traces,
        )
        assertEquals(listOf<Throwable>(startError), handled)
        store.close()
    }

    @Test
    fun failingInitialEnter_countsAsStarted_butTheStartupOutcomeIsFailed() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val probe = RecordingProbe<AppState, AppAction, AppEvent>()
        val enterError = ArithmeticException("enter")
        val handled = mutableListOf<Throwable>()
        val store = Store<AppState, AppAction, AppEvent>(AppState.Loading) {
            coroutineContext(dispatcher)
            exceptionHandler(ExceptionHandler { handled += it })
            probe(probe)
            state<AppState.Loading> {
                enter { throw enterError }
                action<AppAction.Increment> { nextState { AppState.Ready(1) } }
            }
        }

        store.dispatchAndAwaitForTest(AppAction.Increment)

        val startup = InputId(1)
        val input = InputId(2)
        assertTraces(
            listOf(
                StoreTrace.InputAccepted(startup, InputKind.Startup),
                StoreTrace.InputAccepted(input, InputKind.Dispatch(AppAction.Increment)),
                StoreTrace.ProcessingStarted(startup, ordinal = 1),
                StoreTrace.FailureReported(startup, enterError),
                StoreTrace.ProcessingFinished(startup, ordinal = 1, outcome = ProcessingOutcome.Failed(enterError)),
                StoreTrace.ProcessingStarted(input, ordinal = 2),
                StoreTrace.StateCommitted(input, revision = 1, previous = AppState.Loading, state = AppState.Ready(1)),
                StoreTrace.ProcessingFinished(input, ordinal = 2, outcome = ProcessingOutcome.Handled(commits = 1)),
            ),
            probe.traces,
        )
        store.close()
    }

    // --- discarded inputs ---

    @Test
    fun dispatchIf_withAFalsePredicate_isDiscardedAsStale_andConsumesNoOrdinal() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val probe = RecordingProbe<AppState, AppAction, AppEvent>()
        val handled = mutableListOf<Throwable>()
        val store = createStore(probe, dispatcher, exceptionHandler = ExceptionHandler { handled += it })
        store.startAndAwaitForTest()
        probe.clear()
        val internal = store as StoreInternalApi<AppState, AppAction, AppEvent>
        val predicateError = IllegalArgumentException("bad predicate")

        internal.dispatchIf(AppAction.Increment) { false }
        internal.dispatchIf(AppAction.Increment) { throw predicateError }
        store.dispatchAndAwaitForTest(AppAction.Increment)

        assertTraces(
            listOf(
                StoreTrace.InputAccepted(InputId(2), InputKind.Dispatch(AppAction.Increment)),
                StoreTrace.InputDiscarded(InputId(2), DiscardReason.Stale),
                StoreTrace.InputAccepted(InputId(3), InputKind.Dispatch(AppAction.Increment)),
                StoreTrace.InputDiscarded(InputId(3), DiscardReason.Rejected(predicateError)),
                StoreTrace.FailureReported(InputId(3), predicateError),
                StoreTrace.InputAccepted(InputId(4), InputKind.Dispatch(AppAction.Increment)),
                StoreTrace.ProcessingStarted(InputId(4), ordinal = 2),
                StoreTrace.StateCommitted(InputId(4), revision = 2, previous = AppState.Ready(0), state = AppState.Ready(1)),
                StoreTrace.ProcessingFinished(InputId(4), ordinal = 2, outcome = ProcessingOutcome.Handled(commits = 1)),
            ),
            probe.traces,
        )
        assertEquals(listOf<Throwable>(predicateError), handled)
        store.close()
    }

    @Test
    fun clearOnStateExit_discardsTheQueuedDispatches_withThatReason() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val probe = RecordingProbe<AppState, AppAction, AppEvent>()
        val gates = Gates()
        val store = createStore(probe, dispatcher, gates = gates)
        store.startAndAwaitForTest()
        probe.clear()

        store.dispatch(AppAction.AwaitGateThenSave)
        runCurrent()
        store.dispatch(AppAction.Increment)
        store.dispatch(AppAction.Increment)
        runCurrent()
        gates.handler.complete(Unit)
        advanceUntilIdle()

        assertEquals(AppState.Done(0), store.currentState)
        assertTraces(
            listOf(
                StoreTrace.InputDiscarded(InputId(3), DiscardReason.ClearedOnStateExit),
                StoreTrace.InputDiscarded(InputId(4), DiscardReason.ClearedOnStateExit),
            ),
            probe.traces.filterIsInstance<StoreTrace.InputDiscarded>(),
        )
        assertEquals(listOf(InputId(2)), probe.traces.filterIsInstance<StoreTrace.ProcessingStarted>().map { it.input })
        store.close()
    }

    @Test
    fun clearPendingActions_discardsTheQueuedDispatches_asExplicit() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val probe = RecordingProbe<AppState, AppAction, AppEvent>()
        val gates = Gates()
        val store = createStore(probe, dispatcher, gates = gates)
        store.startAndAwaitForTest()
        probe.clear()

        store.dispatch(AppAction.AwaitGate)
        runCurrent()
        store.dispatch(AppAction.Clear)
        store.dispatch(AppAction.Increment)
        runCurrent()
        gates.handler.complete(Unit)
        advanceUntilIdle()

        assertEquals(AppState.Ready(0), store.currentState)
        assertTraces(
            listOf(StoreTrace.InputDiscarded(InputId(4), DiscardReason.ClearedExplicitly)),
            probe.traces.filterIsInstance<StoreTrace.InputDiscarded>(),
        )
        assertEquals(ProcessingOutcome.Unchanged, probe.traces.finished(InputId(2)).outcome)
        assertEquals(ProcessingOutcome.Unchanged, probe.traces.finished(InputId(3)).outcome)
        store.close()
    }

    @Test
    fun close_cancelsTheRunningProcessing_discardsTheQueue_andEndsWithStoreClosed() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val probe = RecordingProbe<AppState, AppAction, AppEvent>()
        val gates = Gates()
        val store = createStore(probe, dispatcher, gates = gates)
        store.startAndAwaitForTest()
        probe.clear()

        store.dispatch(AppAction.AwaitGate)
        runCurrent()
        store.dispatch(AppAction.Increment)
        runCurrent()
        store.close()
        advanceUntilIdle()

        assertContains(probe.traces, StoreTrace.ProcessingFinished(InputId(2), ordinal = 2, outcome = ProcessingOutcome.Cancelled))
        assertContains(probe.traces, StoreTrace.InputDiscarded(InputId(3), DiscardReason.StoreClosed))
        assertEquals(StoreTrace.StoreClosed, probe.traces.last())
        assertFalse(probe.traces.any { it is StoreTrace.StateCommitted<*> }, "nothing commits after close")
    }

    @Test
    fun dispatchOnAClosedStore_isDiscardedAsStoreClosed() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val probe = RecordingProbe<AppState, AppAction, AppEvent>()
        val store = createStore(probe, dispatcher)
        store.close()

        store.dispatch(AppAction.Increment)

        assertTraces(
            listOf(
                StoreTrace.StoreClosed,
                StoreTrace.InputAccepted(InputId(1), InputKind.Startup),
                StoreTrace.InputAccepted(InputId(2), InputKind.Dispatch(AppAction.Increment)),
                StoreTrace.InputDiscarded(InputId(2), DiscardReason.StoreClosed),
            ),
            probe.traces,
        )
    }

    // --- transactions and events ---

    @Test
    fun transaction_isAnInput_whoseOriginIsTheDispatchThatLaunchedIt() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val probe = RecordingProbe<AppState, AppAction, AppEvent>()
        val store = createStore(probe, dispatcher)
        store.startAndAwaitForTest()
        probe.clear()

        store.dispatchAndAwaitForTest(AppAction.LaunchIncrement)
        advanceUntilIdle()

        val dispatch = InputId(2)
        val transaction = InputId(3)
        assertEquals(ProcessingOutcome.Unchanged, probe.traces.finished(dispatch).outcome)
        assertTraces(
            listOf(
                StoreTrace.InputAccepted(transaction, InputKind.Transaction(origin = dispatch)),
                StoreTrace.ProcessingStarted(transaction, ordinal = 3),
                StoreTrace.StateCommitted(transaction, revision = 2, previous = AppState.Ready(0), state = AppState.Ready(1)),
                StoreTrace.ProcessingFinished(transaction, ordinal = 3, outcome = ProcessingOutcome.Handled(commits = 1)),
            ),
            probe.traces.of(transaction),
        )
        store.close()
    }

    @Test
    fun transaction_whoseStateExitedBeforeItGotTheLock_isDiscardedAsLaunchInactive() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val probe = RecordingProbe<AppState, AppAction, AppEvent>()
        val gates = Gates()
        val store = createStore(probe, dispatcher, gates = gates)
        store.startAndAwaitForTest()
        probe.clear()

        store.dispatch(AppAction.LaunchIncrementAfterGate)
        runCurrent()
        store.dispatch(AppAction.AwaitGateThenSave)
        runCurrent()
        // The launch requests its transaction while the second dispatch holds the lock...
        gates.launch.complete(Unit)
        runCurrent()
        // ...and that dispatch exits Ready before the transaction gets it.
        gates.handler.complete(Unit)
        advanceUntilIdle()

        val transaction = InputId(4)
        assertEquals(AppState.Done(0), store.currentState)
        assertTraces(
            listOf(
                StoreTrace.InputAccepted(transaction, InputKind.Transaction(origin = InputId(2))),
                StoreTrace.InputDiscarded(transaction, DiscardReason.LaunchInactive),
            ),
            probe.traces.of(transaction),
        )
        store.close()
    }

    @Test
    fun events_carryTheInputTheyBelongTo_evenWhenEmittedLater() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val probe = RecordingProbe<AppState, AppAction, AppEvent>()
        val gates = Gates()
        val store = createStore(probe, dispatcher, gates = gates)
        store.startAndAwaitForTest()
        probe.clear()

        store.dispatch(AppAction.Emit)
        store.dispatch(AppAction.LaunchEmitAfterGate)
        store.dispatch(AppAction.Noop)
        advanceUntilIdle()
        gates.launch.complete(Unit)
        advanceUntilIdle()

        assertTraces(
            listOf(
                StoreTrace.EventEmitted(InputId(2), AppEvent.Ping("handler")),
                StoreTrace.EventEmitted(InputId(3), AppEvent.Ping("launch")),
            ),
            probe.traces.filterIsInstance<StoreTrace.EventEmitted<AppEvent>>(),
        )
        // The launched event came after the Noop dispatch was processed, yet names its own input.
        val order = probe.traces.map { it::class.simpleName to it.input }
        assertTrue(order.indexOf("ProcessingFinished" to InputId(4)) < order.indexOf("EventEmitted" to InputId(3)))
        store.close()
    }

    // --- probes themselves ---

    @Test
    fun failingProbe_isReportedToTheExceptionHandler_andNeitherProcessingNorOtherProbesStop() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val probe = RecordingProbe<AppState, AppAction, AppEvent>()
        val probeError = IllegalStateException("probe")
        val failing = StoreProbe<AppState, AppAction, AppEvent> { if (it is StoreTrace.StateCommitted<*>) throw probeError }
        val handled = mutableListOf<Throwable>()
        val store = Store<AppState, AppAction, AppEvent>(AppState.Loading) {
            coroutineContext(dispatcher)
            exceptionHandler(ExceptionHandler { handled += it })
            probe(failing, probe)
            state<AppState.Loading> { enter { nextState { AppState.Ready() } } }
            state<AppState.Ready> { action<AppAction.Increment> { nextState { state.copy(count = state.count + 1) } } }
        }

        store.startAndAwaitForTest()
        store.dispatchAndAwaitForTest(AppAction.Increment)

        assertEquals(AppState.Ready(1), store.currentState)
        assertEquals(listOf<Throwable>(probeError, probeError), handled)
        assertEquals(2, probe.traces.count { it is StoreTrace.StateCommitted<*> }, "the probe after the failing one still sees every commit")
        assertTrue(probe.traces.none { it is StoreTrace.FailureReported }, "a probe failure is reported, not traced")
        store.close()
    }

    @Test
    fun probeRegisteredByPatch_seesTheStartup() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val probe = RecordingProbe<AppState, AppAction, AppEvent>()
        val store = Store<AppState, AppAction, AppEvent>(AppState.Loading) {
            coroutineContext(dispatcher)
            state<AppState.Loading> { enter { nextState { AppState.Ready() } } }
        }
        store.patchForTest { probe(probe) }

        store.startAndAwaitForTest()

        assertEquals(InputKind.Startup, assertIs<StoreTrace.InputAccepted<AppAction>>(probe.traces.first()).kind)
        assertEquals(ProcessingOutcome.Handled(1), probe.traces.finished(InputId(1)).outcome)
        store.close()
    }

    // --- many threads with clears and a close ---

    sealed interface StormAction : Action {
        data class Add(val n: Int) : StormAction
        data object Clear : StormAction
        data object Exit : StormAction
    }

    sealed interface StormState : State {
        data class Open(val total: Int = 0) : StormState
        data object Closed : StormState
    }

    /**
     * Dispatches from eight threads while handlers clear the pending queue at random and a
     * variant change clears it by policy, then the Store closes mid-storm. Whatever happened to
     * each accepted input, the traces account for it: processed to the end, or discarded with
     * a reason, never both, never neither.
     */
    @Test
    fun manyThreads_withClearsAndAClose_everyAcceptedInputEndsExactlyOnce() = runTest {
        repeat(6) { iteration ->
            val recorded = Channel<StoreTrace<StormState, StormAction, Nothing>>(Channel.UNLIMITED)
            val store: Store<StormState, StormAction, Nothing> = Store(StormState.Open()) {
                coroutineContext(Dispatchers.Default)
                probe(StoreProbe { recorded.trySend(it) })
                state<StormState.Open> {
                    action<StormAction.Add> { nextState { state.copy(total = state.total + action.n) } }
                    action<StormAction.Clear> { clearPendingActions() }
                    action<StormAction.Exit> { nextState { StormState.Closed } }
                }
                state<StormState.Closed> {
                    action<StormAction.Add> { }
                }
            }
            withContext(Dispatchers.Default) {
                coroutineScope {
                    repeat(8) { sender ->
                        launch {
                            repeat(150) { i ->
                                store.dispatch(
                                    when {
                                        i % 40 == 39 -> StormAction.Clear
                                        i == 90 && sender == 3 -> StormAction.Exit
                                        else -> StormAction.Add(1)
                                    },
                                )
                            }
                        }
                    }
                    launch {
                        delay(Random.nextLong(1, 8))
                        store.close()
                    }
                }
            }
            // Every dispatch() call has returned, so every InputAccepted is already in the channel.
            // The ends come later: a queued dispatch ends before StoreClosed, but a dispatch that
            // arrived after the close is discarded when its cancelled coroutine gets a thread,
            // which on a loaded machine is after StoreClosed. Wait until every accepted dispatch
            // has its end, bounded so that a real gap fails the assertions below by name.
            val traces = mutableListOf<StoreTrace<StormState, StormAction, Nothing>>()
            withContext(Dispatchers.Default) {
                withTimeoutOrNull(10_000) {
                    while (true) {
                        traces += recorded.receive()
                        generateSequence { recorded.tryReceive().getOrNull() }.forEach { traces += it }
                        if (traces.none { it === StoreTrace.StoreClosed }) continue
                        val waiting = traces.filterIsInstance<StoreTrace.InputAccepted<StormAction>>().map { it.input }.toSet() -
                            traces.filterIsInstance<StoreTrace.ProcessingFinished>().map { it.input }.toSet() -
                            traces.filterIsInstance<StoreTrace.InputDiscarded>().map { it.input }.toSet()
                        if (waiting.isEmpty()) break
                    }
                }
            }
            assertTrue(traces.any { it === StoreTrace.StoreClosed }, "iteration $iteration: StoreClosed never came")

            val accepted = traces.filterIsInstance<StoreTrace.InputAccepted<StormAction>>().map { it.input }
            val finished = traces.filterIsInstance<StoreTrace.ProcessingFinished>().map { it.input }
            val discarded = traces.filterIsInstance<StoreTrace.InputDiscarded>().map { it.input }
            val ended = finished + discarded
            assertEquals(ended.size, ended.toSet().size, "iteration $iteration: an input ended twice")
            val dispatches = accepted.toSet() - InputId(1)
            val unaccounted = dispatches - ended.toSet()
            assertTrue(unaccounted.isEmpty(), "iteration $iteration: accepted inputs without an end: ${unaccounted.take(5)} of ${unaccounted.size}")
            assertTrue(discarded.isNotEmpty(), "iteration $iteration: nothing was discarded, so the clears and the close did nothing")
            val reasons = traces.filterIsInstance<StoreTrace.InputDiscarded>().map { it.reason }.toSet()
            assertTrue(reasons.any { it == DiscardReason.StoreClosed } || reasons.any { it == DiscardReason.ClearedExplicitly } || reasons.any { it == DiscardReason.ClearedOnStateExit }, "iteration $iteration: $reasons")
            val ordinals = traces.filterIsInstance<StoreTrace.ProcessingStarted>().map { it.ordinal }.sorted()
            assertEquals((1L..ordinals.size).toList(), ordinals, "iteration $iteration: ordinals are dense")
            val closedAt = traces.indexOfFirst { it === StoreTrace.StoreClosed }
            assertTrue(traces.drop(closedAt + 1).none { it is StoreTrace.ProcessingStarted || it is StoreTrace.StateCommitted<*> }, "iteration $iteration: processing after StoreClosed")
            // Dispatches that arrive after the close are accepted and discarded at once, so they may follow StoreClosed.
            assertTrue(traces.drop(closedAt + 1).all { it is StoreTrace.InputAccepted<*> || it is StoreTrace.InputDiscarded }, "iteration $iteration: ${traces.drop(closedAt + 1).map { it::class.simpleName }.toSet()}")
        }
    }

    // --- many threads ---

    data class Counter(val count: Int = 0) : State

    sealed interface CounterAction : Action {
        data object Increment : CounterAction
        data object LaunchIncrement : CounterAction
    }

    /**
     * Input ids are allocated from every dispatching thread and traces are recorded from several
     * threads, yet every processed input has one start and one end, ordinals and revisions are
     * dense, and no accepted input is left without a processing or a discard.
     */
    @Test
    fun manyThreads_idsAreUnique_ordinalsAndRevisionsAreDense_andEveryInputIsAccountedFor() = runTest {
        val senders = 8
        val perSender = 250
        val total = senders * perSender
        val recorded = Channel<StoreTrace<Counter, CounterAction, Nothing>>(Channel.UNLIMITED)
        val store: Store<Counter, CounterAction, Nothing> = Store(Counter()) {
            coroutineContext(Dispatchers.Default)
            probe(StoreProbe { recorded.trySend(it) })
            state<Counter> {
                action<CounterAction.Increment> { nextState { state.copy(count = state.count + 1) } }
                action<CounterAction.LaunchIncrement> {
                    launch { transaction { nextState { state.copy(count = state.count + 1) } } }
                }
            }
        }

        withContext(Dispatchers.Default) {
            coroutineScope {
                repeat(senders) { sender ->
                    launch {
                        repeat(perSender) { index ->
                            store.dispatch(if ((sender + index) % 2 == 0) CounterAction.Increment else CounterAction.LaunchIncrement)
                        }
                    }
                }
            }
            withTimeout(10_000) { store.state.first { it.count == total } }
        }
        store.close()
        val traces = generateSequence { recorded.tryReceive().getOrNull() }.toList()

        val accepted = traces.filterIsInstance<StoreTrace.InputAccepted<CounterAction>>()
        val transactions = total / 2
        assertEquals(1 + total + transactions, accepted.size, "startup, every dispatch, every transaction")
        assertEquals(accepted.size, accepted.map { it.input }.toSet().size, "input ids are unique")

        val started = traces.filterIsInstance<StoreTrace.ProcessingStarted>()
        val finished = traces.filterIsInstance<StoreTrace.ProcessingFinished>()
        assertEquals((1L..started.size).toList(), started.map { it.ordinal }.sorted(), "ordinals are dense")
        assertEquals(started.map { it.input to it.ordinal }.toSet(), finished.map { it.input to it.ordinal }.toSet())
        assertEquals(accepted.map { it.input }.toSet(), started.map { it.input }.toSet(), "every accepted input was processed")
        assertTrue(traces.none { it is StoreTrace.InputDiscarded })

        val commits = traces.filterIsInstance<StoreTrace.StateCommitted<Counter>>()
        assertEquals((1L..total).toList(), commits.map { it.revision }.sorted(), "revisions are dense")
        assertEquals(total, finished.sumOf { (it.outcome as? ProcessingOutcome.Handled)?.commits ?: 0 })
        assertEquals(transactions + 1, finished.count { it.outcome == ProcessingOutcome.Unchanged }, "a LaunchIncrement dispatch commits nothing itself, nor does the startup of a state without enter {}")
        assertSame(StoreTrace.StoreClosed, traces.last())
    }
}

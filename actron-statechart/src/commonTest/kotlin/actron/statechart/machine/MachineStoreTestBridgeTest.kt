@file:OptIn(ExperimentalActronApi::class, ExperimentalCoroutinesApi::class, InternalActronApi::class)

package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import actron.core.ExceptionHandler
import actron.core.ExperimentalActronApi
import actron.core.InternalActronApi
import actron.core.Plugin
import actron.core.PluginScope
import actron.core.StoreInternalApi
import actron.core.StoreProbe
import actron.observability.StoreInstanceId
import actron.statechart.ActionMatcher
import actron.statechart.AtomicState
import actron.statechart.CompoundState
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.Transition
import actron.test.awaitIdle
import actron.test.createRecorder
import actron.test.dispatchAndAwait
import actron.test.patch
import actron.test.pendingWork
import actron.test.startAndAwait
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A [MachineStore] driven through actron-test: `startAndAwait`, `dispatchAndAwait`, `patch {}` and
 * `createRecorder()` work on it as on any Store, with the machine's meaning: a dispatch is
 * decided under admission, a recorder sees every effect the decisions emitted, transient and
 * retained, and a plugin sees the actions the store's callers sent, not the executor's inputs.
 *
 * ```
 * [*] --> Root
 * state Root {
 *     [*] --> Idle
 *     Idle --Load--> Loading            onEnter: command Fetch in lane "load", Latest; event Started
 *     Loading --Loaded--> Content       onEnter: event Done (retained)
 *     Content --Load--> Loading
 * }
 * Root: onAction Tick / ticks++
 * ```
 */
class MachineStoreTestBridgeTest {

    data class Ctx(val loaded: Int = 0, val ticks: Int = 0)

    sealed interface Act : Action {
        data object Load : Act
        data object Loaded : Act
        data object Tick : Act
    }

    sealed interface Ev : Event {
        data object Started : Ev
        data object Done : Ev
    }

    data object Fetch

    private val root = StateId("Root")
    private val idle = StateId("Idle")
    private val loading = StateId("Loading")
    private val content = StateId("Content")
    private val chart = StateChartDefinition(
        root,
        listOf(CompoundState(root, initial = idle), AtomicState(idle, parent = root), AtomicState(loading, parent = root), AtomicState(content, parent = root)),
        listOf(
            Transition(idle, loading, ActionMatcher.of<Act.Load>("Load")),
            Transition(loading, content, ActionMatcher.of<Act.Loaded>("Loaded"), effect = "count"),
            Transition(content, loading, ActionMatcher.of<Act.Load>("Load")),
        ),
    )
    private val machine = Machine<Ctx, Act, Fetch, Ev>(DefinitionId("bridged"), DefinitionVersion("1"), chart) {
        effect("count") { c, _ -> c.copy(loaded = c.loaded + 1) }
        onEnter(loading) {
            command(Fetch, LaneId("load"), ConcurrencyPolicy.Latest)
            event(Ev.Started)
        }
        onEnter(content) { event(Ev.Done) }
        onAction(root, ActionMatcher.of<Act.Tick>("Tick")) { context = context.copy(ticks = context.ticks + 1) }
    }
    private val policy: (Ev) -> EffectPolicy = { if (it is Ev.Done) EffectPolicy.Retained() else EffectPolicy.Transient }

    private class TestClock(private val scheduler: TestCoroutineScheduler) : MachineClock {
        override fun now(): MachineTime = MachineTime(scheduler.currentTime.milliseconds)

        override suspend fun delayUntil(deadline: MachineTime) {
            val remaining = deadline - now()
            if (remaining.isPositive()) delay(remaining)
        }
    }

    private inner class Harness(scope: TestScope, admission: AdmissionPolicy = AdmissionPolicy.Unbounded, answers: Boolean = true) {
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val executionScope = CoroutineScope(dispatcher + SupervisorJob())
        val started = mutableListOf<CommandId>()
        val handled = mutableListOf<Throwable>()
        val store = MachineStore(
            machine, Ctx(), CommandHandler<Fetch, Act> { command, results ->
                started += command.id
                if (answers) results.result(Act.Loaded) else awaitCancellation()
            },
            executionScope, TestClock(scope.testScheduler), dispatcher, admission = admission, mailbox = MailboxConfig(policy),
        ) { exceptionHandler(ExceptionHandler { handled += it }) }

        fun close() {
            store.close()
            executionScope.cancel()
        }
    }

    @Test
    fun startAndAwait_startsTheMachine_andDispatchAndAwait_returnsAfterTheCommit_beforeTheCommandRuns() = runTest {
        val h = Harness(this)

        h.store.startAndAwait()

        assertTrue(h.store.currentState.isActive(idle))
        assertEquals(1, h.store.currentState.revision)

        h.store.dispatchAndAwait(Act.Load)

        assertTrue(h.store.currentState.isActive(loading), "decided and committed")
        assertEquals(1, h.store.currentState.commands.size, "the command is registered")
        assertTrue(h.started.isEmpty(), "the executor has not had its turn: dispatchAndAwait waits for the decision, not the commands")
        runCurrent()
        assertEquals(1, h.started.size)
        assertTrue(h.store.currentState.isActive(content), "the handler's answer was decided")
        assertTrue(h.handled.isEmpty(), h.handled.toString())
        h.close()
    }

    @Test
    fun aRecorder_recordsEverySnapshot_andEveryEffect_transientAndRetained_inDecisionOrder() = runTest {
        val h = Harness(this)
        val recorder = h.store.createRecorder()

        h.store.startAndAwait()
        h.store.dispatchAndAwait(Act.Load)
        runCurrent()

        assertEquals(listOf(0L, 1L, 2L, 3L), recorder.states.map { it.revision }, "the initial snapshot, then every commit: Start, Load, Loaded")
        assertEquals(listOf<Ev>(Ev.Started, Ev.Done), recorder.events, "the transient and the retained effect alike, in decision order")
        assertEquals(listOf<Ev>(Ev.Done), h.store.mailbox.pending.map { it.event }, "the retained one still waits in the mailbox")
        h.close()
    }

    @Test
    fun aPluginAppendedByPatch_seesTheCallersActions_everyState_everyEffect_andDispatchesFromALaunch() = runTest {
        val h = Harness(this)
        val actions = mutableListOf<Act>()
        val states = mutableListOf<Long>()
        val events = mutableListOf<Ev>()
        h.store.patch {
            plugin(object : Plugin<MachineSnapshot<Ctx>, Act, Ev> {
                override suspend fun onStart(scope: PluginScope<MachineSnapshot<Ctx>, Act>, state: MachineSnapshot<Ctx>) {
                    scope.launch { dispatch(Act.Tick) }
                }

                override suspend fun onAction(scope: PluginScope<MachineSnapshot<Ctx>, Act>, state: MachineSnapshot<Ctx>, action: Act) {
                    actions += action
                }

                override suspend fun onState(scope: PluginScope<MachineSnapshot<Ctx>, Act>, prevState: MachineSnapshot<Ctx>, state: MachineSnapshot<Ctx>) {
                    states += state.revision
                }

                override suspend fun onEvent(scope: PluginScope<MachineSnapshot<Ctx>, Act>, state: MachineSnapshot<Ctx>, event: Ev) {
                    events += event
                }
            })
        }

        h.store.startAndAwait()
        runCurrent()
        h.store.dispatchAndAwait(Act.Load)
        runCurrent()

        assertEquals(1, h.store.currentState.context.ticks, "the plugin's launch dispatched through admission")
        assertEquals(listOf<Act>(Act.Tick, Act.Load), actions, "the callers' actions; the command's result is the executor's input, not an action of the store's callers")
        assertEquals(listOf(1L, 2L, 3L, 4L), states)
        assertEquals(listOf<Ev>(Ev.Started, Ev.Done), events)
        h.close()
    }

    @Test
    fun patch_afterStart_isRefused_andReplacingClearingOrProbing_areRefusedAlways() = runTest {
        val h = Harness(this)
        val plugin = Plugin<MachineSnapshot<Ctx>, Act, Ev>()
        assertFailsWith<IllegalArgumentException> { h.store.patch { replacePlugins(plugin) } }
        assertFailsWith<IllegalArgumentException> { h.store.patch { clearPlugins() } }
        assertFailsWith<IllegalArgumentException> { h.store.patch { probe(StoreProbe { }) } }
        h.store.patch { exceptionHandler(ExceptionHandler.Ignore) }

        h.store.startAndAwait()

        assertFailsWith<IllegalStateException> { h.store.patch { plugin(plugin) } }
        h.close()
    }

    @Test
    fun dispatchAndAwait_onAnActionTheAdmissionRefuses_throws_andTheRefusedActionIsNotDecided() = runTest {
        val h = Harness(this, admission = AdmissionPolicy.Bounded(1))
        h.store.startAndAwait()
        h.store.dispatch(Act.Tick)

        val failure = assertFailsWith<IllegalStateException> { h.store.dispatchAndAwait(Act.Tick) }

        assertTrue("rejected by the admission policy (pending=1, limit=1)" in failure.message.orEmpty(), failure.message)
        runCurrent()
        assertEquals(1, h.store.currentState.context.ticks, "the admitted one was decided, the refused one never became an input")
        h.close()
    }

    @Test
    fun dispatchIf_isNotSupported() = runTest {
        val h = Harness(this)
        @Suppress("UNCHECKED_CAST")
        val bridge = h.store as StoreInternalApi<MachineSnapshot<Ctx>, Act, Ev>

        assertFailsWith<UnsupportedOperationException> { bridge.dispatchIf(Act.Tick) { true } }
        h.close()
    }

    @Test
    fun dispatchAndAwait_duringACut_waitsForTheThaw_andTheInputIsDecidedOnce() = runTest {
        val h = Harness(this)
        h.store.startAndAwait()
        val impl = h.store as MachineStoreImpl<Ctx, Act, Fetch, Ev>
        impl.freeze()
        val awaiting = launch { h.store.dispatchAndAwait(Act.Tick) }
        runCurrent()

        assertTrue(awaiting.isActive, "held by the cut")
        assertEquals(1, impl.heldInputs)
        assertEquals(0, h.store.currentState.context.ticks)

        impl.thaw()
        runCurrent()

        assertTrue(awaiting.isCompleted, "returned after the thaw")
        assertEquals(1, h.store.currentState.context.ticks, "decided once")
        h.close()
    }

    @Test
    fun awaitIdle_waitsForTheExecutorsResultsToBeDecided_andLeavesARunningCommandAsData() = runTest {
        val answering = Harness(this)
        answering.store.startAndAwait()
        answering.store.dispatchAndAwait(Act.Load)

        answering.store.awaitIdle()

        assertTrue(answering.store.currentState.isActive(content), "the command's answer was fed and decided before awaitIdle returned")
        assertEquals(1, answering.store.currentState.context.loaded)
        answering.close()

        val hanging = Harness(this, answers = false)
        hanging.store.startAndAwait()
        hanging.store.dispatchAndAwait(Act.Load)

        hanging.store.awaitIdle()

        assertTrue(hanging.store.currentState.isActive(loading), "a command that never answers is not waited for")
        assertEquals(listOf(CommandId(1)), hanging.store.checkpoint().lanes.running.keys.toList(), "it is data in the checkpoint")
        assertTrue(hanging.store.pendingWork().isIdle)
        hanging.close()
    }

    @Test
    fun dispatchAndAwait_underAStormWithCuts_returnsForEveryCall_andEveryActionIsDecidedOnce() = runTest {
        val executionScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val group = MachineGroup()
        val member = group.member<Ctx, Act, Fetch, Ev>(StoreInstanceId("bridged-1"))
        val store = MachineStore(
            machine, Ctx(), CommandHandler<Fetch, Act> { _, results -> results.result(Act.Loaded) },
            executionScope, coroutineContext = Dispatchers.Default, observers = listOf(member), mailbox = MailboxConfig(policy),
        ) { exceptionHandler(ExceptionHandler.Ignore) }
        val recorder = store.createRecorder()
        member.attach(store)
        store.startAndAwait()
        val senders = 4
        val perSender = 100
        var failedCuts = 0

        withContext(Dispatchers.Default) {
            coroutineScope {
                repeat(senders) {
                    launch { repeat(perSender) { store.dispatchAndAwait(Act.Tick) } }
                }
                launch {
                    repeat(30) {
                        if (group.checkpoint(5.seconds) == null) failedCuts++
                        delay(Random.nextLong(0, 2).milliseconds)
                    }
                }
            }
        }

        assertEquals(senders * perSender, store.currentState.context.ticks, "every call returned with its action decided, none twice")
        assertEquals(0, failedCuts, "no cut timed out")
        assertEquals(senders * perSender + 2, recorder.states.size, "the initial snapshot, Start and one commit per tick, all recorded before their dispatchAndAwait returned")
        store.close()
        executionScope.cancel()
    }
}

package koma.statechart.test

import koma.core.Action
import koma.core.Event
import koma.core.ExceptionHandler
import koma.core.ExperimentalKomaApi
import koma.statechart.StoreConfiguration
import koma.statechart.StateId
import koma.statechart.machine.Admission
import koma.statechart.machine.AdmissionPolicy
import koma.statechart.machine.CommandEnvelope
import koma.statechart.machine.CommandId
import koma.statechart.machine.EffectId
import koma.statechart.machine.ExecutorCheckpoint
import koma.statechart.machine.Machine
import koma.statechart.machine.MachineInput
import koma.statechart.machine.MachineSnapshot
import koma.statechart.machine.MachineStore
import koma.statechart.machine.MailboxConfig
import koma.statechart.machine.PendingEffect
import koma.test.StoreRecorder
import koma.test.createRecorder
import koma.test.dispatchAndAwait
import koma.test.startAndAwait
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A `MachineStore` under test, driven step by step (handoff §12; koma-kt/koma#189 item 2): the
 * store runs on the test's scheduler with a [VirtualMachineClock], its commands wait for the test
 * through a [ScriptedCommandHandler], its states and effects are recorded, and every step ends
 * settled. `send` an action, `settle` to see the machine's data, `answer`/`complete`/`fail` a
 * command, `advanceBy` to fire a timer, `receiveEvent` to account for an effect, and
 * `assertNoPendingWork` to end.
 *
 * @param machine The machine
 * @param context Its initial context
 * @param scope The test's scope; the store's dispatcher and clock are its scheduler's
 * @param handler Runs the commands; the scripted one by default, answered by the test
 * @param admission The admission policy of the store
 * @param mailbox The mailbox configuration; effects are recorded whatever their policy
 * @param builder Store configuration on top of the driver's (exception handler, saver, plugins)
 */
@ExperimentalKomaApi
class MachineTestDriver<C, A : Action, CMD, E : Event>(
    val machine: Machine<C, A, CMD, E>,
    context: C,
    scope: TestScope,
    val handler: ScriptedCommandHandler<CMD, A> = ScriptedCommandHandler(),
    admission: AdmissionPolicy = AdmissionPolicy.Unbounded,
    mailbox: MailboxConfig<E> = MailboxConfig(),
    builder: StoreConfiguration<MachineSnapshot<C>, MachineInput<A>, E>.() -> Unit = {},
) {
    private val scheduler = scope.testScheduler
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val executionScope = CoroutineScope(dispatcher + SupervisorJob())

    /** Immutable starting model data for independent, IO-free generated scenarios. */
    val initialSnapshot: MachineSnapshot<C> = machine.initialSnapshot(context)
    private val coverageRecorder = MachineCoverageRecorder(machine)

    /** Behavioural coverage accumulated from this driver's actual Store decisions. */
    val coverage: MachineCoverage get() = coverageRecorder.snapshot()

    /** What the store reported to its exception handler, in order. */
    val failures: List<Throwable> get() = failureList.toList()
    private val failureList = mutableListOf<Throwable>()

    /** The store; every action a test sends goes through its admission. */
    val store: MachineStore<C, A, CMD, E> = MachineStore(
        machine, context, handler, executionScope, VirtualMachineClock(scheduler), dispatcher, admission = admission, mailbox = mailbox,
        observers = listOf(coverageRecorder),
    ) {
        exceptionHandler(ExceptionHandler { failureList += it })
        builder()
    }

    /** Every snapshot and every effect, transient and retained, in decision order; [receiveEvent] walks the effects. */
    val recorder: StoreRecorder<MachineSnapshot<C>, A, E> = store.createRecorder()

    /** The snapshot now. */
    val snapshot: MachineSnapshot<C> get() = store.currentState

    /** Starts the store and settles. */
    suspend fun start(): ExecutorCheckpoint<C, CMD> {
        store.startAndAwait()
        return settle()
    }

    /** Sends [action] through admission and settles: the decision, and what the executor fed back, decided. */
    suspend fun send(action: A): ExecutorCheckpoint<C, CMD> {
        store.dispatchAndAwait(action)
        return settle()
    }

    /** Sends [action] without waiting; [Admission.Rejected] when the admission policy refused it. */
    fun sendLater(action: A): Admission = store.admit(action)

    /** Waits until the store is idle and returns the executor's checkpoint; see [MachineStore.settle]. */
    suspend fun settle(timeout: Duration = 10.seconds): ExecutorCheckpoint<C, CMD> {
        scheduler.runCurrent()
        val checkpoint = store.settle(timeout)
        assertInvariants()
        return checkpoint
    }

    /** Runs everything the scheduler has, timers included, then settles. */
    suspend fun advanceUntilIdle(): ExecutorCheckpoint<C, CMD> {
        scheduler.advanceUntilIdle()
        return settle()
    }

    /** Moves the virtual clock by [duration], firing the timers that come due, then settles. */
    suspend fun advanceBy(duration: Duration): ExecutorCheckpoint<C, CMD> {
        scheduler.advanceTimeBy(duration)
        scheduler.runCurrent()
        return settle()
    }

    /** The commands the executor runs now, awaiting the test. */
    val runningCommands: List<CommandEnvelope<CMD>> get() = handler.running

    /** Feeds [result] to the running [command] and settles. */
    suspend fun answer(command: CommandId, result: A): ExecutorCheckpoint<C, CMD> {
        handler.answer(command, result)
        return settle()
    }

    /** Completes the running [command] and settles. */
    suspend fun complete(command: CommandId): ExecutorCheckpoint<C, CMD> {
        handler.complete(command)
        return settle()
    }

    /** Fails the running [command] and settles. */
    suspend fun fail(command: CommandId, error: Throwable = IllegalStateException("failed by the test")): ExecutorCheckpoint<C, CMD> {
        handler.fail(command, error)
        return settle()
    }

    /** The next recorded effect, which must be an [E2]; see [StoreRecorder.receiveEvent]. */
    inline fun <reified E2 : E> receiveEvent(): E2 = recorder.receiveEvent<E2>()

    /** The next recorded effect, which must satisfy [predicate]. */
    fun receiveEvent(predicate: (E) -> Boolean): E = recorder.receiveEvent(predicate)

    /** The effects waiting in the mailbox or being handled unacknowledged. */
    val pendingEffects: List<PendingEffect<E>> get() = store.mailbox.pending

    /** Acknowledges the retained effect [id]; `false` when it was not pending. */
    fun acknowledge(id: EffectId): Boolean = store.mailbox.acknowledge(id)

    /**
     * Fails unless [ids] are exactly the active nodes of the snapshot.
     *
     * @throws AssertionError naming the active nodes
     */
    fun assertActive(ids: Set<StateId>) {
        val active = snapshot.configuration.active
        if (active != ids) throw AssertionError("[Koma] Expected the active nodes ${ids.map { it.value }.sorted()}, but they are ${active.map { it.value }.sorted()}")
    }

    /** [assertActive] of the nodes [first] and [rest]. */
    fun assertActive(first: StateId, vararg rest: String) = assertActive(setOf(first) + rest.map(::StateId))

    /**
     * Fails unless the context equals [expected].
     *
     * @throws AssertionError with both
     */
    fun assertContext(expected: C) {
        if (snapshot.context != expected) throw AssertionError("[Koma] Expected the context $expected, but it is ${snapshot.context}")
    }

    private var checkedSnapshots = 0

    /** Checks every newly recorded stable snapshot, including transient states between settles. */
    fun assertInvariants() {
        val states = recorder.states
        while (checkedSnapshots < states.size) {
            val state = states[checkedSnapshots]
            if (state.isStarted) {
                val violations = machine.checkInvariants(state)
                if (violations.isNotEmpty()) throw AssertionError("[Koma] Invariants violated at revision ${state.revision}: ${violations.map { it.name }}")
            }
            checkedSnapshots++
        }
    }

    /** The end-of-test check; see [MachineStore.assertNoPendingWork]. */
    suspend fun assertNoPendingWork() = store.assertNoPendingWork(recorder)

    /** Closes the store and its scope. */
    fun close() {
        store.close()
        executionScope.cancel()
    }
}

@file:OptIn(actron.core.InternalActronApi::class, actron.core.ExperimentalActronApi::class)

package actron.statechart.machine

import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import actron.core.Action
import actron.core.Event
import actron.core.InputId
import actron.core.StoreProbe
import actron.core.StoreTrace
import actron.core.Plugin
import actron.core.PluginScope
import actron.core.ExceptionHandler
import actron.core.StoreInternalApi
import actron.statechart.StoreConfiguration
import actron.statechart.ActionMatcher
import actron.statechart.AtomicState
import actron.statechart.CompoundState
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.Transition
import actron.test.dispatchAndAwait
import actron.test.awaitIdle
import actron.test.startAndAwait
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

/** root -> idle; idle -- Tick / increment --> idle. No commands, timers or effects. */
class AdmissionShutdownJvmTest {
    private data object Tick : Action
    private data object Never : Event

    private class GatedClock : MachineClock {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val armed = AtomicBoolean(false)
        val fail = AtomicBoolean(false)
        override fun now(): MachineTime {
            if (fail.compareAndSet(true, false)) error("clock failure")
            if (armed.compareAndSet(true, false)) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
            return MachineTime.Zero
        }
        override suspend fun delayUntil(deadline: MachineTime) = awaitCancellation()
    }

    private class Fixture(
        policy: AdmissionPolicy = AdmissionPolicy.Bounded(1),
        observers: List<DecisionObserver<Int, Tick, Nothing, Never>> = emptyList(),
        coroutineContext: CoroutineContext = kotlinx.coroutines.Dispatchers.Default,
        configure: StoreConfiguration<MachineSnapshot<Int>, MachineInput<Tick>, Never>.() -> Unit = {},
    ) : AutoCloseable {
        val clock = GatedClock()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val store: MachineStore<Int, Tick, Nothing, Never>
        val impl: MachineStoreImpl<Int, Tick, Nothing, Never>
        init {
            val root = StateId("root")
            val idle = StateId("idle")
            val chart = StateChartDefinition(root, listOf(CompoundState(root, initial = idle), AtomicState(idle, root)), listOf(Transition(idle, idle, ActionMatcher.of<Tick>("tick"), effect = "increment")))
            val machine = Machine<Int, Tick, Nothing, Never>(DefinitionId("shutdown-counter"), DefinitionVersion("1"), chart) {
                effect("increment") { count, _ -> count + 1 }
            }
            store = MachineStore(machine, 0, CommandHandler<Nothing, Tick> { _, _ -> }, scope, clock, coroutineContext, admission = policy, observers = observers, builder = configure)
            impl = store as MachineStoreImpl<Int, Tick, Nothing, Never>
        }
        override fun close() {
            clock.release.countDown()
            store.close()
            scope.cancel()
        }
    }

    @Test fun closeDuringDispatchTimestampDoesNotAcceptTheAction() = closeDuringTimestamp(false)
    @Test fun closeDuringFeedTimestampDoesNotAcceptTheAction() = closeDuringTimestamp(true)

    private fun closeDuringTimestamp(feed: Boolean) = runBlocking {
        Fixture().use { f ->
            f.store.startAndAwait()
            val executor = Executors.newSingleThreadExecutor()
            try {
                f.clock.armed.set(true)
                val outcome = executor.submit<Admission> {
                    if (feed) f.store.feed(SourceId("socket"), Tick) else f.store.admit(Tick)
                }
                assertTrue(f.clock.entered.await(10, TimeUnit.SECONDS))
                f.store.close()
                f.clock.release.countDown()
                assertEquals(Admission.Closed, outcome.get(10, TimeUnit.SECONDS))
                assertEquals(Admission.Closed, f.store.admit(Tick))
                assertEquals(0, f.impl.heldInputs)
                assertEquals(0, f.impl.pendingAdmissions)
                assertEquals(0, f.store.currentState.context)
            } finally {
                f.clock.release.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test fun closeDiscardsHeldInputsAndReleasesAwaitersWithoutThaw() = runBlocking {
        Fixture().use { f ->
            f.store.startAndAwait()
            f.impl.freeze()
            val awaiting = async(start = CoroutineStart.UNDISPATCHED) { f.store.dispatchAndAwait(Tick) }
            assertTrue(awaiting.isActive)
            assertEquals(1, f.impl.heldInputs)
            f.store.close()
            assertEquals(0, f.impl.heldInputs)
            assertEquals(0, f.impl.pendingAdmissions)
            withTimeout(10_000) { awaiting.await() }
            f.impl.freeze()
            f.impl.thaw()
            assertEquals(Admission.Closed, f.store.feed(SourceId("socket"), Tick))
            assertEquals(0, f.impl.heldInputs)
            assertEquals(0, f.store.currentState.context)
        }
    }

    @Test fun closeDuringAwaitTimestampThrowsAndDoesNotReserveSpace() = runBlocking {
        Fixture().use { f ->
            f.store.startAndAwait()
            val executor = Executors.newSingleThreadExecutor()
            try {
                f.clock.armed.set(true)
                val outcome = executor.submit<Throwable> {
                    runBlocking { assertFailsWith<IllegalStateException> { f.store.dispatchAndAwait(Tick) } }
                }
                assertTrue(f.clock.entered.await(10, TimeUnit.SECONDS))
                f.store.close()
                f.clock.release.countDown()
                assertTrue(outcome.get(10, TimeUnit.SECONDS).message!!.contains("closed"))
                assertEquals(0, f.impl.pendingAdmissions)
                assertEquals(0, f.impl.heldInputs)
            } finally {
                f.clock.release.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test fun clockFailureDoesNotLeakBoundedCapacity() = runBlocking {
        Fixture().use { f ->
            f.store.startAndAwait()
            f.impl.freeze()
            f.clock.fail.set(true)
            assertFailsWith<IllegalStateException> { f.store.admit(Tick) }
            assertEquals(0, f.impl.pendingAdmissions)
            assertEquals(Admission.Accepted, f.store.admit(Tick))
            assertEquals(1, f.impl.pendingAdmissions)
            f.impl.thaw()
            withTimeout(10_000) { f.store.awaitIdle() }
            withTimeout(10_000) { f.store.dispatchAndAwait(Tick) }
        }
    }

    @Test fun startupDiscardReleasesCapacityAndTheIndividualWaiter() = runBlocking {
        val failOnce = AtomicBoolean(true)
        Fixture(configure = {
            plugin(object : Plugin<MachineSnapshot<Int>, MachineInput<Tick>, Never> {
                override suspend fun onStart(scope: PluginScope<MachineSnapshot<Int>, MachineInput<Tick>>, state: MachineSnapshot<Int>) {
                    if (failOnce.compareAndSet(true, false)) error("startup failed")
                }
            })
            exceptionHandler(ExceptionHandler {})
        }).use { f ->
            withTimeout(10_000) { f.store.dispatchAndAwait(Tick) }
            assertEquals(0, f.impl.pendingAdmissions)
            assertEquals(0, f.store.currentState.context)
            withTimeout(10_000) { f.store.dispatchAndAwait(Tick) }
            assertEquals(1, f.store.currentState.context)
            assertEquals(0, f.impl.pendingAdmissions)
        }
    }

    @Test fun individualAwaitDoesNotWaitForALaterBlockedInputAndCloseDoesNotUnderflow() = runBlocking {
        val firstEntered = CountDownLatch(1)
        val firstRelease = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val secondRelease = CountDownLatch(1)
        val observer = object : DecisionObserver<Int, Tick, Nothing, Never> {
            override fun onCommitted(input: InputId?, machineInput: MachineInput<Tick>, decision: Decision<Int, Nothing, Never>) {
                if (machineInput !is MachineInput.Dispatch) return
                val first = decision.snapshot.context == 1
                (if (first) firstEntered else secondEntered).countDown()
                check((if (first) firstRelease else secondRelease).await(10, TimeUnit.SECONDS))
            }
        }
        Fixture(observers = listOf(observer)).use { f ->
            try {
                f.store.startAndAwait()
                val first = async(Dispatchers.Default) { f.store.dispatchAndAwait(Tick) }
                assertTrue(firstEntered.await(10, TimeUnit.SECONDS))
                val second = async(start = CoroutineStart.UNDISPATCHED) { f.store.dispatchAndAwait(Tick) }
                assertEquals(1, f.impl.pendingAdmissions)
                assertEquals(Admission.Rejected(1, 1), f.store.admit(Tick))
                firstRelease.countDown()
                assertTrue(secondEntered.await(10, TimeUnit.SECONDS))
                withTimeout(10_000) { first.await() }
                assertTrue(second.isActive, "the second observer is still running")
                f.store.close()
                assertEquals(0, f.impl.pendingAdmissions)
                assertTrue(second.isActive, "close must not finish an observer that has not returned")
                secondRelease.countDown()
                withTimeout(10_000) { second.await() }
                assertEquals(0, f.impl.pendingAdmissions)
                assertEquals(2, f.store.currentState.context)
            } finally {
                firstRelease.countDown()
                secondRelease.countDown()
            }
        }
    }

    @Test fun concurrentOffersRespectTheBoundWhileFrozenAndCloseClearsThem() = runBlocking {
        Fixture(AdmissionPolicy.Bounded(7)).use { f ->
            f.store.startAndAwait()
            f.impl.freeze()
            val start = CountDownLatch(1)
            val ready = CountDownLatch(8)
            val executor = Executors.newFixedThreadPool(8)
            try {
                val outcomes = (0 until 8).map {
                    executor.submit<List<Admission>> {
                        ready.countDown()
                        check(start.await(10, TimeUnit.SECONDS))
                        List(12) { index -> if (index % 2 == 0) f.store.admit(Tick) else f.store.feed(SourceId("socket"), Tick) }
                    }
                }
                assertTrue(ready.await(10, TimeUnit.SECONDS))
                start.countDown()
                val results = outcomes.flatMap { it.get(10, TimeUnit.SECONDS) }
                assertEquals(7, results.count { it == Admission.Accepted })
                assertEquals(89, results.count { it == Admission.Rejected(7, 7) })
                assertEquals(7, f.impl.pendingAdmissions)
                assertEquals(7, f.impl.heldInputs)
                f.store.close()
                f.impl.thaw()
                assertEquals(0, f.impl.pendingAdmissions)
                assertEquals(0, f.impl.heldInputs)
                assertEquals(Admission.Closed, f.store.admit(Tick))
            } finally {
                start.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test fun inlineObserversCanReenterAdmissionAndCloseDuringThaw() = runBlocking {
        lateinit var store: MachineStore<Int, Tick, Nothing, Never>
        val observer = object : DecisionObserver<Int, Tick, Nothing, Never> {
            override fun onCommitted(input: InputId?, machineInput: MachineInput<Tick>, decision: Decision<Int, Nothing, Never>) {
                if (machineInput !is MachineInput.Dispatch) return
                if (decision.snapshot.context == 1) assertEquals(Admission.Accepted, store.admit(Tick))
                else store.close()
            }
        }
        Fixture(observers = listOf(observer), coroutineContext = Dispatchers.Unconfined).use { f ->
            store = f.store
            f.store.startAndAwait()
            f.impl.freeze()
            assertEquals(Admission.Accepted, f.store.admit(Tick))
            val executor = Executors.newSingleThreadExecutor()
            try {
                executor.submit { f.impl.thaw() }.get(10, TimeUnit.SECONDS)
                assertEquals(2, f.store.currentState.context)
                assertEquals(Admission.Closed, f.store.admit(Tick))
                assertEquals(0, f.impl.pendingAdmissions)
                assertEquals(0, f.impl.heldInputs)
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun freezeDoesNotReportIdleWhileAPreCutInlineHandoffIsRunning() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val observer = object : DecisionObserver<Int, Tick, Nothing, Never> {
            override fun onCommitted(input: InputId?, machineInput: MachineInput<Tick>, decision: Decision<Int, Nothing, Never>) {
                if (machineInput is MachineInput.Dispatch) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            }
        }
        Fixture(observers = listOf(observer), coroutineContext = Dispatchers.Unconfined).use { f ->
            f.store.startAndAwait()
            val executor = Executors.newSingleThreadExecutor()
            try {
                val offering = executor.submit<Admission> { f.store.admit(Tick) }
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                f.impl.freeze()
                val api: StoreInternalApi<MachineSnapshot<Int>, Tick, Never> = f.impl
                assertTrue(!api.awaitIdle(Duration.ZERO).isIdle, "a cut must include the pre-freeze handoff")
                assertEquals(Admission.Accepted, f.store.feed(SourceId("socket"), Tick))
                assertEquals(1, f.impl.heldInputs)
                f.store.close()
                assertEquals(0, f.impl.heldInputs)
                assertEquals(0, f.impl.pendingAdmissions)
                release.countDown()
                assertEquals(Admission.Accepted, offering.get(10, TimeUnit.SECONDS), "accepted before close; processing was not guaranteed")
                withTimeout(10_000) { f.store.awaitIdle() }
                assertEquals(1, f.store.currentState.context)
                assertEquals(0, f.impl.pendingAdmissions)
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test fun aFatalProcessingStartProbeDoesNotStrandItsInputWaiter() = failedStartProbe(false)
    @Test fun aRethrowingProcessingStartProbeHandlerDoesNotStrandItsInputWaiter() {
        // ExceptionHandler.Rethrow intentionally reaches global uncaught reporting. Isolate
        // the JVM so coroutine-test does not attribute that expected failure to a later test.
        val log = Files.createTempFile("actron-machine-probe-", ".log").toFile()
        val paths = generateSequence(javaClass.classLoader) { it.parent }.filterIsInstance<URLClassLoader>()
            .flatMap { it.urLs.asSequence() }.map { File(it.toURI()).path }.toList()
        val classpath = (paths + System.getProperty("java.class.path").split(File.pathSeparator)).distinct()
        val child = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path,
            "-cp", classpath.joinToString(File.pathSeparator), MachineProbeFailureProcess::class.java.name)
            .redirectErrorStream(true).redirectOutput(log).start()
        try {
            assertTrue(child.waitFor(20, TimeUnit.SECONDS), "Rethrow child timed out")
            assertEquals(0, child.exitValue(), log.readText())
            assertTrue(log.readText().contains("OK machine rethrow"), log.readText())
        } finally {
            if (child.isAlive) child.destroyForcibly().waitFor()
            log.delete()
        }
    }

    internal fun failedStartProbe(rethrow: Boolean) = runBlocking {
        val failures = LinkedBlockingQueue<Throwable>()
        val armed = AtomicBoolean(false)
        val closed = CountDownLatch(1)
        // Capture a Rethrow handler's uncaught failure on this dedicated thread, without
        // changing global JVM exception handlers or suppressing unrelated coroutine failures.
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "actron-admission-probe-failure").apply {
                uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, failure -> failures.offer(failure) }
            }
        }.asCoroutineDispatcher()
        try {
            Fixture(coroutineContext = dispatcher, configure = {
                exceptionHandler(if (rethrow) ExceptionHandler.Rethrow else ExceptionHandler { failures.offer(it) })
                probe(StoreProbe { trace ->
                    if (trace is StoreTrace.ProcessingStarted && armed.compareAndSet(true, false)) {
                        if (rethrow) throw IllegalStateException("probe failure") else throw AssertionError("fatal probe failure")
                    }
                    if (trace === StoreTrace.StoreClosed) closed.countDown()
                })
            }).use { f ->
                f.store.startAndAwait()
                armed.set(true)
                withTimeout(10_000) { f.store.dispatchAndAwait(Tick) }
                assertTrue(failures.poll(10, TimeUnit.SECONDS) != null)
                assertEquals(0, f.impl.pendingAdmissions)
                assertEquals(0, f.store.currentState.context)
                withTimeout(10_000) { f.store.awaitIdle() }
                withTimeout(10_000) { f.store.dispatchAndAwait(Tick) }
                assertEquals(1, f.store.currentState.context)
                f.store.close()
                assertTrue(closed.await(10, TimeUnit.SECONDS))
                assertEquals(0, f.impl.pendingAdmissions)
            }
        } finally { dispatcher.close() }
    }

    @Test fun awaitingFromTheSameStoresPluginStillFailsFast() = runBlocking {
        lateinit var store: MachineStore<Int, Tick, Nothing, Never>
        val checked = AtomicBoolean(false)
        Fixture(configure = {
            plugin(object : Plugin<MachineSnapshot<Int>, MachineInput<Tick>, Never> {
                override suspend fun onAction(scope: PluginScope<MachineSnapshot<Int>, MachineInput<Tick>>, state: MachineSnapshot<Int>, action: MachineInput<Tick>) {
                    assertFailsWith<IllegalStateException> { store.dispatchAndAwait(Tick) }
                    assertFailsWith<IllegalStateException> { store.awaitIdle() }
                    checked.set(true)
                }
            })
        }).use { f ->
            store = f.store
            withTimeout(10_000) { store.dispatchAndAwait(Tick) }
            assertTrue(checked.get())
            assertEquals(1, store.currentState.context)
            assertEquals(0, f.impl.pendingAdmissions)
        }
    }
}

object MachineProbeFailureProcess {
    @JvmStatic fun main(args: Array<String>) {
        AdmissionShutdownJvmTest().failedStartProbe(true)
        println("OK machine rethrow")
    }
}

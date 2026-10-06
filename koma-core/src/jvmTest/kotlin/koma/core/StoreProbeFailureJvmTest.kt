@file:OptIn(InternalKomaApi::class)

package koma.core

import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration

/** Counter --Bump--> Counter. A ProcessingStarted probe fails before the handler can run. */
class StoreProbeFailureJvmTest {
    private data class Counter(val count: Int = 0) : State
    private data object Bump : Action

    @Test fun fatalProcessingStartProbeStillFinishesInputAndReleasesPendingWork() = failure(false)
    @Test fun rethrowingProcessingStartProbeHandlerStillFinishesInputAndReleasesPendingWork() {
        // A real Rethrow reaches coroutine-test's global exception collector too. Keep that
        // deliberate uncaught failure in a fresh process, without contaminating later tests.
        val log = Files.createTempFile("koma-core-probe-", ".log").toFile()
        val paths = generateSequence(javaClass.classLoader) { it.parent }.filterIsInstance<URLClassLoader>()
            .flatMap { it.urLs.asSequence() }.map { File(it.toURI()).path }.toList()
        val classpath = (paths + System.getProperty("java.class.path").split(File.pathSeparator)).distinct()
        val child = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path,
            "-cp", classpath.joinToString(File.pathSeparator), CoreProbeFailureProcess::class.java.name)
            .redirectErrorStream(true).redirectOutput(log).start()
        try {
            assertTrue(child.waitFor(20, TimeUnit.SECONDS), "Rethrow child timed out")
            assertEquals(0, child.exitValue(), log.readText())
            assertTrue(log.readText().contains("OK core rethrow"), log.readText())
        } finally {
            if (child.isAlive) child.destroyForcibly().waitFor()
            log.delete()
        }
    }

    internal fun failure(rethrow: Boolean) = runBlocking {
        val traces = ConcurrentLinkedQueue<StoreTrace<Counter, Bump, Nothing>>()
        val errors = LinkedBlockingQueue<Throwable>()
        val armed = AtomicBoolean(false)
        val closed = CountDownLatch(1)
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "koma-core-probe-failure").apply {
                uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> errors.offer(error) }
            }
        }.asCoroutineDispatcher()
        val store = Store<Counter, Bump, Nothing>(Counter()) {
            coroutineContext(dispatcher)
            exceptionHandler(if (rethrow) ExceptionHandler.Rethrow else ExceptionHandler { errors.offer(it) })
            probe(StoreProbe { trace ->
                traces += trace
                if (trace is StoreTrace.ProcessingStarted && armed.compareAndSet(true, false)) {
                    if (rethrow) throw IllegalStateException("probe failure") else throw AssertionError("fatal probe failure")
                }
                if (trace === StoreTrace.StoreClosed) closed.countDown()
            })
            state<Counter> { action<Bump> { nextState { state.copy(count = state.count + 1) } } }
        }
        @Suppress("UNCHECKED_CAST")
        val api = store as StoreInternalApi<Counter, Bump, Nothing>
        try {
            withTimeout(10_000) { api.startAndAwait() }
            armed.set(true)
            withTimeout(10_000) { api.dispatchAndAwait(Bump) }
            assertTrue(errors.poll(10, TimeUnit.SECONDS) != null)
            assertEquals(Counter(), store.currentState)
            assertTrue(api.awaitIdle(Duration.ZERO).isIdle, "Failure at start must release pendingInputs")
            val actionInput = traces.filterIsInstance<StoreTrace.InputAccepted<Bump>>()
                .last { it.kind is InputKind.Dispatch }.input
            val finish = traces.filterIsInstance<StoreTrace.ProcessingFinished>().single { it.input == actionInput }
            assertIs<ProcessingOutcome.Failed>(finish.outcome)
            withTimeout(10_000) { api.dispatchAndAwait(Bump) }
            assertEquals(Counter(1), store.currentState)
            assertTrue(api.awaitIdle(Duration.ZERO).isIdle)
            store.close()
            assertTrue(closed.await(10, TimeUnit.SECONDS))
            assertTrue(api.awaitIdle(Duration.ZERO).isIdle)
        } finally {
            store.close()
            dispatcher.close()
        }
    }
}

/** Exercises the actual Rethrow handler, including its uncaught coroutine error. */
object CoreProbeFailureProcess {
    @JvmStatic fun main(args: Array<String>) {
        StoreProbeFailureJvmTest().failure(true)
        println("OK core rethrow")
    }
}

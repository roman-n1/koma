package koma.example.durable

import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val intent = CreditIntent("invoice-42", 25)

/** Ready --Request / persist intent, emit--> Ready --Applied / persist completion--> Ready. */
class DurableEffectProcessTest {
    @Test fun deathBeforeExternalEffectRestartsPendingBusinessIntent() = crash(Boundary.BeforeEffect)
    @Test fun deathAfterExternalEffectBeforeAckDoesNotApplyTwice() = crash(Boundary.AfterEffect)
    @Test fun deathAfterDomainAckBeforeMailboxAckDoesNotReplayCompletedIntent() = crash(Boundary.AfterDomainAck)
    @Test fun deathAfterMailboxAckDoesNotReplayCompletedIntent() = crash(Boundary.AfterMailboxAck)

    private fun crash(boundary: Boundary) = withDirectory { directory ->
        process(directory, boundary.name)
        val durable = DiskOutbox(directory.resolve("domain.json")).durable.value
        val ledger = DiskLedger(directory.resolve("ledger.json")).read()
        assertEquals(if (boundary == Boundary.BeforeEffect) 0L else 25L, ledger.balance)
        if (boundary == Boundary.BeforeEffect || boundary == Boundary.AfterEffect) assertEquals(intent, durable.pending)
        else assertEquals(intent, durable.completed)
        process(directory, "recover")
        process(directory, "recover") // A third fresh Store/process is also harmless.
        assertEquals(Ledger(25, mapOf(intent.id to 25L)), DiskLedger(directory.resolve("ledger.json")).read())
    }

    @Test fun screenReentryBeforeEffectAndAfterEffectRetainsTheSameBusinessIntent() = withDirectory { directory ->
        runBlocking {
            CreditSession(directory).use { session ->
                session.start(); session.request(intent)
                for ((index, stop) in listOf(Boundary.BeforeEffect, Boundary.BeforeEffect, Boundary.AfterEffect).withIndex()) {
                    val subscription = launch {
                        session.store.mailbox.subscribe().take(1).collect { delivery ->
                            assertEquals(index + 1, delivery.attempt)
                            assertEquals(intent, delivery.event.intent)
                            session.handle(delivery) { if (it == stop) throw CancellationException("screen left") }
                        }
                    }
                    withTimeout(10_000) { subscription.join() }
                    assertEquals(1, session.store.mailbox.pending.size)
                    assertFalse(session.store.mailbox.pending.single().handling)
                }
                withTimeout(10_000) { session.store.mailbox.subscribe().take(1).collect { session.handle(it) } }
                assertEquals(Ledger(25, mapOf(intent.id to 25L)), session.ledger.read())
                assertTrue(session.store.mailbox.pending.isEmpty())
            }
        }
    }

    @Test fun screenReentryAfterDurableDomainAckOnlyAcknowledgesMailbox() = withDirectory { directory ->
        runBlocking {
            CreditSession(directory).use { session ->
                session.start(); session.request(intent)
                val subscription = launch {
                    session.store.mailbox.subscribe().take(1).collect {
                        session.handle(it) { if (it == Boundary.AfterDomainAck) throw CancellationException("screen left") }
                    }
                }
                withTimeout(10_000) { subscription.join() }
                assertEquals(intent, session.saver.durable.value.completed)
                assertEquals(1, session.store.mailbox.pending.size)
                withTimeout(10_000) { session.store.mailbox.subscribe().take(1).collect { session.handle(it) } }
                assertTrue(session.store.mailbox.pending.isEmpty())
                assertEquals(25L, session.ledger.read().balance)
            }
        }
    }

    @Test fun failedIntentPersistenceDoesNotRunExternalEffectOrAcknowledge() = withDirectory { directory ->
        runBlocking {
            CreditSession(directory).use { session ->
                session.start()
                val domain = directory.resolve("domain.json")
                // Make rename-to-target fail on the actual filesystem.
                Files.deleteIfExists(domain); Files.createDirectory(domain)
                Files.writeString(domain.resolve("blocker"), "block replacement")
                session.store.dispatch(CreditAction.Request(intent))
                withTimeout(10_000) { session.failures.first { it != null } }
                assertFailsWith<IllegalStateException> {
                    withTimeout(10_000) { session.store.mailbox.subscribe().take(1).collect { session.handle(it) } }
                }
                assertEquals(0L, session.ledger.read().balance)
                assertEquals(1, session.store.mailbox.pending.size)
            }
        }
    }

    @Test fun failedDomainAckPersistenceLeavesDeliveryPendingAndRestartDeduplicates() = withDirectory { directory ->
        runBlocking {
            CreditSession(directory).use { session ->
                session.start(); session.request(intent)
                val domain = directory.resolve("domain.json")
                val backup = directory.resolve("backup.json")
                val subscriber = launch {
                    session.store.mailbox.subscribe().take(1).collect { delivery ->
                        session.handle(delivery) { boundary ->
                            if (boundary == Boundary.AfterEffect) {
                                Files.move(domain, backup)
                                Files.createDirectory(domain)
                                Files.writeString(domain.resolve("blocker"), "block replacement")
                            }
                        }
                    }
                }
                withTimeout(10_000) { session.failures.first { it != null } }
                assertEquals(intent, session.saver.durable.value.pending)
                assertEquals(25L, session.ledger.read().balance)
                assertEquals(1, session.store.mailbox.pending.size)
                assertTrue(subscriber.isActive, "Handler must still await a durable completion")
                assertTrue(session.failures.value != null)
                subscriber.cancelAndJoin()
                Files.delete(domain.resolve("blocker")); Files.delete(domain); Files.move(backup, domain)
            }
        }
        process(directory, "recover")
        assertEquals(25L, DiskLedger(directory.resolve("ledger.json")).read().balance)
    }

    @Test fun idCannotBeReusedForAnotherExternalPayload() = withDirectory { directory ->
        val ledger = DiskLedger(directory.resolve("ledger.json"))
        assertTrue(ledger.apply(intent))
        assertFalse(ledger.apply(intent))
        assertFailsWith<IllegalArgumentException> { ledger.apply(intent.copy(amount = 99)) }
        assertEquals(25L, ledger.read().balance)
    }

    @Test fun retryingAnOlderBusinessIntentAfterAnotherCompletionStillUsesItsReceipt() = withDirectory { directory ->
        runBlocking {
            CreditSession(directory).use { session ->
                session.start()
                for (request in listOf(intent, CreditIntent("invoice-43", 10), intent)) {
                    session.request(request)
                    withTimeout(10_000) { session.store.mailbox.subscribe().take(1).collect { session.handle(it) } }
                }
                assertEquals(Ledger(35, mapOf(intent.id to 25L, "invoice-43" to 10L)), session.ledger.read())
            }
        }
    }

    @Test fun corruptFilesFailClosedInsteadOfSilentlyForgettingPendingWorkOrReceipts() = withDirectory { directory ->
        Files.writeString(directory.resolve("domain.json"), "truncated")
        assertFailsWith<IllegalArgumentException> { DiskOutbox(directory.resolve("domain.json")) }
        Files.writeString(directory.resolve("ledger.json"), "truncated")
        assertFailsWith<IllegalArgumentException> { DiskLedger(directory.resolve("ledger.json")).apply(intent) }
        assertEquals("truncated", Files.readString(directory.resolve("ledger.json")))
    }

    @Test fun incompatibleSchemasFailClosed() = withDirectory { directory ->
        Files.writeString(directory.resolve("domain.json"), "{\"schema\":2,\"outbox\":{}}")
        assertFailsWith<IllegalArgumentException> { DiskOutbox(directory.resolve("domain.json")) }
        Files.writeString(directory.resolve("ledger.json"), "{\"schema\":2,\"ledger\":{}}")
        assertFailsWith<IllegalArgumentException> { DiskLedger(directory.resolve("ledger.json")).apply(intent) }
    }

    @Test fun aSecondSessionCannotWriteTheSameFilesAndCloseReleasesOwnership() = withDirectory { directory ->
        CreditSession(directory).use {
            assertFailsWith<java.nio.channels.OverlappingFileLockException> { CreditSession(directory) }
        }
        CreditSession(directory).close()
    }

    private fun withDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("koma-durable-effect-")
        try { block(directory) } finally { directory.toFile().deleteRecursively() }
    }

    private fun process(directory: Path, phase: String) {
        val loaderPaths = generateSequence(javaClass.classLoader) { it.parent }
            .filterIsInstance<URLClassLoader>().flatMap { it.urLs.asSequence() }.map { File(it.toURI()).path }.toList()
        val classpath = (loaderPaths + System.getProperty("java.class.path").split(File.pathSeparator)).distinct()
        val output = directory.resolve("$phase.log").toFile()
        val child = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path,
            "-cp", classpath.joinToString(File.pathSeparator), DurableEffectProcess::class.java.name,
            directory.toString(), phase).redirectErrorStream(true).redirectOutput(output).start()
        try {
            assertTrue(child.waitFor(30, TimeUnit.SECONDS), "Child timed out: ${output.readText()}")
            assertEquals(0, child.exitValue(), output.readText())
            assertTrue(output.readText().contains("OK $phase"), output.readText())
        } finally { if (child.isAlive) child.destroyForcibly().waitFor() }
    }
}

/** Child JVM has only persisted files; Runtime.halt skips Store close and all finally blocks. */
object DurableEffectProcess {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val directory = Path.of(args[0])
        val phase = args[1]
        CreditSession(directory).use { session ->
            session.start()
            if (phase != "recover") session.request(intent)
            if (session.saver.durable.value.pending != null) {
                withTimeout(10_000) {
                    session.store.mailbox.subscribe().take(1).collect { delivery ->
                        assertEquals(intent, delivery.event.intent)
                        session.handle(delivery) { boundary ->
                            if (boundary.name == phase) {
                                println("OK $phase")
                                System.out.flush()
                                Runtime.getRuntime().halt(0)
                            }
                        }
                    }
                }
            }
            assertEquals(25L, session.ledger.read().balance)
            assertEquals(intent, session.saver.durable.value.completed)
            assertTrue(session.store.mailbox.pending.isEmpty())
            session.request(intent) // Recreating UI and retrying the business request is harmless.
            assertTrue(session.store.mailbox.pending.isEmpty())
        }
        println("OK $phase")
    }
}

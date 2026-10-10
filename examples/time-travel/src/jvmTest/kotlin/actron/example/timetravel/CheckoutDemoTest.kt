@file:OptIn(actron.core.ExperimentalActronApi::class)

package actron.example.timetravel

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import actron.observability.RuntimeSessionId
import actron.observability.file.FileSegmentStorage
import actron.observability.file.JournalFiles
import actron.observability.file.SegmentMark
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import actron.timetravel.GroupMismatch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CheckoutDemoTest {
    @Test
    fun scenarioWaitsForStartupRecordingBeforeCreatingTheReplayBranchPrefix() = runBlocking {
        val parent = Files.createTempDirectory("actron-checkout-startup-")
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        try {
            withTimeout(20_000) {
                val recording = async(Dispatchers.Default) {
                    CheckoutDemo.record(parent, calls, beforeSummaryStartupRecorded = {
                        entered.complete(Unit)
                        check(release.await(15, TimeUnit.SECONDS)) { "Test did not release the summary startup observer" }
                    })
                }
                try {
                    entered.await() // Active state is already published; Start is not in group order yet.
                    val prematurePrice = withTimeoutOrNull(250) {
                        while (calls.get() == 0) delay(1)
                        calls.get()
                    }
                    release.countDown()
                    val loaded = CheckoutDemo.load(recording.await().directory)
                    assertNull(prematurePrice, "The price lookup must wait until every startup observer is recorded")
                    assertEquals(emptyList(), loaded.replay(fixed = false).session.verify())
                    val controls = loaded.controls(fixed = true)
                    val failure = controls.replay.session.verify().filterIsInstance<GroupMismatch.Replay>().single()
                    controls.replay.seek(failure.position)
                    assertTrue(controls.replay.snapshot(CheckoutDemo.summaryId).isStarted, "The true recorded prefix must contain Summary Start")
                    controls.branchHere()
                    val branch = checkNotNull((controls.travel as actron.timetravel.compose.GroupTravel.Experiment).branch)
                    branch.answer(CheckoutDemo.answers.first(), branch.awaiting(CheckoutDemo.cartId).single().id)
                    assertEquals(CartContext(20, 100, 80), branch.snapshot(CheckoutDemo.cartId).context)
                    assertEquals(SummaryContext(80), branch.snapshot(CheckoutDemo.summaryId).context)
                    assertEquals(1, calls.get(), "An experiment must not execute live command handlers")
                } finally {
                    release.countDown()
                }
            }
        } finally {
            release.countDown()
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun recordingWaitsForCommittedObserversBeforeFinalizingTheFiles() = runBlocking {
        val parent = Files.createTempDirectory("actron-checkout-observer-")
        val entered = CompletableDeferred<Unit>()
        val flushed = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        try {
            withTimeout(20_000) {
                val recording = async(Dispatchers.Default) {
                    CheckoutDemo.record(parent, AtomicInteger(), onJournalFlushed = { flushed.complete(Unit) }) {
                        entered.complete(Unit)
                        check(release.await(15, TimeUnit.SECONDS)) { "Test did not release the summary observer" }
                    }
                }
                try {
                    entered.await() // The summary StateFlow already says 100; its file observer has not run.
                    assertNull(withTimeoutOrNull(250) { recording.await() }, "A published snapshot must not finalize recording before observers finish")
                    assertFalse(recording.isCompleted)
                    flushed.await() // A real sink write and flush has completed on the independent writer.
                    val directory = Files.list(parent).use { it.findFirst().orElseThrow() }
                    val journals = JournalFiles(FileSegmentStorage(directory.toString()))
                    assertTrue(journals.read(RuntimeSessionId("checkout-demo-run")).marks.any { it is SegmentMark.Unfinished },
                        "The flushed journal must remain unfinished while a committed observer is held")
                    release.countDown()
                    val recorded = recording.await()
                    val loaded = CheckoutDemo.load(recorded.directory)
                    assertTrue(loaded.journal.marks.isEmpty(), "Shutdown joins StoreClosed publishers before finishing journal")
                    val replay = loaded.replay(fixed = false)
                    assertEquals(emptyList(), replay.session.verify())
                    replay.seek(replay.length)
                    assertEquals(SummaryContext(100), replay.snapshot(CheckoutDemo.summaryId).context)
                } finally {
                    release.countDown()
                }
            }
        } finally {
            release.countDown()
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun diskRecordingReopens_reproducesTheBug_andExperimentsWithoutCallingLiveHandlers() = runBlocking {
        val parent = Files.createTempDirectory("actron-checkout-test-")
        try {
            val calls = AtomicInteger()
            val recorded = CheckoutDemo.record(parent, calls)
            assertEquals(1, calls.get())
            assertTrue(Files.list(recorded.directory).use { it.count() >= 4 }, "journal, order and two member files")
            val loaded = CheckoutDemo.load(recorded.directory)
            val original = loaded.replay(fixed = false)
            assertEquals(emptyList(), original.session.verify())
            original.seek(original.length)
            assertEquals(SummaryContext(100), original.snapshot(CheckoutDemo.summaryId).context)

            val controls = loaded.controls(fixed = true)
            controls.replay.verify()
            val failure = (controls.replay.verification as actron.timetravel.compose.GroupVerification.Checked).problems.filterIsInstance<GroupMismatch.Replay>().single()
            assertEquals(CheckoutDemo.cartId, failure.store)
            assertTrue(failure.mismatch.differences.any { "context" in it })
            controls.replay.seek(failure.position)
            val checkpoints = controls.replay.members.associateWith { controls.replay.checkpoint(it) }
            controls.replay.stepForward()
            assertIs<actron.timetravel.compose.GroupReplayMovement.Diverged>(controls.replay.movement)
            assertEquals(failure.position, controls.replay.position)
            assertTrue(controls.replay.snapshot(CheckoutDemo.cartId).isActive(CheckoutDemo.loading))

            controls.branchHere()
            val branch = checkNotNull((controls.travel as actron.timetravel.compose.GroupTravel.Experiment).branch)
            branch.answer(CheckoutDemo.answers.first(), branch.awaiting(CheckoutDemo.cartId).single().id)
            assertEquals(CartContext(20, 100, 80), branch.snapshot(CheckoutDemo.cartId).context)
            assertEquals(SummaryContext(80), branch.snapshot(CheckoutDemo.summaryId).context)
            assertEquals(checkpoints, controls.replay.members.associateWith { controls.replay.checkpoint(it) })
            controls.returnToReplay()
            assertIs<actron.timetravel.compose.GroupReplayMovement.Diverged>(controls.replay.movement)
            assertEquals(failure.position, controls.replay.position)
            assertEquals(1, calls.get(), "replay, verification and branch must never re-execute the price handler")
            assertEquals(loaded.recording.order, CheckoutDemo.load(recorded.directory).recording.order)

            val next = CheckoutDemo.record(parent)
            assertNotEquals(recorded.directory, next.directory, "existing run must not be overwritten")
            assertEquals(emptyList(), CheckoutDemo.load(recorded.directory).replay(false).session.verify())
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun anUnrelatedDirectoryIsRefused_withAUsefulReason() {
        val parent = Files.createTempDirectory("actron-empty-demo-")
        try {
            val error = assertFailsWith<IllegalArgumentException> { CheckoutDemo.load(parent) }
            assertTrue(checkNotNull(error.message).contains("No checkout recording"))
        } finally {
            parent.toFile().deleteRecursively()
        }
    }
}

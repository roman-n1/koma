@file:OptIn(koma.core.ExperimentalKomaApi::class)

package koma.example.timetravel

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import koma.timetravel.GroupMismatch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CheckoutDemoTest {
    @Test
    fun diskRecordingReopens_reproducesTheBug_andExperimentsWithoutCallingLiveHandlers() = runBlocking {
        val parent = Files.createTempDirectory("koma-checkout-test-")
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
            val failure = controls.replay.verification.orEmpty().filterIsInstance<GroupMismatch.Replay>().single()
            assertEquals(CheckoutDemo.cartId, failure.store)
            assertTrue(failure.mismatch.differences.any { "context" in it })
            controls.replay.seek(failure.position)
            val checkpoints = controls.replay.members.associateWith { controls.replay.checkpoint(it) }
            controls.replay.stepForward()
            assertNotNull(controls.replay.divergence)
            assertEquals(failure.position, controls.replay.position)
            assertTrue(controls.replay.snapshot(CheckoutDemo.cartId).isActive(CheckoutDemo.loading))

            controls.branchHere()
            val branch = checkNotNull(controls.branch)
            branch.answer(CheckoutDemo.answers.first(), branch.awaiting(CheckoutDemo.cartId).single().id)
            assertEquals(CartContext(20, 100, 80), branch.snapshot(CheckoutDemo.cartId).context)
            assertEquals(SummaryContext(80), branch.snapshot(CheckoutDemo.summaryId).context)
            assertEquals(checkpoints, controls.replay.members.associateWith { controls.replay.checkpoint(it) })
            controls.returnToReplay()
            assertNotNull(controls.replay.divergence)
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
        val parent = Files.createTempDirectory("koma-empty-demo-")
        try {
            val error = assertFailsWith<IllegalArgumentException> { CheckoutDemo.load(parent) }
            assertTrue(checkNotNull(error.message).contains("No checkout recording"))
        } finally {
            parent.toFile().deleteRecursively()
        }
    }
}

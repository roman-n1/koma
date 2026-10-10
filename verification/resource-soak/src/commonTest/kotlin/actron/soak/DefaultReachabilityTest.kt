package actron.soak

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DefaultReachabilityTest {
    @Test fun defaultDispatcherReleasesRealBatchGraphsWithRecordingOnAndOff() = runBlocking {
        repeat(4) { batch ->
            val witnesses = mutableListOf<NamedWitness>()
            val result = runBatch(100000 + batch, witnesses = witnesses, activeMillis = 300)
            assertEquals(batch % 2 == 0, result.recording)
            val prefix = "batch-${100000 + batch}:"
            val expected = buildSet {
                add(prefix + "owner"); add(prefix + "writerOwner")
                if (result.recording) add(prefix + "recording")
                repeat(16) { index ->
                    add(prefix + "store-$index"); add(prefix + "context-$index"); add(prefix + "payload-$index")
                }
            }
            assertEquals(if (result.recording) 51 else 50, witnesses.size)
            assertEquals(expected, witnesses.map { it.label }.toSet())
            val memory = SoakPlatform.afterGc()
            assertReleased(witnesses)
            println("DEFAULT_REACHABILITY batch=$batch recording=${result.recording} witnesses=${witnesses.size} memory=$memory")
        }
    }

    @Test fun retainedObjectIsDetectedByTheSameReachabilityCheck() = runBlocking {
        val held = ByteArray(4096)
        val witness = NamedWitness("intentionally-retained-payload", weakWitness(held))
        SoakPlatform.afterGc()
        assertFailsWith<IllegalStateException> { assertReleased(listOf(witness)) }
        assertEquals(4096, held.size) // Keep a strong reference live across the collection/check.
    }
}

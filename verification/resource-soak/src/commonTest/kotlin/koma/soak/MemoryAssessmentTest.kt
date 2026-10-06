package koma.soak

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryAssessmentTest {
    private val mib = 1024L * 1024

    @Test fun stableRecordingModesWithDifferentLevelsDoNotLookLikeCumulativeGrowth() {
        // An odd mixed-mode window starts with more OFF samples and ends with more ON samples.
        // Its medians differ by 32 MiB even though neither workload accumulates memory.
        val samples = List(34) { index -> sample(index, index % 2 != 0, if (index % 2 != 0) 40 * mib else 8 * mib) }
        val growth = assertNotNull(memoryGrowthByMode(samples))
        assertEquals(MemoryGrowth(0, 0, 0, 0), growth.getValue("recording-on"))
        assertEquals(MemoryGrowth(0, 0, 0, 0), growth.getValue("recording-off"))
    }

    @Test fun aLeakInOneRecordingModeStillExceedsTheUnchangedBudget() {
        val samples = (0 until 9).flatMap { index ->
            listOf(sample(index * 2, false, 8 * mib), sample(index * 2 + 1, true, (40 + if (index >= 5) 12 else 0) * mib))
        }
        val growth = assertNotNull(memoryGrowthByMode(samples))
        assertEquals(0L, growth.getValue("recording-off").heapBytes)
        assertEquals(12 * mib, growth.getValue("recording-on").heapBytes)
        assertTrue(growth.getValue("recording-on").heapBytes > 8 * mib)
    }

    @Test fun oneOnAndOneOffSampleCannotEstablishAMemoryPlateau() {
        assertNull(memoryGrowthByMode(listOf(sample(0, false, 8 * mib), sample(1, true, 17 * mib))))
        assertNull(memoryGrowthByMode(List(17) { sample(it, it % 2 == 0, 8 * mib) }))
        assertNull(memoryGrowthByMode(List(18) { sample(it, false, 8 * mib) }))
    }

    private fun sample(index: Int, recording: Boolean, heap: Long) = Sample(
        startedMillis = index * 1000L, elapsedMillis = index * 1000L + 900,
        result = BatchResult(index, recording, 100, 0, 10, 20, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0),
        memory = MemorySample(heap, heap, heap, 20),
    )
}

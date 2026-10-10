package actron.soak

import kotlinx.serialization.Serializable

@Serializable
data class MemoryGrowth(val heapBytes: Long, val nativeBytes: Long, val residentBytes: Long, val threads: Long)

internal const val MINIMUM_PLATEAU_SAMPLES_PER_MODE = 9

/** Compare like workloads. An on/off level difference is not cumulative memory growth. */
internal fun memoryGrowthByMode(samples: List<Sample>): Map<String, MemoryGrowth>? {
    val groups = listOf(false, true).associateWith { recording -> samples.filter { it.result.recording == recording } }
    if (groups.values.any { it.size < MINIMUM_PLATEAU_SAMPLES_PER_MODE }) return null
    return groups.mapKeys { if (it.key) "recording-on" else "recording-off" }.mapValues { (_, group) ->
        val window = group.size / 3
        fun delta(select: (MemorySample) -> Long): Long {
            fun median(values: List<Long>) = values.sorted()[values.size / 2]
            return median(group.takeLast(window).map { select(it.memory) }) - median(group.take(window).map { select(it.memory) })
        }
        MemoryGrowth(delta { it.heapBytes }, delta { it.nativeBytes }, delta { it.residentBytes }, delta { it.threads.toLong() })
    }
}

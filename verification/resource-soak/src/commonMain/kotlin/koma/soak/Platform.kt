package koma.soak

import kotlinx.serialization.Serializable

@Serializable
data class MemorySample(val heapBytes: Long, val nativeBytes: Long, val residentBytes: Long, val threads: Int)

expect object SoakPlatform {
    var reportDirectory: String
    fun metadata(): Map<String, String>
    suspend fun afterGc(): MemorySample
    fun blockingDelay(milliseconds: Long)
    fun directory(batch: Int): String
    fun removeDirectory(path: String)
    fun writeReport(name: String, content: String)
}

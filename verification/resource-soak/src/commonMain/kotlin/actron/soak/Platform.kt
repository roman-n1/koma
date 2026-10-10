package actron.soak

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

internal interface WeakWitness { val isAlive: Boolean }
internal expect fun weakWitness(value: Any): WeakWitness
internal data class NamedWitness(val label: String, val witness: WeakWitness)
internal fun assertReleased(witnesses: List<NamedWitness>) {
    val retained = witnesses.filter { it.witness.isAlive }.map { it.label }
    check(retained.isEmpty()) { "Objects remain reachable after batch teardown and GC: $retained" }
}

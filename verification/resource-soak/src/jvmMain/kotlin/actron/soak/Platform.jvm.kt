package actron.soak

import java.io.File
import kotlinx.coroutines.delay

actual object SoakPlatform {
    actual var reportDirectory = "$SOAK_REPORT_DIRECTORY/jvm"
    actual fun metadata() = mapOf("platform" to "JVM smoke", "runtime" to System.getProperty("java.runtime.version"),
        "os" to System.getProperty("os.name"), "architecture" to System.getProperty("os.arch"), "processors" to Runtime.getRuntime().availableProcessors().toString())
    actual suspend fun afterGc(): MemorySample {
        repeat(2) { System.gc(); delay(50) }
        val runtime = Runtime.getRuntime()
        return MemorySample(runtime.totalMemory() - runtime.freeMemory(), 0, 0, Thread.getAllStackTraces().size)
    }
    actual fun blockingDelay(milliseconds: Long) = Thread.sleep(milliseconds)
    actual fun directory(batch: Int): String = File(reportDirectory, "batch-$batch").apply { mkdirs() }.absolutePath
    actual fun removeDirectory(path: String) { File(path).deleteRecursively() }
    actual fun writeReport(name: String, content: String) { File(reportDirectory).mkdirs(); File(reportDirectory, name).writeText(content) }
}

internal actual fun weakWitness(value: Any): WeakWitness {
    val reference = java.lang.ref.WeakReference(value)
    return object : WeakWitness { override val isAlive get() = reference.get() != null }
}

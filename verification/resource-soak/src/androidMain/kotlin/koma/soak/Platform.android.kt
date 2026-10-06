package koma.soak

import android.os.Build
import android.os.Debug
import java.io.File
import kotlinx.coroutines.delay

actual object SoakPlatform {
    actual var reportDirectory = SOAK_REPORT_DIRECTORY
    actual fun metadata() = mapOf("platform" to "Android", "runtime" to System.getProperty("java.vm.version").orEmpty(),
        "os" to "API ${Build.VERSION.SDK_INT}", "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "architecture" to Build.SUPPORTED_ABIS.joinToString(), "processors" to Runtime.getRuntime().availableProcessors().toString(),
        "memoryUnits" to "bytes; resident=Debug.MemoryInfo.totalPss*1024")
    actual suspend fun afterGc(): MemorySample {
        repeat(2) { System.gc(); System.runFinalization(); delay(100) }
        val runtime = Runtime.getRuntime()
        val info = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        return MemorySample(runtime.totalMemory() - runtime.freeMemory(), Debug.getNativeHeapAllocatedSize(), info.totalPss.toLong() * 1024,
            File("/proc/self/task").list()?.size ?: -1)
    }
    actual fun blockingDelay(milliseconds: Long) = Thread.sleep(milliseconds)
    actual fun directory(batch: Int): String = File(reportDirectory, "batch-$batch").apply { mkdirs() }.absolutePath
    actual fun removeDirectory(path: String) { File(path).deleteRecursively() }
    actual fun writeReport(name: String, content: String) { File(reportDirectory).mkdirs(); File(reportDirectory, name).writeText(content) }
}

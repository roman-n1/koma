@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.native.runtime.NativeRuntimeApi::class, kotlin.ExperimentalStdlibApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package actron.soak

import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.cinterop.get
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.rawValue
import kotlinx.coroutines.delay
import kotlin.native.runtime.GC
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.darwin.MACH_TASK_BASIC_INFO
import platform.darwin.MACH_TASK_BASIC_INFO_COUNT
import platform.darwin.mach_msg_type_number_tVar
import platform.darwin.mach_task_basic_info
import platform.darwin.mach_task_self_
import platform.darwin.task_info
import platform.darwin.task_threads
import platform.darwin.thread_act_array_tVar
import platform.darwin.thread_tVar
import platform.darwin.mach_port_deallocate
import platform.darwin.vm_deallocate
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.usleep

actual object SoakPlatform {
    actual var reportDirectory = "$SOAK_REPORT_DIRECTORY/ios"
    actual fun metadata() = mapOf("platform" to "iOS", "runtime" to "Kotlin/Native ${KotlinVersion.CURRENT}", "os" to NSProcessInfo.processInfo.operatingSystemVersionString,
        "device" to NSProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"].toString(),
        "architecture" to kotlin.native.Platform.cpuArchitecture.name, "debugBinary" to kotlin.native.Platform.isDebugBinary.toString(),
        "processors" to NSProcessInfo.processInfo.processorCount.toString(), "memoryUnits" to "bytes; heap/native=GCInfo custom allocator heap bytes; resident=Mach resident_size")
    actual suspend fun afterGc(): MemorySample {
        repeat(2) { GC.collect(); delay(100) }
        val heap = checkNotNull(GC.lastGCInfo).memoryUsageAfter.getValue("heap").totalObjectsSizeBytes
        val resident = memScoped {
            val info = alloc<mach_task_basic_info>()
            val count = alloc<mach_msg_type_number_tVar>()
            count.value = MACH_TASK_BASIC_INFO_COUNT.convert()
            check(task_info(mach_task_self_, MACH_TASK_BASIC_INFO.convert(), info.ptr.reinterpret(), count.ptr) == 0)
            info.resident_size.toLong()
        }
        val threads = memScoped {
            val list = alloc<thread_act_array_tVar>()
            val count = alloc<mach_msg_type_number_tVar>()
            check(task_threads(mach_task_self_, list.ptr, count.ptr) == 0)
            val size = count.value.toInt()
            val array = checkNotNull(list.value)
            repeat(size) { check(mach_port_deallocate(mach_task_self_, array[it]) == 0) }
            check(vm_deallocate(mach_task_self_, array.rawValue.toLong().convert(), (size * sizeOf<thread_tVar>()).convert()) == 0)
            size
        }
        return MemorySample(heap, heap, resident, threads)
    }
    actual fun blockingDelay(milliseconds: Long) { usleep((milliseconds * 1000).convert()) }
    private fun mkdir(path: String) { check(NSFileManager.defaultManager.createDirectoryAtPath(path, true, null, null)) }
    actual fun directory(batch: Int): String = "$reportDirectory/batch-$batch".also(::mkdir)
    actual fun removeDirectory(path: String) { check(NSFileManager.defaultManager.removeItemAtPath(path, null)) }
    actual fun writeReport(name: String, content: String) {
        mkdir(reportDirectory)
        val output = checkNotNull(fopen("$reportDirectory/$name", "wb"))
        try {
            val bytes = content.encodeToByteArray()
            bytes.usePinned { check(fwrite(it.addressOf(0), 1u, bytes.size.convert(), output).toLong() == bytes.size.toLong()) }
        } finally { check(fclose(output) == 0) }
    }
}

internal actual fun weakWitness(value: Any): WeakWitness {
    val reference = kotlin.native.ref.WeakReference(value)
    return object : WeakWitness { override val isAlive get() = reference.get() != null }
}

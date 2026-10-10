@file:OptIn(actron.core.ExperimentalActronApi::class)

package actron.timetravel.file

import actron.timetravel.verify

import actron.core.InputAttribution

import java.io.File
import java.io.IOException
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import actron.core.Action
import actron.core.Event
import actron.core.InputId
import actron.observability.*
import actron.observability.file.*
import actron.statechart.*
import actron.statechart.machine.*
import actron.timetravel.GroupReplaySession
import actron.timetravel.RecordingCodec
import actron.timetravel.ReplaySession
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

/** Real process termination, not damage synthesized by truncating an already closed file.
 * Counter: [*] --> Idle; Idle --Add--> Idle (context += 1).
 */
class RecordingDurabilityJvmTest {
    @Test fun journalRecoversAfterWriterIsKilled() = crashCases("journal")
    @Test fun memberRecordingRecoversAndReplaysAfterWriterIsKilled() = crashCases("member")
    @Test fun groupOrderRecoversAndReplaysAfterWriterIsKilled() = crashCases("group")

    @Test fun fullFilesystemStopsAllWritersAndPreservesTheirFlushedPrefix() {
        // Only the dedicated Linux CI supplies a small, isolated mount. Never fill a developer's disk.
        val mount = System.getenv("ACTRON_ENOSPC_DIRECTORY") ?: return
        val root = File(mount)
        require(File(root, ".actron-bounded-filesystem").isFile)
        val capacity = Files.getFileStore(root.toPath()).totalSpace
        require(capacity in 1..(16L * 1024 * 1024)) { "Refusing to fill a filesystem of $capacity bytes" }
        for (kind in listOf("journal", "member", "group")) {
            val directory = Files.createTempDirectory(root.toPath(), "$kind-").toFile()
            val control = Files.createTempDirectory("actron-enospc-control-").toFile()
            try {
                runChild("full", kind, "full", directory, control)
                runChild("read", kind, "full", directory, control)
            } finally {
                directory.deleteRecursively()
                control.deleteRecursively()
            }
        }
    }

    private fun crashCases(kind: String) {
        for (cut in listOf("buffered", "flushed", "frame", "rotation-header", "rotation-end")) {
            val root = Files.createTempDirectory("actron-crash-$kind-").toFile()
            val directory = File(root, "segments").apply { mkdir() }
            val control = File(root, "control").apply { mkdir() }
            val child = startChild("write", kind, cut, directory, control)
            try {
                val ready = File(control, "ready")
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                while (!ready.exists() && child.isAlive && System.nanoTime() < deadline) Thread.sleep(10)
                assertTrue(ready.exists(), "Writer did not reach $kind/$cut: ${File(control, "write.log").readText()}")
                child.destroyForcibly()
                assertTrue(child.waitFor(10, TimeUnit.SECONDS), "Killed writer did not exit")
                assertNotEquals(0, child.exitValue(), "Writer must be killed, without running finally/close")
                runChild("read", kind, cut, directory, control)
            } finally {
                if (child.isAlive) child.destroyForcibly().waitFor()
                root.deleteRecursively()
            }
        }
    }

    private fun runChild(phase: String, kind: String, cut: String, directory: File, control: File) {
        val child = startChild(phase, kind, cut, directory, control)
        try {
            assertTrue(child.waitFor(25, TimeUnit.SECONDS), "$phase child timed out")
            val output = File(control, "$phase.log").readText()
            assertEquals(0, child.exitValue(), "$kind/$cut $phase: $output")
            assertTrue(output.contains("OK $phase"), output)
        } finally {
            if (child.isAlive) child.destroyForcibly().waitFor()
        }
    }

    private fun startChild(phase: String, kind: String, cut: String, directory: File, control: File): Process {
        val paths = generateSequence(javaClass.classLoader) { it.parent }.filterIsInstance<URLClassLoader>()
            .flatMap { it.urLs.asSequence() }.map { File(it.toURI()).path }.toList()
        val classpath = (paths + System.getProperty("java.class.path").split(File.pathSeparator)).distinct()
        return ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path,
            "-cp", classpath.joinToString(File.pathSeparator), RecordingDurabilityProcess::class.java.name,
            phase, kind, cut, directory.path, control.path)
            .redirectErrorStream(true).redirectOutput(File(control, "$phase.log")).start()
    }
}

/** A separately launched writer/reader. Each reader has no live writer or in-memory recording. */
object RecordingDurabilityProcess {
    @Serializable data class Add(val payload: String = "") : Action
    @Serializable data object NoEvent : Event
    private val member = StoreInstanceId("counter")
    private val groupId = MachineGroupId("group")
    private val session = RuntimeSessionId("session")
    private val idle = StateId("Idle")
    private val machine = Machine<Int, Add, Unit, NoEvent>(DefinitionId("counter"), DefinitionVersion("1"),
        StateChartDefinition(idle, listOf(AtomicState(idle)), emptyList())) {
        onAction(idle, ActionMatcher.of<Add>("Add")) { context++ }
    }
    private val codec = RecordingCodec(Int.serializer(), Add.serializer(), Unit.serializer(), NoEvent.serializer())

    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val (phase, kind, cut, path, controlPath) = args.toList()
        val directory = File(path)
        val control = File(controlPath)
        when (phase) {
            "write" -> write(kind, cut, directory, control)
            "read" -> read(kind, cut, directory)
            "full" -> full(kind, directory, control)
            else -> error(phase)
        }
        println("OK $phase")
    }

    private fun journalRecord(seq: Long) = JournalRecord(JOURNAL_FORMAT_VERSION, session, groupId, member,
        ExecutionMode.Live, GroupSeq(seq), StoreSeq(seq), seq.milliseconds, JournalEntry.ProcessingStarted(InputId(seq), seq))

    /** Intercepts actual filesystem writes at a deterministic byte boundary; never fabricates file contents. */
    private class GateStorage(private val directory: File, private val control: File, private val kind: String, private val cut: String) : SegmentStorage {
        private val actual = FileSegmentStorage(directory.path)
        override fun list() = actual.list()
        override fun read(name: String) = actual.read(name)
        override fun delete(name: String) = actual.delete(name)
        private val extension = when (kind) { "journal" -> ".journal"; "member" -> ".recording"; else -> ".group" }
        private fun stopWhenMemberFlushed(): Nothing {
            if (kind == "group" && cut != "buffered") {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while ((RecordingFiles(actual).read(member, codec).recording?.length ?: 0) < 2) {
                    check(System.nanoTime() < deadline) { "Member writer did not flush its first step" }
                    Thread.sleep(5)
                }
            }
            stop(control)
        }
        override fun append(name: String): SegmentOutput {
            val output = actual.append(name)
            var writes = 0
            var frames = 0
            val second = name.contains("-000001.")
            return object : SegmentOutput {
                override fun write(bytes: ByteArray) {
                    writes++
                    val frame = writes > (if (kind == "member") 2 else 1) && !bytes.contentEquals(Framing.END)
                    if (frame) frames++
                    if (name.endsWith(extension)) {
                        val partial = (cut == "frame" && frame && frames == 2) ||
                            (cut == "rotation-header" && second && writes == 1) ||
                            (cut == "rotation-end" && bytes.contentEquals(Framing.END))
                        if (partial) {
                            output.write(bytes.copyOf(if (cut == "rotation-end") 3 else 9))
                            output.flush()
                            stopWhenMemberFlushed()
                        }
                    }
                    output.write(bytes)
                    if (name.endsWith(extension) && cut == "buffered" && frame && frames == 1) stopWhenMemberFlushed()
                }
                override fun flush() {
                    output.flush()
                    if (name.endsWith(extension) && cut == "flushed" && frames == 1) stopWhenMemberFlushed()
                }
                override fun close() = output.close()
            }
        }
    }

    private fun stop(control: File): Nothing {
        File(control, "ready").writeText("ready")
        while (true) Thread.sleep(1000)
    }

    private suspend fun write(kind: String, cut: String, directory: File, control: File) {
        val storage = GateStorage(directory, control, kind, cut)
        val rotating = cut.startsWith("rotation")
        if (kind == "journal") {
            val sink = JournalFileSink(storage, JournalFileConfig(maxSegmentBytes = if (rotating) 1 else 1_000_000,
                flushEveryRecords = if (cut == "buffered") 100 else 1))
            sink.write(journalRecord(1))
            sink.write(journalRecord(2))
            error("Gate was not reached")
        }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val config = RecordingFileConfig(maxSegmentBytes = if (rotating) 1 else 1_000_000,
            flushEveryFrames = if (cut == "buffered") 100 else 1)
        val observer = if (kind == "member") RecordingFileSink(member, machine, 0, codec, storage, scope, config)
        else GroupRecordingFileSink(MachineGroup(), groupId, storage, scope, config).member(member, machine, 0, codec)
        val start = MachineInput.Start(MachineTime(0.milliseconds))
        val first = machine.decide(machine.initialSnapshot(0), start)
        observer.onCommitted(InputAttribution.Unattributed, start, first)
        // The group member must also flush before its order is interrupted; the second input is queued after that.
        if (kind == "group" && cut != "buffered") {
            val memberFile = File(directory, RecordingFileFormat.segmentName(member, 0))
            withTimeout(10_000) { while (!memberFile.exists() || memberFile.length() == 0L) delay(5) }
        }
        val add = MachineInput.Dispatch(Add(), MachineTime(1.milliseconds))
        observer.onCommitted(InputAttribution.Unattributed, add, machine.decide(first.snapshot, add))
        awaitCancellation()
    }

    private fun read(kind: String, cut: String, directory: File) {
        val storage = FileSegmentStorage(directory.path)
        val expected = if (cut == "buffered") 0 else 1
        val damaged: List<SegmentMark>
        when (kind) {
            "journal" -> {
                val contents = JournalFiles(storage).read(session)
                assertEquals((1L..expected.toLong()).toList(), contents.records.map { it.groupSeq.value })
                damaged = contents.marks
            }
            "member" -> {
                val contents = RecordingFiles(storage).read(member, codec)
                assertEquals(expected, contents.recording?.length ?: 0)
                contents.recording?.let { assertNull(ReplaySession(machine, it).verify()) }
                damaged = contents.marks.filterIsInstance<RecordingFileMark.Damaged>().map { it.mark }
            }
            else -> {
                val contents = GroupRecordingFiles(storage).read(groupId, mapOf(member to codec))
                assertEquals(expected, contents.recording?.order?.size ?: 0)
                contents.recording?.let { assertTrue(GroupReplaySession(mapOf(member to machine), it).verify().isEmpty()) }
                damaged = contents.marks.filterIsInstance<RecordingFileMark.Damaged>().map { it.mark }
            }
        }
        if (cut == "frame" || cut.startsWith("rotation")) {
            assertTrue(damaged.any { it is SegmentMark.TruncatedTail }, damaged.toString())
        } else if (cut != "buffered" && cut != "full") {
            assertTrue(damaged.any { it is SegmentMark.Unfinished }, damaged.toString())
        }
        if (cut == "full") assertTrue(damaged.isNotEmpty(), "Failed writes must leave a marked tail")
        assertTrue(damaged.none { it is SegmentMark.Corrupt }, damaged.toString())
    }

    private suspend fun full(kind: String, directory: File, control: File) {
        val storage = FileSegmentStorage(directory.path)
        val failures = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val config = RecordingFileConfig(flushEveryFrames = 1, onFailure = { failures += it })
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val journal = if (kind == "journal") JournalFileSink(storage, JournalFileConfig(flushEveryRecords = 1)) else null
        val sink = if (kind == "member") RecordingFileSink(member, machine, 0, codec, storage, scope, config) else null
        val group = if (kind == "group") GroupRecordingFileSink(MachineGroup(), groupId, storage, scope, config) else null
        val observer = sink ?: group?.member(member, machine, 0, codec)
        val start = MachineInput.Start(MachineTime(0.milliseconds))
        val decision = machine.decide(machine.initialSnapshot(0), start)
        journal?.write(journalRecord(1)) ?: observer!!.onCommitted(InputAttribution.Unattributed, start, decision)
        // Wait for the actual frames to reach the filesystem before exhausting it.
        withTimeout(10_000) {
            while (storage.list().size < (if (kind == "group") 2 else 1) || storage.list().any { it.size == 0L }) delay(5)
        }
        val filler = File(directory, "filler")
        try {
            filler.outputStream().use { output -> repeat(4096) { output.write(ByteArray(4096)) } }
            error("The bounded filesystem did not return ENOSPC")
        } catch (failure: IOException) {
            assertTrue(failure.message.orEmpty().contains("No space left on device"), failure.toString())
        }
        assertEquals(0L, Files.getFileStore(directory.toPath()).usableSpace)
        if (journal != null) {
            val failure = assertFailsWith<IOException> { journal.write(journalRecord(2).copy(entry = JournalEntry.FailureReported(InputId(2), FailureDescriptor("disk-full", "x".repeat(32_768))))); journal.flush() }
            assertTrue(failure.message.orEmpty().contains("No space left on device"))
            journal.write(journalRecord(3))
            journal.close()
        } else {
            val input = MachineInput.Dispatch(Add("x".repeat(32_768)), MachineTime(1.milliseconds))
            var snapshot = decision.snapshot
            // A short order frame can still fit the already allocated last filesystem page
            // even with usableSpace == 0. Drive it across that page boundary, not a fake quota.
            repeat(if (kind == "group") 300 else 1) {
                val next = machine.decide(snapshot, input)
                observer!!.onCommitted(InputAttribution.Unattributed, input, next)
                snapshot = next.snapshot
            }
            withTimeout(10_000) { while (failures.size < (if (kind == "group") 2 else 1)) delay(5) }
            sink?.close() ?: group!!.close()
            assertEquals(if (kind == "group") 2 else 1, failures.size)
            assertTrue(failures.all { it is IOException && it.message.orEmpty().contains("No space left on device") }, failures.toString())
        }
        // Retain ENOSPC artifacts until a fresh reader process has checked the prefix.
        scope.cancel()
        File(control, "full").writeText("ENOSPC confirmed")
    }
}

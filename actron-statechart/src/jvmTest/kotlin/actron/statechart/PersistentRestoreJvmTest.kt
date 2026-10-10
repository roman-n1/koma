@file:OptIn(actron.core.ExperimentalActronApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package actron.statechart

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import actron.core.Action
import actron.core.Event
import actron.core.ExceptionHandler
import actron.core.State
import actron.core.StateSaver
import actron.statechart.machine.ActivationId
import actron.statechart.machine.CommandHandler
import actron.statechart.machine.CommandId
import actron.statechart.machine.CommandRecord
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.LaneId
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineClock
import actron.statechart.machine.MachineCounters
import actron.statechart.machine.MachineSnapshot
import actron.statechart.machine.MachineStore
import actron.statechart.machine.MachineTime
import actron.statechart.machine.TimerId
import actron.statechart.machine.TimerRecord
import actron.statechart.machine.TransitionId
import actron.test.dispatchAndAwait
import actron.test.startAndAwait
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Persistence integration, with writer and reader in different JVMs. No Store, coroutine,
 * saver, virtual clock or singleton can survive between the phases; only the file survives.
 *
 * Chart: Idle --Begin / remember(42)--> Waiting --after 5s--> Done.
 * Machine: Idle --Begin / remember(42)--> Waiting(command + timer).
 * A new MachineStore starts again at Idle (or Ready in version 2), retaining context only.
 */
class PersistentRestoreJvmTest {
    @Test
    fun chartRestoresFromAnotherProcessAndRestartsActivitiesAndTheFullTimerDelay() = withFile { file ->
        process("chart-write", file)
        assertTrue(file.length() > 0)
        process("chart-read", file)
    }

    @Test
    fun chartDefinitionUpdateFallsBackToNewInitialNodesAndKeepsPersistedContext() = withFile { file ->
        process("chart-write", file)
        process("chart-upgrade", file)
    }

    @Test
    fun machineRestoresContextWithoutResumingOldCommandsOrTimerDeadlines() = withFile { file ->
        process("machine-write", file)
        process("machine-read", file)
    }

    @Test
    fun machineDefinitionUpdateUsesNewVersionInitialNodesAndStartupCommands() = withFile { file ->
        process("machine-write", file)
        process("machine-upgrade", file)
    }

    @Test
    fun chartRestoresTheLastCommitAfterProcessDeathWithoutStoreCleanup() = withFile { file ->
        process("chart-write-abrupt", file)
        process("chart-read", file)
    }

    @Test
    fun machineRestartsFromSavedContextAfterProcessDeathWithPendingWork() = withFile { file ->
        process("machine-write-abrupt", file)
        process("machine-read", file)
    }

    @Test
    fun truncatedSavedStateReportsTheDecodeFailureAndStartsFresh() = withFile { file ->
        process("chart-write", file)
        file.writeBytes(file.readBytes().take(7).toByteArray())
        process("chart-invalid", file)
    }

    @Test
    fun unsupportedApplicationSchemaReportsTheFailureAndStartsFresh() = withFile { file ->
        DataOutputStream(file.outputStream()).use { it.writeInt(99) }
        process("chart-invalid", file)
    }

    @Test
    fun invalidMachinePersistenceReportsTheFailureAndStartsFresh() = withFile { file ->
        file.writeText("not a snapshot")
        process("machine-invalid", file)
    }

    @Test
    fun absentSavedFileUsesTheDeclaredInitialState() = withFile { file ->
        process("chart-fresh", file)
        file.delete()
        process("machine-fresh", file)
    }

    private fun withFile(test: (File) -> Unit) {
        val directory = Files.createTempDirectory("actron-restore-").toFile()
        try {
            test(File(directory, "state.bin"))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun process(phase: String, file: File) {
        // Support both an ordinary JVM runtime and Gradle workers with a separate test loader.
        val loaderPaths = generateSequence(javaClass.classLoader) { it.parent }
            .filterIsInstance<URLClassLoader>().flatMap { it.urLs.asSequence() }
            .map { File(it.toURI()).path }.toList()
        val classpath = (loaderPaths + System.getProperty("java.class.path").split(File.pathSeparator)).distinct()
        val output = File(file.parentFile, "$phase.log")
        val child = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").path,
            "-cp", classpath.joinToString(File.pathSeparator),
            PersistentRestoreProcess::class.java.name, phase, file.path,
        ).redirectErrorStream(true).redirectOutput(output).start()
        try {
            assertTrue(child.waitFor(30, TimeUnit.SECONDS), "Child timed out: $phase\n${output.readText()}")
            assertEquals(0, child.exitValue(), "Child failed: $phase\n${output.readText()}")
            assertTrue(output.readText().contains("OK $phase"), "Child did not complete its assertions")
        } finally {
            if (child.isAlive) child.destroyForcibly().waitFor()
        }
    }
}

/** Executed by the child JVM; assertions fail the child exit code. */
object PersistentRestoreProcess {
    private data object Begin : Action
    private data object UnusedEvent : Event
    private val idle = StateId("Idle")
    private val waiting = StateId("Waiting")
    private val done = StateId("Done")
    private val ready = StateId("Ready")

    @JvmStatic
    fun main(args: Array<String>) {
        val phase = args[0]
        val file = File(args[1])
        if (phase.startsWith("chart")) chart(phase, file) else machine(phase, file)
        println("OK $phase")
    }

    private fun chart(phase: String, file: File) = runTest {
        val upgraded = phase == "chart-upgrade"
        val initial = if (upgraded) ready else idle
        val definition = if (upgraded) {
            StateChartDefinition(ready, listOf(AtomicState(ready)), emptyList())
        } else {
            StateChartDefinition(idle, listOf(AtomicState(idle), AtomicState(waiting), AtomicState(done)), listOf(
                Transition(idle, waiting, ActionMatcher.of<Begin>("begin"), effect = actron.statechart.EffectKey("remember")),
                Transition(waiting, done, Trigger.After(5.seconds)),
            ))
        }
        val enters = mutableListOf<StateId>()
        var activities = 0
        var launches = 0
        val failures = mutableListOf<Throwable>()
        val store = StateChartStore<Int, Begin, UnusedEvent>(definition, 0, StandardTestDispatcher(testScheduler)) {
            if (!upgraded) {
                effect("remember") { _, _ -> 42 }
                onEnter(waiting) {
                    enters += waiting
                    launch { launches++; awaitCancellation() }
                }
                activity(waiting) { activities++; awaitCancellation() }
            }
            onEnter(initial) { enters += initial }
            store {
                stateSaver(DiskSaver(file, DataOutputStream::chartState, DataInputStream::chartState))
                exceptionHandler(ExceptionHandler { failures += it })
            }
        }
        try {
            store.startAndAwait()
            runCurrent()
            when (phase) {
                "chart-write", "chart-write-abrupt" -> {
                    store.dispatchAndAwait(Begin)
                    runCurrent()
                    advanceTimeBy(3.seconds)
                    runCurrent()
                    assertTrue(store.currentState.isActive(waiting))
                    assertEquals(42, store.currentState.context)
                    assertEquals(1, activities)
                    assertEquals(1, launches)
                    assertTrue(store.currentState.timers.running.isNotEmpty())
                    assertEquals(store.currentState, DiskSaver(file, DataOutputStream::chartState, DataInputStream::chartState).restore(store.currentState))
                    if (phase.endsWith("-abrupt")) haltAfterCommit(phase)
                }
                "chart-read" -> {
                    assertEquals(42, store.currentState.context)
                    assertTrue(store.currentState.isActive(waiting))
                    assertTrue(enters.isEmpty(), "Restored nodes do not re-run enter hooks")
                    assertEquals(0, launches, "Enter-launched work is not resumed")
                    assertEquals(1, activities, "Declared activity starts in the new runtime")
                    advanceTimeBy(2.seconds)
                    runCurrent()
                    assertTrue(store.currentState.isActive(waiting), "Old remaining delay is not restored")
                    advanceTimeBy(3.seconds - 1.milliseconds)
                    runCurrent()
                    assertTrue(store.currentState.isActive(waiting))
                    advanceTimeBy(1.milliseconds)
                    runCurrent()
                    assertTrue(store.currentState.isActive(done), "Timer restarts with full delay")
                }
                "chart-upgrade" -> {
                    assertTrue(store.currentState.isActive(ready))
                    assertEquals(42, store.currentState.context)
                    assertEquals(listOf(ready), enters, "Incompatible configuration enters new initial nodes")
                    assertTrue(store.currentState.timers.running.isEmpty())
                }
                "chart-invalid", "chart-fresh" -> {
                    assertTrue(store.currentState.isActive(idle))
                    assertEquals(0, store.currentState.context)
                    assertEquals(listOf(idle), enters)
                }
                else -> error("Unknown phase: $phase")
            }
            assertEquals(if (phase == "chart-invalid") 1 else 0, failures.size)
            // A decode failure cannot leave the store unusable: a later committed transition
            // replaces the bad file with a usable snapshot for the application's next launch.
            if (phase == "chart-invalid") {
                store.dispatchAndAwait(Begin)
                assertEquals(42, DiskSaver(file, DataOutputStream::chartState, DataInputStream::chartState).restore(ChartState(StateConfiguration(emptySet()), -1)).context)
            }
        } finally {
            store.close()
            runCurrent()
        }
    }

    private fun machine(phase: String, file: File) = runTest {
        val upgraded = phase == "machine-upgrade"
        val initial = if (upgraded) ready else idle
        val version = if (upgraded) "2" else "1"
        val definition = StateChartDefinition(initial, listOf(AtomicState(initial), AtomicState(waiting), AtomicState(done)), listOf(
            Transition(initial, waiting, ActionMatcher.of<Begin>("begin"), effect = actron.statechart.EffectKey("remember")),
            Transition(initial, done, Trigger.After(10.seconds)),
            Transition(waiting, done, Trigger.After(5.seconds)),
        ))
        val model = Machine<Int, Begin, String, UnusedEvent>(DefinitionId("persistent"), DefinitionVersion(version), definition) {
            effect("remember") { _, _ -> 42 }
            onEnter(initial) { command("startup-$version") }
            onEnter(waiting) { command("pending-fetch") }
        }
        val commands = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        val clock = object : MachineClock {
            override fun now() = MachineTime(testScheduler.currentTime.milliseconds)
            override suspend fun delayUntil(deadline: MachineTime) {
                val remaining = deadline - now()
                if (remaining.isPositive()) delay(remaining)
            }
        }
        val store = MachineStore(model, 0, CommandHandler<String, Begin> { envelope, _ ->
            commands += envelope.command
            awaitCancellation()
        }, scope, clock, dispatcher) {
            stateSaver(DiskSaver(file, DataOutputStream::machineState, DataInputStream::machineState))
            exceptionHandler(ExceptionHandler { failures += it })
        }
        try {
            store.startAndAwait()
            runCurrent()
            if (phase == "machine-write" || phase == "machine-write-abrupt") {
                store.dispatchAndAwait(Begin)
                runCurrent()
                advanceTimeBy(3.seconds)
                runCurrent()
                assertEquals(42, store.currentState.context)
                assertTrue(store.currentState.isActive(waiting))
                assertTrue(store.currentState.commands.isNotEmpty())
                assertTrue(store.currentState.timers.isNotEmpty())
                assertEquals(listOf("startup-1", "pending-fetch"), commands)
                assertEquals(store.currentState, DiskSaver(file, DataOutputStream::machineState, DataInputStream::machineState).restore(store.currentState))
                if (phase.endsWith("-abrupt")) haltAfterCommit(phase)
            } else {
                assertEquals(if (phase == "machine-invalid" || phase == "machine-fresh") 0 else 42, store.currentState.context)
                assertEquals(DefinitionVersion(version), store.currentState.version)
                assertEquals(1, store.currentState.revision)
                assertTrue(store.currentState.isActive(initial))
                assertEquals(listOf("startup-$version"), commands, "Only new initial commands run")
                assertEquals(10.seconds, store.currentState.timers.values.single().deadline.sinceStart)
                advanceTimeBy(5.seconds)
                runCurrent()
                assertTrue(store.currentState.isActive(initial), "Persisted Waiting timer is not restored")
                advanceTimeBy(5.seconds)
                runCurrent()
                assertTrue(store.currentState.isActive(done), "New initial timer runs on the new clock")
            }
            assertEquals(if (phase == "machine-invalid" || upgraded) 1 else 0, failures.size)
            if (upgraded) {
                val reported = assertIs<IllegalStateException>(failures.single())
                assertTrue(reported.message.orEmpty().contains("version 1"))
                assertTrue(reported.message.orEmpty().contains("version 2"))
            }
        } finally {
            store.close()
            runCurrent()
            scope.cancel()
        }
    }

    private fun haltAfterCommit(phase: String): Nothing {
        // Terminate this isolated child before Store.close(), coroutine cancellation, shutdown
        // hooks or finally blocks can run. The saver must already have persisted the commit.
        println("OK $phase")
        System.out.flush()
        Runtime.getRuntime().halt(0)
        error("Runtime.halt returned")
    }
}

/**
 * Application-owned test codec, deliberately independent of Time Travel. The library promises
 * StateSaver semantics; this fixture's schema version, encoding and error policy belong to its
 * consumer. It stores complete snapshots for these charts, including in-flight bookkeeping.
 */
private class DiskSaver<S : State>(
    private val file: File,
    private val encode: DataOutputStream.(S) -> Unit,
    private val decode: DataInputStream.() -> S,
) : StateSaver<S> {
    override fun save(state: S) {
        DataOutputStream(file.outputStream().buffered()).use { output ->
            output.writeInt(1)
            output.encode(state)
        }
    }

    override fun restore(initialState: S): S {
        if (!file.exists()) return initialState
        return DataInputStream(file.inputStream().buffered()).use { input ->
            require(input.readInt() == 1) { "Unsupported application state schema" }
            input.decode().also { require(input.read() == -1) { "Trailing application state bytes" } }
        }
    }
}

private fun DataOutputStream.configuration(value: StateConfiguration) {
    writeInt(value.active.size)
    value.active.forEach { writeUTF(it.value) }
    writeInt(value.history.size)
    value.history.forEach { (id, nodes) ->
        writeUTF(id.value)
        writeInt(nodes.size)
        nodes.forEach { writeUTF(it.value) }
    }
}

private fun DataInputStream.configuration(): StateConfiguration {
    val active = List(readInt()) { StateId(readUTF()) }.toSet()
    val history = List(readInt()) { StateId(readUTF()) to List(readInt()) { StateId(readUTF()) }.toSet() }.toMap()
    return StateConfiguration(active, history)
}

private fun DataOutputStream.chartState(value: ChartState<Int>) {
    configuration(value.configuration)
    writeInt(value.context)
    writeLong(value.timers.issued)
    writeInt(value.timers.running.size)
    value.timers.running.forEach { (index, token) -> writeInt(index); writeLong(token) }
}

private fun DataInputStream.chartState(): ChartState<Int> {
    val configuration = configuration()
    val context = readInt()
    val issued = readLong()
    val running = List(readInt()) { readInt() to readLong() }.toMap()
    return ChartState(configuration, context, ChartTimers(running, issued))
}

private fun DataOutputStream.machineState(value: MachineSnapshot<Int>) {
    writeUTF(value.definition.value)
    writeUTF(value.version.value)
    writeLong(value.revision)
    configuration(value.configuration)
    writeInt(value.context)
    writeInt(value.activations.size)
    value.activations.forEach { (id, activation) -> writeUTF(id.value); writeLong(activation.value) }
    writeInt(value.commands.size)
    value.commands.forEach { (id, record) ->
        writeLong(id.value)
        writeLong(record.scope.value)
        writeBoolean(record.lane is LaneId)
        (record.lane as? LaneId)?.let { writeUTF(it.value) }
    }
    writeInt(value.timers.size)
    value.timers.forEach { (id, record) ->
        writeLong(id.value)
        writeInt(record.transition.index)
        writeLong(record.activation.value)
        writeLong(record.deadline.sinceStart.inWholeMilliseconds)
    }
    with(value.counters) { writeLong(activations); writeLong(commands); writeLong(timers); writeLong(effects) }
}

private fun DataInputStream.machineState(): MachineSnapshot<Int> {
    val definition = DefinitionId(readUTF())
    val version = DefinitionVersion(readUTF())
    val revision = readLong()
    val configuration = configuration()
    val context = readInt()
    val activations = List(readInt()) { StateId(readUTF()) to ActivationId(readLong()) }.toMap()
    val commands = List(readInt()) {
        val id = CommandId(readLong())
        id to CommandRecord(ActivationId(readLong()), if (readBoolean()) LaneId(readUTF()) else actron.statechart.machine.CommandLane.Independent)
    }.toMap()
    val timers = List(readInt()) {
        TimerId(readLong()) to TimerRecord(TransitionId(readInt()), ActivationId(readLong()), MachineTime(readLong().milliseconds))
    }.toMap()
    val counters = MachineCounters(readLong(), readLong(), readLong(), readLong())
    return MachineSnapshot(definition, version, revision, configuration, context, activations, commands, timers, counters)
}

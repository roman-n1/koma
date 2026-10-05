@file:OptIn(koma.core.ExperimentalKomaApi::class)

package koma.statechart.machine

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import koma.core.Action
import koma.core.Event
import koma.core.Plugin
import koma.core.PluginScope
import koma.observability.StoreInstanceId
import koma.statechart.ActionMatcher
import koma.statechart.AtomicState
import koma.statechart.CompoundState
import koma.statechart.StateChartDefinition
import koma.statechart.StateId
import koma.test.startAndAwait
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Real TCP framing/reconnects with a source-owned cursor; no external network service.
 * ```
 * [*] --> Ready; Ready --> Ready: Frame / append accepted sequence
 * ```
 */
class SocketSourceIntegrationTest {
    private data class Frame(val sequence: Int) : Action
    private data object Ev : Event
    private data class Attempt(val sequence: Int, val admission: Admission)

    /**
     * A deliberately small adapter, not a socket transport shipped by the library. Stable server
     * sequence numbers survive reconnects. Blocking reads and admission retry waits never own
     * feedLock. The snapshot cursor advances only inside the successful admission critical section.
     */
    private class SocketSource(
        private val port: Int,
        private val store: MachineStore<List<Int>, Frame, Nothing, Ev>,
        private val scope: CoroutineScope,
    ) : ExternalSource {
        override val id = SourceId("socket:frames")
        override val kind = "socket"
        private val feedLock = Mutex()
        private val activeSocket = AtomicReference<Socket?>()
        val connected = Channel<Int>(Channel.UNLIMITED)
        val read = Channel<Int>(Channel.UNLIMITED)
        val admitted = Channel<Int>(Channel.UNLIMITED)
        val attempted = Channel<Attempt>(Channel.UNLIMITED)
        val retry = Channel<Unit>(Channel.UNLIMITED)
        val pauseEntered = Channel<Unit>(Channel.UNLIMITED)
        var pauseRelease: CompletableDeferred<Unit>? = null
        private var generation = 0
        private var lastAccepted = 0
        private val stopped = CompletableDeferred<Unit>()
        private val reader = scope.launch(Dispatchers.IO) {
            try {
                while (!stopped.isCompleted) {
                    val socket = Socket()
                    activeSocket.set(socket)
                    socket.use {
                        socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 5_000)
                        feedLock.withLock { generation++; connected.send(generation) }
                        val lines = socket.getInputStream().bufferedReader()
                        while (!stopped.isCompleted) {
                            val line = lines.readLine() ?: break
                            val sequence = line.toInt()
                            read.send(sequence)
                            while (!feedLock.withLock {
                                if (sequence <= lastAccepted) return@withLock true
                                val outcome = store.feed(id, Frame(sequence))
                                attempted.send(Attempt(sequence, outcome))
                                if (outcome == Admission.Accepted) {
                                    lastAccepted = sequence
                                    admitted.send(sequence)
                                    true
                                } else false
                            }) retry.receive()
                        }
                    }
                    activeSocket.compareAndSet(socket, null)
                }
            } catch (failure: Throwable) {
                if (!stopped.isCompleted) throw failure
            }
        }

        override suspend fun pause() {
            feedLock.lock()
            try {
                pauseEntered.send(Unit)
                pauseRelease?.await()
            } catch (failure: Throwable) {
                feedLock.unlock()
                throw failure
            }
        }
        override fun snapshot() = SourceSnapshot(id, kind, 1, mapOf("lastAccepted" to lastAccepted.toString(), "generation" to generation.toString()))
        override fun resume() = feedLock.unlock()
        suspend fun close() {
            stopped.complete(Unit)
            activeSocket.getAndSet(null)?.close() // Unblocks a real readLine or connect.
            reader.cancel()
            reader.join()
        }
    }

    private class Fixture(
        admission: AdmissionPolicy = AdmissionPolicy.Unbounded,
        plugin: Plugin<MachineSnapshot<List<Int>>, MachineInput<Frame>, Ev>? = null,
    ) {
        val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress()).apply { soTimeout = 5_000 }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val group = MachineGroup()
        val memberId = StoreInstanceId("socket-member")
        private val member = group.member<List<Int>, Frame, Nothing, Ev>(memberId)
        private val root = StateId("root")
        private val idle = StateId("idle")
        private val chart = StateChartDefinition(root, listOf(CompoundState(root, initial = idle), AtomicState(idle, parent = root)), emptyList())
        private val machine = Machine<List<Int>, Frame, Nothing, Ev>(DefinitionId("socket-integration"), DefinitionVersion("1"), chart) {
            onAction(root, ActionMatcher.of<Frame>("Frame")) { context = context + (action as Frame).sequence }
        }
        val store = MachineStore(machine, emptyList(), CommandHandler<Nothing, Frame> { _, _ -> }, scope,
            admission = admission, observers = listOf(member)) { plugin?.let { plugin(it) } }
        lateinit var source: SocketSource
        suspend fun start() {
            member.attach(store)
            store.startAndAwait()
            source = SocketSource(server.localPort, store, scope)
            group.source(source)
        }
        suspend fun accept(): Socket = withContext(Dispatchers.IO) { server.accept() }
        suspend fun close() {
            if (::source.isInitialized) source.close()
            server.close()
            store.close()
            scope.cancel()
        }
        fun assertCut(checkpoint: GroupCheckpoint, last: Int, generation: Int) {
            assertEquals((1..last).toList(), checkpoint.members.getValue(memberId).snapshot.context)
            assertEquals(last.toString(), checkpoint.sources.getValue(source.id).fields.getValue("lastAccepted"))
            assertEquals(generation.toString(), checkpoint.sources.getValue(source.id).fields.getValue("generation"))
        }
    }

    private fun Socket.send(vararg sequences: Int) {
        val output = getOutputStream().bufferedWriter()
        sequences.forEach { output.appendLine(it.toString()) }
        output.flush()
    }

    @Test
    fun reconnectRedeliveryDoesNotDuplicateAdmittedInputsAndTheCutRetainsTheCursor() = runBlocking {
        withTimeout(15_000) {
            val f = Fixture()
            try {
                f.start()
                f.accept().use { first ->
                    assertEquals(1, f.source.connected.receive())
                    first.send(1, 2)
                    assertEquals(1, f.source.admitted.receive())
                    assertEquals(2, f.source.admitted.receive())
                }
                f.accept().use { second ->
                    assertEquals(2, f.source.connected.receive())
                    second.send(2, 3, 4) // The server repeats its last unacknowledged frame.
                    assertEquals(3, f.source.admitted.receive())
                    assertEquals(4, f.source.admitted.receive())
                    f.assertCut(checkNotNull(f.group.checkpoint(5.seconds)), last = 4, generation = 2)
                }
            } finally { f.close() }
        }
    }

    @Test
    fun framesReadDuringACutWaitUntilItsSnapshotAndThenEnterExactlyOnce() = runBlocking {
        withTimeout(15_000) {
            val f = Fixture()
            try {
                f.start()
                f.accept().use { connection ->
                    f.source.connected.receive()
                    connection.send(1, 2)
                    assertEquals(1, f.source.admitted.receive())
                    assertEquals(2, f.source.admitted.receive())
                    f.source.read.receive()
                    f.source.read.receive()
                    val release = CompletableDeferred<Unit>()
                    f.source.pauseRelease = release
                    val cut = async(Dispatchers.Default) { f.group.checkpoint(5.seconds) }
                    f.source.pauseEntered.receive()
                    connection.send(3, 4)
                    assertEquals(3, f.source.read.receive(), "the real socket read completed while feeding was paused")
                    assertTrue(f.source.admitted.tryReceive().isFailure)
                    release.complete(Unit)
                    f.assertCut(checkNotNull(cut.await()), last = 2, generation = 1)
                    assertEquals(3, f.source.admitted.receive())
                    assertEquals(4, f.source.admitted.receive())
                    f.assertCut(checkNotNull(f.group.checkpoint(5.seconds)), last = 4, generation = 1)
                }
            } finally { f.close() }
        }
    }

    @Test
    fun aFullQueueLeavesTheSocketCursorUnchangedAndDoesNotBlockAPause() = runBlocking {
        withTimeout(15_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val blocker = object : Plugin<MachineSnapshot<List<Int>>, MachineInput<Frame>, Ev> {
                override suspend fun onState(scope: PluginScope<MachineSnapshot<List<Int>>, MachineInput<Frame>>, prevState: MachineSnapshot<List<Int>>, state: MachineSnapshot<List<Int>>) {
                    if (state.context == listOf(1)) { entered.complete(Unit); release.await() }
                }
            }
            val f = Fixture(AdmissionPolicy.Bounded(1), blocker)
            try {
                f.start()
                f.accept().use { connection ->
                    f.source.connected.receive()
                    connection.send(1)
                    entered.await()
                    assertEquals(1, f.source.admitted.receive())
                    assertEquals(Attempt(1, Admission.Accepted), f.source.attempted.receive())
                    connection.send(2, 3)
                    assertEquals(2, f.source.admitted.receive())
                    assertEquals(Attempt(2, Admission.Accepted), f.source.attempted.receive())
                    assertEquals(Attempt(3, Admission.Rejected(1, 1)), f.source.attempted.receive())
                    val cut = async(Dispatchers.Default) { f.group.checkpoint(5.seconds) }
                    f.source.pauseEntered.receive() // Retry waiting did not retain the feed lock.
                    release.complete(Unit)
                    f.assertCut(checkNotNull(cut.await()), last = 2, generation = 1)
                    f.source.retry.send(Unit)
                    assertEquals(3, f.source.admitted.receive())
                    f.assertCut(checkNotNull(f.group.checkpoint(5.seconds)), last = 3, generation = 1)
                }
            } finally {
                release.complete(Unit)
                f.close()
            }
        }
    }
}

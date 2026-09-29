package koma.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Soak tests for a long-lived Store on [Dispatchers.Default]: many threads dispatch for a while
 * through variant changes, recover handlers, cancelled launches and transactions. They check that
 * no update is lost, nothing deadlocks, close() stops everything, and that the Store's internal
 * bookkeeping (state runtimes, dispatch jobs, the dispatch-ordering chain) does not grow.
 * JVM only: the leak checks use reflection and weak references.
 */
@OptIn(InternalKomaApi::class)
class StoreSoakJvmTest {

    sealed interface S : State {
        val count: Int
        val query: String
        data class Idle(override val count: Int = 0, override val query: String = "", val results: Int = 0) : S
        data class Busy(override val count: Int = 0, override val query: String = "", val ticks: Int = 0) : S
        data class Failed(override val count: Int = 0, override val query: String = "") : S
    }

    sealed interface A : Action {
        data object Inc : A
        data object Go : A
        data object Finish : A
        data object Fail : A
        data object Recover : A
        data class Query(val q: String) : A
    }

    data object Ev : Event

    private fun soakStore(
        policy: PendingActionPolicy,
        handled: MutableList<Throwable>,
        saved: AtomicInteger,
        busyEnters: AtomicInteger,
        incOnIo: Boolean = false,
    ): Store<S, A, Ev> = Store(S.Idle()) {
        coroutineContext(Dispatchers.Default)
        pendingActionPolicy(policy)
        exceptionHandler(ExceptionHandler { synchronized(handled) { handled += it } })
        stateSaver(StateSaver(save = { saved.incrementAndGet() }, restore = { null }))
        plugin(Plugin(onState = { _, _ -> }, onAction = { _, _ -> }))
        state<S.Idle> {
            action<A.Go> { nextState { S.Busy(state.count, state.query) } }
            action<A.Fail> { throw IllegalStateException("fail") }
            recover<IllegalStateException> { nextState { S.Failed(state.count, state.query) } }
            action<A.Query> {
                nextState { state.copy(query = action.q) }
                launch(control = LaunchControl.CancelPrevious()) {
                    delay(1)
                    transaction { nextState { state.copy(results = state.results + 1) } }
                }
            }
        }
        state<S.Busy> {
            enter {
                busyEnters.incrementAndGet()
                launch {
                    while (isActive) {
                        transaction { nextState { state.copy(ticks = state.ticks + 1) } }
                        delay(1)
                    }
                }
            }
            action<A.Finish> {
                event(Ev)
                nextState { S.Idle(state.count, state.query) }
            }
        }
        state<S.Failed> {
            action<A.Recover> { nextState { S.Idle(state.count, state.query) } }
        }
        state<S> {
            action<A.Inc>(dispatcher = if (incOnIo) Dispatchers.IO else null) { nextState { withCount(state, state.count + 1) } }
            action<A> { } // ignore the rest in this variant
        }
    }

    private fun withCount(s: S, c: Int): S = when (s) {
        is S.Idle -> s.copy(count = c)
        is S.Busy -> s.copy(count = c)
        is S.Failed -> s.copy(count = c)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(store: Any, name: String): T {
        var cls: Class<*>? = store.javaClass
        while (cls != null) {
            try {
                val f = cls.getDeclaredField(name); f.isAccessible = true
                return f.get(store) as T
            } catch (_: NoSuchFieldException) { cls = cls.superclass }
        }
        error("no field $name")
    }

    @Test
    fun soak_keepPolicy_noLostIncrementsNoLeaksNoDeadlock() = runTest {
        val handled = mutableListOf<Throwable>()
        val saved = AtomicInteger()
        val busyEnters = AtomicInteger()
        val store = soakStore(PendingActionPolicy.Keep, handled, saved, busyEnters)
        val threads = 8
        val perThread = 2000
        val incs = AtomicInteger()
        val events = AtomicInteger()
        store.collectEvent { events.incrementAndGet() }
        val stateSeen = AtomicInteger()
        store.collectState { stateSeen.incrementAndGet() }
        val start = System.nanoTime()
        withContext(Dispatchers.Default) {
            withTimeout(60_000) {
                coroutineScope {
                    repeat(threads) { t ->
                        launch {
                            val rnd = Random(t)
                            repeat(perThread) { i ->
                                val a: A = when (rnd.nextInt(10)) {
                                    0 -> A.Go
                                    1 -> A.Finish
                                    2 -> A.Fail
                                    3 -> A.Recover
                                    4 -> A.Query("q$i")
                                    else -> A.Inc.also { incs.incrementAndGet() }
                                }
                                store.dispatch(a)
                            }
                        }
                    }
                }
                store.state.first { it.count == incs.get() }
            }
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        println("soak: ${threads * perThread} dispatches in ${elapsedMs}ms, incs=${incs.get()} busyEnters=$busyEnters saved=$saved events=$events stateSeen=$stateSeen")
        assertEquals(incs.get(), store.currentState.count)
        assertEquals(emptyList(), handled.map { it.toString() })
        // internal maps do not grow
        val runtimes: Map<*, *> = field(store, "stateRuntimes")
                assertTrue(runtimes.size <= 1, "stateRuntimes=${runtimes.size}")
        val dispatchScopeLazy: Lazy<*> = field(store, "dispatchScope\$delegate")
        val dispatchScope = dispatchScopeLazy.value as kotlinx.coroutines.CoroutineScope
        val children = dispatchScope.coroutineContext[Job]!!.children.count()
                assertTrue(children <= 1, "children=$children")
        store.close()
    }

    @Test
    fun soak_clearOnStateExit_neverDeadlocksAndCancelledJobsDoNotBlockSuccessors() = runTest {
        val handled = mutableListOf<Throwable>()
        val store = soakStore(PendingActionPolicy.ClearOnStateExit, handled, AtomicInteger(), AtomicInteger())
        withContext(Dispatchers.Default) {
            withTimeout(60_000) {
                repeat(20) { round ->
                    coroutineScope {
                        repeat(8) { t ->
                            launch {
                                val rnd = Random(round * 100 + t)
                                repeat(500) { i ->
                                    store.dispatch(
                                        when (rnd.nextInt(6)) {
                                            0 -> A.Go
                                            1 -> A.Finish
                                            2 -> A.Fail
                                            3 -> A.Recover
                                            4 -> A.Query("q$i")
                                            else -> A.Inc
                                        },
                                    )
                                }
                            }
                        }
                    }
                    // A marker dispatched after the storm settles must still be processed: the queue is not stuck.
                    delay(200)
                    val before = store.currentState.count
                    store.dispatch(A.Inc)
                    store.state.first { it.count > before }
                }
            }
        }
        assertEquals(emptyList(), handled.map { it.toString() })
        store.close()
    }

    @Test
    fun dispatchJobs_areNotRetainedThroughTheOrderingChain() = runTest {
        val store = soakStore(PendingActionPolicy.Keep, mutableListOf(), AtomicInteger(), AtomicInteger())
        withContext(Dispatchers.Default) {
            store.dispatch(A.Inc)
            store.state.first { it.count == 1 }
            val last: MutableStateFlow<Job?> = field(store, "lastDispatchDone")
            val ref = WeakReference(last.value!!)
            repeat(5000) { store.dispatch(A.Inc) }
            store.state.first { it.count == 5001 }
            repeat(5) { System.gc(); delay(50) }
            assertNull(ref.get(), "first dispatch's completion signal is still reachable")
        }
        store.close()
    }

    @Test
    fun closeMidWork_stopsEverythingAndLaterCallsAreNoops() = runTest {
        val handled = mutableListOf<Throwable>()
        val store = soakStore(PendingActionPolicy.Keep, handled, AtomicInteger(), AtomicInteger())
        withContext(Dispatchers.Default) {
            withTimeout(30_000) {
                val storm = launch {
                    var i = 0
                    while (isActive) { store.dispatch(if (i++ % 50 == 0) A.Go else if (i % 50 == 25) A.Finish else A.Inc) }
                }
                store.state.first { it.count > 500 }
                store.close()
                storm.cancel()
                val frozen = store.currentState
                delay(200)
                assertEquals(frozen, store.currentState)
                store.dispatch(A.Inc)
                (store as StoreInternalApi<S, A, Ev>).dispatchAndAwait(A.Inc)
                store.startAndAwait()
                delay(100)
                assertEquals(frozen, store.currentState)
            }
        }
        assertEquals(emptyList(), handled.map { it.toString() })
    }

    @Test
    fun soak_repeated_withIoHandlers() = runTest {
        repeat(6) { round ->
            val handled = mutableListOf<Throwable>()
            val store = soakStore(PendingActionPolicy.Keep, handled, AtomicInteger(), AtomicInteger(), incOnIo = round % 2 == 0)
            val incs = AtomicInteger()
            withContext(Dispatchers.Default) {
                withTimeout(60_000) {
                    coroutineScope {
                        repeat(8) { t ->
                            launch {
                                val rnd = Random(round * 31 + t)
                                repeat(1500) { i ->
                                    store.dispatch(
                                        when (rnd.nextInt(10)) {
                                            0 -> A.Go
                                            1 -> A.Finish
                                            2 -> A.Fail
                                            3 -> A.Recover
                                            4 -> A.Query("q$i")
                                            else -> A.Inc.also { incs.incrementAndGet() }
                                        },
                                    )
                                }
                            }
                        }
                    }
                    store.state.first { it.count == incs.get() }
                }
            }
            assertEquals(incs.get(), store.currentState.count, "round $round")
            assertEquals(emptyList(), handled.map { it.toString() }, "round $round")
            store.close()
        }
    }
}

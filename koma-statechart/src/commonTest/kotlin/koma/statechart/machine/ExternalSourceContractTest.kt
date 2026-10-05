@file:OptIn(koma.core.ExperimentalKomaApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package koma.statechart.machine

import koma.core.Action
import koma.core.Event
import koma.observability.StoreInstanceId
import koma.statechart.ActionMatcher
import koma.statechart.AtomicState
import koma.statechart.CompoundState
import koma.statechart.StateChartDefinition
import koma.statechart.StateId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * A paging adapter owns its retry cursor and rolls back interrupted pauses.
 * ```
 * [*] --> Ready; Ready --> Ready: Page / append accepted index
 * ```
 */
class ExternalSourceContractTest {
    private data class Page(val index: Int) : Action
    private data object Ev : Event

    /** A page remains pending until admitted. Rejected pages do not advance the cursor. */
    private class Pager(private val store: MachineStore<List<Int>, Page, Nothing, Ev>) : ExternalSource {
        override val id = SourceId("paging")
        override val kind = "paging"
        private val feedLock = Mutex()
        var next = 0
            private set
        var pauseGate: CompletableDeferred<Unit>? = null
        var resumes = 0
            private set
        val paused get() = feedLock.isLocked

        suspend fun loadNext(): Admission = feedLock.withLock {
            store.feed(id, Page(next)).also { if (it == Admission.Accepted) next++ }
        }

        override suspend fun pause() {
            feedLock.lock()
            try {
                // Models a multi-resource adapter: feeding stopped, subscription acknowledgement
                // still pending. Cancellation must relinquish the resource already acquired.
                pauseGate?.await()
            } catch (failure: Throwable) {
                feedLock.unlock()
                throw failure
            }
        }

        override fun snapshot() = SourceSnapshot(id, kind, 1, mapOf("next" to next.toString()))
        override fun resume() {
            resumes++
            feedLock.unlock()
        }
    }

    private class Fixture(scope: TestScope, admission: AdmissionPolicy = AdmissionPolicy.Unbounded) {
        val group = MachineGroup()
        val id = StoreInstanceId("pages")
        private val member = group.member<List<Int>, Page, Nothing, Ev>(id)
        private val root = StateId("root")
        private val ready = StateId("ready")
        private val chart = StateChartDefinition(root, listOf(CompoundState(root, initial = ready), AtomicState(ready, parent = root)), emptyList())
        private val machine = Machine<List<Int>, Page, Nothing, Ev>(DefinitionId("paging"), DefinitionVersion("1"), chart) {
            onAction(root, ActionMatcher.of<Page>("Page")) { context = context + (action as Page).index }
        }
        val store = MachineStore(machine, emptyList(), CommandHandler<Nothing, Page> { _, _ -> }, scope.backgroundScope,
            coroutineContext = StandardTestDispatcher(scope.testScheduler), admission = admission, observers = listOf(member))
        val pager = Pager(store)
        init {
            member.attach(store)
            group.source(pager)
            store.start()
        }
    }

    @Test
    fun pagingRetryCursorAndMemberRemainEqualAcrossBackpressureAndRepeatedCuts() = runTest {
        val f = Fixture(this, AdmissionPolicy.Bounded(1))
        try {
            runCurrent()
            repeat(20) { index ->
                assertEquals(Admission.Accepted, f.pager.loadNext())
                assertEquals(Admission.Rejected(1, 1), f.pager.loadNext())
                assertEquals(index + 1, f.pager.next, "rejection must preserve the pending page")
                val cut = async { f.group.checkpoint() }
                runCurrent()
                val checkpoint = checkNotNull(cut.await())
                assertEquals((0..index).toList(), checkpoint.members.getValue(f.id).snapshot.context)
                assertEquals((index + 1).toString(), checkpoint.sources.getValue(f.pager.id).fields.getValue("next"))
            }
            assertEquals((0 until 20).toList(), f.store.currentState.context)
        } finally { f.store.close() }
    }

    @Test
    fun cancellingPartialPauseRollsBackTheAdapterAndAllowsRetryAndAnotherCut() = runTest {
        val f = Fixture(this)
        try {
            runCurrent()
            f.pager.pauseGate = CompletableDeferred()
            val cut = async { f.group.checkpoint() }
            runCurrent()
            assertTrue(f.pager.paused)
            cut.cancelAndJoin()
            assertFalse(f.pager.paused, "the source owns rollback of an unfinished pause")
            assertEquals(0, f.pager.resumes, "the group never acquired the partial pause")
            assertEquals(Admission.Accepted, f.pager.loadNext())
            f.pager.pauseGate = null
            val next = async { f.group.checkpoint() }
            runCurrent()
            assertEquals(listOf(0), checkNotNull(next.await()).members.getValue(f.id).snapshot.context)
            assertEquals(1, f.pager.resumes)
        } finally { f.store.close() }
    }

    @Test
    fun timeoutRollsBackAPartialPauseAndResumesEarlierSources() = runTest {
        val f = Fixture(this)
        val earlier = Fixture(this)
        val group = MachineGroup()
        group.source(earlier.pager)
        // Unique source id: delegates to the same real feed-lock implementation.
        group.source(object : ExternalSource by f.pager {
            override val id = SourceId("second")
            override fun snapshot() = f.pager.snapshot().copy(source = id)
        })
        try {
            runCurrent()
            f.pager.pauseGate = CompletableDeferred()
            val cut = async { group.checkpoint(100.milliseconds) }
            runCurrent()
            assertTrue(earlier.pager.paused && f.pager.paused)
            advanceTimeBy(101)
            runCurrent()
            assertNull(cut.await())
            assertFalse(earlier.pager.paused || f.pager.paused)
            assertEquals(1, earlier.pager.resumes)
            assertEquals(0, f.pager.resumes)
            assertEquals(Admission.Accepted, f.pager.loadNext())
        } finally {
            f.store.close()
            earlier.store.close()
        }
    }

    @Test
    fun dataArrivingDuringPartialPauseIsAppliedOnlyAfterTheCut() = runTest {
        val f = Fixture(this)
        try {
            runCurrent()
            assertEquals(Admission.Accepted, f.pager.loadNext())
            f.pager.pauseGate = CompletableDeferred()
            val cut = async { f.group.checkpoint() }
            runCurrent()
            val pendingPage = async { f.pager.loadNext() }
            runCurrent()
            assertTrue(pendingPage.isActive)
            f.pager.pauseGate!!.complete(Unit)
            runCurrent()
            val checkpoint = checkNotNull(cut.await())
            assertEquals(listOf(0), checkpoint.members.getValue(f.id).snapshot.context)
            assertEquals("1", checkpoint.sources.getValue(f.pager.id).fields.getValue("next"))
            assertEquals(Admission.Accepted, pendingPage.await())
            assertEquals(listOf(0, 1), f.store.currentState.context)
        } finally { f.store.close() }
    }

    @Test
    fun aSuccessfulPauseIsResumedWhenCancellationDiscardsTheTimeoutResult() = runTest {
        val group = MachineGroup()
        var paused = false
        var resumes = 0
        group.source(object : ExternalSource {
            override val id = SourceId("cancel-on-return")
            override val kind = "subscription"
            override suspend fun pause() {
                paused = true
                // A subscriber completes its pause just as its deadline context is cancelled.
                // The block returns normally, but withTimeoutOrNull cannot return its result.
                currentCoroutineContext().cancel(CancellationException("cancel as pause returns"))
            }
            override fun snapshot() = SourceSnapshot(id, kind, 1, emptyMap())
            override fun resume() { paused = false; resumes++ }
        })
        assertFailsWith<CancellationException> { group.checkpoint() }
        assertFalse(paused)
        assertEquals(1, resumes)
    }
}

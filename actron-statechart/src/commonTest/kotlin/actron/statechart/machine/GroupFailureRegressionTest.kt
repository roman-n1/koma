@file:OptIn(actron.core.ExperimentalActronApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import actron.observability.StoreInstanceId
import actron.statechart.AtomicState
import actron.statechart.ActionMatcher
import actron.statechart.CompoundState
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Failed registration or checkpoint cleanup must not remove a member or strand another source. */
class GroupFailureRegressionTest {
    private data object Act : Action
    private data object Ev : Event

    private class Source(
        name: String,
        val pauseFailure: Throwable? = null,
        val snapshotFailure: Throwable? = null,
        val resumeFailure: Throwable? = null,
        val gate: CompletableDeferred<Unit>? = null,
    ) : ExternalSource {
        override val id = SourceId(name)
        override val kind = "test"
        var paused = false
        var resumes = 0
        override suspend fun pause() {
            gate?.await()
            pauseFailure?.let { throw it }
            paused = true
        }
        override fun snapshot(): SourceSnapshot {
            snapshotFailure?.let { throw it }
            return SourceSnapshot(id, kind, 1, emptyMap())
        }
        override fun resume() {
            resumes++
            resumeFailure?.let { throw it }
            paused = false
        }
    }

    @Test
    fun duplicateMemberRejectionKeepsTheOriginalStoreInTheCheckpoint() = runTest {
        val group = MachineGroup()
        val id = StoreInstanceId("original")
        val member = group.member<Unit, Act, Nothing, Ev>(id)
        val root = StateId("Root")
        val idle = StateId("Idle")
        val chart = StateChartDefinition(root, listOf(CompoundState(root, initial = idle), AtomicState(idle, parent = root)), emptyList())
        val machine = Machine<Unit, Act, Nothing, Ev>(DefinitionId("registration"), DefinitionVersion("1"), chart) {}
        val store = MachineStore(machine, Unit, CommandHandler<Nothing, Act> { _, _ -> }, backgroundScope,
            coroutineContext = StandardTestDispatcher(testScheduler), observers = listOf(member))
        try {
            member.attach(store)
            store.start()
            runCurrent()
            assertFailsWith<IllegalArgumentException> { group.member<Unit, Act, Nothing, Ev>(id) }
            assertTrue(member.isAttached)
            assertEquals(store.currentState, kotlin.test.assertIs<actron.statechart.machine.GroupCut.Ready>(group.checkpoint()).checkpoint.members.getValue(id).snapshot)
        } finally { store.close() }
    }

    @Test
    fun aResumeFailureDoesNotSkipTheRemainingSources() = runTest {
        val failure = IllegalStateException("resume")
        val group = MachineGroup()
        val first = Source("first", resumeFailure = failure)
        val last = Source("last")
        group.source(first)
        group.source(last)
        assertSame(failure, assertFailsWith<IllegalStateException> { group.checkpoint() })
        assertEquals(1, first.resumes)
        assertEquals(1, last.resumes)
        assertFalse(last.paused)
    }

    @Test
    fun multipleResumeFailuresAreSuppressedWithoutSkippingTheHealthySource() = runTest {
        val firstFailure = IllegalStateException("first resume")
        val secondFailure = IllegalArgumentException("second resume")
        val group = MachineGroup()
        val last = Source("last")
        group.source(Source("first", resumeFailure = firstFailure))
        group.source(Source("second", resumeFailure = secondFailure))
        group.source(last)
        assertSame(firstFailure, assertFailsWith<IllegalStateException> { group.checkpoint() })
        assertEquals(listOf(secondFailure), firstFailure.suppressedExceptions)
        assertFalse(last.paused)
    }

    @Test
    fun snapshotFailureStaysPrimaryWhenCleanupAlsoFails() = runTest {
        val failure = IllegalStateException("snapshot")
        val cleanupFailure = IllegalArgumentException("resume")
        val group = MachineGroup()
        val last = Source("last")
        group.source(Source("first", snapshotFailure = failure, resumeFailure = cleanupFailure))
        group.source(last)
        assertSame(failure, assertFailsWith<IllegalStateException> { group.checkpoint() })
        assertEquals(listOf(cleanupFailure), failure.suppressedExceptions)
        assertFalse(last.paused)
    }

    @Test
    fun pauseFailureResumesOnlyTheSourcesThatFinishedPausing() = runTest {
        val failure = IllegalStateException("pause")
        val group = MachineGroup()
        val first = Source("first")
        val failing = Source("failing", pauseFailure = failure)
        val last = Source("last")
        listOf(first, failing, last).forEach(group::source)
        // Coroutine stack-trace recovery can copy an exception across pause's suspension.
        assertEquals(failure.message, assertFailsWith<IllegalStateException> { group.checkpoint() }.message)
        assertEquals(listOf(1, 0, 0), listOf(first, failing, last).map { it.resumes })
        assertFalse(first.paused)
    }

    @Test
    fun cancellingACutResumesAlreadyPausedSourcesAndReleasesCoordination() = runTest {
        val group = MachineGroup()
        val first = Source("first")
        val gate = CompletableDeferred<Unit>()
        val last = Source("last", gate = gate)
        group.source(first)
        group.source(last)
        val cut = async { group.checkpoint() }
        runCurrent()
        assertTrue(first.paused)
        cut.cancelAndJoin()
        assertFalse(first.paused)
        assertEquals(0, last.resumes)
        gate.complete(Unit)
        assertEquals(setOf(first.id, last.id), kotlin.test.assertIs<actron.statechart.machine.GroupCut.Ready>(group.checkpoint()).checkpoint.sources.keys)
        assertFalse(first.paused || last.paused)
    }

    @Test
    fun failedSnapshotAndResumeStillThawTheStoresHeldInputs() = runTest {
        val group = MachineGroup()
        val id = StoreInstanceId("counter")
        val member = group.member<Int, Act, Nothing, Ev>(id)
        val root = StateId("Root")
        val idle = StateId("Idle")
        val chart = StateChartDefinition(root, listOf(CompoundState(root, initial = idle), AtomicState(idle, parent = root)), emptyList())
        val machine = Machine<Int, Act, Nothing, Ev>(DefinitionId("thaw"), DefinitionVersion("1"), chart) {
            onAction(root, ActionMatcher.of<Act>("Tick")) { context += 1 }
        }
        val store = MachineStore(machine, 0, CommandHandler<Nothing, Act> { _, _ -> }, backgroundScope,
            coroutineContext = StandardTestDispatcher(testScheduler), observers = listOf(member))
        val failure = IllegalStateException("snapshot")
        val cleanup = IllegalArgumentException("resume")
        val healthy = Source("healthy")
        group.source(object : ExternalSource {
            override val id = SourceId("failing")
            override val kind = "test"
            override suspend fun pause() = Unit
            override fun snapshot(): SourceSnapshot {
                store.dispatch(Act) // Held while the member is frozen.
                throw failure
            }
            override fun resume() { throw cleanup }
        })
        group.source(healthy)
        try {
            member.attach(store)
            store.start()
            runCurrent()
            assertSame(failure, assertFailsWith<IllegalStateException> { group.checkpoint() })
            assertEquals(listOf(cleanup), failure.suppressedExceptions)
            assertFalse(healthy.paused)
            runCurrent()
            assertEquals(1, store.currentState.context, "the input held by the failed cut was released")
            store.dispatch(Act)
            runCurrent()
            assertEquals(2, store.currentState.context, "later inputs continue normally")
        } finally { store.close() }
    }
}

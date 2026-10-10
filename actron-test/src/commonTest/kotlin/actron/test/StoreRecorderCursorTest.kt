package actron.test

import actron.core.Action
import actron.core.Event
import actron.core.State
import actron.core.Store
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The recorder's cursor: events are received one by one, in order, by type or by predicate; a
 * wrong type or an empty tail fails and leaves the cursor; the end-of-test checks name what was
 * not received and what is still running.
 *
 * ```
 * Main --EmitCount--> Main    event CountUpdated(count)
 * Main --Ping--> Main         event Pinged
 * Main --Block--> Main        launch { gate.await() }
 * Main --Subscribe--> Main    subscribe { awaitCancellation() }
 * ```
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StoreRecorderCursorTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    data class Main(val count: Int = 0) : State

    sealed interface Act : Action {
        data object Increment : Act
        data object EmitCount : Act
        data object Ping : Act
        data object Block : Act
        data object Subscribe : Act
    }

    sealed interface Ev : Event {
        data class CountUpdated(val count: Int) : Ev
        data object Pinged : Ev
    }

    private class Fixture {
        val gate = CompletableDeferred<Unit>()
        val store: Store<Main, Act, Ev> = Store(Main()) {
            coroutineContext(Dispatchers.Unconfined)
            state<Main> {
                action<Act.Increment> { nextState(state.copy(count = state.count + 1)) }
                action<Act.EmitCount> { event(Ev.CountUpdated(state.count)) }
                action<Act.Ping> { event(Ev.Pinged) }
                action<Act.Block> { launch { gate.await() } }
                action<Act.Subscribe> { subscribe { awaitCancellation() } }
            }
        }
    }

    @Test
    fun receiveEvent_consumesInOrder_byTypeAndByPredicate_andEventsKeepsThemAll() = runTest(testDispatcher) {
        val f = Fixture()
        val recorder = f.store.createRecorder()
        f.store.dispatchAndAwait(Act.EmitCount)
        f.store.dispatchAndAwait(Act.Ping)
        f.store.dispatchAndAwait(Act.Increment)
        f.store.dispatchAndAwait(Act.EmitCount)

        assertEquals(Ev.CountUpdated(0), recorder.receiveEvent<Ev.CountUpdated>())
        assertEquals(Ev.Pinged, recorder.receiveEvent { it is Ev.Pinged })
        assertEquals(listOf<Ev>(Ev.CountUpdated(1)), recorder.unconsumedEvents)
        assertEquals(Ev.CountUpdated(1), recorder.receiveEvent<Ev.CountUpdated>())

        recorder.assertNoUnconsumedEvents()
        assertTrue(recorder.unconsumedEvents.isEmpty())
        assertEquals(listOf(Ev.CountUpdated(0), Ev.Pinged, Ev.CountUpdated(1)), recorder.events, "received or not, the events stay recorded")
        f.store.assertNoPendingWork(recorder)
        f.store.close()
    }

    @Test
    fun receiveEvent_ofTheWrongType_fails_andLeavesTheCursorWhereItWas() = runTest(testDispatcher) {
        val f = Fixture()
        val recorder = f.store.createRecorder()
        f.store.dispatchAndAwait(Act.Ping)

        val failure = assertFailsWith<AssertionError> { recorder.receiveEvent<Ev.CountUpdated>() }

        assertTrue("the next unconsumed event is Pinged" in failure.message.orEmpty(), failure.message)
        assertEquals(listOf<Ev>(Ev.Pinged), recorder.unconsumedEvents, "not consumed by the failed receive")
        assertEquals(Ev.Pinged, recorder.receiveEvent<Ev.Pinged>())
        val exhausted = assertFailsWith<AssertionError> { recorder.receiveEvent<Ev.Pinged>() }
        assertTrue("every recorded event was received already (1 recorded)" in exhausted.message.orEmpty(), exhausted.message)
        val predicate = assertFailsWith<AssertionError> {
            f.store.dispatchAndAwait(Act.Ping)
            recorder.receiveEvent { it is Ev.CountUpdated }
        }
        assertTrue("does not satisfy the predicate" in predicate.message.orEmpty(), predicate.message)
        f.store.close()
    }

    @Test
    fun assertNoUnconsumedEvents_listsTheTail_andClearResetsTheCursor() = runTest(testDispatcher) {
        val f = Fixture()
        val recorder = f.store.createRecorder()
        f.store.dispatchAndAwait(Act.Ping)
        f.store.dispatchAndAwait(Act.EmitCount)
        recorder.receiveEvent<Ev.Pinged>()

        val failure = assertFailsWith<AssertionError> { recorder.assertNoUnconsumedEvents() }

        assertTrue("1 recorded event(s) were not received: [CountUpdated(count=0)]" in failure.message.orEmpty(), failure.message)
        recorder.clear()
        assertTrue(recorder.events.isEmpty() && recorder.unconsumedEvents.isEmpty())
        f.store.dispatchAndAwait(Act.Ping)
        assertEquals(Ev.Pinged, recorder.receiveEvent<Ev.Pinged>(), "the cursor starts over with the history")
        f.store.close()
    }

    @Test
    fun assertNoPendingWork_failsOnAnUnreceivedEvent_andOnARunningLaunch_andPassesWithASubscription() = runTest(testDispatcher) {
        val f = Fixture()
        val recorder = f.store.createRecorder()
        f.store.dispatchAndAwait(Act.Subscribe)
        f.store.assertNoPendingWork(recorder)

        f.store.dispatchAndAwait(Act.Ping)
        val unreceived = assertFailsWith<AssertionError> { f.store.assertNoPendingWork(recorder) }
        assertTrue("1 recorded event(s) not received: [Pinged]" in unreceived.message.orEmpty(), unreceived.message)
        recorder.receiveEvent<Ev.Pinged>()

        f.store.dispatchAndAwait(Act.Block)
        val running = assertFailsWith<AssertionError> { f.store.assertNoPendingWork(recorder) }
        assertTrue("0 input(s) pending, 1 launch(es) running" in running.message.orEmpty(), running.message)

        f.gate.complete(Unit)
        f.store.assertNoPendingWork(recorder)
        f.store.assertNoPendingWork()
        f.store.close()
    }

    @Test
    fun record_scopesTheRecorder_andTheCursorWorksInside() = runTest(testDispatcher) {
        val f = Fixture()
        f.store.record { recorder ->
            dispatchAndAwait(Act.Ping)
            dispatchAndAwait(Act.EmitCount)
            assertEquals(Ev.Pinged, recorder.receiveEvent<Ev.Pinged>())
            assertEquals(Ev.CountUpdated(0), recorder.receiveEvent<Ev.CountUpdated>())
            recorder.assertNoUnconsumedEvents()
        }
        f.store.close()
    }

    @Test
    fun randomEventSequences_areReceivedExactlyOnce_inOrder() = runTest(testDispatcher) {
        val random = Random(20261001)
        repeat(50) { round ->
            val f = Fixture()
            val recorder = f.store.createRecorder()
            val actions = List(random.nextInt(0, 12)) { listOf(Act.EmitCount, Act.Ping, Act.Increment).random(random) }
            for (action in actions) f.store.dispatchAndAwait(action)
            val expected = recorder.events.toList()

            val received = expected.map { recorder.receiveEvent { true } }

            assertEquals(expected, received, "round $round: the cursor walks the record in order")
            recorder.assertNoUnconsumedEvents()
            assertFailsWith<AssertionError>("round $round: nothing left") { recorder.receiveEvent { true } }
            f.store.assertNoPendingWork(recorder)
            f.store.close()
        }
    }
}

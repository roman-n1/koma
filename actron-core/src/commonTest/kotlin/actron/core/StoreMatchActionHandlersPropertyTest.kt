@file:OptIn(InternalActronApi::class, ExperimentalCoroutinesApi::class)

package actron.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Property-based checks for [StoreInternalApi.matchActionHandlers]: it must report exactly the
 * predicates the Store uses, in first-match order, without running, starting or locking the Store.
 *
 * ```
 * matchActionHandlers(s, a)  ==  [ predicate_i(s, a) for i in registration order ]
 * first i with match         ==  handler that dispatch(a) runs in state s
 *
 * busy Store:  dispatch(Slow) --holds mutex for 1000 ms-->  ...
 *              matchActionHandlers(...) returns at the same virtual time, also from inside a handler
 * ```
 */
class StoreMatchActionHandlersPropertyTest {

    private fun Store<RtState, RtAction, RtEvent>.match(s: RtState, a: RtAction): List<ActionHandlerMatch> =
        (this as StoreInternalApi<RtState, RtAction, RtEvent>).matchActionHandlers(s, a)

    private fun Store<RtState, RtAction, RtEvent>.registry(): HandlerRegistry<RtState, RtAction> =
        (this as StoreImpl<RtState, RtAction, RtEvent>).handlerRegistry

    @Test
    fun matchesEqualTheRealPredicatesForEveryStateAndActionIncludingLegacyHandlers() {
        var legacyMatches = 0
        repeat(300) { seed ->
            val program = RoutingProgram.random(Random(seed), maxBlocks = 10)
            val planned = program.planned(HandlerKind.ACTION)
            val store = buildRtStore(program, RtState.B, mutableListOf())
            val registry = store.registry()
            assertEquals(registry.action.size, registry.actionPredicates.size, "seed=$seed")

            for (s in statePool) for (a in actionPool) {
                val matches = store.match(s, a)
                val context = "seed=$seed s=$s a=$a"
                assertEquals(planned.indices.toList(), matches.map { it.index }, context)
                assertEquals(planned.map { it.matches(s, a) }, matches.map { it.matches }, context)
                assertEquals(registry.action.map { it?.stateType }, matches.map { it.stateType }, context)
                assertEquals(registry.action.map { it?.inputType }, matches.map { it.actionType }, context)
                legacyMatches += matches.count { it.matches && it.actionType == RtAction::class }
            }
        }
        assertTrue(legacyMatches > 100, "legacy handlers should match sometimes, saw $legacyMatches")
    }

    @Test
    fun firstMatchIsTheHandlerThatDispatchRuns() = runTest {
        var checked = 0
        repeat(300) { seed ->
            val random = Random(seed)
            val program = RoutingProgram.random(random, maxBlocks = 14)
            val actionIds = program.planned(HandlerKind.ACTION).map { it.id }
            val initial = statePool.random(random)
            val action = randomAction(random)
            val log = mutableListOf<Int>()
            val store = buildRtStore(program, initial, log) {
                coroutineContext(StandardTestDispatcher(testScheduler))
                exceptionHandler(ExceptionHandler.Ignore)
            }

            val selected = store.match(initial, action).firstOrNull { it.matches }?.index
            store.startAndAwaitForTest()
            log.clear()
            store.dispatchAndAwaitForTest(action)

            assertEquals(selected?.let { actionIds[it] }, log.firstOrNull { it in actionIds }, "seed=$seed")
            if (selected != null) checked++
            store.close()
        }
        assertTrue(checked > 90, "checked=$checked")
    }

    @Test
    fun matchingDoesNotRunHandlersReadStateOrStartTheStore() = runTest {
        val random = Random(42)
        repeat(50) { seed ->
            val program = RoutingProgram.random(random, maxBlocks = 10)
            val log = mutableListOf<Int>()
            val store = buildRtStore(program, RtState.B, log) { coroutineContext(StandardTestDispatcher(testScheduler)) }

            for (s in statePool) for (a in actionPool) store.match(s, a)

            assertTrue(log.isEmpty(), "seed=$seed log=$log")
            // patch() rejects initialState once the state was read and rejects everything once started.
            store.patchForTest { initialState(RtState.Sub.Y) }
            assertEquals(RtState.Sub.Y, store.currentState)
            store.close()
        }
    }

    @Test
    fun matchingAnswersImmediatelyWhileADispatchHoldsTheStore() = runTest {
        val inside = mutableListOf<List<Boolean>>()
        lateinit var store: Store<RtState, RtAction, RtEvent>
        store = Store(RtState.A(0)) {
            coroutineContext(StandardTestDispatcher(testScheduler))
            state<RtState.A> {
                action<RtAction.P> {
                    // Re-entrant call from inside a running handler: must not wait for the Store.
                    inside += store.match(state, RtAction.Q()).map { it.matches }
                    delay(1_000)
                    nextState { RtState.B }
                }
                action<RtAction.Q> { }
            }
            state<RtState> {
                action<RtAction> { }
            }
        }

        store.dispatch(RtAction.P())
        testScheduler.advanceTimeBy(10)
        val before = currentTime
        val whileBusy = store.match(RtState.A(0), RtAction.Q()).map { it.matches }
        assertEquals(before, currentTime)
        assertEquals(RtState.A(0), store.currentState, "the slow handler is still running")

        advanceUntilIdle()
        assertEquals(RtState.B, store.currentState)
        assertEquals(listOf(listOf(false, true, true)), inside)
        assertEquals(listOf(false, true, true), whileBusy)
        store.close()
    }

    @Test
    fun underConcurrentDispatchesEveryProcessedActionRanItsFirstMatch() = runTest {
        repeat(30) { seed ->
            val random = Random(1_000 + seed)
            val program = RoutingProgram.random(random, maxBlocks = 10)
            val actionIds = program.planned(HandlerKind.ACTION).map { it.id }.toSet()
            val log = mutableListOf<Int>()
            val seen = mutableListOf<Pair<RtState, RtAction>>()
            val snapshots = mutableListOf<Triple<RtState, RtAction, List<ActionHandlerMatch>>>()
            val store = buildRtStore(program, statePool.random(random), log) {
                coroutineContext(StandardTestDispatcher(testScheduler))
                exceptionHandler(ExceptionHandler.Ignore)
                pendingActionPolicy(if (random.nextBoolean()) PendingActionPolicy.ClearOnStateExit else PendingActionPolicy.Keep)
                plugin(
                    object : Plugin<RtState, RtAction, RtEvent> {
                        override suspend fun onAction(scope: PluginScope<RtState, RtAction>, state: RtState, action: RtAction) {
                            seen += state to action
                            delay(random.nextLong(0, 5))
                        }
                    },
                )
            }

            repeat(60) {
                val action = randomAction(random)
                val at = random.nextLong(0, 200)
                launch {
                    delay(at)
                    store.dispatch(action)
                }
                launch {
                    delay(random.nextLong(0, 200))
                    val s = store.currentState
                    snapshots += Triple(s, action, store.match(s, action))
                }
            }
            advanceUntilIdle()

            val expectedIds = seen.mapNotNull { (s, a) ->
                store.match(s, a).firstOrNull { it.matches }?.let { program.planned(HandlerKind.ACTION)[it.index].id }
            }
            assertEquals(expectedIds, log.filter { it in actionIds }, "seed=$seed")
            // Answers taken mid-flight are a pure function of (state, action).
            val reference = buildRtStore(program, RtState.B, mutableListOf())
            for ((s, a, m) in snapshots) {
                assertEquals(reference.match(s, a).map { it.matches }, m.map { it.matches }, "seed=$seed s=$s a=$a")
            }
            store.close()
        }
    }

    @Test
    fun storeWithoutActionHandlersMatchesNothing() {
        val store = buildRtStore(RoutingProgram(emptyList()), RtState.B, mutableListOf())
        for (s in statePool) for (a in actionPool) assertTrue(store.match(s, a).isEmpty())
    }
}

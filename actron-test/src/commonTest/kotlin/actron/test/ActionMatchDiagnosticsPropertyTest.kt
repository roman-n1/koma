package actron.test

import actron.core.Store
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Property-based and timing checks for [diagnoseActionMatches].
 *
 * Random definitions check the result against an independent model built from the declared types.
 * The startup scenarios use this chain, with virtual-time delays inside `enter {}`:
 *
 * ```
 * A(0) --enter (300 ms)--> X(0) --enter (200 ms)--> B        diagnose(action) sees B at t = 500 ms
 * B    --P--> A(7)                                            (handler #0, state<B> / action<P>)
 * any  --action<KtAction>-->  (fallback, #1)
 * ```
 */
class ActionMatchDiagnosticsPropertyTest {

    private fun expectedText(s: KtState, a: KtAction, matched: List<ActionHandlerDescription>): String {
        val body = if (matched.isEmpty()) {
            "no handler matches"
        } else {
            "selected " + matched.joinToString(", shadowed ") { "#${it.index} state<${it.stateType!!.simpleName}> / action<${it.actionType!!.simpleName}>" }
        }
        return "ActionMatchDiagnostics(state=$s, action=$a: $body)"
    }

    @Test
    fun diagnosticsAgreeWithAnIndependentModelForRandomDefinitions() {
        var shadowing = 0
        var unhandled = 0
        repeat(400) { seed ->
            val random = Random(seed)
            val actionRegs = randomRegistrations(random).filter { it.kind == "action" }
            val store = buildKtStore(actionRegs, KtState.B)
            val expectedHandlers = actionRegs.mapIndexed { i, r -> ActionHandlerDescription(i, r.stateType, requireNotNull(r.inputType)) }

            for (s in ktStates) for (a in ktActions) {
                val d = store.diagnoseActionMatches(s, a)
                val model = actionRegs.indices.filter { actionRegs[it].stateType.isInstance(s) && actionRegs[it].inputType!!.isInstance(a) }
                val context = "seed=$seed s=$s a=$a"

                assertSame(s, d.state, context)
                assertSame(a, d.action, context)
                assertEquals(expectedHandlers, d.handlers, context)
                assertEquals(model, d.matchedHandlerIndices, context)
                assertEquals(model.size, d.matchedHandlerCount, context)
                assertEquals(model.firstOrNull(), d.observedSelectedIndex(), context)
                assertEquals(model.map { expectedHandlers[it] }, d.matchedHandlers, context)
                assertTrue(d.matchedHandlerIndices.zipWithNext().all { (x, y) -> x < y }, context)
                assertEquals(expectedText(s, a, d.matchedHandlers), d.toString(), context)

                if (model.size >= 2) shadowing++
                if (model.isEmpty()) unhandled++
            }
        }
        assertTrue(shadowing > 500 && unhandled > 500, "shadowing=$shadowing unhandled=$unhandled")
    }

    @Test
    fun selectedHandlerIsTheOneTheRealStoreRuns() = runTest {
        var handled = 0
        repeat(300) { seed ->
            val random = Random(seed)
            val regs = randomRegistrations(random).filter { it.kind == "action" }
            val s = ktStates.random(random)
            val a = ktActions.random(random)
            val log = mutableListOf<String>()
            val store = buildKtStore(regs, s, log) { coroutineContext(StandardTestDispatcher(testScheduler)) }

            val selected = store.diagnoseActionMatches(s, a).observedSelectedIndex()
            store.dispatchAndAwait(a)

            assertEquals(listOfNotNull(selected?.let { "action#$it" }), log, "seed=$seed")
            if (selected != null) handled++
            store.close()
        }
        assertTrue(handled > 100, "handled=$handled")
    }

    @Test
    fun diagnosingEveryPairDoesNotStartTheStoreOrRunAnyHandler() {
        repeat(100) { seed ->
            val log = mutableListOf<String>()
            val store = buildKtStore(randomRegistrations(Random(seed), max = 30), KtState.A(3), log)

            for (s in ktStates) for (a in ktActions) store.diagnoseActionMatches(s, a)

            assertEquals(emptyList(), log, "seed=$seed")
            // Still configurable, so the Store was neither started nor asked for its state.
            store.patch { initialState(KtState.Sub.Y) }
            assertEquals(KtState.Sub.Y, store.currentState)
        }
    }

    private fun TestScope.startupChainStore(log: MutableList<String>): Store<KtState, KtAction, KtEvent> =
        Store(KtState.A(0)) {
            coroutineContext(StandardTestDispatcher(testScheduler))
            state<KtState.A> {
                enter {
                    log += "enter A"
                    delay(300)
                    nextState { KtState.Sub.X(0) }
                }
            }
            state<KtState.Sub.X> {
                enter {
                    log += "enter X"
                    delay(200)
                    nextState { KtState.B }
                }
            }
            state<KtState.B> {
                enter { log += "enter B" }
                action<KtAction.P> { nextState { KtState.A(7) } }
            }
            state<KtState> {
                action<KtAction> { }
            }
        }

    @Test
    fun suspendingOverloadWaitsForTheWholeStartupChain() = runTest {
        val log = mutableListOf<String>()
        val store = startupChainStore(log)

        val d = store.diagnoseActionMatches(KtAction.P)

        assertEquals(500, currentTime)
        assertEquals(listOf("enter A", "enter X", "enter B"), log)
        assertEquals(KtState.B, d.state)
        assertEquals(listOf(0, 1), d.matchedHandlerIndices)
        store.close()
    }

    @Test
    fun concurrentSuspendingCallsShareOneStartup() = runTest {
        val log = mutableListOf<String>()
        val store = startupChainStore(log)

        val results = List(50) { i ->
            async {
                delay(i * 7L) // some callers arrive mid-startup, some after it
                store.diagnoseActionMatches(if (i % 2 == 0) KtAction.P else KtAction.Q)
            }
        }.awaitAll()

        assertEquals(listOf("enter A", "enter X", "enter B"), log, "startup ran exactly once")
        assertTrue(results.all { it.state == KtState.B })
        results.forEachIndexed { i, d ->
            assertEquals(if (i % 2 == 0) listOf(0, 1) else listOf(1), d.matchedHandlerIndices, "caller $i")
        }
        store.close()
    }

    @Test
    fun suspendingOverloadOnARunningStoreUsesItsCurrentStateWithoutReentering() = runTest {
        val log = mutableListOf<String>()
        val store = startupChainStore(log)
        store.startAndAwait()
        store.dispatchAndAwait(KtAction.P) // B -> A(7) -> X(0) -> B again
        advanceUntilIdle()
        val enters = log.size

        val d = store.diagnoseActionMatches(KtAction.Q)

        assertEquals(enters, log.size)
        assertEquals(store.currentState, d.state)
        assertEquals(store.diagnoseActionMatches(store.currentState, KtAction.Q).matchedHandlerIndices, d.matchedHandlerIndices)
        store.close()
    }

    @Test
    fun suspendingOverloadQueuesBehindAnInFlightDispatchAndSeesItsResult() = runTest {
        val store: Store<KtState, KtAction, KtEvent> = Store(KtState.B) {
            coroutineContext(StandardTestDispatcher(testScheduler))
            state<KtState.B> {
                action<KtAction.P> {
                    delay(1_000)
                    nextState { KtState.Sub.Y }
                }
            }
            state<KtState.Sub.Y> { action<KtAction.Q> { } }
        }
        store.startAndAwait()
        launch { store.dispatchAndAwait(KtAction.P) }
        testScheduler.advanceTimeBy(100)

        // The non-suspending overload answers at once for any state, even mid-dispatch.
        assertEquals(emptyList(), store.diagnoseActionMatches(KtState.B, KtAction.Q).matchedHandlerIndices)
        assertEquals(100, currentTime)

        // The suspending overload awaits startup like startAndAwait(), which takes the Store lock even
        // when already started, so it waits for the running dispatch and reports the state after it.
        val d = store.diagnoseActionMatches(KtAction.Q)

        assertEquals(1_000, currentTime)
        assertEquals(KtState.Sub.Y, d.state)
        assertEquals(listOf(1), d.matchedHandlerIndices)
        store.close()
    }

    @Test
    fun suspendingOverloadAfterCloseReportsTheInitialStateWithoutStarting() = runTest {
        val log = mutableListOf<String>()
        val store = startupChainStore(log)
        store.close()

        val d = store.diagnoseActionMatches(KtAction.P)

        assertEquals(KtState.A(0), d.state)
        assertEquals(emptyList(), log, "a closed Store does not run startup")
        assertEquals(listOf(1), d.matchedHandlerIndices)
        assertEquals(0, currentTime)
    }

    @Test
    fun nonActronStoreIsRejectedByBothOverloads() = runTest {
        val real = buildKtStore(emptyList(), KtState.B)
        val foreign = object : Store<KtState, KtAction, KtEvent> by real {}

        assertFailsWith<IllegalStateException> { foreign.diagnoseActionMatches(KtState.B, KtAction.P) }
        assertFailsWith<IllegalStateException> { foreign.diagnoseActionMatches(KtAction.P) }
    }

    @Test
    fun unnamedTypesRenderAsQuestionMarks() {
        val unnamed = object {}::class
        val d = ActionMatchDiagnostics(
            state = KtState.B,
            action = KtAction.P,
            handlers = listOf(
                ActionHandlerDescription(0, unnamed, unnamed),
                ActionHandlerDescription(1, KtState::class, unnamed),
                ActionHandlerDescription(2, unnamed, KtAction.P::class),
            ),
            matchedHandlerIndices = listOf(0, 2),
        )

        assertEquals("#1 state<KtState> / action<?>", d.handlers[1].toString())
        assertEquals(
            "ActionMatchDiagnostics(state=B, action=P: selected #0 state<?> / action<?>, shadowed #2 state<?> / action<P>)",
            d.toString(),
        )
    }

    @Test
    fun typesWithoutASimpleNameRenderLikeUnknownTypes() {
        // Anonymous classes have no simple name on the JVM; whatever a platform reports must be used as is.
        val anonymousState = object : actron.core.State {}::class
        val anonymousAction = object : actron.core.Action {}::class
        val name = { k: kotlin.reflect.KClass<*> -> k.simpleName ?: "?" }

        assertEquals(
            "#3 state<${name(anonymousState)}> / action<${name(anonymousAction)}>",
            ActionHandlerDescription(3, anonymousState, anonymousAction).toString(),
        )
    }
}

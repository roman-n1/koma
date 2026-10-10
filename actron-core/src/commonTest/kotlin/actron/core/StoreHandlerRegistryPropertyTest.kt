package actron.core

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Property-based checks that the handler registry is a faithful, order-preserving description of
 * what the Store actually runs, for Store definitions generated from fixed seeds.
 *
 * Each random scenario runs this path, and every arrow picks the first registered handler whose
 * predicate matches:
 *
 * ```
 * start(s) --enter?--> s --action(a)?--+-- a.fail  --> recover?(s, fail) --> s
 *                                      +-- a.goto, other class --> exit?(s) --> enter?(goto) --> goto
 *                                      +-- a.goto, same class  --> goto (no exit/enter)
 *                                      +-- no handler          --> s
 * ```
 */
class StoreHandlerRegistryPropertyTest {

    private fun Store<RtState, RtAction, RtEvent>.registry(): HandlerRegistry<RtState, RtAction> =
        (this as StoreImpl<RtState, RtAction, RtEvent>).handlerRegistry

    private fun HandlerRegistry<RtState, RtAction>.of(kind: HandlerKind): List<HandlerMatcher?> = when (kind) {
        HandlerKind.ENTER -> enter
        HandlerKind.ACTION -> action
        HandlerKind.EXIT -> exit
        HandlerKind.RECOVER -> recover
    }

    @Test
    fun registryListsEveryHandlerInRegistrationOrderForRandomDefinitions() {
        repeat(400) { seed ->
            val program = RoutingProgram.random(Random(seed))
            val registry = buildRtStore(program, RtState.B, mutableListOf()).registry()

            for (kind in HandlerKind.entries) {
                assertEquals(
                    program.planned(kind).map { it.expectedMatcher },
                    registry.of(kind),
                    "seed=$seed kind=$kind",
                )
            }
        }
    }

    @Test
    fun legacyHandlersLeaveNullAtExactlyTheirPositions() {
        var legacySeen = 0
        repeat(400) { seed ->
            val program = RoutingProgram.random(Random(seed), maxBlocks = 12)
            val registry = buildRtStore(program, RtState.B, mutableListOf()).registry()
            for (kind in HandlerKind.entries) {
                val legacyIds = program.blocks.filterIsInstance<Block.LegacyTop>().filter { it.kind == kind }.map { it.id }.toSet()
                val expectedNullAt = program.planned(kind).withIndex().filter { it.value.id in legacyIds }.map { it.index }
                val actualNullAt = registry.of(kind).withIndex().filter { it.value == null }.map { it.index }
                assertEquals(expectedNullAt, actualNullAt, "seed=$seed kind=$kind")
                legacySeen += legacyIds.size
            }
        }
        assertTrue(legacySeen > 50, "the generator should produce legacy handlers, saw $legacySeen")
    }

    @Test
    fun declaredMatchersAgreeWithTheRealPredicatesOnEveryStateAndInput() {
        // A matcher with an input type claims "state is stateType && input is inputType". That claim
        // must hold for the predicate the Store really uses, for every pooled state and input.
        repeat(300) { seed ->
            val program = RoutingProgram.random(Random(seed), legacy = false)
            val store = buildRtStore(program, RtState.B, mutableListOf())
            val builderPredicates = store.registry()
            for (kind in HandlerKind.entries) {
                val inputs: List<Any?> = when (kind) {
                    HandlerKind.ACTION -> actionPool
                    HandlerKind.RECOVER -> exceptionPool()
                    else -> listOf(null)
                }
                builderPredicates.of(kind).forEachIndexed { index, matcher ->
                    val planned = program.planned(kind)[index]
                    for (s in statePool) for (input in inputs) {
                        val claimed = matcher!!.stateType.isInstance(s) && (input == null || matcher.inputType!!.isInstance(input))
                        assertEquals(planned.matches(s, input), claimed, "seed=$seed kind=$kind #$index s=$s input=$input")
                    }
                }
            }
        }
    }

    @Test
    fun registryPredictsWhichHandlersTheRunningStoreInvokes() = runTest {
        var dispatchedWithHandler = 0
        var recovered = 0
        var transitions = 0
        repeat(250) { seed ->
            val random = Random(seed)
            val program = RoutingProgram.random(random)
            repeat(3) { round ->
                val initial = statePool.random(random)
                val action = randomAction(random)
                val expected = program.expect(initial, action)
                val log = mutableListOf<Int>()
                val unhandled = mutableListOf<Throwable>()
                val store = buildRtStore(program, initial, log) {
                    coroutineContext(StandardTestDispatcher(testScheduler))
                    exceptionHandler(ExceptionHandler { unhandled += it })
                }

                store.startAndAwaitForTest()
                store.dispatchAndAwaitForTest(action)

                val context = "seed=$seed round=$round initial=$initial action=$action"
                assertEquals(expected.log, log, context)
                assertEquals(expected.finalState, store.currentState, context)
                assertEquals(expected.unhandled.size, unhandled.size, context)
                expected.unhandled.zip(unhandled).forEach { (e, a) -> assertSame(e, a, context) }
                store.close()

                if (program.planned(HandlerKind.ACTION).any { it.matches(initial, action) }) dispatchedWithHandler++
                if (action.fail != null && expected.unhandled.isEmpty() && expected.log.size >= 2) recovered++
                if (expected.finalState::class != initial::class) transitions++
            }
        }
        // Guard against a generator that silently stops exercising the interesting paths.
        assertTrue(dispatchedWithHandler > 150, "handled=$dispatchedWithHandler")
        assertTrue(recovered > 20, "recovered=$recovered")
        assertTrue(transitions > 40, "transitions=$transitions")
    }

    /**
     * ```
     * Parent first:  state<RtState>{ action<RtAction> }  #0  <- selected for A + P
     *                state<A>{ action<P> }               #1  (shadowed)
     * Child first:   state<A>{ action<P> }               #0  <- selected for A + P
     *                state<RtState>{ action<RtAction> }  #1  <- selected for B + P
     * ```
     */
    @Test
    fun parentRegisteredBeforeChildShadowsItAndRegistryShowsWhy() = runTest {
        for (parentFirst in listOf(true, false)) {
            val log = mutableListOf<Int>()
            val store: Store<RtState, RtAction, RtEvent> = Store(RtState.A(0)) {
                coroutineContext(StandardTestDispatcher(testScheduler))
                val parent: RtBuilder.() -> Unit = { state<RtState> { action<RtAction> { log += 100; action.goto?.let { g -> nextState { g } } } } }
                val child: RtBuilder.() -> Unit = { state<RtState.A> { action<RtAction.P> { log += 200; action.goto?.let { g -> nextState { g } } } } }
                if (parentFirst) { parent(); child() } else { child(); parent() }
            }
            val parentMatcher = HandlerMatcher(RtState::class, RtAction::class)
            val childMatcher = HandlerMatcher(RtState.A::class, RtAction.P::class)
            assertEquals(
                if (parentFirst) listOf(parentMatcher, childMatcher) else listOf(childMatcher, parentMatcher),
                store.registry().action,
            )

            store.dispatchAndAwaitForTest(RtAction.P())
            store.dispatchAndAwaitForTest(RtAction.P(goto = RtState.B))
            store.dispatchAndAwaitForTest(RtAction.P())

            assertEquals(if (parentFirst) listOf(100, 100, 100) else listOf(200, 200, 100), log)
            store.close()
        }
    }

    /**
     * ```
     * state<A> { exit#e0  recover<ISE>#r0  action<P>#a0  enter#n0  action<Q>#a1  recover<Exception>#r1  exit#e1 }
     * ```
     * Kinds written interleaved inside one block keep their relative order within each kind.
     */
    @Test
    fun interleavedKindsInOneBlockKeepPerKindOrder() {
        val store: Store<RtState, RtAction, RtEvent> = Store(RtState.A(0)) {
            state<RtState.A> {
                exit { }
                recover<IllegalStateException> { }
                action<RtAction.P> { }
                enter { }
                action<RtAction.Q> { }
                @Suppress("DEPRECATION")
                error<Exception> { }
                exit { }
            }
        }
        val registry = store.registry()
        val a = RtState.A::class
        assertEquals(listOf(HandlerMatcher(a)), registry.enter)
        assertEquals(listOf(HandlerMatcher(a, RtAction.P::class), HandlerMatcher(a, RtAction.Q::class)), registry.action)
        assertEquals(listOf(HandlerMatcher(a), HandlerMatcher(a)), registry.exit)
        assertEquals(listOf(HandlerMatcher(a, IllegalStateException::class), HandlerMatcher(a, Exception::class)), registry.recover)
        assertNull(registry.enter.single()!!.inputType)
    }

    @Test
    fun recoverOrderDecidesWhichHandlerCatchesASubclass() = runTest {
        // CustomBoom is an IllegalStateException is a RuntimeException is an Exception.
        val orders = listOf(
            listOf(recoverKinds[0], recoverKinds[4]), // Exception first: catches CustomBoom
            listOf(recoverKinds[4], recoverKinds[0]), // CustomBoom first
            listOf(recoverKinds[3], recoverKinds[2], recoverKinds[1]), // IAE skipped, ISE catches
        )
        val expectedWinner = listOf(0, 0, 1)
        orders.forEachIndexed { case, kinds ->
            val log = mutableListOf<Int>()
            val program = RoutingProgram(
                listOf(
                    Block.StateBlock(
                        stateKinds[1],
                        listOf(Inner(HandlerKind.ACTION, 99, actionKind = actionKinds[0])) +
                            kinds.mapIndexed { i, k -> Inner(HandlerKind.RECOVER, i, recoverKind = k) },
                    ),
                ),
            )
            val store = buildRtStore(program, RtState.A(0), log) { coroutineContext(StandardTestDispatcher(testScheduler)) }
            assertEquals(kinds.map { HandlerMatcher(RtState.A::class, it.type) }, store.registry().recover)
            store.dispatchAndAwaitForTest(RtAction.Q(fail = CustomBoom()))
            assertEquals(listOf(99, expectedWinner[case]), log, "case=$case")
            store.close()
        }
    }

    @Test
    fun eachStoreGetsItsOwnRegistrySnapshot() {
        val program = RoutingProgram.random(Random(7), maxBlocks = 10)
        val first = buildRtStore(program, RtState.B, mutableListOf()).registry()
        val second = buildRtStore(program, RtState.B, mutableListOf()).registry()
        for (kind in HandlerKind.entries) {
            assertEquals(first.of(kind), second.of(kind))
        }
        assertTrue(first !== second)
    }
}

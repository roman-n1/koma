@file:OptIn(InternalActronApi::class)

package actron.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * Property-based checks for [StoreInternalApi.handlerMetadata]: it must mirror the handler registry
 * per kind, with indices equal to first-match positions, and agree with
 * [StoreInternalApi.matchActionHandlers] on the action handlers.
 *
 * ```
 * state<S2> { enter  exit  action<A2>  recover<T> }  --build-->  registry
 * registry --handlerMetadata()--> (index, stateType, inputType) per kind, legacy -> (index, null, null)
 * ```
 */
class StoreHandlerMetadataPropertyTest {

    private fun Store<RtState, RtAction, RtEvent>.api() = this as StoreInternalApi<RtState, RtAction, RtEvent>

    private fun StoreHandlerMetadata.of(kind: HandlerKind): List<HandlerMetadata> = when (kind) {
        HandlerKind.ENTER -> enter
        HandlerKind.ACTION -> action
        HandlerKind.EXIT -> exit
        HandlerKind.RECOVER -> recover
    }

    @Test
    fun metadataMirrorsThePlannedHandlersOfEveryKind() {
        var nulls = 0
        repeat(400) { seed ->
            val program = RoutingProgram.random(Random(seed), maxBlocks = 10)
            val metadata = buildRtStore(program, RtState.B, mutableListOf()).api().handlerMetadata()

            for (kind in HandlerKind.entries) {
                val expected = program.planned(kind).map { it.expectedMatcher }
                val actual = metadata.of(kind)
                val context = "seed=$seed kind=$kind"
                assertEquals(expected.indices.toList(), actual.map { it.index }, context)
                assertEquals(expected.map { it?.stateType }, actual.map { it.stateType }, context)
                assertEquals(expected.map { it?.inputType }, actual.map { it.inputType }, context)
                if (kind == HandlerKind.ENTER || kind == HandlerKind.EXIT) {
                    assertTrue(actual.all { it.inputType == null }, context)
                }
                nulls += actual.count { it.stateType == null }
            }
        }
        assertTrue(nulls > 50, "legacy handlers should appear, saw $nulls")
    }

    @Test
    fun actionMetadataAgreesWithMatchActionHandlersForAnyPair() {
        repeat(200) { seed ->
            val random = Random(seed)
            val store = buildRtStore(RoutingProgram.random(random, maxBlocks = 10), RtState.B, mutableListOf())
            val action = store.api().handlerMetadata().action
            val matches = store.api().matchActionHandlers(statePool.random(random), actionPool.random(random))
            assertEquals(action.map { Triple(it.index, it.stateType, it.inputType) }, matches.map { Triple(it.index, it.stateType, it.actionType) }, "seed=$seed")
        }
    }

    @Test
    fun eachCallReturnsAFreshEqualSnapshotWithoutStartingTheStore() = runTest {
        val log = mutableListOf<Int>()
        val program = RoutingProgram.random(Random(99), maxBlocks = 12)
        val store = buildRtStore(program, RtState.B, log) { coroutineContext(StandardTestDispatcher(testScheduler)) }

        val first = store.api().handlerMetadata()
        val second = store.api().handlerMetadata()

        assertNotSame(first, second)
        for (kind in HandlerKind.entries) {
            assertEquals(first.of(kind).map { Triple(it.index, it.stateType, it.inputType) }, second.of(kind).map { Triple(it.index, it.stateType, it.inputType) })
        }
        assertTrue(log.isEmpty())
        store.patchForTest { initialState(RtState.Sub.Y) } // would throw if started or state read
        store.close()
    }

    @Test
    fun metadataIsAvailableWhileAHandlerHoldsTheStore() = runTest {
        lateinit var store: Store<RtState, RtAction, RtEvent>
        var fromInside: StoreHandlerMetadata? = null
        store = Store(RtState.A(0)) {
            coroutineContext(StandardTestDispatcher(testScheduler))
            state<RtState.A> {
                action<RtAction.P> {
                    fromInside = store.api().handlerMetadata()
                    delay(5_000)
                }
                recover<CustomBoom> { }
            }
        }

        store.dispatch(RtAction.P())
        testScheduler.advanceTimeBy(1)
        val t = currentTime
        val outside = store.api().handlerMetadata()

        assertEquals(t, currentTime)
        assertEquals(listOf(RtAction.P::class), outside.action.map { it.inputType })
        assertEquals(listOf(CustomBoom::class), fromInside!!.recover.map { it.inputType })
        store.close()
    }
}

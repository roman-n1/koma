package actron.statechart.machine

import actron.core.Action
import actron.core.Event
import kotlin.test.*

class EventRouteTest {
    private data object Tick : Event
    private data object Go : Action

    @Test fun aFilteredEventCarriesNothing() {
        val route = EventRoute<Event, Action> { _, _ -> }
        assertFalse(route.mapEvent(Tick) { error("filtered event delivered") })
    }

    @Test fun deliveryWaitsUntilTheMapperReturns() {
        val order = mutableListOf<String>()
        val route = EventRoute<Event, Action> { _, carry ->
            order += "mapping"
            carry(Go)
            order += "mapped"
        }
        assertTrue(route.mapEvent(Tick) { order += "delivered" })
        assertEquals(listOf("mapping", "mapped", "delivered"), order)
    }

    @Test fun failedOrDuplicateMappingNeverStartsDelivery() {
        val failure = IllegalArgumentException("mapping failed")
        val throwing = EventRoute<Event, Action> { _, carry -> carry(Go); throw failure }
        assertSame(failure, assertFailsWith<IllegalArgumentException> {
            throwing.mapEvent(Tick) { error("delivery started before mapping completed") }
        })
        val duplicate = EventRoute<Event, Action> { _, carry -> carry(Go); carry(Go) }
        assertFailsWith<IllegalStateException> {
            duplicate.mapEvent(Tick) { error("duplicate mapping started delivery") }
        }
    }

    @Test fun aMapperCannotCarryAnActionAfterItReturns() {
        val captured = mutableListOf<(Action) -> Unit>()
        val route = EventRoute<Event, Action> { _, carry -> captured += carry }
        assertFalse(route.mapEvent(Tick) { error("late delivery") })
        assertFailsWith<IllegalStateException> { captured.single()(Go) }
    }
}

package actron.statechart.machine

import actron.core.Action
import actron.core.Event

/** Maps an event by preparing one synchronous delivery, or deliberately filtering the event.
 * Delivery starts only after the mapper has returned successfully.
 */
class EventRoute<in E : Event, out A : Action>(private val map: (E, (A) -> Unit) -> Unit) {
    fun mapEvent(event: E, carry: (A) -> Unit): Boolean {
        var open = true
        var mapped = false
        var deliver: () -> Unit = {}
        try {
            map(event) { action ->
                check(open) { "[Actron] An event route must map synchronously" }
                check(!mapped) { "[Actron] An event route may carry one action per event" }
                mapped = true
                deliver = { carry(action) }
            }
        } finally {
            open = false
        }
        deliver()
        return mapped
    }
}

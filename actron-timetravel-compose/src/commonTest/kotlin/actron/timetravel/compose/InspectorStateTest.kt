@file:OptIn(ExperimentalActronApi::class)

package actron.timetravel.compose

import actron.core.ExperimentalActronApi
import actron.core.InputId
import actron.observability.Capability
import actron.observability.ExecutionMode
import actron.observability.GroupSeq
import actron.observability.InputDescriptor
import actron.observability.JOURNAL_FORMAT_VERSION
import actron.observability.JournalEntry
import actron.observability.JournalRecord
import actron.observability.MachineGroupId
import actron.observability.OutcomeDescriptor
import actron.observability.OutcomeKind
import actron.observability.Payload
import actron.observability.RuntimeSessionId
import actron.observability.StoreInstanceId
import actron.observability.StoreSeq
import actron.timetravel.inspect.Inspector
import actron.timetravel.inspect.TimelineItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The screen's state without the screen: selection, the Store filter and a refresh that keeps
 * the selection where it still exists.
 */
class InspectorStateTest {

    private val a = StoreInstanceId("a")
    private val b = StoreInstanceId("b")

    private fun records(processings: Int): List<JournalRecord<*, *, *>> {
        var seq = 0L
        fun record(entry: JournalEntry<*, *, *>, store: StoreInstanceId? = a) =
            JournalRecord(JOURNAL_FORMAT_VERSION, RuntimeSessionId("s"), MachineGroupId("g"), store ?: actron.observability.RecordSubject.Session, ExecutionMode.Live, GroupSeq(++seq), store?.let { StoreSeq(seq) } ?: actron.observability.RecordOrdinal.Session, seq.milliseconds, entry)
        return buildList {
            add(record(JournalEntry.StoreRegistered(Capability.InspectOnly)))
            add(record(JournalEntry.StoreRegistered(Capability.InspectOnly), b))
            for (i in 1..processings) {
                val store = if (i % 2 == 0) b else a
                add(record(JournalEntry.InputAccepted(InputId(i.toLong()), InputDescriptor.Dispatch(Payload.Projected("Tap$i"))), store))
                add(record(JournalEntry.ProcessingStarted(InputId(i.toLong()), i.toLong()), store))
                add(record(JournalEntry.ProcessingFinished(InputId(i.toLong()), i.toLong(), OutcomeDescriptor(OutcomeKind.Unchanged), 1.milliseconds), store))
            }
            add(record(JournalEntry.JournalGap(2), null))
        }
    }

    @Test
    fun selection_filter_andRefresh() {
        val state = InspectorState(Inspector.of(records(4)))
        assertEquals(InspectorMode.Inspect, state.mode)
        assertNull(state.selectedItem)

        state.select(2)
        assertTrue(state.selectedItem is TimelineItem.Registered || state.selectedItem is TimelineItem.Processing)
        state.select(999)
        assertNull(state.selected, "out of range selects nothing")

        state.toggleFilter(a)
        assertEquals(a, state.storeFilter)
        assertTrue(state.visibleTimeline.all { (_, item) -> item.store == a || item.store == null }, "a's items and the session's own")
        assertTrue(state.visibleTimeline.any { (_, item) -> item is TimelineItem.Gap }, "the gap stays visible")
        val indices = state.visibleTimeline.map { it.index }
        assertEquals(indices.sorted(), indices, "indices are those of the whole timeline")
        state.toggleFilter(a)
        assertNull(state.storeFilter)

        state.select(state.inspector.timeline.lastIndex)
        state.refresh(Inspector.of(records(1)))
        assertNull(state.selected, "the selection fell off the shorter timeline")
        state.select(1)
        state.refresh(Inspector.of(records(6)))
        assertEquals(1, state.selected, "the selection stays on a longer timeline")
    }
}

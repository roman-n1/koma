package actron.timetravel.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import actron.core.ExperimentalActronApi
import actron.observability.RecordingSession
import actron.observability.RecordSubject
import actron.observability.StoreInstanceId
import actron.timetravel.Recording
import actron.timetravel.inspect.Inspector
import actron.timetravel.inspect.TimelineItem
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * What the inspector is doing (handoff §11): the modes look different, so no one mistakes a
 * replayed or branched history for the live one.
 */
@ExperimentalActronApi
enum class InspectorMode {
    /** The journal of a running session, refreshed as it grows. */
    Live,

    /** A history as recorded, read only. */
    Inspect,

    /** A recording decided again by the machine, position by position. */
    Replay,

    /** A branch from a position, with inputs of the viewer's choosing. */
    Branch,
}

/**
 * The state of an [InspectorScreen]: the [inspector] it shows, the selected position, the Store
 * the timeline is narrowed to, and the mode. Plain Compose state; [refresh] replaces the
 * inspector when the journal grew, keeping the selection where it still exists.
 */
@ExperimentalActronApi
@Stable
class InspectorState(initial: Inspector, mode: InspectorMode = InspectorMode.Inspect) {
    var inspector: Inspector by mutableStateOf(initial)
        private set

    var selection: InspectorSelection by mutableStateOf(InspectorSelection.Overview)
    var filter: StoreFilter by mutableStateOf(StoreFilter.All)

    var mode: InspectorMode by mutableStateOf(mode)

    /** Visits the selected timeline item only while it still belongs to the shown history. */
    fun withSelected(accept: (TimelineItem) -> Unit): Boolean {
        val current = selection
        if (current !is InspectorSelection.Position || current.index !in inspector.timeline.indices) return false
        accept(inspector.timeline[current.index])
        return true
    }
    val visibleTimeline: List<IndexedValue<TimelineItem>>
        get() = inspector.timeline.withIndex().filter { (_, item) -> filter.includes(item.subject) }

    /** Shows [inspector] from now on; the selection stays if it is still a position. */
    fun refresh(inspector: Inspector) {
        this.inspector = inspector
        val current = selection
        if (current is InspectorSelection.Position && current.index >= inspector.timeline.size) clearSelection()
    }

    /** Selects the position [index] of the whole timeline, or nothing. */
    fun select(index: Int) {
        selection = if (index in inspector.timeline.indices) InspectorSelection.Position(index) else InspectorSelection.Overview
    }
    fun clearSelection() { selection = InspectorSelection.Overview }

    /** Narrows the timeline to [store], or widens it again when it already is. */
    fun toggleFilter(store: StoreInstanceId) {
        filter = if (filter is StoreFilter.Single && (filter as StoreFilter.Single).store == store) StoreFilter.All else StoreFilter.Single(store)
    }
}

sealed interface InspectorSelection {
    data object Overview : InspectorSelection
    data class Position(val index: Int) : InspectorSelection
}

/** Selects the whole history or the records of one Store, retaining session-level diagnostics. */
sealed interface StoreFilter {
    fun includes(subject: RecordSubject): Boolean
    data object All : StoreFilter { override fun includes(subject: RecordSubject): Boolean = true }
    data class Single(val store: StoreInstanceId) : StoreFilter {
        override fun includes(subject: RecordSubject): Boolean = subject == store || subject == RecordSubject.Session
    }
}

/** Remembers an [InspectorState] for [inspector]; a new inspector replaces the shown one. */
@ExperimentalActronApi
@Composable
fun rememberInspectorState(inspector: Inspector, mode: InspectorMode = InspectorMode.Inspect): InspectorState {
    val state = remember { InspectorState(inspector, mode) }
    LaunchedEffect(inspector) { state.refresh(inspector) }
    return state
}

/**
 * Remembers an [InspectorState] in [InspectorMode.Live] over a running [session], rebuilt every
 * [every] from the session's retained records and [recordings]; a debug screen's view of the
 * app as it runs. The rebuild copies the retained records; keep [every] coarse.
 */
@ExperimentalActronApi
@Composable
fun rememberLiveInspector(
    session: RecordingSession,
    recordings: Map<StoreInstanceId, Recording<*, *, *, *>> = emptyMap(),
    every: Duration = 500.milliseconds,
): InspectorState {
    val state = remember(session) { InspectorState(Inspector.of(session, recordings), InspectorMode.Live) }
    LaunchedEffect(session, recordings, every) {
        while (true) {
            delay(every)
            state.refresh(Inspector.of(session, recordings))
        }
    }
    return state
}

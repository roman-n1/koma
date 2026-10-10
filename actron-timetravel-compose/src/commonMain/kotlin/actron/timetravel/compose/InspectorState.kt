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

    /** The index in [Inspector.timeline] of the selected position, or `null`. */
    var selected: Int? by mutableStateOf(null)

    /** The Store the timeline is narrowed to, or `null` for the whole group. */
    var storeFilter: StoreInstanceId? by mutableStateOf(null)

    var mode: InspectorMode by mutableStateOf(mode)

    /** The selected position, or `null`. */
    val selectedItem: TimelineItem? get() = selected?.let { inspector.timeline.getOrNull(it) }

    /** The timeline as the filter leaves it, with the index of each item in the whole timeline. */
    val visibleTimeline: List<IndexedValue<TimelineItem>>
        get() {
            val filter = storeFilter
            return inspector.timeline.withIndex().filter { (_, item) -> filter == null || item.store == filter || item.store == null }
        }

    /** Shows [inspector] from now on; the selection stays if it is still a position. */
    fun refresh(inspector: Inspector) {
        this.inspector = inspector
        val index = selected
        if (index != null && index >= inspector.timeline.size) selected = null
    }

    /** Selects the position [index] of the whole timeline, or nothing. */
    fun select(index: Int?) {
        selected = index?.takeIf { it in inspector.timeline.indices }
    }

    /** Narrows the timeline to [store], or widens it again when it already is. */
    fun toggleFilter(store: StoreInstanceId) {
        storeFilter = if (storeFilter == store) null else store
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

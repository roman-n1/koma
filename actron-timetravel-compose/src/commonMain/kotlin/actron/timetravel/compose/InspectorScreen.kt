package actron.timetravel.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import actron.core.ExperimentalActronApi
import actron.observability.StoreInstanceId
import actron.timetravel.inspect.Availability
import actron.timetravel.inspect.Completeness
import actron.timetravel.inspect.InspectorText
import actron.timetravel.inspect.StoreView
import actron.timetravel.inspect.TimelineItem
import kotlin.time.Duration.Companion.seconds

/**
 * The inspector of a group's history (handoff §11): the mode and the completeness on top, the
 * Stores with their capabilities and reasons on the left, the timeline in the middle, the
 * selected position on the right, or the branch when there is one; stacked on a narrow
 * screen. Everything shown comes from the [actron.timetravel.inspect.Inspector] in [state];
 * nothing here runs a Store, a [replay] moves a `ReplaySession` that decides and compares,
 * never executes, and a [branch] drives a `GroupBranch` that decides with the pure machines.
 * With a replay, the position panel draws the machine's definition as Mermaid with the
 * replay's active states highlighted.
 *
 * @param state The inspector and the selection
 * @param replay The replay of one Store's recording to drive, when the screen is in [InspectorMode.Replay]
 * @param branch The branch to drive, when the screen is in [InspectorMode.Branch]
 */
@ExperimentalActronApi
@Composable
fun InspectorScreen(state: InspectorState, modifier: Modifier = Modifier) = InspectorScreenContent(
    state, state.mode, modifier, navigation = {}, timeline = { TimelinePanel(state, it) }, widePosition = { PositionPanel(state, it) }, stackedPosition = { PositionPanel(state, it) },
)

@ExperimentalActronApi
@Composable
fun InspectorScreen(state: InspectorState, replay: ReplayControls<*, *, *, *>, modifier: Modifier = Modifier) = InspectorScreenContent(
    state, state.mode, modifier, navigation = { ReplayBar(replay, state) }, timeline = { TimelinePanel(state, it) }, widePosition = { PositionPanel(state, replay, it) }, stackedPosition = { PositionPanel(state, replay, it) },
)

@ExperimentalActronApi
@Composable
fun InspectorScreen(state: InspectorState, branch: BranchControls, modifier: Modifier = Modifier) = InspectorScreenContent(
    state, state.mode, modifier, navigation = {}, timeline = { TimelinePanel(state, it) }, widePosition = { BranchPanel(branch, it) }, stackedPosition = { PositionPanel(state, it) }, afterPosition = { BranchPanel(branch, it) },
)

/** A group's recording on one cursor; all members share one global position. */
@ExperimentalActronApi
@Composable
fun InspectorScreen(state: InspectorState, groupReplay: GroupReplayControls, modifier: Modifier = Modifier) = InspectorScreenContent(
    state, state.mode, modifier, navigation = { GroupReplayBar(groupReplay) }, timeline = { GroupTimelinePanel(groupReplay, state.filter, it) }, widePosition = { GroupPositionPanel(groupReplay, it) }, stackedPosition = { GroupPositionPanel(groupReplay, it) },
)

@ExperimentalActronApi
@Composable
fun InspectorScreen(state: InspectorState, groupReplay: GroupReplayControls, branch: BranchControls, modifier: Modifier = Modifier) = InspectorScreenContent(
    state, state.mode, modifier, navigation = { GroupReplayBar(groupReplay) }, timeline = { GroupTimelinePanel(groupReplay, state.filter, it) }, widePosition = { BranchPanel(branch, it) }, stackedPosition = { GroupPositionPanel(groupReplay, it) }, afterPosition = { BranchPanel(branch, it) },
)

/** Replay, experiment from the current group checkpoint, then return to recorded history. */
@ExperimentalActronApi
@Composable
fun InspectorScreen(state: InspectorState, timeTravel: GroupTimeTravelControls, modifier: Modifier = Modifier) {
    val travel = timeTravel.travel
    if (travel is GroupTravel.Experiment) InspectorScreenContent(
        state, timeTravel.mode, modifier, showTimeline = false,
        navigation = { GroupTimeTravelBar(timeTravel) }, timeline = { GroupTimelinePanel(timeTravel.replay, state.filter, it) },
        widePosition = { BranchPanel(travel.branch, it) }, stackedPosition = { GroupPositionPanel(timeTravel.replay, it) }, afterPosition = { BranchPanel(travel.branch, it) },
    ) else InspectorScreenContent(
        state, timeTravel.mode, modifier, navigation = { GroupTimeTravelBar(timeTravel); GroupReplayBar(timeTravel.replay) },
        timeline = { GroupTimelinePanel(timeTravel.replay, state.filter, it) }, widePosition = { GroupPositionPanel(timeTravel.replay, it) }, stackedPosition = { GroupPositionPanel(timeTravel.replay, it) },
    )
}

@OptIn(ExperimentalActronApi::class)
@Composable
private fun InspectorScreenContent(
    state: InspectorState, mode: InspectorMode, modifier: Modifier,
    navigation: @Composable () -> Unit,
    timeline: @Composable (Modifier) -> Unit,
    widePosition: @Composable (Modifier) -> Unit,
    stackedPosition: @Composable (Modifier) -> Unit,
    afterPosition: @Composable (Modifier) -> Unit = {},
    showTimeline: Boolean = true,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            ModeBarContent(state, mode)
            navigation()
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                if (maxWidth >= 900.dp) Row(modifier = Modifier.fillMaxSize()) {
                    StoresPanel(state, modifier = Modifier.weight(1f).fillMaxSize())
                    if (showTimeline) { VerticalRule(); timeline(Modifier.weight(2f).fillMaxSize()) }
                    VerticalRule()
                    widePosition(Modifier.weight(2f).fillMaxSize())
                } else Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    StoresPanel(state, modifier = Modifier.fillMaxWidth())
                    if (showTimeline) {
                        HorizontalDivider()
                        timeline(Modifier.fillMaxWidth().height(360.dp))
                        HorizontalDivider()
                        stackedPosition(Modifier.fillMaxWidth())
                    }
                    afterPosition(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** The mode, in its own colour, with the session and the group's completeness. */
@ExperimentalActronApi
@Composable
fun ModeBar(state: InspectorState, modifier: Modifier = Modifier) {
    ModeBarContent(state, state.mode, modifier)
}

@OptIn(ExperimentalActronApi::class)
@Composable
private fun ModeBarContent(state: InspectorState, mode: InspectorMode, modifier: Modifier = Modifier) {
    val inspector = state.inspector
    Row(
        modifier = modifier.fillMaxWidth().background(modeColor(mode)).padding(horizontal = 12.dp, vertical = 8.dp).semantics(mergeDescendants = true) {}.testTag("mode-bar"),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(mode.name.uppercase(), fontWeight = FontWeight.Bold, color = Color.White)
        var run = "session -  group -"
        inspector.withRun { session, group, _ -> run = "session $session  group $group" }
        Text("$run  records ${inspector.records.size}", color = Color.White)
        Text(InspectorText.completeness(inspector.completeness), fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.testTag("completeness"))
    }
}

/** The Stores of the group: capability, counts, recording, completeness and its reasons; a click narrows the timeline. */
@ExperimentalActronApi
@Composable
fun StoresPanel(state: InspectorState, modifier: Modifier = Modifier) {
    val inspector = state.inspector
    Column(modifier = modifier.padding(8.dp)) {
        Heading("Stores")
        val groupReasons = inspector.completeness.reasons.filter { it !in inspector.stores.flatMap { s -> s.completeness.reasons } }
        for (reason in groupReasons) ReasonLine(InspectorText.reason(reason))
        for (store in inspector.stores) StoreCard(store, selected = state.filter == StoreFilter.Single(store.id), replay = inspector.replayability(store.id)) { state.toggleFilter(store.id) }
        if (inspector.stores.isEmpty()) Text("no Stores in this journal", style = MaterialTheme.typography.bodySmall)
    }
}

@ExperimentalActronApi
@Composable
private fun StoreCard(store: StoreView, selected: Boolean, replay: Availability, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick).padding(6.dp).semantics(mergeDescendants = true) {}.testTag("store-${store.id.value}"),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(store.id.value, fontWeight = FontWeight.Bold)
            var capability = "unregistered"
            store.withCapability { capability = it.name }
            Text(capability, style = MaterialTheme.typography.bodySmall)
            Text(InspectorText.completeness(store.completeness), style = MaterialTheme.typography.bodySmall, color = if (store.completeness.isComplete) Color(0xFF2E7D32) else Color(0xFFC62828))
        }
        var revision = "-"
        store.withRevision { revision = it.toString() }
        Text(
            "records ${store.records}  processings ${store.processings}  revision $revision  ${if (store.closed) "closed" else "open"}  recording ${recordingLabel(store)}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        for (reason in store.completeness.reasons) ReasonLine(InspectorText.reason(reason))
        when (replay) {
            Availability.Available -> Text("replay available", style = MaterialTheme.typography.bodySmall, color = Color(0xFF2E7D32))
            is Availability.Unavailable -> for (why in replay.reasons) Text("replay unavailable: $why", style = MaterialTheme.typography.bodySmall, color = Color(0xFF8E24AA))
        }
    }
}

@OptIn(ExperimentalActronApi::class)
private fun recordingLabel(store: StoreView): String = when (val status = store.recording) {
    actron.timetravel.inspect.RecordingStatus.Unrecorded -> "none"
    is actron.timetravel.inspect.RecordingStatus.Attached -> "attached (${status.steps} steps)"
    is actron.timetravel.inspect.RecordingStatus.Mismatch -> "mismatch"
}

/** The timeline, one line per position, narrowed to the selected Store; a click selects. */
@ExperimentalActronApi
@Composable
fun TimelinePanel(state: InspectorState, modifier: Modifier = Modifier) {
    val visible = state.visibleTimeline
    Column(modifier = modifier.padding(8.dp)) {
        val filter = state.filter
        Heading("Timeline" + (if (filter is StoreFilter.Single) " of ${filter.store}" else "") + " (${visible.size})")
        LazyColumn(modifier = Modifier.fillMaxSize().testTag("timeline")) {
            itemsIndexed(visible, key = { _, indexed -> indexed.index }) { _, indexed ->
                val (index, item) = indexed
                val selected = state.selection == InspectorSelection.Position(index)
                Text(
                    InspectorText.line(item),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = itemColor(item),
                    modifier = Modifier.fillMaxWidth()
                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .clickable { state.select(index) }
                        .padding(vertical = 2.dp)
                        .testTag("timeline-$index"),
                )
            }
        }
    }
}

/**
 * The selected position in full: input and cause, outcome, active nodes, before and after, what
 * changed, commands, timers, events, failures; with a [replay], the machine's definition as
 * Mermaid with the replay's active states highlighted.
 */
@ExperimentalActronApi
@Composable
fun PositionPanel(state: InspectorState, modifier: Modifier = Modifier) = PositionPanelContent(state, modifier) {}

@ExperimentalActronApi
@Composable
fun PositionPanel(state: InspectorState, replay: ReplayControls<*, *, *, *>, modifier: Modifier = Modifier) = PositionPanelContent(state, modifier) {
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = { replay.explainNext() }, modifier = Modifier.testTag("explain-next")) { Text("Explain next input / check invariants") }
    val inspection = replay.inspection
    if (inspection is ChartInspection.Decided) Text(inspection.outcome)
    StateChartPanel(replay.session.machine.chart, replay.snapshot,
        previousActive = replay.session.recording.snapshotAt((replay.position - 1).coerceAtLeast(0)).configuration.active,
        inspection = inspection)
    DefinitionPanel(replay.mermaid(), "Definition of ${replay.store} at ${replay.position}", tag = "definition")
}

@OptIn(ExperimentalActronApi::class)
@Composable
private fun PositionPanelContent(state: InspectorState, modifier: Modifier, extra: @Composable () -> Unit) {
    val selection = state.selection
    Column(modifier = modifier.padding(8.dp).verticalScroll(rememberScrollState())) {
        Column(modifier = Modifier.semantics(mergeDescendants = true) {}.testTag("position")) {
            Heading("Position" + (if (selection is InspectorSelection.Position) " ${selection.index}" else ""))
            if (selection is InspectorSelection.Position && selection.index in state.inspector.timeline.indices) {
                val item = state.inspector.timeline[selection.index]
                for ((index, line) in InspectorText.detail(item).withIndex()) Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Normal)
                if (item is TimelineItem.Processing && item.attachment is actron.timetravel.inspect.ProcessingAttachment.JournalOnly) {
                    Spacer(Modifier.height(8.dp))
                    Text("snapshots are what the journal kept; attach the Store's recording to see the machine's", style = MaterialTheme.typography.bodySmall, color = Color(0xFF8E24AA))
                }
            } else Text("select a position of the timeline", style = MaterialTheme.typography.bodySmall)
        }
        extra()
    }
}

/** A definition as Mermaid text, the active states highlighted, selectable for pasting into a renderer; the screen renders no diagram (handoff §11). */
@ExperimentalActronApi
@Composable
fun DefinitionPanel(mermaid: String, title: String, tag: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.semantics(mergeDescendants = true) {}.testTag(tag)) {
        Heading(title)
        SelectionContainer {
            Text(mermaid, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * The branch: every member's snapshot, clock, awaiting and queued commands with the buttons to
 * complete, fail or answer them, the scripted inputs the application allows, the clock's
 * advance, the definition with the active states, and every decision made so far.
 */
@OptIn(ExperimentalLayoutApi::class)
@ExperimentalActronApi
@Composable
fun BranchPanel(branch: BranchControls, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(8.dp).verticalScroll(rememberScrollState()).testTag("branch")) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Heading("Branch")
            OutlinedButton(onClick = { branch.advance(1.seconds) }, modifier = Modifier.testTag("branch-advance")) { Text("advance 1s") }
        }
        val request = branch.request
        if (request is BranchRequest.Refused) { Text("! ${request.reason}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFC62828), modifier = Modifier.testTag("branch-problem")) }
        for (store in branch.members) BranchMemberCard(branch, store)
        Heading("Decisions (${branch.decisions.size})")
        for (decision in branch.decisions) {
            Text(BranchText.line(decision), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("branch-decision-${decision.index}"))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@ExperimentalActronApi
@Composable
private fun BranchMemberCard(branch: BranchControls, store: StoreInstanceId) {
    val snapshot = branch.snapshot(store)
    val inputs = branch.inputsOf(store)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(modifier = Modifier.semantics(mergeDescendants = true) {}.testTag("branch-${store.value}")) {
            Text("${store.value}  revision ${snapshot.revision}  now ${branch.now(store).sinceStart}", fontWeight = FontWeight.Bold)
            Text("active ${snapshot.configuration.active.map { it.value }.sorted().joinToString(",", "[", "]")}  context ${snapshot.context}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            for ((lane, registrations) in branch.queued(store)) {
                Text("queued in ${lane.value}: ${registrations.joinToString(",") { "c${it.id.value}" }}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (input in inputs) when (input) {
                is BranchInput.Dispatch -> OutlinedButton(onClick = { branch.dispatch(input) }, modifier = Modifier.testTag("branch-input-${store.value}-${input.label}")) { Text(input.label) }
                is BranchInput.Feed -> OutlinedButton(onClick = { branch.feed(input) }, modifier = Modifier.testTag("branch-input-${store.value}-${input.label}")) { Text("${input.label} (${input.source.value})") }
                is BranchInput.Answer -> Unit
            }
        }
        for (registration in branch.awaiting(store)) {
            val id = registration.id
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("branch-awaiting-${store.value}-${id.value}")) {
                Text("awaiting c${id.value} ${registration.command}${(registration.lane as? actron.statechart.machine.LaneId)?.let { " in ${it.value}" } ?: ""}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
                OutlinedButton(onClick = { branch.complete(store, id) }, modifier = Modifier.testTag("branch-complete-${store.value}-${id.value}")) { Text("complete") }
                OutlinedButton(onClick = { branch.fail(store, id) }, modifier = Modifier.testTag("branch-fail-${store.value}-${id.value}")) { Text("fail") }
                for (input in inputs) if (input is BranchInput.Answer) {
                    OutlinedButton(onClick = { branch.answer(input, id) }, modifier = Modifier.testTag("branch-answer-${store.value}-${id.value}-${input.label}")) { Text(input.label) }
                }
            }
        }
        DefinitionPanel(branch.mermaid(store), "Definition of ${store.value}", tag = "branch-definition-${store.value}")
    }
}

/** Back, forward, seek and verify over a [ReplayControls]; a disabled button says why. */
@ExperimentalActronApi
@Composable
fun ReplayBar(replay: ReplayControls<*, *, *, *>, state: InspectorState, modifier: Modifier = Modifier) {
    var seeking by remember { mutableStateOf<SeekGesture>(SeekGesture.Idle) }
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag("replay-bar")) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { replay.stepBackward(); state.clearSelection() }, enabled = replay.canStepBackward, modifier = Modifier.testTag("replay-back")) { Text("◀ back") }
            OutlinedButton(onClick = { replay.stepForward(); state.clearSelection() }, enabled = replay.canStepForward && replay.movement is ReplayMovement.Open, modifier = Modifier.testTag("replay-forward")) { Text("forward ▶") }
            OutlinedButton(onClick = { replay.verify() }, modifier = Modifier.testTag("replay-verify")) { Text("verify") }
            Text("${replay.position} / ${replay.length} of ${replay.store}", modifier = Modifier.padding(top = 12.dp).testTag("replay-position"))
        }
        Slider(
            value = if (seeking is SeekGesture.Dragging) (seeking as SeekGesture.Dragging).value else replay.position.toFloat(),
            onValueChange = { seeking = SeekGesture.Dragging(it) },
            onValueChangeFinished = {
                val gesture = seeking
                if (gesture is SeekGesture.Dragging) replay.seek(gesture.value.toInt())
                seeking = SeekGesture.Idle
                state.clearSelection()
            },
            valueRange = 0f..replay.length.toFloat().coerceAtLeast(1f),
            steps = (replay.length - 1).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth().testTag("replay-seek"),
        )
        val availability = replay.forwardAvailability
        if (availability is Availability.Unavailable) for (why in availability.reasons) { Text("forward disabled: $why", style = MaterialTheme.typography.bodySmall, color = Color(0xFF8E24AA), modifier = Modifier.testTag("replay-why")) }
        val verification = replay.verification
        if (verification is ReplayVerification.Checked) { Text(verification.verdict, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("replay-verdict")) }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun ReasonLine(text: String) {
    Text("! $text", style = MaterialTheme.typography.bodySmall, color = Color(0xFFC62828), modifier = Modifier.padding(start = 4.dp))
}

@Composable
private fun VerticalRule() {
    Spacer(Modifier.width(1.dp).fillMaxSize().background(MaterialTheme.colorScheme.outlineVariant))
}

@ExperimentalActronApi
private fun modeColor(mode: InspectorMode): Color = when (mode) {
    InspectorMode.Live -> Color(0xFF2E7D32)
    InspectorMode.Inspect -> Color(0xFF1565C0)
    InspectorMode.Replay -> Color(0xFF6A1B9A)
    InspectorMode.Branch -> Color(0xFFEF6C00)
}

@ExperimentalActronApi
private fun itemColor(item: TimelineItem): Color = when (item) {
    is TimelineItem.Processing -> Color.Unspecified
    is TimelineItem.Discarded, is TimelineItem.Rejected, is TimelineItem.Pending, is TimelineItem.Dropped -> Color(0xFFEF6C00)
    is TimelineItem.Gap, is TimelineItem.Damage, is TimelineItem.Stopped, is TimelineItem.Unattributed -> Color(0xFFC62828)
    is TimelineItem.Checkpoint -> Color(0xFF1565C0)
    is TimelineItem.Effect, is TimelineItem.Sent, is TimelineItem.Registered, is TimelineItem.Closed, is TimelineItem.Abandoned -> Color(0xFF616161)
}

/** For a screen that shows a completeness elsewhere. */
@ExperimentalActronApi
fun Completeness.label(): String = InspectorText.completeness(this)

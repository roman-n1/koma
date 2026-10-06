package koma.timetravel.compose

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
import koma.core.ExperimentalKomaApi
import koma.observability.StoreInstanceId
import koma.timetravel.inspect.Availability
import koma.timetravel.inspect.Completeness
import koma.timetravel.inspect.InspectorText
import koma.timetravel.inspect.StoreView
import koma.timetravel.inspect.TimelineItem
import kotlin.time.Duration.Companion.seconds

/**
 * The inspector of a group's history (handoff §11): the mode and the completeness on top, the
 * Stores with their capabilities and reasons on the left, the timeline in the middle, the
 * selected position on the right, or the branch when there is one; stacked on a narrow
 * screen. Everything shown comes from the [koma.timetravel.inspect.Inspector] in [state];
 * nothing here runs a Store, a [replay] moves a `ReplaySession` that decides and compares,
 * never executes, and a [branch] drives a `GroupBranch` that decides with the pure machines.
 * With a replay, the position panel draws the machine's definition as Mermaid with the
 * replay's active states highlighted.
 *
 * @param state The inspector and the selection
 * @param replay The replay of one Store's recording to drive, when the screen is in [InspectorMode.Replay]
 * @param branch The branch to drive, when the screen is in [InspectorMode.Branch]
 */
@ExperimentalKomaApi
@Composable
fun InspectorScreen(state: InspectorState, replay: ReplayControls<*, *, *, *>? = null, branch: BranchControls? = null, modifier: Modifier = Modifier) {
    InspectorScreenContent(state, replay, null, branch, modifier)
}

/** A group's recording on one cursor; all members are restored at the same global position. */
@ExperimentalKomaApi
@Composable
fun InspectorScreen(state: InspectorState, groupReplay: GroupReplayControls, branch: BranchControls? = null, modifier: Modifier = Modifier) {
    InspectorScreenContent(state, null, groupReplay, branch, modifier)
}

/** Replay, experiment from the current group position, then return to recorded history. */
@ExperimentalKomaApi
@Composable
fun InspectorScreen(state: InspectorState, timeTravel: GroupTimeTravelControls, modifier: Modifier = Modifier) {
    InspectorScreenContent(state, null, timeTravel.replay, timeTravel.branch, modifier, timeTravel)
}

@OptIn(ExperimentalKomaApi::class)
@Composable
private fun InspectorScreenContent(state: InspectorState, replay: ReplayControls<*, *, *, *>?, groupReplay: GroupReplayControls?, branch: BranchControls?, modifier: Modifier, timeTravel: GroupTimeTravelControls? = null) {
    val showReplay = timeTravel?.branch == null
    Surface(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            ModeBarContent(state, timeTravel?.mode ?: state.mode)
            if (timeTravel != null) GroupTimeTravelBar(timeTravel)
            if (replay != null) ReplayBar(replay, state)
            if (groupReplay != null && showReplay) GroupReplayBar(groupReplay)
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                if (maxWidth >= 900.dp) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        StoresPanel(state, modifier = Modifier.weight(1f).fillMaxSize())
                        if (showReplay) {
                            VerticalRule()
                            if (groupReplay != null) GroupTimelinePanel(groupReplay, state.storeFilter, modifier = Modifier.weight(2f).fillMaxSize()) else TimelinePanel(state, modifier = Modifier.weight(2f).fillMaxSize())
                        }
                        VerticalRule()
                        when {
                            branch != null -> BranchPanel(branch, modifier = Modifier.weight(2f).fillMaxSize())
                            groupReplay != null -> GroupPositionPanel(groupReplay, modifier = Modifier.weight(2f).fillMaxSize())
                            else -> PositionPanel(state, replay, modifier = Modifier.weight(2f).fillMaxSize())
                        }
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        StoresPanel(state, modifier = Modifier.fillMaxWidth())
                        if (showReplay) {
                            HorizontalDivider()
                            if (groupReplay != null) GroupTimelinePanel(groupReplay, state.storeFilter, modifier = Modifier.fillMaxWidth().height(360.dp)) else TimelinePanel(state, modifier = Modifier.fillMaxWidth().height(360.dp))
                            HorizontalDivider()
                            if (groupReplay != null) GroupPositionPanel(groupReplay, modifier = Modifier.fillMaxWidth()) else PositionPanel(state, replay, modifier = Modifier.fillMaxWidth())
                        }
                        if (branch != null) {
                            HorizontalDivider()
                            BranchPanel(branch, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

/** The mode, in its own colour, with the session and the group's completeness. */
@ExperimentalKomaApi
@Composable
fun ModeBar(state: InspectorState, modifier: Modifier = Modifier) {
    ModeBarContent(state, state.mode, modifier)
}

@OptIn(ExperimentalKomaApi::class)
@Composable
private fun ModeBarContent(state: InspectorState, mode: InspectorMode, modifier: Modifier = Modifier) {
    val inspector = state.inspector
    Row(
        modifier = modifier.fillMaxWidth().background(modeColor(mode)).padding(horizontal = 12.dp, vertical = 8.dp).semantics(mergeDescendants = true) {}.testTag("mode-bar"),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(mode.name.uppercase(), fontWeight = FontWeight.Bold, color = Color.White)
        Text("session ${inspector.session ?: "-"}  group ${inspector.group ?: "-"}  records ${inspector.records.size}", color = Color.White)
        Text(InspectorText.completeness(inspector.completeness), fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.testTag("completeness"))
    }
}

/** The Stores of the group: capability, counts, recording, completeness and its reasons; a click narrows the timeline. */
@ExperimentalKomaApi
@Composable
fun StoresPanel(state: InspectorState, modifier: Modifier = Modifier) {
    val inspector = state.inspector
    Column(modifier = modifier.padding(8.dp)) {
        Heading("Stores")
        val groupReasons = inspector.completeness.reasons.filter { it !in inspector.stores.flatMap { s -> s.completeness.reasons } }
        for (reason in groupReasons) ReasonLine(InspectorText.reason(reason))
        for (store in inspector.stores) StoreCard(store, selected = state.storeFilter == store.id, replay = inspector.replayability(store.id)) { state.toggleFilter(store.id) }
        if (inspector.stores.isEmpty()) Text("no Stores in this journal", style = MaterialTheme.typography.bodySmall)
    }
}

@ExperimentalKomaApi
@Composable
private fun StoreCard(store: StoreView, selected: Boolean, replay: Availability, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick).padding(6.dp).semantics(mergeDescendants = true) {}.testTag("store-${store.id.value}"),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(store.id.value, fontWeight = FontWeight.Bold)
            Text(store.capability?.name ?: "unregistered", style = MaterialTheme.typography.bodySmall)
            Text(InspectorText.completeness(store.completeness), style = MaterialTheme.typography.bodySmall, color = if (store.completeness.isComplete) Color(0xFF2E7D32) else Color(0xFFC62828))
        }
        Text(
            "records ${store.records}  processings ${store.processings}  revision ${store.revision ?: "-"}  ${if (store.closed) "closed" else "open"}  recording ${recordingLabel(store)}",
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

private fun recordingLabel(store: StoreView): String = when (val status = store.recording) {
    koma.timetravel.inspect.RecordingStatus.None -> "none"
    is koma.timetravel.inspect.RecordingStatus.Attached -> "attached (${status.steps} steps)"
    is koma.timetravel.inspect.RecordingStatus.Mismatch -> "mismatch"
}

/** The timeline, one line per position, narrowed to the selected Store; a click selects. */
@ExperimentalKomaApi
@Composable
fun TimelinePanel(state: InspectorState, modifier: Modifier = Modifier) {
    val visible = state.visibleTimeline
    Column(modifier = modifier.padding(8.dp)) {
        Heading("Timeline" + (state.storeFilter?.let { " of $it" } ?: "") + " (${visible.size})")
        LazyColumn(modifier = Modifier.fillMaxSize().testTag("timeline")) {
            itemsIndexed(visible, key = { _, indexed -> indexed.index }) { _, indexed ->
                val (index, item) = indexed
                val selected = state.selected == index
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
@ExperimentalKomaApi
@Composable
fun PositionPanel(state: InspectorState, replay: ReplayControls<*, *, *, *>? = null, modifier: Modifier = Modifier) {
    val item = state.selectedItem
    Column(modifier = modifier.padding(8.dp).verticalScroll(rememberScrollState())) {
        Column(modifier = Modifier.semantics(mergeDescendants = true) {}.testTag("position")) {
            Heading("Position" + (state.selected?.let { " $it" } ?: ""))
            if (item == null) {
                Text("select a position of the timeline", style = MaterialTheme.typography.bodySmall)
            } else {
                val lines = InspectorText.detail(item)
                for ((index, line) in lines.withIndex()) {
                    Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Normal)
                }
                if (item is TimelineItem.Processing && item.recorded == null) {
                    Spacer(Modifier.height(8.dp))
                    Text("snapshots are what the journal kept; attach the Store's recording to see the machine's", style = MaterialTheme.typography.bodySmall, color = Color(0xFF8E24AA))
                }
            }
        }
        if (replay != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { replay.explainNext() }, modifier = Modifier.testTag("explain-next")) { Text("Explain next input / check invariants") }
            replay.explanationOutcome?.let { Text(it) }
            StateChartPanel(replay.session.machine.chart, replay.snapshot,
                previous = replay.session.recording.snapshotAt((replay.position - 1).coerceAtLeast(0)).configuration,
                explanation = replay.explanation, violations = replay.invariantViolations)
            DefinitionPanel(replay.mermaid(), "Definition of ${replay.store} at ${replay.position}", tag = "definition")
        }
    }
}

/** A definition as Mermaid text, the active states highlighted, selectable for pasting into a renderer; the screen renders no diagram (handoff §11). */
@ExperimentalKomaApi
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
@ExperimentalKomaApi
@Composable
fun BranchPanel(branch: BranchControls, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(8.dp).verticalScroll(rememberScrollState()).testTag("branch")) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Heading("Branch")
            OutlinedButton(onClick = { branch.advance(1.seconds) }, modifier = Modifier.testTag("branch-advance")) { Text("advance 1s") }
        }
        branch.problem?.let { Text("! $it", style = MaterialTheme.typography.bodySmall, color = Color(0xFFC62828), modifier = Modifier.testTag("branch-problem")) }
        for (store in branch.members) BranchMemberCard(branch, store)
        Heading("Decisions (${branch.decisions.size})")
        for (decision in branch.decisions) {
            Text(BranchText.line(decision), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("branch-decision-${decision.index}"))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@ExperimentalKomaApi
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
                Text("awaiting c${id.value} ${registration.command}${registration.lane?.let { " in ${it.value}" } ?: ""}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
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
@ExperimentalKomaApi
@Composable
fun ReplayBar(replay: ReplayControls<*, *, *, *>, state: InspectorState, modifier: Modifier = Modifier) {
    var seeking by remember { mutableStateOf<Float?>(null) }
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag("replay-bar")) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { replay.stepBackward(); state.select(null) }, enabled = replay.canStepBackward, modifier = Modifier.testTag("replay-back")) { Text("◀ back") }
            OutlinedButton(onClick = { replay.stepForward(); state.select(null) }, enabled = replay.canStepForward && replay.divergence == null, modifier = Modifier.testTag("replay-forward")) { Text("forward ▶") }
            OutlinedButton(onClick = { replay.verify() }, modifier = Modifier.testTag("replay-verify")) { Text("verify") }
            Text("${replay.position} / ${replay.length} of ${replay.store}", modifier = Modifier.padding(top = 12.dp).testTag("replay-position"))
        }
        Slider(
            value = seeking ?: replay.position.toFloat(),
            onValueChange = { seeking = it },
            onValueChangeFinished = {
                seeking?.let { replay.seek(it.toInt()) }
                seeking = null
                state.select(null)
            },
            valueRange = 0f..replay.length.toFloat().coerceAtLeast(1f),
            steps = (replay.length - 1).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth().testTag("replay-seek"),
        )
        replay.forwardUnavailable?.let { Text("forward disabled: $it", style = MaterialTheme.typography.bodySmall, color = Color(0xFF8E24AA), modifier = Modifier.testTag("replay-why")) }
        replay.verdict?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("replay-verdict")) }
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

@ExperimentalKomaApi
private fun modeColor(mode: InspectorMode): Color = when (mode) {
    InspectorMode.Live -> Color(0xFF2E7D32)
    InspectorMode.Inspect -> Color(0xFF1565C0)
    InspectorMode.Replay -> Color(0xFF6A1B9A)
    InspectorMode.Branch -> Color(0xFFEF6C00)
}

@ExperimentalKomaApi
private fun itemColor(item: TimelineItem): Color = when (item) {
    is TimelineItem.Processing -> Color.Unspecified
    is TimelineItem.Discarded, is TimelineItem.Rejected, is TimelineItem.Pending, is TimelineItem.Dropped -> Color(0xFFEF6C00)
    is TimelineItem.Gap, is TimelineItem.Damage, is TimelineItem.Stopped, is TimelineItem.Unattributed -> Color(0xFFC62828)
    is TimelineItem.Checkpoint -> Color(0xFF1565C0)
    is TimelineItem.Effect, is TimelineItem.Sent, is TimelineItem.Registered, is TimelineItem.Closed, is TimelineItem.Abandoned -> Color(0xFF616161)
}

/** For a screen that shows a completeness elsewhere. */
@ExperimentalKomaApi
fun Completeness.label(): String = InspectorText.completeness(this)

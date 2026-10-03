package koma.timetravel.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import koma.timetravel.RecordedStep

/** Movement and validation of the whole group, independent of the timeline's Store filter. */
@ExperimentalKomaApi
@Composable
fun GroupReplayBar(replay: GroupReplayControls, modifier: Modifier = Modifier) {
    var seeking by remember(replay) { mutableStateOf<Float?>(null) }
    Column(modifier = modifier.fillMaxWidth().padding(8.dp).testTag("group-replay-bar")) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = replay::stepBackward, enabled = replay.canStepBackward, modifier = Modifier.testTag("group-replay-back")) { Text("◀ back") }
            OutlinedButton(onClick = replay::stepForward, enabled = replay.canStepForward, modifier = Modifier.testTag("group-replay-forward")) { Text("forward ▶") }
            OutlinedButton(onClick = replay::verify, modifier = Modifier.testTag("group-replay-verify")) { Text("verify group") }
            Text("${replay.position} / ${replay.length} of group", modifier = Modifier.padding(top = 12.dp).testTag("group-replay-position"))
        }
        Slider(
            value = seeking ?: replay.position.toFloat(),
            onValueChange = { seeking = it },
            onValueChangeFinished = { seeking?.let { replay.seek(it.toInt()) }; seeking = null },
            valueRange = 0f..replay.length.toFloat().coerceAtLeast(1f),
            steps = (replay.length - 1).coerceAtLeast(0),
            enabled = replay.length > 0,
            modifier = Modifier.testTag("group-replay-seek"),
        )
        replay.forwardUnavailable?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("group-replay-why")) }
        replay.verification?.let { problems ->
            val verdict = if (problems.isEmpty()) "no divergence in ${replay.length} group steps" else problems.joinToString("\n") { it.explain() }
            Text(verdict, modifier = Modifier.testTag("group-replay-verdict"))
        }
    }
}

/** The recording's inputs in their global order; a click seeks after that input. */
@ExperimentalKomaApi
@Composable
fun GroupTimelinePanel(replay: GroupReplayControls, storeFilter: StoreInstanceId? = null, modifier: Modifier = Modifier) {
    val visible = replay.session.recording.order.withIndex().filter { storeFilter == null || it.value.store == storeFilter }
    Column(modifier = modifier.padding(8.dp)) {
        Text("Group recording (${visible.size} inputs)", style = MaterialTheme.typography.titleSmall)
        LazyColumn(modifier = Modifier.fillMaxSize().testTag("group-timeline")) {
            items(visible, key = { it.index }) { indexed ->
                val item = replay.at(indexed.index)
                Text(
                    "#${item.index} ${item.store.value}: ${item.recorded.input}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth()
                        .background(if (replay.selected?.index == item.index) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .clickable { replay.seek(item.index + 1) }.padding(vertical = 4.dp).testTag("group-step-${item.index}"),
                )
            }
        }
    }
}

/** Expected transition details and every member's checkpoint at the group's current prefix. */
@ExperimentalKomaApi
@Composable
fun GroupPositionPanel(replay: GroupReplayControls, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(8.dp).verticalScroll(rememberScrollState())) {
        Column(modifier = Modifier.semantics(mergeDescendants = true) {}.testTag("group-position")) {
            Text("Group state at ${replay.position}", style = MaterialTheme.typography.titleSmall)
            val item = replay.selected
            if (item == null) {
                Text("before the first input")
            } else {
                Text("#${item.index} ${item.store.value}: ${item.recorded.input}")
                Text("recorded before: ${item.before.context}", fontFamily = FontFamily.Monospace)
                Text("recorded after: ${item.after.context}", fontFamily = FontFamily.Monospace)
                Text("entered ${item.diff.entered.map { it.value }.sorted()}  exited ${item.diff.exited.map { it.value }.sorted()}")
                when (val step = item.recorded) {
                    is RecordedStep.Committed<*, *, *, *> -> {
                        Text("transitions ${step.decision.transitions}")
                        Text("commands ${step.decision.commands}  timers ${step.decision.timersScheduled}  effects ${step.decision.effects}")
                    }
                    is RecordedStep.Ignored<*, *, *, *> -> Text("ignored: ${step.reason}")
                    is RecordedStep.Failed<*, *, *, *> -> Text("failed: ${step.failure}")
                }
            }
        }
        for (store in replay.members) {
            val snapshot = replay.snapshot(store)
            val checkpoint = replay.checkpoint(store)
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics(mergeDescendants = true) {}.testTag("group-member-${store.value}")) {
                Text("${store.value}  revision ${snapshot.revision}", fontWeight = FontWeight.Bold)
                Text("active ${snapshot.configuration.active.map { it.value }.sorted()}  context ${snapshot.context}", fontFamily = FontFamily.Monospace)
                Text("recorded clock ${checkpoint.now.sinceStart}  timers ${snapshot.timers.keys}")
                Text("running ${checkpoint.lanes.running.keys}  queued ${checkpoint.lanes.queued}  ending ${checkpoint.ending.keys}")
                Text("pending effects ${checkpoint.effects}")
            }
            DefinitionPanel(replay.mermaid(store), "Definition of ${store.value} at ${replay.position}", "group-definition-${store.value}")
        }
    }
}

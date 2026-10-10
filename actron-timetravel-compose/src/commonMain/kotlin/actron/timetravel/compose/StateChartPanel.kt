package actron.timetravel.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import actron.statechart.*
import actron.statechart.machine.*

/**
 * Interactive diagram over read-only model/snapshot data. Select a node or transition to inspect
 * activity, timers, candidate disposition and actual guard evaluations. No guard or decision runs
 * during composition, and no context/action payload is rendered implicitly.
 */
@Composable
fun <C : Any> StateChartPanel(
    chart: StateChartDefinition,
    snapshot: MachineSnapshot<C>,
    previousActive: Set<StateId> = emptySet(),
    inspection: ChartInspection = ChartInspection.Unrequested,
    modifier: Modifier = Modifier,
) {
    var selection by remember(chart) { mutableStateOf<ChartSelection>(ChartSelection.Overview) }
    val nodes = remember(chart) { chart.states.distinctBy { it.id } }
    val positions = remember(nodes) { nodes.mapIndexed { index, node -> node.id to (index % 3 to index / 3) }.toMap() }
    val outline = MaterialTheme.colorScheme.outlineVariant
    val highlight = MaterialTheme.colorScheme.primary
    Column(modifier.testTag("statechart-panel"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Revision ${snapshot.revision} · ${snapshot.commands.size} commands · ${snapshot.timers.size} timers")
        for (violation in inspection.violations) Text("Invariant failed: ${violation.name}", color = MaterialTheme.colorScheme.error)
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            Box(Modifier.width(580.dp).height((maxOf(1, (nodes.size + 2) / 3) * 96).dp)) {
                Canvas(Modifier.matchParentSize()) {
                    fun center(id: StateId): Offset {
                        val (x, y) = positions.getValue(id)
                        return Offset((x * 190 + 97).dp.toPx(), (y * 96 + 48).dp.toPx())
                    }
                    for ((index, transition) in chart.transitions.withIndex()) {
                        if (transition.source !in positions || transition.target !in positions) continue
                        val from = center(transition.source)
                        val to = center(transition.target)
                        val id = TransitionId(index)
                        var selected = selection == ChartSelection.Transition(id)
                        inspection.withExplanation { explanation -> if (explanation.candidates.any { it.transition == id && it.disposition == CandidateDisposition.Selected }) selected = true }
                        val color = if (selected) highlight else outline
                        if (from == to) drawCircle(color, 26.dp.toPx(), from + Offset(55.dp.toPx(), -12.dp.toPx()), style = Stroke(2.dp.toPx()))
                        else {
                            val vector = to - from
                            val length = vector.getDistance()
                            val direction = vector / length
                            val xDistance = if (kotlin.math.abs(direction.x) < 0.001f) Float.POSITIVE_INFINITY else 79.dp.toPx() / kotlin.math.abs(direction.x)
                            val yDistance = if (kotlin.math.abs(direction.y) < 0.001f) Float.POSITIVE_INFINITY else 30.dp.toPx() / kotlin.math.abs(direction.y)
                            val border = minOf(xDistance, yDistance) + 5.dp.toPx()
                            val end = to - direction * border
                            val start = from + direction * border
                            drawLine(color, start, end, strokeWidth = 2.dp.toPx())
                            val normal = Offset(-direction.y, direction.x)
                            drawLine(color, end, end - direction * 9.dp.toPx() + normal * 5.dp.toPx(), strokeWidth = 2.dp.toPx())
                            drawLine(color, end, end - direction * 9.dp.toPx() - normal * 5.dp.toPx(), strokeWidth = 2.dp.toPx())
                        }
                    }
                }
                for (node in nodes) {
                    val (x, y) = positions.getValue(node.id)
                    val active = snapshot.isActive(node.id)
                    val wasActive = node.id in previousActive
                    Surface(
                        color = when { active -> MaterialTheme.colorScheme.primaryContainer; wasActive -> MaterialTheme.colorScheme.tertiaryContainer; else -> MaterialTheme.colorScheme.surfaceVariant },
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.offset((x * 190 + 18).dp, (y * 96 + 18).dp).width(158.dp).height(60.dp)
                            .clickable { selection = ChartSelection.Node(node.id) }.testTag("chart-node-${node.id.value}"),
                    ) {
                        Column(Modifier.padding(8.dp)) {
                            Text(node.id.value, maxLines = 1)
                            Text(if (active) "active" else if (wasActive) "previous" else "inactive", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        for ((index, transition) in chart.transitions.withIndex()) {
            OutlinedButton(onClick = { selection = ChartSelection.Transition(TransitionId(index)) }, modifier = Modifier.testTag("chart-transition-$index")) {
                Text("T$index: ${transition.source} → ${transition.target} · ${transition.triggerLabel}")
            }
        }
        val selected = selection
        if (selected is ChartSelection.Node) {
            val id = selected.id
            Text("State $id · parent ${chart.node(id).parent} · activation ${snapshot.activations[id] ?: "none"}", modifier = Modifier.testTag("chart-detail"))
            snapshot.timers.forEach { (timer, record) ->
                if (chart.transitions.getOrNull(record.transition.index)?.source == id) Text("Timer $timer · ${record.deadline}")
            }
        }
        if (selected is ChartSelection.Transition) {
            val id = selected.id
            val transition = chart.transitions[id.index]
            var disposition = "no evaluation recorded"
            inspection.withExplanation { explanation ->
                for (candidate in explanation.candidates) if (candidate.transition == id) { disposition = candidate.disposition.toString(); break }
            }
            Text("$id · ${transition.kind} · $disposition", modifier = Modifier.testTag("chart-detail"))
            if (inspection is ChartInspection.Decided) for (guard in inspection.explanation.guards) if (guard.transition == id) Text("${guard.label} = ${guard.result}")
        }
    }
}

private sealed interface ChartSelection {
    data object Overview : ChartSelection
    data class Node(val id: StateId) : ChartSelection
    data class Transition(val id: TransitionId) : ChartSelection
}

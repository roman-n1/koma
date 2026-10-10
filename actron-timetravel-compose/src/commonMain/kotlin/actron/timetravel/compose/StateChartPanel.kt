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
fun <C> StateChartPanel(
    chart: StateChartDefinition,
    snapshot: MachineSnapshot<C>,
    previous: StateConfiguration? = null,
    explanation: DecisionExplanation? = null,
    violations: List<InvariantViolation> = emptyList(),
    modifier: Modifier = Modifier,
) {
    var selectedNode by remember(chart) { mutableStateOf<StateId?>(null) }
    var selectedTransition by remember(chart) { mutableStateOf<TransitionId?>(null) }
    val nodes = remember(chart) { chart.states.distinctBy { it.id } }
    val positions = remember(nodes) { nodes.mapIndexed { index, node -> node.id to (index % 3 to index / 3) }.toMap() }
    val outline = MaterialTheme.colorScheme.outlineVariant
    val highlight = MaterialTheme.colorScheme.primary
    Column(modifier.testTag("statechart-panel"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Revision ${snapshot.revision} · ${snapshot.commands.size} commands · ${snapshot.timers.size} timers")
        for (violation in violations) Text("Invariant failed: ${violation.name}", color = MaterialTheme.colorScheme.error)
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            Box(Modifier.width(580.dp).height((maxOf(1, (nodes.size + 2) / 3) * 96).dp)) {
                Canvas(Modifier.matchParentSize()) {
                    fun center(id: StateId): Offset? = positions[id]?.let { (x, y) -> Offset((x * 190 + 97).dp.toPx(), (y * 96 + 48).dp.toPx()) }
                    for ((index, transition) in chart.transitions.withIndex()) {
                        val from = center(transition.source) ?: continue
                        val to = center(transition.target) ?: continue
                        val id = TransitionId(index)
                        val color = if (selectedTransition == id || explanation?.candidates?.any { it.transition == id && it.disposition == CandidateDisposition.Selected } == true) highlight else outline
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
                    val wasActive = node.id in previous?.active.orEmpty()
                    Surface(
                        color = when { active -> MaterialTheme.colorScheme.primaryContainer; wasActive -> MaterialTheme.colorScheme.tertiaryContainer; else -> MaterialTheme.colorScheme.surfaceVariant },
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.offset((x * 190 + 18).dp, (y * 96 + 18).dp).width(158.dp).height(60.dp)
                            .clickable { selectedNode = node.id; selectedTransition = null }.testTag("chart-node-${node.id.value}"),
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
            OutlinedButton(onClick = { selectedTransition = TransitionId(index); selectedNode = null }, modifier = Modifier.testTag("chart-transition-$index")) {
                Text("T$index: ${transition.source} → ${transition.target} · ${transition.on?.name ?: transition.trigger}")
            }
        }
        selectedNode?.let { id ->
            Text("State $id · parent ${chart.node(id)?.parent ?: "root"} · activation ${snapshot.activations[id] ?: "none"}", modifier = Modifier.testTag("chart-detail"))
            snapshot.timers.forEach { (timer, record) ->
                if (chart.transitions.getOrNull(record.transition.index)?.source == id) Text("Timer $timer · ${record.deadline}")
            }
        }
        selectedTransition?.let { id ->
            val transition = chart.transitions[id.index]
            val disposition = explanation?.candidates?.firstOrNull { it.transition == id }?.disposition
            Text("$id · ${transition.kind} · ${disposition ?: "no evaluation recorded"}", modifier = Modifier.testTag("chart-detail"))
            explanation?.guards?.filter { it.transition == id }?.forEach { guard -> Text("${guard.label} = ${guard.result ?: "failed"}") }
        }
    }
}

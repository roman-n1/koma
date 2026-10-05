@file:OptIn(koma.core.ExperimentalKomaApi::class)
package consumer

import androidx.compose.runtime.Composable
import koma.core.Action
import koma.core.Event
import koma.core.State
import koma.core.Store
import koma.statechart.machine.Machine
import koma.statechart.test.MachineTestDriver
import koma.test.startAndAwait
import koma.timetravel.compose.InspectorScreen
import koma.timetravel.compose.InspectorState
import koma.timetravel.inspect.Inspector
import kotlinx.coroutines.test.TestScope

fun <C, A : Action, CMD, E : Event> publishedDriver(machine: Machine<C, A, CMD, E>, context: C, scope: TestScope) =
    MachineTestDriver(machine, context, scope)

suspend fun <S : State, A : Action, E : Event> publishedTestHelper(store: Store<S, A, E>) = store.startAndAwait()

@Composable
fun PublishedInspector() = InspectorScreen(InspectorState(Inspector(emptyList())))

@file:OptIn(actron.core.ExperimentalActronApi::class)
package consumer

import androidx.compose.runtime.Composable
import actron.core.Action
import actron.core.Event
import actron.core.State
import actron.core.Store
import actron.statechart.machine.Machine
import actron.statechart.test.MachineTestDriver
import actron.test.startAndAwait
import actron.timetravel.compose.InspectorScreen
import actron.timetravel.compose.InspectorState
import actron.timetravel.inspect.Inspector
import kotlinx.coroutines.test.TestScope

fun <C, A : Action, CMD, E : Event> publishedDriver(machine: Machine<C, A, CMD, E>, context: C, scope: TestScope) =
    MachineTestDriver(machine, context, scope)

suspend fun <S : State, A : Action, E : Event> publishedTestHelper(store: Store<S, A, E>) = store.startAndAwait()

@Composable
fun PublishedInspector() = InspectorScreen(InspectorState(Inspector(emptyList())))

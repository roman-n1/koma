@file:OptIn(actron.core.ExperimentalActronApi::class)
package consumer

import actron.core.ExceptionHandler
import actron.message.message
import actron.statechart.StateChartStore
import actron.statechart.machine.CommandHandler
import actron.statechart.machine.MachineStore
import kotlinx.coroutines.CoroutineScope

fun configuredMachine(scope: CoroutineScope) = MachineStore(machine, 0, CommandHandler<Nothing, Increment> { _, _ -> }, scope,
    coroutineContext = scope.coroutineContext) {
    exceptionHandler(ExceptionHandler.Ignore)
}
fun configuredChart(scope: CoroutineScope) = StateChartStore<Int, Increment, Ev>(definition, 0, scope.coroutineContext) {
    store { recover<IllegalStateException> { context = 1 } }
    onEnter(counting) { store.message(Ping) }
    activity(counting) { store.message(Ping) }
}

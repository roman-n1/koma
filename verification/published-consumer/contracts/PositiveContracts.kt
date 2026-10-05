@file:OptIn(koma.core.ExperimentalKomaApi::class)
package consumer

import koma.core.ExceptionHandler
import koma.message.message
import koma.statechart.StateChartStore
import koma.statechart.machine.CommandHandler
import koma.statechart.machine.MachineStore
import kotlinx.coroutines.CoroutineScope

fun configuredMachine(scope: CoroutineScope) = MachineStore(machine, 0, CommandHandler<Nothing, Increment> { _, _ -> }, scope) {
    exceptionHandler(ExceptionHandler.Ignore)
}
fun configuredChart(scope: CoroutineScope) = StateChartStore<Int, Increment, Ev>(definition, 0, scope.coroutineContext) {
    store { recover<IllegalStateException> { context = 1 } }
    onEnter(counting) { store.message(Ping) }
    activity(counting) { store.message(Ping) }
}

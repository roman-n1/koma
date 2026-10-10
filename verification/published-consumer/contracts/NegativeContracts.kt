@file:OptIn(actron.core.ExperimentalActronApi::class)
package consumer

import actron.core.ExceptionHandler
import actron.message.message
import actron.statechart.StateChartStore
import actron.statechart.machine.CommandHandler
import actron.statechart.machine.MachineSnapshot
import actron.statechart.machine.MachineStore
import kotlinx.coroutines.CoroutineScope

// Each forbidden operation has its own expected compiler diagnostic. One unrelated error must
// never make this test pass; the verifier requires a diagnostic for every labelled line.
fun forbiddenMachineConfiguration(scope: CoroutineScope) =
    MachineStore(machine, 0, CommandHandler<Nothing, Increment> { _, _ -> }, scope) {
        state<MachineSnapshot<Int>> {} // reject: state
        initialState(TODO()) // reject: initialState
        recover<IllegalStateException> {} // reject: recover
    }

fun forbiddenChartRecovery(scope: CoroutineScope) = StateChartStore<Int, Increment, Ev>(definition, 0, scope.coroutineContext) {
    store {
        recover<IllegalStateException> {
            configuration = TODO() // reject: configuration
            timers = TODO() // reject: timers
            nextState { TODO() } // reject: nextState
        }
    }
    onEnter(counting) {
        message(Ping) // reject: message
    }
}

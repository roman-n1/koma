@file:OptIn(actron.core.ExperimentalActronApi::class)
package consumer

import androidx.compose.runtime.Composable
import actron.compose.rememberViewStore
import actron.core.Action
import actron.core.Event
import actron.core.State
import actron.core.Store
import actron.logging.Logger
import actron.logging.simpleLogging
import actron.message.Message
import actron.message.message
import actron.message.receiveMessages
import actron.observability.StoreInstanceId
import actron.statechart.ActionMatcher
import actron.statechart.AtomicState
import actron.statechart.ChartState
import actron.statechart.StateChartDefinition
import actron.statechart.StateChartStore
import actron.statechart.StateId
import actron.statechart.Transition
import actron.statechart.compose.MailboxEffect
import actron.statechart.machine.CommandHandler
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineGroup
import actron.statechart.machine.MachineStore
import kotlinx.coroutines.CoroutineScope

// [*] --> Counting; Counting --> Counting: Increment / count++
data class Counter(val count: Int = 0) : State
data object Increment : Action
data object Ev : Event
data object Ping : Message

fun counter(scope: CoroutineScope): Store<Counter, Increment, Ev> = Store(Counter(), context = scope.coroutineContext) {
    plugin(simpleLogging(logger = Logger { _, _, _, _ -> }))
    state<Counter> {
        action<Increment> {
            nextState { state.copy(count = state.count + 1) }
            message(Ping)
        }
    }
}

fun receiver(scope: CoroutineScope): Store<Counter, Increment, Ev> = Store(Counter(), context = scope.coroutineContext) {
    plugin(receiveMessages { if (it == Ping) dispatch(Increment) })
    state<Counter> { action<Increment> { nextState { state.copy(count = state.count + 1) } } }
}

val counting = StateId("Counting")
val definition = StateChartDefinition(counting, listOf(AtomicState(counting)), listOf(
    Transition(counting, counting, ActionMatcher.of<Increment>("Increment"), effect = "increment"),
))
val machine = Machine<Int, Increment, Nothing, Ev>(DefinitionId("published-counter"), DefinitionVersion("1"), definition) {
    effect("increment") { count, _ -> count + 1 }
}
fun chart(scope: CoroutineScope): Store<ChartState<Int>, Increment, Ev> = StateChartStore(definition, 0, scope.coroutineContext) {
    effect("increment") { count, _ -> count + 1 }
}
fun runningMachine(scope: CoroutineScope): MachineStore<Int, Increment, Nothing, Ev> {
    val group = MachineGroup()
    val member = group.member<Int, Increment, Nothing, Ev>(StoreInstanceId("published"))
    return MachineStore(machine, 0, CommandHandler<Nothing, Increment> { _, _ -> }, scope,
        coroutineContext = scope.coroutineContext, observers = listOf(member)).also { member.attach(it) }
}

@Composable
fun CounterUi(store: Store<Counter, Increment, Ev>): Int = rememberViewStore(store).select { it.count }

@Composable
fun MachineUi(store: MachineStore<Int, Increment, Nothing, Ev>) {
    MailboxEffect(store.mailbox) { it.acknowledge() }
}

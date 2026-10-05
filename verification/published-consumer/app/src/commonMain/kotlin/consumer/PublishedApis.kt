@file:OptIn(koma.core.ExperimentalKomaApi::class)
package consumer

import androidx.compose.runtime.Composable
import koma.compose.rememberViewStore
import koma.core.Action
import koma.core.Event
import koma.core.State
import koma.core.Store
import koma.logging.Logger
import koma.logging.simpleLogging
import koma.message.Message
import koma.message.message
import koma.message.receiveMessages
import koma.observability.StoreInstanceId
import koma.statechart.ActionMatcher
import koma.statechart.AtomicState
import koma.statechart.ChartState
import koma.statechart.StateChartDefinition
import koma.statechart.StateChartStore
import koma.statechart.StateId
import koma.statechart.Transition
import koma.statechart.compose.MailboxEffect
import koma.statechart.machine.CommandHandler
import koma.statechart.machine.DefinitionId
import koma.statechart.machine.DefinitionVersion
import koma.statechart.machine.Machine
import koma.statechart.machine.MachineGroup
import koma.statechart.machine.MachineStore
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

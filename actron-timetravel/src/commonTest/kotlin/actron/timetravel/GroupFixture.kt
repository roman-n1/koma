@file:OptIn(ExperimentalActronApi::class, ExperimentalCoroutinesApi::class, ExperimentalTime::class)

package actron.timetravel

import actron.core.Action
import actron.core.Event
import actron.core.ExceptionHandler
import actron.core.ExperimentalActronApi
import actron.observability.MachineGroupId
import actron.observability.RecordingSession
import actron.observability.RuntimeSessionId
import actron.observability.StoreInstanceId
import actron.statechart.ActionMatcher
import actron.statechart.AtomicState
import actron.statechart.CompoundState
import actron.statechart.StateChartDefinition
import actron.statechart.StateId
import actron.statechart.Transition
import actron.statechart.machine.CommandHandler
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.Machine
import actron.statechart.machine.MachineClock
import actron.statechart.machine.MachineGroup
import actron.statechart.machine.MachineStore
import actron.statechart.machine.MachineTime
import actron.statechart.test.VirtualMachineClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlin.time.TestTimeSource

/**
 * A group of two machines for the group tests: a picker whose picks are routed to a root as
 * applies, whose acknowledgements are routed back.
 *
 * ```
 * picker: [*] --> Idle; Idle --Pick(name) / remember--> Idle   onEnter(Idle): event Picked(last) when there is one; command Fetch(last)
 *         onAction Ack(n) / count acks; onAction Fetched(name) / count fetched
 * root:   [*] --> Idle; Idle --Apply(name) / add--> Idle       onEnter(Idle): event Applied(size) when not empty
 * routes: picker.Picked(name) -> root.Apply(name); root.Applied(n) -> picker.Ack(n)
 * ```
 */
object GroupFixture {

    data class PickerCtx(val last: String? = null, val acks: List<Int> = emptyList(), val fetched: List<String> = emptyList())

    sealed interface PickerAct : Action {
        data class Pick(val name: String) : PickerAct
        data class Ack(val applied: Int) : PickerAct
        data class Fetched(val name: String) : PickerAct
    }

    sealed interface PickerEv : Event {
        data class Picked(val name: String) : PickerEv
    }

    data class Fetch(val name: String)

    data class RootCtx(val names: List<String> = emptyList())

    sealed interface RootAct : Action {
        data class Apply(val name: String) : RootAct
    }

    sealed interface RootEv : Event {
        data class Applied(val count: Int) : RootEv
    }

    val pickerId = StoreInstanceId("picker-1")
    val rootId = StoreInstanceId("root-1")

    private val root = StateId("Root")
    private val idle = StateId("Idle")
    private fun chart(matcher: ActionMatcher) = StateChartDefinition(root, listOf(CompoundState(root, initial = idle), AtomicState(idle, parent = root)), listOf(Transition(idle, idle, matcher, effect = actron.statechart.EffectKey("remember"))))

    val picker: Machine<PickerCtx, PickerAct, Fetch, PickerEv> = Machine(DefinitionId("picker"), DefinitionVersion("1"), chart(ActionMatcher.of<PickerAct.Pick>("Pick"))) {
        effect("remember") { c, a -> c.copy(last = (a as PickerAct.Pick).name) }
        onEnter(idle) {
            context.last?.let { last ->
                event(PickerEv.Picked(last))
                command(Fetch(last))
            }
        }
        onAction(root, ActionMatcher.of<PickerAct.Ack>("Ack")) { context = context.copy(acks = context.acks + (action as PickerAct.Ack).applied) }
        onAction(root, ActionMatcher.of<PickerAct.Fetched>("Fetched")) { context = context.copy(fetched = context.fetched + (action as PickerAct.Fetched).name) }
    }

    val rootMachine: Machine<RootCtx, RootAct, Nothing, RootEv> = Machine(DefinitionId("root"), DefinitionVersion("1"), chart(ActionMatcher.of<RootAct.Apply>("Apply"))) {
        effect("remember") { c, a -> c.copy(names = c.names + (a as RootAct.Apply).name) }
        onEnter(idle) { if (context.names.isNotEmpty()) event(RootEv.Applied(context.names.size)) }
    }

    val machines: Map<StoreInstanceId, Machine<*, *, *, *>> = mapOf(pickerId to picker, rootId to rootMachine)

    /** The routes of the group, for a live [MachineGroup] and for a [GroupBranch]. */
    fun pickerToRoot(event: PickerEv): RootAct? = (event as? PickerEv.Picked)?.let { RootAct.Apply(it.name) }

    fun rootToPicker(event: RootEv): PickerAct? = (event as? RootEv.Applied)?.let { PickerAct.Ack(it.count) }

    fun MachineGroup.routeBoth() {
        route<PickerEv, RootAct>(pickerId, rootId) { event, carry -> pickerToRoot(event)?.let(carry) }
        route<RootEv, PickerAct>(rootId, pickerId) { event, carry -> rootToPicker(event)?.let(carry) }
    }

    /** The same two routes as a request/reply pair named `apply`. */
    fun MachineGroup.routeAsPair(picker: MachineGroup.Member<PickerCtx, PickerAct, Fetch, PickerEv>, root: MachineGroup.Member<RootCtx, RootAct, Nothing, RootEv>) {
        requestReply(picker, root, "apply", { event, carry -> pickerToRoot(event)?.let(carry) }, { event, carry -> rootToPicker(event)?.let(carry) })
    }

    val branchRoutes: List<GroupBranch.Route> = listOf(
        GroupBranch.Route(pickerId, rootId) { event, carry -> (event as? PickerEv)?.let(::pickerToRoot)?.let(carry) },
        GroupBranch.Route(rootId, pickerId) { event, carry -> (event as? RootEv)?.let(::rootToPicker)?.let(carry) },
    )

    /** A live, journaled, recorded group on a test dispatcher; [paired] routes as a request/reply pair. */
    class Live(scope: TestScope, paired: Boolean = false, rootContext: kotlin.coroutines.CoroutineContext? = null) {
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val executionScope = CoroutineScope(dispatcher + SupervisorJob())
        val session = RecordingSession(scope.backgroundScope, id = RuntimeSessionId("g"), group = MachineGroupId("picker"), timeSource = TestTimeSource())
        val group = MachineGroup(session)
        val recorder = GroupRecorder(group)
        val pickerMember = group.member<PickerCtx, PickerAct, Fetch, PickerEv>(pickerId)
        val rootMember = group.member<RootCtx, RootAct, Nothing, RootEv>(rootId)
        val pickerStore: MachineStore<PickerCtx, PickerAct, Fetch, PickerEv> = MachineStore(
            picker, PickerCtx(), CommandHandler<Fetch, PickerAct> { command, results ->
                delay(50.milliseconds)
                results.result(PickerAct.Fetched(command.command.name))
            },
            executionScope, VirtualMachineClock(scope.testScheduler), dispatcher, observers = listOf(pickerMember, recorder.member(pickerId, picker, PickerCtx())),
        ) { exceptionHandler(ExceptionHandler.Ignore) }
        val rootStore: MachineStore<RootCtx, RootAct, Nothing, RootEv> = MachineStore(
            rootMachine, RootCtx(), CommandHandler<Nothing, RootAct> { _, _ -> }, executionScope, VirtualMachineClock(scope.testScheduler), rootContext ?: dispatcher,
            observers = listOf(rootMember, recorder.member(rootId, rootMachine, RootCtx())),
        ) { exceptionHandler(ExceptionHandler.Ignore) }

        init {
            if (paired) group.routeAsPair(pickerMember, rootMember) else group.routeBoth()
            pickerMember.attach(pickerStore)
            rootMember.attach(rootStore)
            pickerStore.start()
            rootStore.start()
        }

        fun close() {
            pickerStore.close()
            rootStore.close()
        }
    }
}

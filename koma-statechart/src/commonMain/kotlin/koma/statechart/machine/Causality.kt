package koma.statechart.machine

import koma.core.Action
import koma.core.Event
import koma.core.InputId
import koma.observability.FailureDescriptor
import koma.observability.StoreInstanceId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One input's metadata-only cause; command output links to its registering input. */
data class CausalInput(
    val store: StoreInstanceId,
    val input: InputId,
    val root: InputId,
    val parent: InputId? = null,
    val command: CommandId? = null,
    val source: SourceId? = null,
    /** A bounded history evicted the parent's node, so the root cannot be reconstructed. */
    val incomplete: Boolean = false,
)

/**
 * Bounded, observable causality for one store. Correlates command outputs/completion/failure with
 * the input that registered the command. Bridge cross-store causality remains in MachineGroup's
 * message references. No action/context/command payloads are retained.
 */
class CausalityTracker<C, A : Action, CMD, E : Event>(
    private val store: StoreInstanceId,
    private val capacity: Int = 100,
) : DecisionObserver<C, A, CMD, E> {
    init { require(capacity > 0) { "[Koma] Causality history needs a positive capacity" } }
    private val history = MutableStateFlow<List<CausalInput>>(emptyList())
    val inputs: StateFlow<List<CausalInput>> = history.asStateFlow()
    private val commands = linkedMapOf<CommandId, InputId>()

    private fun record(input: InputId?, machineInput: MachineInput<A>) {
        if (input == null) return
        val command = when (machineInput) {
            is MachineInput.CommandResult -> machineInput.command
            is MachineInput.CommandCompleted -> machineInput.command
            is MachineInput.CommandFailed -> machineInput.command
            is MachineInput.CommandAbandoned -> machineInput.command
            else -> null
        }
        val parent = command?.let(commands::get)
        val cause = history.value.firstOrNull { it.input == parent }
        val source = (machineInput as? MachineInput.External)?.source
        history.value = (history.value + CausalInput(store, input, cause?.root ?: input, parent, command, source,
            incomplete = command != null && (parent == null || cause == null || cause.incomplete))).takeLast(capacity)
    }

    override fun onCommitted(input: InputId?, machineInput: MachineInput<A>, decision: Decision<C, CMD, E>) {
        record(input, machineInput)
        if (input != null) for (command in decision.commands) commands[command.id] = input
        while (commands.size > capacity) commands.remove(commands.keys.first())
    }
    override fun onIgnored(input: InputId?, machineInput: MachineInput<A>, reason: IgnoreReason) = record(input, machineInput)
    override fun onFailed(input: InputId?, machineInput: MachineInput<A>, failure: FailureDescriptor) = record(input, machineInput)
}

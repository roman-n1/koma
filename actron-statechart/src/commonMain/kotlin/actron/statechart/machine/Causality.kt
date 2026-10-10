package actron.statechart.machine

import actron.core.InputAttribution

import actron.core.Action
import actron.core.Event
import actron.core.InputId
import actron.observability.FailureDescriptor
import actron.observability.StoreInstanceId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Metadata of the input origin; a local operation has no external producer. */
sealed interface CausalProvenance {
    data object Local : CausalProvenance
    data class Command(val command: CommandId) : CausalProvenance
    data class External(val source: SourceId) : CausalProvenance
}

/** One input's metadata-only cause; command output links to its registering input. */
data class CausalInput(
    val store: StoreInstanceId,
    val input: InputId,
    val root: InputId,
    val parent: InputAttribution = InputAttribution.Unattributed,
    val provenance: CausalProvenance = CausalProvenance.Local,
    /** A bounded history evicted the parent's node, so the root cannot be reconstructed. */
    val incomplete: Boolean = false,
)

/**
 * Bounded, observable causality for one store. Correlates command outputs/completion/failure with
 * the input that registered the command. Bridge cross-store causality remains in MachineGroup's
 * message references. No action/context/command payloads are retained.
 */
class CausalityTracker<C : Any, A : Action, CMD : Any, E : Event>(
    private val store: StoreInstanceId,
    private val capacity: Int = 100,
) : DecisionObserver<C, A, CMD, E> {
    init { require(capacity > 0) { "[Actron] Causality history needs a positive capacity" } }
    private val history = MutableStateFlow<List<CausalInput>>(emptyList())
    val inputs: StateFlow<List<CausalInput>> = history.asStateFlow()
    private val commands = linkedMapOf<CommandId, InputId>()

    private fun record(input: InputAttribution, machineInput: MachineInput<A>) {
        if (input !is InputId) return
        var provenance: CausalProvenance = CausalProvenance.Local
        var parent: InputAttribution = InputAttribution.Unattributed
        var root = input
        var incomplete = false
        fun link(command: CommandId) {
            provenance = CausalProvenance.Command(command)
            if (command !in commands) { incomplete = true; return }
            val registering = commands.getValue(command)
            parent = registering
            val index = history.value.indexOfFirst { it.input == registering }
            if (index < 0) { incomplete = true; return }
            val cause = history.value[index]
            root = cause.root
            incomplete = cause.incomplete
        }
        when (machineInput) {
            is MachineInput.CommandResult -> link(machineInput.command)
            is MachineInput.CommandCompleted -> link(machineInput.command)
            is MachineInput.CommandFailed -> link(machineInput.command)
            is MachineInput.CommandAbandoned -> link(machineInput.command)
            is MachineInput.External -> provenance = CausalProvenance.External(machineInput.source)
            else -> Unit
        }
        history.value = (history.value + CausalInput(store, input, root, parent, provenance, incomplete)).takeLast(capacity)
    }

    override fun onCommitted(input: InputAttribution, machineInput: MachineInput<A>, decision: Decision<C, CMD, E>) {
        record(input, machineInput)
        if (input is InputId) for (command in decision.commands) commands[command.id] = input
        while (commands.size > capacity) commands.remove(commands.keys.first())
    }
    override fun onIgnored(input: InputAttribution, machineInput: MachineInput<A>, reason: IgnoreReason) = record(input, machineInput)
    override fun onFailed(input: InputAttribution, machineInput: MachineInput<A>, failure: FailureDescriptor) = record(input, machineInput)
}

package koma.timetravel.compose

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import koma.core.Action
import koma.core.ExperimentalKomaApi
import koma.observability.FailureDescriptor
import koma.observability.StoreInstanceId
import koma.statechart.machine.CommandId
import koma.statechart.machine.CommandRegistration
import koma.statechart.machine.Decision
import koma.statechart.machine.DecisionOutcome
import koma.statechart.machine.LaneId
import koma.statechart.machine.Machine
import koma.statechart.machine.MachineSnapshot
import koma.statechart.machine.MachineTime
import koma.statechart.machine.SourceId
import koma.statechart.toMermaid
import koma.timetravel.GroupBranch
import koma.timetravel.inspect.SnapshotDiff
import kotlin.time.Duration

/**
 * An input the application scripts for a branch (handoff §10: a branch gets scripted data in
 * place of the world). The screen has no way to build a Store's actions; the application
 * lists what a viewer may send, with a label for the button.
 */
@ExperimentalKomaApi
sealed interface BranchInput {
    val store: StoreInstanceId
    val label: String

    /** [action] dispatched to [store]. */
    data class Dispatch(override val store: StoreInstanceId, override val label: String, val action: Action) : BranchInput

    /** [action] fed to [store] as data of the external source [source]. */
    data class Feed(override val store: StoreInstanceId, override val label: String, val source: SourceId, val action: Action) : BranchInput

    /** [result] for an awaiting command of [store]; the viewer picks which command. */
    data class Answer(override val store: StoreInstanceId, override val label: String, val result: Action) : BranchInput
}

/**
 * One decision the branch made, as the panel lists it.
 *
 * @property index The position in the branch's decisions
 * @property store The member that decided
 * @property cause The input's label, `timer` for a firing, `bridge` for a delivery the local bridge made
 * @property diff What the decision changed in the member's snapshot
 */
@ExperimentalKomaApi
data class BranchDecision(val index: Int, val store: StoreInstanceId, val cause: String, val decision: Decision<*, *, *>, val diff: SnapshotDiff)

/**
 * A group's branch as the screen drives it (handoff §11, v2): the members' snapshots and
 * awaiting commands, the scripted [inputs] the application allows, answering, completing or
 * failing a command, advancing the virtual clock, and every decision made, in order. It holds
 * a [GroupBranch], which decides with the pure machines and runs nothing; the live group the
 * branch came from is untouched.
 *
 * @param branch The branch
 * @param machines The members' machines, for the definitions the panel draws
 * @param inputs What a viewer may send, by member
 */
@ExperimentalKomaApi
@Stable
class BranchControls(
    val branch: GroupBranch,
    val machines: Map<StoreInstanceId, Machine<*, *, *, *>>,
    val inputs: List<BranchInput> = emptyList(),
) {
    init {
        require(machines.keys.containsAll(branch.members.keys)) { "[Koma] Machines are missing for ${branch.members.keys - machines.keys}" }
    }

    /** Every decision the branch made through these controls, in order. */
    var decisions: List<BranchDecision> by mutableStateOf(emptyList())
        private set

    /** Why the last request was refused (a command not awaiting), or `null`; cleared by the next one. */
    var problem: String? by mutableStateOf(null)
        private set

    // Bumped by every change: reading it subscribes a composition to the branch's state.
    private var generation: Int by mutableStateOf(0)

    /** The members, in the branch's order. */
    val members: List<StoreInstanceId> get() = branch.members.keys.toList()

    /** The snapshot of [store] in the branch now. */
    fun snapshot(store: StoreInstanceId): MachineSnapshot<*> {
        generation
        return branch.snapshot(store)
    }

    /** The virtual clock of [store] now. */
    fun now(store: StoreInstanceId): MachineTime {
        generation
        return member(store).now
    }

    /** The commands of [store] a live run would be waiting on; the viewer answers, completes or fails them. */
    fun awaiting(store: StoreInstanceId): List<CommandRegistration<*>> {
        generation
        return member(store).awaiting
    }

    /** The commands of [store] waiting in their lanes, by lane. */
    fun queued(store: StoreInstanceId): Map<LaneId, List<CommandRegistration<*>>> {
        generation
        return member(store).queued
    }

    /** The scripted inputs of [store]. */
    fun inputsOf(store: StoreInstanceId): List<BranchInput> = inputs.filter { it.store == store }

    /** The definition of [store] as Mermaid, its active states highlighted. */
    fun mermaid(store: StoreInstanceId): String = machines.getValue(store).chart.toMermaid(snapshot(store).configuration.active)

    fun dispatch(input: BranchInput.Dispatch) = produce(input.store, input.label) { branch.dispatch(input.store, input.action) }

    fun feed(input: BranchInput.Feed) = produce(input.store, input.label) { branch.feed(input.store, input.source, input.action) }

    fun answer(input: BranchInput.Answer, command: CommandId) = produce(input.store, "${input.label} -> c${command.value}") { branch.answer(input.store, command, input.result) }

    fun complete(store: StoreInstanceId, command: CommandId) = produce(store, "complete c${command.value}") { branch.complete(store, command) }

    fun fail(store: StoreInstanceId, command: CommandId, reason: String = "failed in the branch") =
        produce(store, "fail c${command.value}") { branch.fail(store, command, FailureDescriptor.of(IllegalStateException(reason))) }

    /** Moves every member's clock by [duration]; the timers that come due fire. */
    fun advance(duration: Duration) = produce(null, "timer") { branch.advance(duration) }

    private fun produce(store: StoreInstanceId?, cause: String, act: () -> List<Decision<*, *, *>>) {
        problem = null
        val counts = branch.members.mapValues { (_, member) -> member.history.size }
        val last = branch.members.mapValues { (_, member) -> member.snapshot }.toMutableMap()
        val produced = try {
            act()
        } catch (e: IllegalArgumentException) {
            problem = e.message
            return
        }
        // Which member made which decision: its history grew by it.
        val fresh = branch.members.mapValues { (id, member) -> member.history.drop(counts.getValue(id)) }
        var index = decisions.size
        val listed = produced.map { decision ->
            val owner = fresh.entries.first { (_, list) -> list.any { it === decision } }.key
            val diff = SnapshotDiff.between(last.getValue(owner), decision.snapshot)
            last[owner] = decision.snapshot
            BranchDecision(index++, owner, if (store == null || owner == store) cause else "bridge", decision, diff)
        }
        decisions = decisions + listed
        generation++
    }

    private fun member(store: StoreInstanceId): koma.timetravel.Branch<*, *, *, *> = requireNotNull(branch.members[store]) { "[Koma] $store is not a member of this branch" }
}

/** Text of a branch's decision for the panel. */
@ExperimentalKomaApi
object BranchText {
    fun line(decision: BranchDecision): String = buildString {
        append('#').append(decision.index).append(' ').append(decision.store).append(' ').append(decision.cause).append(": ")
        when (val outcome = decision.decision.outcome) {
            DecisionOutcome.Handled -> append("handled revision=").append(decision.decision.snapshot.revision)
            is DecisionOutcome.Ignored -> append("ignored ").append(outcome.reason.name)
            is DecisionOutcome.Failed -> append("failed ").append(outcome.cause::class.simpleName)
        }
        val changed = changes(decision.diff)
        if (changed.isNotEmpty()) append("; ").append(changed)
        if (decision.decision.effects.isNotEmpty()) append("; effects ").append(decision.decision.effects.size)
    }

    private fun changes(diff: SnapshotDiff): String = buildList {
        if (diff.contextChanged) add("context")
        if (diff.entered.isNotEmpty()) add("entered ${diff.entered.map { it.value }.sorted().joinToString(",")}")
        if (diff.exited.isNotEmpty()) add("exited ${diff.exited.map { it.value }.sorted().joinToString(",")}")
        if (diff.commandsRegistered.isNotEmpty()) add("commands +${diff.commandsRegistered.sortedBy { it.value }.joinToString(",") { "c${it.value}" }}")
        if (diff.commandsEnded.isNotEmpty()) add("commands -${diff.commandsEnded.sortedBy { it.value }.joinToString(",") { "c${it.value}" }}")
        if (diff.timersScheduled.isNotEmpty()) add("timers +${diff.timersScheduled.sortedBy { it.value }.joinToString(",") { "t${it.value}" }}")
        if (diff.timersCancelled.isNotEmpty()) add("timers -${diff.timersCancelled.sortedBy { it.value }.joinToString(",") { "t${it.value}" }}")
    }.joinToString("; ")
}

package actron.statechart.machine

import actron.core.ExperimentalActronApi
import kotlin.time.Duration

/**
 * The state of a [MachineStore]'s executor at one point of its run, as data: the snapshot of
 * the last decision it carried out, its clock, and what became of the snapshot's commands.
 * Every command the snapshot holds is in exactly one place: running or queued in the [lanes],
 * or [ending]. A restored checkpoint continues a run where the live one was; a replay branch
 * starts from one.
 *
 * Not in a checkpoint: the store's queue of accepted, not yet decided inputs (they are the
 * steps after it), the events not yet delivered, and anything that is not data (jobs, scopes,
 * handlers). Timers are in the snapshot; [remaining] gives what is left of one at [now].
 *
 * @property snapshot The machine's snapshot the executor had carried out
 * @property now The executor's clock when the checkpoint was taken
 * @property lanes The commands running and waiting, with their registrations
 * @property ending The commands the executor has finished with (completed, failed, abandoned)
 * whose last input the machine had not decided yet, so the snapshot still holds them
 * @property effects The effects waiting in the mailbox or being handled without an
 * acknowledgement yet (handoff §8: unhandled UI effects and their delivery state)
 * @throws IllegalArgumentException if the lanes and [ending] are not a partition of the
 * snapshot's commands
 */
data class ExecutorCheckpoint<C, CMD>(
    val snapshot: MachineSnapshot<C>,
    val now: MachineTime,
    val lanes: Lanes<CMD> = Lanes(),
    val ending: Map<CommandId, CommandRegistration<CMD>> = emptyMap(),
    val effects: List<PendingEffect<*>> = emptyList(),
) {
    init {
        val running = lanes.running.keys
        val queued = lanes.queued.values.flatten().map { it.id }
        val all = running.toList() + queued + ending.keys
        require(all.size == all.toSet().size) { "[Actron] A command is in more than one place of the checkpoint: ${all.groupBy { it }.filterValues { it.size > 1 }.keys}" }
        require(all.toSet() == snapshot.commands.keys) {
            "[Actron] The checkpoint's commands ${all.toSet()} are not the snapshot's ${snapshot.commands.keys}"
        }
        for (registration in registrations.values) {
            val record = snapshot.commands.getValue(registration.id)
            require(record.scope == registration.scope && record.lane == registration.lane) { "[Actron] Command ${registration.id} is registered as $record in the snapshot, as $registration in the checkpoint" }
        }
    }

    /** Every command of the snapshot with its registration, wherever it is. */
    val registrations: Map<CommandId, CommandRegistration<CMD>>
        get() = lanes.all + ending

    /** What is left of the timer [id] at [now]; negative when it is due, `null` when not scheduled. */
    fun remaining(id: TimerId): Duration? = snapshot.timers[id]?.let { it.deadline - now }

    companion object {
        /** The checkpoint of a run that has not started: [snapshot] before any decision, clock at zero, nothing running. */
        fun <C, CMD> initial(snapshot: MachineSnapshot<C>): ExecutorCheckpoint<C, CMD> {
            require(!snapshot.isStarted) { "[Actron] An initial checkpoint is before the first decision; the snapshot is at revision ${snapshot.revision}" }
            return ExecutorCheckpoint(snapshot, MachineTime.Zero)
        }
    }
}

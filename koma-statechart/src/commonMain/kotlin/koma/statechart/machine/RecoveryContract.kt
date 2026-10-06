package koma.statechart.machine

/** Actual recovery boundaries; external repeat safety of ordinary commands is never inferred. */
enum class RecoveryPolicy { CancelOnExit, DurableIdempotent, CompletedReceipt }
data class CommandRecovery(
    val registrations: Set<CommandId>, val policy: RecoveryPolicy, val survivesExit: Boolean,
    val hasPersistedPayload: Boolean, val idempotencyKey: IdempotencyKey? = null, val attempts: Long = 0,
    /** null = the application must decide whether repeating its ordinary IO is safe. */
    val mayReexecute: Boolean? = null,
)
data class RecoveryContract(
    val commands: List<CommandRecovery>, val recoverableTimers: Set<TimerId>,
    val persistedEffectIds: Set<EffectId>, val liveEventsAreEphemeral: Boolean = true,
)

fun <C, CMD> ExecutorCheckpoint<C, CMD>.recoveryContract(): RecoveryContract = RecoveryContract(
    registrations.keys.map { CommandRecovery(setOf(it), RecoveryPolicy.CancelOnExit, false, true) },
    snapshot.timers.keys.toSet(), effects.mapTo(linkedSetOf()) { it.id })

/** Requires the same validated checkpoint used by DurableMachine; does not classify arbitrary payloads. */
fun <C, CMD> DurableCheckpoint<C, CMD>.recoveryContract(): RecoveryContract = RecoveryContract(
    ephemeral.keys.map { CommandRecovery(setOf(it), RecoveryPolicy.CancelOnExit, false, true) } + outbox.values.map {
        CommandRecovery(it.registrations, if (it.status == DurableStatus.Pending) RecoveryPolicy.DurableIdempotent else RecoveryPolicy.CompletedReceipt,
            true, true, it.key, it.attempts, it.status == DurableStatus.Pending)
    }, snapshot.timers.keys.toSet(), emptySet())

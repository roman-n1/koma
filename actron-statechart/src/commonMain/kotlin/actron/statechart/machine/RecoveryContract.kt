package actron.statechart.machine

/** Actual recovery boundaries; external repeat safety of ordinary commands is never inferred. */
enum class RecoveryPolicy { CancelOnExit, DurableIdempotent, CompletedReceipt }
enum class RepeatSafety { ApplicationDecision, IdempotentRetry, ReceiptSuppresses }

sealed interface RecoveryDelivery {
    val policy: RecoveryPolicy
    val survivesExit: Boolean
    val attempts: Long
    val repeatSafety: RepeatSafety

    data object Ordinary : RecoveryDelivery {
        override val policy = RecoveryPolicy.CancelOnExit
        override val survivesExit = false
        override val attempts = 0L
        override val repeatSafety = RepeatSafety.ApplicationDecision
    }
    data class Pending(val key: IdempotencyKey, override val attempts: Long) : RecoveryDelivery {
        override val policy = RecoveryPolicy.DurableIdempotent
        override val survivesExit = true
        override val repeatSafety = RepeatSafety.IdempotentRetry
    }
    data class Receipt(val key: IdempotencyKey, override val attempts: Long) : RecoveryDelivery {
        override val policy = RecoveryPolicy.CompletedReceipt
        override val survivesExit = true
        override val repeatSafety = RepeatSafety.ReceiptSuppresses
    }
}

data class CommandRecovery(
    val registrations: Set<CommandId>, val hasPersistedPayload: Boolean,
    val delivery: RecoveryDelivery = RecoveryDelivery.Ordinary,
) {
    val policy: RecoveryPolicy get() = delivery.policy
    val survivesExit: Boolean get() = delivery.survivesExit
    val attempts: Long get() = delivery.attempts
    val repeatSafety: RepeatSafety get() = delivery.repeatSafety
}
data class RecoveryContract(
    val commands: List<CommandRecovery>, val recoverableTimers: Set<TimerId>,
    val persistedEffectIds: Set<EffectId>, val liveEventsAreEphemeral: Boolean = true,
)

fun <C : Any, CMD : Any> ExecutorCheckpoint<C, CMD>.recoveryContract(): RecoveryContract = RecoveryContract(
    registrations.keys.map { CommandRecovery(setOf(it), true) },
    snapshot.timers.keys.toSet(), effects.mapTo(linkedSetOf()) { it.id })

/** Requires the same validated checkpoint used by DurableMachine; does not classify arbitrary payloads. */
fun <C : Any, CMD : Any> DurableCheckpoint<C, CMD>.recoveryContract(): RecoveryContract = RecoveryContract(
    ephemeral.keys.map { CommandRecovery(setOf(it), true) } + outbox.values.map {
        CommandRecovery(it.registrations, true, if (it.status == DurableStatus.Pending) RecoveryDelivery.Pending(it.key, it.attempts) else RecoveryDelivery.Receipt(it.key, it.attempts))
    }, snapshot.timers.keys.toSet(), emptySet())

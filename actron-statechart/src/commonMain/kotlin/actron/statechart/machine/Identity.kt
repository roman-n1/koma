package actron.statechart.machine

import actron.core.ExperimentalActronApi
import actron.observability.MessageRef
import actron.observability.StoreInstanceId
import kotlin.jvm.JvmInline
import kotlin.time.Duration

/**
 * The logical machine a [Machine] implements, stable across versions of its behaviour.
 */
@JvmInline
value class DefinitionId(val value: String) {
    init {
        require(value.isNotBlank()) { "[Actron] DefinitionId must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * The exact version of a machine's behaviour: chart, guards, reducers and hook rules. A change
 * to any of them needs a new version even when the chart's graph is unchanged; a snapshot is
 * only decided by the version that produced it.
 */
@JvmInline
value class DefinitionVersion(val value: String) {
    init {
        require(value.isNotBlank()) { "[Actron] DefinitionVersion must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * One entry into a node. Every entry, including a re-entry through a self-loop, gets a new id, so
 * work and timers belong to a particular stay in the node and not to the node itself.
 */
@JvmInline
value class ActivationId(val value: Long) {
    override fun toString(): String = "a$value"
}

/**
 * One registered command.
 */
@JvmInline
value class CommandId(val value: Long) {
    override fun toString(): String = "c$value"
}

/**
 * One scheduled timer: a start of a [actron.statechart.Trigger.After] transition for one activation
 * of its source.
 */
@JvmInline
value class TimerId(val value: Long) {
    override fun toString(): String = "t$value"
}

/**
 * One event the machine asked to deliver (the handoff's UI effect).
 */
@JvmInline
value class EffectId(val value: Long) {
    override fun toString(): String = "e$value"
}

/**
 * A transition of the chart by its position in [actron.statechart.StateChartDefinition.transitions],
 * stable within a [DefinitionVersion].
 */
@JvmInline
value class TransitionId(val index: Int) {
    override fun toString(): String = "T$index"
}

/**
 * A lane groups commands that must not run freely next to each other; see [ConcurrencyPolicy].
 * Lanes are local to one Store instance.
 */
@JvmInline
value class LaneId(val value: String) : CommandLane {
    init {
        require(value.isNotBlank()) { "[Actron] LaneId must not be blank" }
    }

    override fun toString(): String = value
}

/** Commands run independently or participate in a named concurrency lane. */
sealed interface CommandLane {
    data object Independent : CommandLane
}

/**
 * The machine's clock: time since its runtime session started. Live, it is monotonic elapsed
 * time; in a replay, a virtual clock. It always arrives in the input, never from a clock read by
 * the machine, so deciding is a pure function.
 */
@JvmInline
value class MachineTime(val sinceStart: Duration) : Comparable<MachineTime> {
    operator fun plus(duration: Duration): MachineTime = MachineTime(sinceStart + duration)

    operator fun minus(other: MachineTime): Duration = sinceStart - other.sinceStart

    override fun compareTo(other: MachineTime): Int = sinceStart.compareTo(other.sinceStart)

    override fun toString(): String = "+$sinceStart"

    companion object {
        val Zero: MachineTime = MachineTime(Duration.ZERO)
    }
}

/**
 * One message a bridge carried from a Store of a group to another: the sender and the effect it
 * was routed from. The same effect gives the same message in a replay, so the id is a correlation
 * reference that a replay checks against, never a second live delivery.
 */
data class MessageId(val from: StoreInstanceId, val effect: EffectId) {
    /** The journal's form of this id. */
    fun toRef(): MessageRef = MessageRef(from, effect.value)

    override fun toString(): String = "${from.value}/$effect"
}

/**
 * An external source of inputs attached to a group: a pagination engine, a socket reader, a
 * subscription to a repository. What it feeds is identified by it, and its state is data in a
 * cut ([ExternalSource]).
 */
@JvmInline
value class SourceId(val value: String) {
    init {
        require(value.isNotBlank()) { "[Actron] SourceId must not be blank" }
    }

    override fun toString(): String = value
}

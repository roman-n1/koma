package koma.statechart.machine

import koma.core.ExperimentalKomaApi
import kotlin.jvm.JvmInline
import kotlin.time.Duration

/**
 * The logical machine a [Machine] implements, stable across versions of its behaviour.
 */
@ExperimentalKomaApi
@JvmInline
value class DefinitionId(val value: String) {
    init {
        require(value.isNotBlank()) { "[Koma] DefinitionId must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * The exact version of a machine's behaviour: chart, guards, reducers and hook rules. A change
 * to any of them needs a new version even when the chart's graph is unchanged; a snapshot is
 * only decided by the version that produced it.
 */
@ExperimentalKomaApi
@JvmInline
value class DefinitionVersion(val value: String) {
    init {
        require(value.isNotBlank()) { "[Koma] DefinitionVersion must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * One entry into a node. Every entry, including a re-entry through a self-loop, gets a new id, so
 * work and timers belong to a particular stay in the node and not to the node itself.
 */
@ExperimentalKomaApi
@JvmInline
value class ActivationId(val value: Long) {
    override fun toString(): String = "a$value"
}

/**
 * One registered command.
 */
@ExperimentalKomaApi
@JvmInline
value class CommandId(val value: Long) {
    override fun toString(): String = "c$value"
}

/**
 * One scheduled timer: a start of a [koma.statechart.Trigger.After] transition for one activation
 * of its source.
 */
@ExperimentalKomaApi
@JvmInline
value class TimerId(val value: Long) {
    override fun toString(): String = "t$value"
}

/**
 * One event the machine asked to deliver (the handoff's UI effect).
 */
@ExperimentalKomaApi
@JvmInline
value class EffectId(val value: Long) {
    override fun toString(): String = "e$value"
}

/**
 * A transition of the chart by its position in [koma.statechart.StateChartDefinition.transitions],
 * stable within a [DefinitionVersion].
 */
@ExperimentalKomaApi
@JvmInline
value class TransitionId(val index: Int) {
    override fun toString(): String = "T$index"
}

/**
 * A lane groups commands that must not run freely next to each other; see [ConcurrencyPolicy].
 * Lanes are local to one Store instance.
 */
@ExperimentalKomaApi
@JvmInline
value class LaneId(val value: String) {
    init {
        require(value.isNotBlank()) { "[Koma] LaneId must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * The machine's clock: time since its runtime session started. Live, it is monotonic elapsed
 * time; in a replay, a virtual clock. It always arrives in the input, never from a clock read by
 * the machine, so deciding is a pure function.
 */
@ExperimentalKomaApi
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

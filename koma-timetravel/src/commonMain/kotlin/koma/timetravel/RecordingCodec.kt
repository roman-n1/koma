package koma.timetravel

import koma.core.Action
import koma.core.Event
import koma.core.ExperimentalKomaApi
import koma.observability.FailureDescriptor
import koma.observability.StoreInstanceId
import koma.statechart.StateConfiguration
import koma.statechart.StateId
import koma.statechart.machine.AbandonReason
import koma.statechart.machine.Activation
import koma.statechart.machine.ActivationId
import koma.statechart.machine.CommandId
import koma.statechart.machine.CommandRecord
import koma.statechart.machine.CommandRegistration
import koma.statechart.machine.ConcurrencyPolicy
import koma.statechart.machine.Decision
import koma.statechart.machine.DecisionOutcome
import koma.statechart.machine.DefinitionId
import koma.statechart.machine.DefinitionVersion
import koma.statechart.machine.EffectEnvelope
import koma.statechart.machine.EffectId
import koma.statechart.machine.EffectPolicy
import koma.statechart.machine.ExecutorCheckpoint
import koma.statechart.machine.IgnoreReason
import koma.statechart.machine.LaneId
import koma.statechart.machine.Lanes
import koma.statechart.machine.MachineCounters
import koma.statechart.machine.MachineInput
import koma.statechart.machine.MachineSnapshot
import koma.statechart.machine.MachineTime
import koma.statechart.machine.MessageId
import koma.statechart.machine.PendingEffect
import koma.statechart.machine.TimerId
import koma.statechart.machine.TimerRecord
import koma.statechart.machine.TimerSchedule
import koma.statechart.machine.TransitionId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlin.time.Duration

/**
 * Version of the JSON form of a [Recording]. Bumped when a field or a value changes meaning; a
 * reader that meets a newer version refuses, an older one is migrated by an explicit
 * [FormatMigration] or refused, never filled in with defaults.
 *
 * History: 1 began at an `initial` snapshot; 2 begins at a `start` checkpoint of the executor
 * (snapshot, clock, commands running, queued and ending); 3 adds the `bridgeReceived` input
 * with its message; 4 adds the pending `effects` of the mailbox to the checkpoint. The codec
 * migrates each version to the next itself.
 */
const val RECORDING_FORMAT_VERSION: Int = 4

/**
 * Turns the JSON of one format version into the next: an explicit, testable step.
 */
@ExperimentalKomaApi
class FormatMigration(val from: Int, val to: Int, val migrate: (JsonObject) -> JsonObject) {
    init {
        require(to > from) { "[Koma] A migration goes forward: $from -> $to" }
    }
}

/**
 * What [RecordingCodec.decode] found.
 */
@ExperimentalKomaApi
sealed interface DecodedRecording<C, A : Action, CMD, E : Event> {
    /** The recording, exactly as encoded. */
    data class Decoded<C, A : Action, CMD, E : Event>(val recording: Recording<C, A, CMD, E>) : DecodedRecording<C, A, CMD, E>

    /** The format version is unknown to this codec and no migration leads to a known one. */
    data class Unsupported<C, A : Action, CMD, E : Event>(val formatVersion: Int?, val reason: String) : DecodedRecording<C, A, CMD, E>

    /** The text is not a recording of this format, or a payload could not be decoded; [at] says where. */
    data class Invalid<C, A : Action, CMD, E : Event>(val reason: String, val at: String? = null) : DecodedRecording<C, A, CMD, E>
}

/**
 * Encodes a [Recording] as JSON and decodes it back, with the application's serializers for its
 * own types. The structure (the start checkpoint, snapshots, inputs, decisions, ids, timers,
 * failures) is the codec's and versioned by [RECORDING_FORMAT_VERSION]; the context, actions,
 * commands and events are whatever the serializers write. The output is canonical: fields in a
 * fixed order, maps sorted by key, commands sorted by id, no defaults, no nulls, durations in
 * ISO-8601, so equal recordings encode to equal text and the text can be a golden fixture.
 *
 * A decoded recording equals the encoded one when the serializers round-trip their values; an
 * older format is migrated by the codec's own migrations and the [migrations] given, applied in
 * sequence until the current version is reached; a newer or an unknown one is
 * [DecodedRecording.Unsupported]; a malformed text, an inconsistent checkpoint or a payload the
 * serializers reject is [DecodedRecording.Invalid] with the position.
 *
 * @param context Serializer of the machine's context
 * @param action Serializer of the actions (dispatched and returned by commands)
 * @param command Serializer of the commands
 * @param event Serializer of the events
 * @param migrations Migrations from format versions the codec does not migrate itself
 */
@ExperimentalKomaApi
class RecordingCodec<C, A : Action, CMD, E : Event>(
    private val context: KSerializer<C>,
    private val action: KSerializer<A>,
    private val command: KSerializer<CMD>,
    private val event: KSerializer<E>,
    private val migrations: List<FormatMigration> = emptyList(),
) {
    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
        prettyPrint = false
        ignoreUnknownKeys = false
    }

    // Format 1 had an `initial` snapshot where format 2 has the `start` checkpoint of a run that
    // has not started: the snapshot, the clock at zero, nothing running.
    private val builtIn = listOf(
        FormatMigration(from = 1, to = 2) { v1 ->
            buildJsonObject {
                put("formatVersion", JsonPrimitive(2))
                v1["definition"]?.let { put("definition", it) }
                v1["version"]?.let { put("version", it) }
                put("start", buildJsonObject {
                    v1["initial"]?.let { put("snapshot", it) }
                    put("now", JsonPrimitive("PT0S"))
                })
                v1["steps"]?.let { put("steps", it) }
            }
        },
        // Format 3 only adds an input type; a format 2 text is a format 3 text without bridge inputs.
        FormatMigration(from = 2, to = 3) { v2 ->
            buildJsonObject {
                for ((key, value) in v2) put(key, if (key == "formatVersion") JsonPrimitive(3) else value)
            }
        },
        // Format 4 only adds the checkpoint's effects; a format 3 text is a format 4 text with none pending.
        FormatMigration(from = 3, to = 4) { v3 ->
            buildJsonObject {
                for ((key, value) in v3) put(key, if (key == "formatVersion") JsonPrimitive(4) else value)
            }
        },
    )

    /**
     * The canonical JSON of [recording].
     */
    fun encode(recording: Recording<C, A, CMD, E>): String {
        val wire = RecordingWire(
            formatVersion = RECORDING_FORMAT_VERSION,
            definition = recording.definition.value,
            version = recording.version.value,
            start = recording.start.toWire(),
            steps = recording.steps.map { it.toWire() },
        )
        return json.encodeToString(RecordingWire.serializer(), wire)
    }

    /**
     * The recording in [text], migrated to the current format when older.
     */
    fun decode(text: String): DecodedRecording<C, A, CMD, E> {
        var element: JsonObject = try {
            json.parseToJsonElement(text) as? JsonObject ?: return DecodedRecording.Invalid("not a JSON object")
        } catch (e: SerializationException) {
            return DecodedRecording.Invalid("not JSON: ${e.message}")
        }
        var formatVersion = (element["formatVersion"] as? JsonPrimitive)?.let { runCatching { it.int }.getOrNull() }
            ?: return DecodedRecording.Invalid("no formatVersion")
        if (formatVersion > RECORDING_FORMAT_VERSION) {
            return DecodedRecording.Unsupported(formatVersion, "format $formatVersion is newer than this codec's $RECORDING_FORMAT_VERSION")
        }
        while (formatVersion < RECORDING_FORMAT_VERSION) {
            val migration = (builtIn + migrations).firstOrNull { it.from == formatVersion }
                ?: return DecodedRecording.Unsupported(formatVersion, "no migration from format $formatVersion")
            element = migration.migrate(element)
            formatVersion = migration.to
        }
        val wire = try {
            json.decodeFromJsonElement(RecordingWire.serializer(), element)
        } catch (e: SerializationException) {
            return DecodedRecording.Invalid("not a recording of format $RECORDING_FORMAT_VERSION: ${e.message}")
        } catch (e: IllegalArgumentException) {
            return DecodedRecording.Invalid("not a recording of format $RECORDING_FORMAT_VERSION: ${e.message}")
        }
        if (wire.formatVersion != RECORDING_FORMAT_VERSION) return DecodedRecording.Unsupported(wire.formatVersion, "migrated to ${wire.formatVersion}, not to $RECORDING_FORMAT_VERSION")
        return try {
            val definition = DefinitionId(wire.definition)
            val version = DefinitionVersion(wire.version)
            val start = wire.start.toCheckpoint(definition, version, at = "start")
            val steps = wire.steps.mapIndexed { index, step -> step.toStep(definition, version, at = "step $index") }
            DecodedRecording.Decoded(Recording(definition, version, start, steps))
        } catch (e: DecodeFailure) {
            DecodedRecording.Invalid(e.message ?: "invalid", e.at)
        } catch (e: IllegalArgumentException) {
            DecodedRecording.Invalid(e.message ?: "invalid")
        }
    }

    // --- encoding ---

    private fun MachineSnapshot<C>.toWire(): SnapshotWire = SnapshotWire(
        revision = revision,
        active = configuration.active.map { it.value }.sorted(),
        history = configuration.history.entries.sortedBy { it.key.value }.associate { (id, ids) -> id.value to ids.map { it.value }.sorted() },
        context = json.encodeToJsonElement(this@RecordingCodec.context, context),
        activations = activations.entries.sortedBy { it.key.value }.associate { (node, id) -> node.value to id.value },
        commands = commands.entries.sortedBy { it.key.value }.associate { (id, record) -> id.value.toString() to CommandRecordWire(record.scope.value, record.lane?.value) },
        timers = timers.entries.sortedBy { it.key.value }.associate { (id, record) -> id.value.toString() to TimerWire(record.transition.index, record.activation.value, record.deadline.sinceStart.toIsoString()) },
        counters = CountersWire(counters.activations, counters.commands, counters.timers, counters.effects),
    )

    private fun ExecutorCheckpoint<C, CMD>.toWire(): CheckpointWire = CheckpointWire(
        snapshot = snapshot.toWire(),
        now = now.sinceStart.toIsoString(),
        running = lanes.running.values.sortedBy { it.id.value }.map { it.toWire() },
        queued = lanes.queued.entries.sortedBy { it.key.value }.associate { (lane, waiting) -> lane.value to waiting.map { it.toWire() } },
        ending = ending.values.sortedBy { it.id.value }.map { it.toWire() },
        effects = effects.map { pending ->
            @Suppress("UNCHECKED_CAST")
            PendingEffectWire(pending.id.value, json.encodeToJsonElement(event, pending.event as E), pending.policy.toWire(), pending.attempts, pending.handling)
        },
    )

    private fun EffectPolicy.toWire(): EffectPolicyWire = when (this) {
        EffectPolicy.Transient -> EffectPolicyWire("transient")
        EffectPolicy.Retained -> EffectPolicyWire("retained")
        is EffectPolicy.Latest -> EffectPolicyWire("latest", key)
    }

    private fun CommandRegistration<CMD>.toWire(): CommandRegistrationWire =
        CommandRegistrationWire(id.value, json.encodeToJsonElement(this@RecordingCodec.command, command), scope.value, lane?.value, policy?.toWire())

    private fun RecordedStep<C, A, CMD, E>.toWire(): StepWire = when (this) {
        is RecordedStep.Committed -> StepWire(input.toWire(), committed = decision.toWire())
        is RecordedStep.Ignored -> StepWire(input.toWire(), ignored = reason.name)
        is RecordedStep.Failed -> StepWire(input.toWire(), failed = failure.toWire())
    }

    private fun MachineInput<A>.toWire(): InputWire = when (this) {
        is MachineInput.Start -> InputWire("start", now.sinceStart.toIsoString())
        is MachineInput.Dispatch -> InputWire("dispatch", now.sinceStart.toIsoString(), action = json.encodeToJsonElement(this@RecordingCodec.action, action))
        is MachineInput.BridgeReceived -> InputWire("bridgeReceived", now.sinceStart.toIsoString(), action = json.encodeToJsonElement(this@RecordingCodec.action, action), message = MessageWire(message.from.value, message.effect.value))
        is MachineInput.TimerFired -> InputWire("timerFired", now.sinceStart.toIsoString(), timer = timer.value)
        is MachineInput.CommandResult -> InputWire("commandResult", now.sinceStart.toIsoString(), command = command.value, action = json.encodeToJsonElement(this@RecordingCodec.action, action))
        is MachineInput.CommandCompleted -> InputWire("commandCompleted", now.sinceStart.toIsoString(), command = command.value)
        is MachineInput.CommandFailed -> InputWire("commandFailed", now.sinceStart.toIsoString(), command = command.value, failure = failure.toWire())
        is MachineInput.CommandAbandoned -> InputWire("commandAbandoned", now.sinceStart.toIsoString(), command = command.value, reason = reason.name)
    }

    private fun Decision<C, CMD, E>.toWire(): DecisionWire = DecisionWire(
        snapshot = snapshot.toWire(),
        transitions = transitions.map { it.index },
        exited = exited.map { ActivationWire(it.node.value, it.id.value) },
        entered = entered.map { ActivationWire(it.node.value, it.id.value) },
        commands = commands.map { it.toWire() },
        cancelledScopes = cancelledScopes.map { it.value },
        timersScheduled = timersScheduled.map { TimerScheduleWire(it.id.value, it.transition.index, it.activation.value, it.deadline.sinceStart.toIsoString()) },
        timersCancelled = timersCancelled.map { it.value },
        effects = effects.map { EffectWire(it.id.value, json.encodeToJsonElement(event, it.event)) },
    )

    private fun ConcurrencyPolicy.toWire(): PolicyWire = when (this) {
        ConcurrencyPolicy.Latest -> PolicyWire("latest")
        ConcurrencyPolicy.Sequential -> PolicyWire("sequential")
        ConcurrencyPolicy.DropIfRunning -> PolicyWire("dropIfRunning")
        is ConcurrencyPolicy.Parallel -> PolicyWire("parallel", limit)
    }

    private fun FailureDescriptor.toWire(): FailureWire = FailureWire(type, message, cause?.toWire(), suppressed.map { it.toWire() })

    // --- decoding ---

    private class DecodeFailure(message: String, val at: String) : RuntimeException(message)

    private inline fun <T> decoding(at: String, block: () -> T): T = try {
        block()
    } catch (e: DecodeFailure) {
        throw e
    } catch (e: SerializationException) {
        throw DecodeFailure(e.message ?: "cannot decode", at)
    } catch (e: IllegalArgumentException) {
        throw DecodeFailure(e.message ?: "cannot decode", at)
    }

    private fun SnapshotWire.toSnapshot(definition: DefinitionId, version: DefinitionVersion, at: String): MachineSnapshot<C> = MachineSnapshot(
        definition = definition,
        version = version,
        revision = revision,
        configuration = StateConfiguration(
            active = active.map(::StateId).toSet(),
            history = history.entries.associate { (id, ids) -> StateId(id) to ids.map(::StateId).toSet() },
        ),
        context = decoding("$at: context") { json.decodeFromJsonElement(this@RecordingCodec.context, context) },
        activations = activations.entries.associate { (node, id) -> StateId(node) to ActivationId(id) },
        commands = commands.entries.associate { (id, record) -> CommandId(id.toLongOrThrow(at)) to CommandRecord(ActivationId(record.scope), record.lane?.let(::LaneId)) },
        timers = timers.entries.associate { (id, record) -> TimerId(id.toLongOrThrow(at)) to TimerRecord(TransitionId(record.transition), ActivationId(record.activation), MachineTime(record.deadline.toDuration(at))) },
        counters = MachineCounters(counters.activations, counters.commands, counters.timers, counters.effects),
    )

    private fun CheckpointWire.toCheckpoint(definition: DefinitionId, version: DefinitionVersion, at: String): ExecutorCheckpoint<C, CMD> {
        val snapshot = snapshot.toSnapshot(definition, version, "$at: snapshot")
        val now = MachineTime(now.toDuration(at))
        val running = running.mapIndexed { index, it -> it.toRegistration("$at: running $index") }
        val queued = queued.entries.associate { (lane, waiting) -> LaneId(lane) to waiting.mapIndexed { index, it -> it.toRegistration("$at: queued in $lane $index") } }
        val ending = ending.mapIndexed { index, it -> it.toRegistration("$at: ending $index") }
        val effects = effects.mapIndexed { index, it ->
            PendingEffect(EffectId(it.id), decoding("$at: effect $index") { json.decodeFromJsonElement(event, it.event) }, it.policy.toPolicy(at), it.attempts, it.handling)
        }
        // The checkpoint checks itself: commands in one place each, and the snapshot's.
        return decoding(at) { ExecutorCheckpoint(snapshot, now, Lanes(running.associateBy { it.id }, queued), ending.associateBy { it.id }, effects) }
    }

    private fun EffectPolicyWire.toPolicy(at: String): EffectPolicy = when (kind) {
        "transient" -> EffectPolicy.Transient
        "retained" -> EffectPolicy.Retained
        "latest" -> EffectPolicy.Latest(key ?: throw DecodeFailure("latest policy without a key", at))
        else -> throw DecodeFailure("unknown effect policy '$kind'", at)
    }

    private fun CommandRegistrationWire.toRegistration(at: String): CommandRegistration<CMD> = decoding(at) {
        CommandRegistration(CommandId(id), json.decodeFromJsonElement(this@RecordingCodec.command, command), ActivationId(scope), lane?.let(::LaneId), policy?.toPolicy(at))
    }

    private fun StepWire.toStep(definition: DefinitionId, version: DefinitionVersion, at: String): RecordedStep<C, A, CMD, E> {
        val input = input.toInput(at)
        return when {
            committed != null -> RecordedStep.Committed(input, committed.toDecision(definition, version, at))
            ignored != null -> RecordedStep.Ignored(input, decoding("$at: ignore reason") { IgnoreReason.valueOf(ignored) })
            failed != null -> RecordedStep.Failed(input, failed.toDescriptor())
            else -> throw DecodeFailure("a step is committed, ignored or failed", at)
        }
    }

    private fun InputWire.toInput(at: String): MachineInput<A> {
        val now = MachineTime(this.now.toDuration(at))
        return when (type) {
            "start" -> MachineInput.Start(now)
            "dispatch" -> MachineInput.Dispatch(decodeAction(action, "$at: action"), now)
            "bridgeReceived" -> MachineInput.BridgeReceived(
                (message ?: throw DecodeFailure("bridgeReceived without a message", at)).let { MessageId(StoreInstanceId(it.from), EffectId(it.effect)) },
                decodeAction(action, "$at: action"),
                now,
            )
            "timerFired" -> MachineInput.TimerFired(TimerId(timer ?: throw DecodeFailure("timerFired without a timer", at)), now)
            "commandResult" -> MachineInput.CommandResult(CommandId(command ?: throw DecodeFailure("commandResult without a command", at)), decodeAction(action, "$at: result"), now)
            "commandCompleted" -> MachineInput.CommandCompleted(CommandId(command ?: throw DecodeFailure("commandCompleted without a command", at)), now)
            "commandFailed" -> MachineInput.CommandFailed(CommandId(command ?: throw DecodeFailure("commandFailed without a command", at)), (failure ?: throw DecodeFailure("commandFailed without a failure", at)).toDescriptor(), now)
            "commandAbandoned" -> MachineInput.CommandAbandoned(
                CommandId(command ?: throw DecodeFailure("commandAbandoned without a command", at)),
                decoding("$at: abandon reason") { AbandonReason.valueOf(reason ?: throw DecodeFailure("commandAbandoned without a reason", at)) },
                now,
            )
            else -> throw DecodeFailure("unknown input type '$type'", at)
        }
    }

    private fun decodeAction(element: JsonElement?, at: String): A =
        decoding(at) { json.decodeFromJsonElement(action, element ?: throw DecodeFailure("missing action", at)) }

    private fun DecisionWire.toDecision(definition: DefinitionId, version: DefinitionVersion, at: String): Decision<C, CMD, E> = Decision(
        outcome = DecisionOutcome.Handled,
        snapshot = snapshot.toSnapshot(definition, version, "$at: snapshot"),
        transitions = transitions.map(::TransitionId),
        exited = exited.map { Activation(StateId(it.node), ActivationId(it.id)) },
        entered = entered.map { Activation(StateId(it.node), ActivationId(it.id)) },
        commands = commands.mapIndexed { index, it -> it.toRegistration("$at: command $index") },
        cancelledScopes = cancelledScopes.map(::ActivationId),
        timersScheduled = timersScheduled.map { TimerSchedule(TimerId(it.id), TransitionId(it.transition), ActivationId(it.activation), MachineTime(it.deadline.toDuration(at))) },
        timersCancelled = timersCancelled.map(::TimerId),
        effects = effects.mapIndexed { index, it -> EffectEnvelope(EffectId(it.id), decoding("$at: effect $index") { json.decodeFromJsonElement(event, it.event) }) },
    )

    private fun PolicyWire.toPolicy(at: String): ConcurrencyPolicy = when (kind) {
        "latest" -> ConcurrencyPolicy.Latest
        "sequential" -> ConcurrencyPolicy.Sequential
        "dropIfRunning" -> ConcurrencyPolicy.DropIfRunning
        "parallel" -> ConcurrencyPolicy.Parallel(limit ?: throw DecodeFailure("parallel policy without a limit", at))
        else -> throw DecodeFailure("unknown policy '$kind'", at)
    }

    private fun FailureWire.toDescriptor(): FailureDescriptor = FailureDescriptor(type, message, cause?.toDescriptor(), suppressed.map { it.toDescriptor() })

    private fun String.toDuration(at: String): Duration = try {
        Duration.parseIsoString(this)
    } catch (e: IllegalArgumentException) {
        throw DecodeFailure("not an ISO-8601 duration: '$this'", at)
    }

    private fun String.toLongOrThrow(at: String): Long = toLongOrNull() ?: throw DecodeFailure("not an id: '$this'", at)
}

// The wire model: field order is the canonical order of the JSON.

@Serializable
internal class RecordingWire(val formatVersion: Int, val definition: String, val version: String, val start: CheckpointWire, val steps: List<StepWire>)

@Serializable
internal class CheckpointWire(
    val snapshot: SnapshotWire,
    val now: String,
    val running: List<CommandRegistrationWire> = emptyList(),
    val queued: Map<String, List<CommandRegistrationWire>> = emptyMap(),
    val ending: List<CommandRegistrationWire> = emptyList(),
    val effects: List<PendingEffectWire> = emptyList(),
)

@Serializable
internal class PendingEffectWire(val id: Long, val event: JsonElement, val policy: EffectPolicyWire, val attempts: Int, val handling: Boolean)

@Serializable
internal class EffectPolicyWire(val kind: String, val key: String? = null)

@Serializable
internal class SnapshotWire(
    val revision: Long,
    val active: List<String>,
    val history: Map<String, List<String>>,
    val context: JsonElement,
    val activations: Map<String, Long>,
    val commands: Map<String, CommandRecordWire>,
    val timers: Map<String, TimerWire>,
    val counters: CountersWire,
)

@Serializable
internal class CommandRecordWire(val scope: Long, val lane: String? = null)

@Serializable
internal class TimerWire(val transition: Int, val activation: Long, val deadline: String)

@Serializable
internal class CountersWire(val activations: Long, val commands: Long, val timers: Long, val effects: Long)

@Serializable
internal class StepWire(val input: InputWire, val committed: DecisionWire? = null, val ignored: String? = null, val failed: FailureWire? = null)

@Serializable
internal class InputWire(
    val type: String,
    val now: String,
    val action: JsonElement? = null,
    val command: Long? = null,
    val timer: Long? = null,
    val failure: FailureWire? = null,
    val reason: String? = null,
    val message: MessageWire? = null,
)

@Serializable
internal class MessageWire(val from: String, val effect: Long)

@Serializable
internal class DecisionWire(
    val snapshot: SnapshotWire,
    val transitions: List<Int>,
    val exited: List<ActivationWire>,
    val entered: List<ActivationWire>,
    val commands: List<CommandRegistrationWire>,
    val cancelledScopes: List<Long>,
    val timersScheduled: List<TimerScheduleWire>,
    val timersCancelled: List<Long>,
    val effects: List<EffectWire>,
)

@Serializable
internal class ActivationWire(val node: String, val id: Long)

@Serializable
internal class CommandRegistrationWire(val id: Long, val command: JsonElement, val scope: Long, val lane: String? = null, val policy: PolicyWire? = null)

@Serializable
internal class PolicyWire(val kind: String, val limit: Int? = null)

@Serializable
internal class TimerScheduleWire(val id: Long, val transition: Int, val activation: Long, val deadline: String)

@Serializable
internal class EffectWire(val id: Long, val event: JsonElement)

@Serializable
internal class FailureWire(val type: String? = null, val message: String? = null, val cause: FailureWire? = null, val suppressed: List<FailureWire> = emptyList())

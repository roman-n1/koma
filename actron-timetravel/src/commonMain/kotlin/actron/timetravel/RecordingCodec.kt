package actron.timetravel

import actron.core.Action
import actron.core.Event
import actron.observability.FailureDescriptor
import actron.observability.FailureDetails
import actron.observability.FailureRelation
import actron.observability.StoreInstanceId
import actron.statechart.StateConfiguration
import actron.statechart.StateId
import actron.statechart.machine.AbandonReason
import actron.statechart.machine.Activation
import actron.statechart.machine.ActivationId
import actron.statechart.machine.CommandId
import actron.statechart.machine.CommandRecord
import actron.statechart.machine.CommandRegistration
import actron.statechart.machine.CommandLane
import actron.statechart.machine.ConcurrencyPolicy
import actron.statechart.machine.Decision
import actron.statechart.machine.DecisionOutcome
import actron.statechart.machine.DefinitionId
import actron.statechart.machine.DefinitionVersion
import actron.statechart.machine.EffectEnvelope
import actron.statechart.machine.EffectId
import actron.statechart.machine.EffectPolicy
import actron.statechart.machine.ExecutorCheckpoint
import actron.statechart.machine.IgnoreReason
import actron.statechart.machine.LaneId
import actron.statechart.machine.RetryBudget
import actron.statechart.machine.Lanes
import actron.statechart.machine.MachineCounters
import actron.statechart.machine.MachineInput
import actron.statechart.machine.MachineSnapshot
import actron.statechart.machine.MachineTime
import actron.statechart.machine.MessageId
import actron.statechart.machine.PendingEffect
import actron.statechart.machine.SourceId
import actron.statechart.machine.TimerId
import actron.statechart.machine.TimerRecord
import actron.statechart.machine.TimerSchedule
import actron.statechart.machine.TransitionId
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlin.time.Duration

/**
 * Version of the JSON form of a [Recording]. Bumped when a field or a value changes meaning; a
 * reader that meets a newer version refuses, an older one is migrated by an explicit
 * [FormatMigration] or refused, never filled in with defaults.
 *
 * History: 1 began at an `initial` snapshot; 2 begins at a `start` checkpoint of the executor
 * (snapshot, clock, commands running, queued and ending); 3 adds the `bridgeReceived` input
 * with its message; 4 adds the pending `effects` of the mailbox to the checkpoint; 5 adds the
 * `external` input with its source. The codec migrates each version to the next itself.
 */
const val RECORDING_FORMAT_VERSION: Int = 6

/**
 * Turns the JSON of one format version into the next: an explicit, testable step.
 */
class FormatMigration(val from: Int, val to: Int, val migrate: (JsonObject) -> JsonObject) {
    init {
        require(to > from) { "[Actron] A migration goes forward: $from -> $to" }
    }
}

/**
 * What [RecordingCodec.decode] found.
 */
sealed interface DecodedRecording<C : Any, A : Action, CMD : Any, E : Event> {
    /** The recording, exactly as encoded. */
    data class Decoded<C : Any, A : Action, CMD : Any, E : Event>(val recording: Recording<C, A, CMD, E>) : DecodedRecording<C, A, CMD, E>

    /** The format version is unknown to this codec and no migration leads to a known one. */
    data class Unsupported<C : Any, A : Action, CMD : Any, E : Event>(val formatVersion: Int, val reason: String) : DecodedRecording<C, A, CMD, E>

    /** The text is not a recording of this format, or a payload could not be decoded; [at] says where. */
    data class Invalid<C : Any, A : Action, CMD : Any, E : Event>(val reason: String, val location: RecordingLocation = RecordingLocation.Document) : DecodedRecording<C, A, CMD, E> {
        constructor(reason: String, at: String) : this(reason, RecordingLocation.Part(at))
    }
}

/** Scope of a decoder failure: the document itself or an actual named part of it. */
sealed interface RecordingLocation {
    fun withPart(accept: (String) -> Unit)
    data object Document : RecordingLocation {
        override fun withPart(accept: (String) -> Unit) = Unit
    }
    data class Part(val path: String) : RecordingLocation {
        override fun withPart(accept: (String) -> Unit) = accept(path)
    }
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
class RecordingCodec<C : Any, A : Action, CMD : Any, E : Event>(
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
                if ("definition" in v1) put("definition", v1.getValue("definition"))
                if ("version" in v1) put("version", v1.getValue("version"))
                put("start", buildJsonObject {
                    if ("initial" in v1) put("snapshot", v1.getValue("initial"))
                    put("now", JsonPrimitive("PT0S"))
                })
                if ("steps" in v1) put("steps", v1.getValue("steps"))
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
        // Format 5 only adds an input type; a format 4 text is a format 5 text without external inputs.
        FormatMigration(from = 4, to = 5) { v4 ->
            buildJsonObject {
                for ((key, value) in v4) put(key, if (key == "formatVersion") JsonPrimitive(5) else value)
            }
        },
        // Format 6 only adds an effect policy's optional budget; a format 5 text is a format 6 text whose policies have none.
        FormatMigration(from = 5, to = 6) { v5 ->
            buildJsonObject {
                for ((key, value) in v5) put(key, if (key == "formatVersion") JsonPrimitive(6) else value)
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
        var formatVersion = (element["formatVersion"] as? JsonPrimitive)?.intOrNull
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

    // --- parts, for the segments of a recording file ---

    /** The JSON of [checkpoint] alone. */
    fun encodeCheckpoint(checkpoint: ExecutorCheckpoint<C, CMD>): String = json.encodeToString(CheckpointWire.serializer(), checkpoint.toWire())

    /**
     * The checkpoint [encodeCheckpoint] wrote, for a recording of [definition] and [version].
     *
     * @throws IllegalArgumentException if [text] is not one, with where it failed
     */
    fun decodeCheckpoint(text: String, definition: DefinitionId, version: DefinitionVersion): ExecutorCheckpoint<C, CMD> =
        part("checkpoint") { json.decodeFromString(CheckpointWire.serializer(), text).toCheckpoint(definition, version, "checkpoint") }

    /** The JSON of [step] alone. */
    fun encodeStep(step: RecordedStep<C, A, CMD, E>): String = json.encodeToString(StepWire.serializer(), step.toWire())

    /**
     * The step [encodeStep] wrote, for a recording of [definition] and [version].
     *
     * @throws IllegalArgumentException if [text] is not one, with where it failed
     */
    fun decodeStep(text: String, definition: DefinitionId, version: DefinitionVersion): RecordedStep<C, A, CMD, E> =
        part("step") { json.decodeFromString(StepWire.serializer(), text).toStep(definition, version, "step") }

    private inline fun <T : Any> part(what: String, block: () -> T): T = try {
        block()
    } catch (e: SerializationException) {
        throw IllegalArgumentException("not a $what of format $RECORDING_FORMAT_VERSION: ${e.message}")
    } catch (e: DecodeFailure) {
        throw IllegalArgumentException("${e.message} (at ${e.at})")
    }

    // --- encoding ---

    private fun MachineSnapshot<C>.toWire(): SnapshotWire = SnapshotWire(
        revision = revision,
        active = configuration.active.map { it.value }.sorted(),
        history = configuration.history.entries.sortedBy { it.key.value }.associate { (id, ids) -> id.value to ids.map { it.value }.sorted() },
        context = json.encodeToJsonElement(this@RecordingCodec.context, context),
        activations = activations.entries.sortedBy { it.key.value }.associate { (node, id) -> node.value to id.value },
        commands = commands.entries.sortedBy { it.key.value }.associate { (id, record) -> id.value.toString() to when (val lane = record.lane) {
            CommandLane.Independent -> CommandRecordWire(record.scope.value)
            is LaneId -> CommandRecordWire(record.scope.value, JsonPrimitive(lane.value))
        } },
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
        is EffectPolicy.Retained -> when (val budget = retryBudget) {
            RetryBudget.Unlimited -> EffectPolicyWire("retained")
            is RetryBudget.Limited -> EffectPolicyWire("retained", maxAttempts = JsonPrimitive(budget.maxAttempts))
        }
        is EffectPolicy.Latest -> when (val budget = retryBudget) {
            RetryBudget.Unlimited -> EffectPolicyWire("latest", JsonPrimitive(key))
            is RetryBudget.Limited -> EffectPolicyWire("latest", JsonPrimitive(key), JsonPrimitive(budget.maxAttempts))
        }
    }

    private fun CommandRegistration<CMD>.toWire(): CommandRegistrationWire =
        when (val assigned = lane) {
            CommandLane.Independent -> CommandRegistrationWire(id.value, json.encodeToJsonElement(this@RecordingCodec.command, command), scope.value)
            is LaneId -> CommandRegistrationWire(id.value, json.encodeToJsonElement(this@RecordingCodec.command, command), scope.value, JsonPrimitive(assigned.value), json.encodeToJsonElement(PolicyWire.serializer(), policy.toWire()))
        }

    private fun RecordedStep<C, A, CMD, E>.toWire(): StepWire = when (this) {
        is RecordedStep.Committed -> StepWire(input.toWire(), committed = json.encodeToJsonElement(DecisionWire.serializer(), decision.toWire()))
        is RecordedStep.Ignored -> StepWire(input.toWire(), ignored = JsonPrimitive(reason.name))
        is RecordedStep.Failed -> StepWire(input.toWire(), failed = json.encodeToJsonElement(FailureWire.serializer(), failure.toWire()))
    }

    private fun MachineInput<A>.toWire(): JsonObject = buildJsonObject {
        val input = this@toWire
        val type = when (input) {
            is MachineInput.Start -> "start"; is MachineInput.Dispatch -> "dispatch"
            is MachineInput.BridgeReceived -> "bridgeReceived"; is MachineInput.External -> "external"
            is MachineInput.TimerFired -> "timerFired"; is MachineInput.CommandResult -> "commandResult"
            is MachineInput.CommandCompleted -> "commandCompleted"; is MachineInput.CommandFailed -> "commandFailed"
            is MachineInput.CommandAbandoned -> "commandAbandoned"
        }
        put("type", JsonPrimitive(type))
        put("now", JsonPrimitive(input.now.sinceStart.toIsoString()))
        when (input) {
            is MachineInput.Start -> Unit
            is MachineInput.Dispatch -> put("action", json.encodeToJsonElement(this@RecordingCodec.action, input.action))
            is MachineInput.BridgeReceived -> {
                put("action", json.encodeToJsonElement(this@RecordingCodec.action, input.action))
                put("message", json.encodeToJsonElement(MessageWire.serializer(), MessageWire(input.message.from.value, input.message.effect.value)))
            }
            is MachineInput.External -> {
                put("action", json.encodeToJsonElement(this@RecordingCodec.action, input.action))
                put("source", JsonPrimitive(input.source.value))
            }
            is MachineInput.TimerFired -> put("timer", JsonPrimitive(input.timer.value))
            is MachineInput.CommandResult -> {
                put("action", json.encodeToJsonElement(this@RecordingCodec.action, input.action))
                put("command", JsonPrimitive(input.command.value))
            }
            is MachineInput.CommandCompleted -> put("command", JsonPrimitive(input.command.value))
            is MachineInput.CommandFailed -> {
                put("command", JsonPrimitive(input.command.value))
                put("failure", json.encodeToJsonElement(FailureWire.serializer(), input.failure.toWire()))
            }
            is MachineInput.CommandAbandoned -> {
                put("command", JsonPrimitive(input.command.value))
                put("reason", JsonPrimitive(input.reason.name))
            }
        }
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
        ConcurrencyPolicy.Independent -> error("[Actron] Independent commands have no serialized lane policy")
        ConcurrencyPolicy.Latest -> PolicyWire("latest")
        ConcurrencyPolicy.Sequential -> PolicyWire("sequential")
        ConcurrencyPolicy.DropIfRunning -> PolicyWire("dropIfRunning")
        is ConcurrencyPolicy.Parallel -> PolicyWire("parallel", JsonPrimitive(limit))
    }

    private fun FailureDescriptor.toWire(): FailureWire {
        var type: JsonElement = JsonNull
        var message: JsonElement = JsonNull
        var cause: JsonElement = JsonNull
        withType { type = JsonPrimitive(it) }
        withMessage { message = JsonPrimitive(it) }
        withCause { cause = json.encodeToJsonElement(FailureWire.serializer(), it.toWire()) }
        return FailureWire(type, message, cause, suppressed.map { it.toWire() })
    }

    // --- decoding ---

    private class DecodeFailure(message: String, val at: String) : RuntimeException(message)

    private inline fun <T : Any> decoding(at: String, block: () -> T): T = try {
        block()
    } catch (e: DecodeFailure) {
        throw e
    } catch (e: SerializationException) {
        throw DecodeFailure(e.message ?: "cannot decode", at)
    } catch (e: IllegalArgumentException) {
        throw DecodeFailure(e.message ?: "cannot decode", at)
    }

    private fun JsonElement.required(message: String, at: String): JsonElement {
        if (this == JsonNull) throw DecodeFailure(message, at)
        return this
    }
    private fun JsonElement.text(at: String): String = decoding(at) {
        json.decodeFromJsonElement(String.serializer(), this)
    }
    private fun JsonElement.integer(at: String): Int = decoding(at) {
        json.decodeFromJsonElement(Int.serializer(), this)
    }
    private fun JsonElement.long(at: String): Long = decoding(at) {
        json.decodeFromJsonElement(Long.serializer(), this)
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
        commands = commands.entries.associate { (id, record) -> CommandId(id.toLongOrThrow(at)) to CommandRecord(ActivationId(record.scope), if (record.lane == JsonNull) CommandLane.Independent else LaneId(record.lane.text(at))) },
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

    private fun EffectPolicyWire.toPolicy(at: String): EffectPolicy {
        fun budget(): RetryBudget = if (maxAttempts == JsonNull) RetryBudget.Unlimited else RetryBudget.Limited(maxAttempts.integer(at))
        return when (kind) {
            "transient" -> EffectPolicy.Transient
            "retained" -> EffectPolicy.Retained(budget())
            "latest" -> EffectPolicy.Latest(key.required("latest policy without a key", at).text(at), budget())
            else -> throw DecodeFailure("unknown effect policy '$kind'", at)
        }
    }

    private fun CommandRegistrationWire.toRegistration(at: String): CommandRegistration<CMD> = decoding(at) {
        CommandRegistration(CommandId(id), json.decodeFromJsonElement(this@RecordingCodec.command, command), ActivationId(scope),
            if (lane == JsonNull) CommandLane.Independent else LaneId(lane.text(at)),
            if (policy == JsonNull) ConcurrencyPolicy.Independent else json.decodeFromJsonElement(PolicyWire.serializer(), policy).toPolicy(at))
    }

    private fun StepWire.toStep(definition: DefinitionId, version: DefinitionVersion, at: String): RecordedStep<C, A, CMD, E> {
        val input = json.decodeFromJsonElement(InputWire.serializer(), this.input).toInput(at, this.input.keys)
        return when {
            committed != JsonNull -> RecordedStep.Committed(input, json.decodeFromJsonElement(DecisionWire.serializer(), committed).toDecision(definition, version, at))
            ignored != JsonNull -> RecordedStep.Ignored(input, decoding("$at: ignore reason") { IgnoreReason.valueOf(ignored.text(at)) })
            failed != JsonNull -> RecordedStep.Failed(input, json.decodeFromJsonElement(FailureWire.serializer(), failed).toDescriptor())
            else -> throw DecodeFailure("a step is committed, ignored or failed", at)
        }
    }

    private fun InputWire.toInput(at: String, fields: Set<String>): MachineInput<A> {
        val now = MachineTime(this.now.toDuration(at))
        fun action(where: String): A = decoding(where) {
            if ("action" !in fields) throw DecodeFailure("missing action", where)
            // An application's non-null Action serializer can legitimately encode JSON null.
            json.decodeFromJsonElement(this@RecordingCodec.action, this.action)
        }
        fun command(message: String): CommandId = CommandId(this.command.required(message, at).long(at))
        return when (type) {
            "start" -> MachineInput.Start(now)
            "dispatch" -> MachineInput.Dispatch(action("$at: action"), now)
            "external" -> MachineInput.External(SourceId(source.required("external without a source", at).text(at)), action("$at: action"), now)
            "bridgeReceived" -> MachineInput.BridgeReceived(
                json.decodeFromJsonElement(MessageWire.serializer(), message.required("bridgeReceived without a message", at)).let { MessageId(StoreInstanceId(it.from), EffectId(it.effect)) },
                action("$at: action"), now)
            "timerFired" -> MachineInput.TimerFired(TimerId(timer.required("timerFired without a timer", at).long(at)), now)
            "commandResult" -> MachineInput.CommandResult(command("commandResult without a command"), action("$at: result"), now)
            "commandCompleted" -> MachineInput.CommandCompleted(command("commandCompleted without a command"), now)
            "commandFailed" -> MachineInput.CommandFailed(command("commandFailed without a command"),
                json.decodeFromJsonElement(FailureWire.serializer(), failure.required("commandFailed without a failure", at)).toDescriptor(), now)
            "commandAbandoned" -> MachineInput.CommandAbandoned(command("commandAbandoned without a command"),
                decoding("$at: abandon reason") { AbandonReason.valueOf(reason.required("commandAbandoned without a reason", at).text(at)) }, now)
            else -> throw DecodeFailure("unknown input type '$type'", at)
        }
    }

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
        "parallel" -> ConcurrencyPolicy.Parallel(limit.required("parallel policy without a limit", at).integer(at))
        else -> throw DecodeFailure("unknown policy '$kind'", at)
    }

    private fun FailureWire.toDescriptor(): FailureDescriptor {
        require(type == JsonNull || type is JsonPrimitive && type.isString) { "[Actron] Failure type must be a JSON string" }
        require(message == JsonNull || message is JsonPrimitive && message.isString) { "[Actron] Failure message must be a JSON string" }
        val details = when {
            type != JsonNull && message != JsonNull -> FailureDetails.Detailed(type.jsonPrimitive.content, message.jsonPrimitive.content)
            type != JsonNull -> FailureDetails.MetadataOnly(type.jsonPrimitive.content)
            message != JsonNull -> FailureDetails.AnonymousMessage(message.jsonPrimitive.content)
            else -> FailureDetails.Redacted
        }
        val related = buildList {
            if (cause != JsonNull) add(FailureRelation.Cause(json.decodeFromJsonElement(FailureWire.serializer(), cause).toDescriptor()))
            suppressed.forEach { add(FailureRelation.Suppressed(it.toDescriptor())) }
        }
        return FailureDescriptor(details, related)
    }

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
internal class EffectPolicyWire(val kind: String, @Serializable(with = StringWireNode::class) val key: JsonElement = JsonNull, @Serializable(with = IntWireNode::class) val maxAttempts: JsonElement = JsonNull)

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
internal class CommandRecordWire(val scope: Long, @Serializable(with = StringWireNode::class) val lane: JsonElement = JsonNull)

@Serializable
internal class TimerWire(val transition: Int, val activation: Long, val deadline: String)

@Serializable
internal class CountersWire(val activations: Long, val commands: Long, val timers: Long, val effects: Long)

@Serializable
internal class StepWire(val input: JsonObject, @Serializable(with = DecisionWireNode::class) val committed: JsonElement = JsonNull, @Serializable(with = StringWireNode::class) val ignored: JsonElement = JsonNull, @Serializable(with = FailureWireNode::class) val failed: JsonElement = JsonNull)

@Serializable
internal class InputWire(
    val type: String,
    val now: String,
    val action: JsonElement = JsonNull,
    @Serializable(with = LongWireNode::class) val command: JsonElement = JsonNull,
    @Serializable(with = LongWireNode::class) val timer: JsonElement = JsonNull,
    @Serializable(with = FailureWireNode::class) val failure: JsonElement = JsonNull,
    @Serializable(with = StringWireNode::class) val reason: JsonElement = JsonNull,
    @Serializable(with = MessageWireNode::class) val message: JsonElement = JsonNull,
    @Serializable(with = StringWireNode::class) val source: JsonElement = JsonNull,
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
internal class CommandRegistrationWire(val id: Long, val command: JsonElement, val scope: Long, @Serializable(with = StringWireNode::class) val lane: JsonElement = JsonNull, @Serializable(with = PolicyWireNode::class) val policy: JsonElement = JsonNull)

@Serializable
internal class PolicyWire(val kind: String, @Serializable(with = IntWireNode::class) val limit: JsonElement = JsonNull)

@Serializable
internal class TimerScheduleWire(val id: Long, val transition: Int, val activation: Long, val deadline: String)

@Serializable
internal class EffectWire(val id: Long, val event: JsonElement)

@Serializable
internal class FailureWire(@Serializable(with = StringWireNode::class) val type: JsonElement = JsonNull, @Serializable(with = StringWireNode::class) val message: JsonElement = JsonNull, @Serializable(with = FailureWireNode::class) val cause: JsonElement = JsonNull, val suppressed: List<FailureWire> = emptyList())

// These serializers preserve nullable legacy JSON FIELD schemas without Kotlin nullable values.
// The concrete JsonNull node denotes a protocol token; domain contracts are normalized above.
private abstract class WireNode<T : Any>(private val schema: () -> KSerializer<T>) : KSerializer<JsonElement> {
    override val descriptor: SerialDescriptor get() = JsonElement.serializer().descriptor
    override fun serialize(encoder: Encoder, value: JsonElement) {
        (encoder as JsonEncoder).encodeJsonElement(value)
    }
    override fun deserialize(decoder: Decoder): JsonElement {
        val input = decoder as JsonDecoder
        val node = input.decodeJsonElement()
        if (node != JsonNull) input.json.decodeFromJsonElement(schema(), node)
        return node
    }
}
private object StringWireNode : WireNode<String>({ String.serializer() })
private object IntWireNode : WireNode<Int>({ Int.serializer() })
private object LongWireNode : WireNode<Long>({ Long.serializer() })
private object MessageWireNode : WireNode<MessageWire>({ MessageWire.serializer() })
private object PolicyWireNode : WireNode<PolicyWire>({ PolicyWire.serializer() })
private object DecisionWireNode : WireNode<DecisionWire>({ DecisionWire.serializer() })
private object FailureWireNode : WireNode<FailureWire>({ FailureWire.serializer() })

@file:OptIn(ExperimentalKomaApi::class)

package koma.timetravel

import koma.core.Action
import koma.core.Event
import koma.core.ExperimentalKomaApi
import koma.observability.FailureDescriptor
import koma.statechart.ActionMatcher
import koma.statechart.AtomicState
import koma.statechart.CompoundState
import koma.statechart.StateChartDefinition
import koma.statechart.StateId
import koma.statechart.Transition
import koma.statechart.Trigger
import koma.statechart.machine.AbandonReason
import koma.statechart.machine.CommandId
import koma.statechart.machine.ConcurrencyPolicy
import koma.statechart.machine.DefinitionId
import koma.statechart.machine.DefinitionVersion
import koma.statechart.machine.LaneId
import koma.statechart.machine.Machine
import koma.statechart.machine.MachineInput
import koma.statechart.machine.MachineSnapshot
import koma.statechart.machine.MachineTime
import koma.statechart.machine.TimerId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The JSON form of a recording, pinned as a golden fixture: a deterministic recording built by
 * the pure machine encodes to exactly [GOLDEN], decodes back to an equal recording, and the
 * codec refuses what it must (a newer format, an older one without a migration, a payload the
 * serializers reject) instead of guessing. Changing [GOLDEN] is changing the format: bump
 * [RECORDING_FORMAT_VERSION] and add a [FormatMigration].
 *
 * ```
 * [*] --> Idle
 * Idle --Load(query)--> Loading            onEnter: command Fetch(query) in lane "load", Latest; timer 10s to Idle
 * Loading --Loaded / store--> Content      onEnter: event Shown
 * Loading --Load / remember--> Loading
 * ```
 */
class RecordingCodecGoldenTest {

    @Serializable
    data class Ctx(val query: String = "", val items: List<String> = emptyList(), val timeouts: Int = 0)

    // Explicit serial names, never the class names: they survive renaming and obfuscation.
    @Serializable
    sealed interface Act : Action {
        @Serializable
        @SerialName("Load")
        data class Load(val query: String) : Act

        @Serializable
        @SerialName("Loaded")
        data class Loaded(val items: List<String>) : Act
    }

    @Serializable
    sealed interface Ev : Event {
        @Serializable
        @SerialName("Shown")
        data object Shown : Ev
    }

    @Serializable
    data class Fetch(val query: String)

    private val root = StateId("Root")
    private val idle = StateId("Idle")
    private val loading = StateId("Loading")
    private val content = StateId("Content")

    private val chart = StateChartDefinition(
        root,
        listOf(CompoundState(root, initial = idle), AtomicState(idle, parent = root), AtomicState(loading, parent = root), AtomicState(content, parent = root)),
        listOf(
            Transition(idle, loading, ActionMatcher.of<Act.Load>("Load"), effect = "remember"),
            Transition(loading, content, ActionMatcher.of<Act.Loaded>("Loaded"), effect = "store"),
            Transition(loading, loading, ActionMatcher.of<Act.Load>("Load"), effect = "remember"),
            Transition(loading, idle, Trigger.After(10.seconds), effect = "timeout"),
        ),
    )

    private val machine = Machine<Ctx, Act, Fetch, Ev>(DefinitionId("golden"), DefinitionVersion("1"), chart) {
        effect("remember") { c, a -> c.copy(query = (a as Act.Load).query) }
        effect("store") { c, a -> c.copy(items = (a as Act.Loaded).items) }
        effect("timeout") { c, _ -> c.copy(timeouts = c.timeouts + 1) }
        onEnter(loading) { command(Fetch(context.query), LaneId("load"), ConcurrencyPolicy.Latest) }
        onEnter(content) { event(Ev.Shown) }
    }

    private val codec = RecordingCodec(Ctx.serializer(), Act.serializer(), Fetch.serializer(), Ev.serializer())

    /** Every kind of step and input: start, dispatch, stale result, abandonment, timer, result, failure, ignored. */
    private fun recording(): Recording<Ctx, Act, Fetch, Ev> {
        val inputs: List<MachineInput<Act>> = listOf(
            MachineInput.Start(MachineTime.Zero),
            MachineInput.Dispatch(Act.Load("cats"), MachineTime(50.milliseconds)),
            MachineInput.Dispatch(Act.Load("dogs"), MachineTime(1.5.seconds)),
            MachineInput.CommandResult(CommandId(1), Act.Loaded(listOf("stale")), MachineTime(1.6.seconds)),
            MachineInput.CommandAbandoned(CommandId(2), AbandonReason.Superseded, MachineTime(2.seconds)),
            MachineInput.TimerFired(TimerId(2), MachineTime(11.5.seconds)),
            MachineInput.Dispatch(Act.Load("birds"), MachineTime(12.seconds)),
            MachineInput.CommandFailed(CommandId(3), FailureDescriptor("IOException", cause = FailureDescriptor("SocketException")), MachineTime(13.seconds)),
            MachineInput.CommandResult(CommandId(3), Act.Loaded(listOf("tweety")), MachineTime(14.seconds)),
            MachineInput.Dispatch(Act.Loaded(emptyList()), MachineTime(15.seconds)),
        )
        var snapshot = machine.initialSnapshot(Ctx())
        val steps = inputs.map { input ->
            val decision = machine.decide(snapshot, input)
            when (val outcome = decision.outcome) {
                koma.statechart.machine.DecisionOutcome.Handled -> {
                    snapshot = decision.snapshot
                    RecordedStep.Committed<Ctx, Act, Fetch, Ev>(input, decision)
                }
                is koma.statechart.machine.DecisionOutcome.Ignored -> RecordedStep.Ignored<Ctx, Act, Fetch, Ev>(input, outcome.reason)
                is koma.statechart.machine.DecisionOutcome.Failed -> RecordedStep.Failed<Ctx, Act, Fetch, Ev>(input, outcome.failure)
            }
        }
        return Recording(machine.id, machine.version, machine.initialSnapshot(Ctx()), steps)
    }

    @Test
    fun theGoldenFixture_isTheCanonicalJsonOfTheRecording() {
        val encoded = codec.encode(recording())
        assertEquals(GOLDEN, encoded, "the format changed; if that is intended, bump RECORDING_FORMAT_VERSION and add a migration.\nACTUAL:\n$encoded")
    }

    @Test
    fun theGoldenFixture_decodesToTheRecording_andRoundTrips() {
        val recording = recording()
        val decoded = assertIs<DecodedRecording.Decoded<Ctx, Act, Fetch, Ev>>(codec.decode(GOLDEN))

        assertEquals(recording.definition, decoded.recording.definition)
        assertEquals(recording.version, decoded.recording.version)
        assertEquals(recording.initial, decoded.recording.initial)
        assertEquals(recording.steps, decoded.recording.steps)
        assertEquals(GOLDEN, codec.encode(decoded.recording), "encode(decode(golden)) is the golden again")
        // The decoded recording replays.
        val session = ReplaySession(machine, decoded.recording)
        assertEquals(null, session.verify())
    }

    @Test
    fun aNewerFormat_isUnsupported_notGuessed() {
        val newer = GOLDEN.replaceFirst("\"formatVersion\":1", "\"formatVersion\":${RECORDING_FORMAT_VERSION + 1}")

        val result = codec.decode(newer)

        val unsupported = assertIs<DecodedRecording.Unsupported<Ctx, Act, Fetch, Ev>>(result)
        assertEquals(RECORDING_FORMAT_VERSION + 1, unsupported.formatVersion)
    }

    @Test
    fun anOlderFormat_isMigratedByAnExplicitMigration_orUnsupported() {
        // A pretend format 0 that called the context "ctx", as an older writer might have.
        val older = GOLDEN.replaceFirst("\"formatVersion\":1", "\"formatVersion\":0").replace("\"context\":", "\"ctx\":")
        assertTrue(older != GOLDEN)

        assertIs<DecodedRecording.Unsupported<Ctx, Act, Fetch, Ev>>(codec.decode(older), "no migration registered: never guessed")

        fun JsonElement.renamed(): JsonElement = when (this) {
            is JsonObject -> buildJsonObject { for ((key, value) in this@renamed) put(if (key == "ctx") "context" else key, value.renamed()) }
            is JsonArray -> JsonArray(map { it.renamed() })
            else -> this
        }
        val migrating = RecordingCodec(
            Ctx.serializer(), Act.serializer(), Fetch.serializer(), Ev.serializer(),
            migrations = listOf(
                FormatMigration(from = 0, to = 1) { obj ->
                    buildJsonObject { (obj.renamed() as JsonObject).forEach { (k, v) -> put(k, if (k == "formatVersion") JsonPrimitive(1) else v) } }
                },
            ),
        )

        val migrated = assertIs<DecodedRecording.Decoded<Ctx, Act, Fetch, Ev>>(migrating.decode(older))
        assertEquals(recording().steps, migrated.recording.steps)
        assertEquals(recording().initial, migrated.recording.initial)
        assertEquals(GOLDEN, migrating.encode(migrated.recording), "the migrated recording is the current format")
    }

    @Test
    fun aPayloadTheSerializersReject_isInvalid_atTheStep() {
        val broken = GOLDEN.replaceFirst("{\"query\":\"birds\"}", "{\"qwery\":\"birds\"}")
        assertTrue(broken != GOLDEN)

        val result = codec.decode(broken)

        val invalid = assertIs<DecodedRecording.Invalid<Ctx, Act, Fetch, Ev>>(result)
        assertTrue(invalid.at != null && invalid.at!!.startsWith("step 6"), "$invalid")
        assertIs<DecodedRecording.Invalid<Ctx, Act, Fetch, Ev>>(codec.decode("not json"))
        assertIs<DecodedRecording.Invalid<Ctx, Act, Fetch, Ev>>(codec.decode("{\"definition\":\"x\"}"))
    }

    companion object {
        /** Format 1. Change it only together with RECORDING_FORMAT_VERSION and a migration. */
        const val GOLDEN: String = """{"formatVersion":1,"definition":"golden","version":"1","initial":{"revision":0,"active":[],"history":{},"context":{},"activations":{},"commands":{},"timers":{},"counters":{"activations":0,"commands":0,"timers":0,"effects":0}},"steps":[{"input":{"type":"start","now":"PT0S"},"committed":{"snapshot":{"revision":1,"active":["Idle","Root"],"history":{},"context":{},"activations":{"Idle":2,"Root":1},"commands":{},"timers":{},"counters":{"activations":2,"commands":0,"timers":0,"effects":0}},"transitions":[],"exited":[],"entered":[{"node":"Root","id":1},{"node":"Idle","id":2}],"commands":[],"cancelledScopes":[],"timersScheduled":[],"timersCancelled":[],"effects":[]}},{"input":{"type":"dispatch","now":"PT0.050S","action":{"type":"Load","query":"cats"}},"committed":{"snapshot":{"revision":2,"active":["Loading","Root"],"history":{},"context":{"query":"cats"},"activations":{"Loading":3,"Root":1},"commands":{"1":{"scope":3,"lane":"load"}},"timers":{"1":{"transition":3,"activation":3,"deadline":"PT10.050S"}},"counters":{"activations":3,"commands":1,"timers":1,"effects":0}},"transitions":[0],"exited":[{"node":"Idle","id":2}],"entered":[{"node":"Loading","id":3}],"commands":[{"id":1,"command":{"query":"cats"},"scope":3,"lane":"load","policy":{"kind":"latest"}}],"cancelledScopes":[2],"timersScheduled":[{"id":1,"transition":3,"activation":3,"deadline":"PT10.050S"}],"timersCancelled":[],"effects":[]}},{"input":{"type":"dispatch","now":"PT1.500S","action":{"type":"Load","query":"dogs"}},"committed":{"snapshot":{"revision":3,"active":["Loading","Root"],"history":{},"context":{"query":"dogs"},"activations":{"Loading":4,"Root":1},"commands":{"2":{"scope":4,"lane":"load"}},"timers":{"2":{"transition":3,"activation":4,"deadline":"PT11.500S"}},"counters":{"activations":4,"commands":2,"timers":2,"effects":0}},"transitions":[2],"exited":[{"node":"Loading","id":3}],"entered":[{"node":"Loading","id":4}],"commands":[{"id":2,"command":{"query":"dogs"},"scope":4,"lane":"load","policy":{"kind":"latest"}}],"cancelledScopes":[3],"timersScheduled":[{"id":2,"transition":3,"activation":4,"deadline":"PT11.500S"}],"timersCancelled":[1],"effects":[]}},{"input":{"type":"commandResult","now":"PT1.600S","action":{"type":"Loaded","items":["stale"]},"command":1},"ignored":"StaleCommand"},{"input":{"type":"commandAbandoned","now":"PT2S","command":2,"reason":"Superseded"},"committed":{"snapshot":{"revision":4,"active":["Loading","Root"],"history":{},"context":{"query":"dogs"},"activations":{"Loading":4,"Root":1},"commands":{},"timers":{"2":{"transition":3,"activation":4,"deadline":"PT11.500S"}},"counters":{"activations":4,"commands":2,"timers":2,"effects":0}},"transitions":[],"exited":[],"entered":[],"commands":[],"cancelledScopes":[],"timersScheduled":[],"timersCancelled":[],"effects":[]}},{"input":{"type":"timerFired","now":"PT11.500S","timer":2},"committed":{"snapshot":{"revision":5,"active":["Idle","Root"],"history":{},"context":{"query":"dogs","timeouts":1},"activations":{"Idle":5,"Root":1},"commands":{},"timers":{},"counters":{"activations":5,"commands":2,"timers":2,"effects":0}},"transitions":[3],"exited":[{"node":"Loading","id":4}],"entered":[{"node":"Idle","id":5}],"commands":[],"cancelledScopes":[4],"timersScheduled":[],"timersCancelled":[2],"effects":[]}},{"input":{"type":"dispatch","now":"PT12S","action":{"type":"Load","query":"birds"}},"committed":{"snapshot":{"revision":6,"active":["Loading","Root"],"history":{},"context":{"query":"birds","timeouts":1},"activations":{"Loading":6,"Root":1},"commands":{"3":{"scope":6,"lane":"load"}},"timers":{"3":{"transition":3,"activation":6,"deadline":"PT22S"}},"counters":{"activations":6,"commands":3,"timers":3,"effects":0}},"transitions":[0],"exited":[{"node":"Idle","id":5}],"entered":[{"node":"Loading","id":6}],"commands":[{"id":3,"command":{"query":"birds"},"scope":6,"lane":"load","policy":{"kind":"latest"}}],"cancelledScopes":[5],"timersScheduled":[{"id":3,"transition":3,"activation":6,"deadline":"PT22S"}],"timersCancelled":[],"effects":[]}},{"input":{"type":"commandFailed","now":"PT13S","command":3,"failure":{"type":"IOException","cause":{"type":"SocketException"}}},"committed":{"snapshot":{"revision":7,"active":["Loading","Root"],"history":{},"context":{"query":"birds","timeouts":1},"activations":{"Loading":6,"Root":1},"commands":{},"timers":{"3":{"transition":3,"activation":6,"deadline":"PT22S"}},"counters":{"activations":6,"commands":3,"timers":3,"effects":0}},"transitions":[],"exited":[],"entered":[],"commands":[],"cancelledScopes":[],"timersScheduled":[],"timersCancelled":[],"effects":[]}},{"input":{"type":"commandResult","now":"PT14S","action":{"type":"Loaded","items":["tweety"]},"command":3},"ignored":"StaleCommand"},{"input":{"type":"dispatch","now":"PT15S","action":{"type":"Loaded","items":[]}},"committed":{"snapshot":{"revision":8,"active":["Content","Root"],"history":{},"context":{"query":"birds","timeouts":1},"activations":{"Content":7,"Root":1},"commands":{},"timers":{},"counters":{"activations":7,"commands":3,"timers":3,"effects":1}},"transitions":[1],"exited":[{"node":"Loading","id":6}],"entered":[{"node":"Content","id":7}],"commands":[],"cancelledScopes":[6],"timersScheduled":[],"timersCancelled":[3],"effects":[{"id":1,"event":{"type":"Shown"}}]}}]}"""
    }
}

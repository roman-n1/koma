# actron-timetravel

Use this module to **reproduce a recorded Machine decision**, find the first difference and
try another input from its historical checkpoint. A replay is an offline computation, not a
live Store rewound in place. Ordinary Store logging alone cannot supply a replay recording.

New here? Run the [disk-to-inspector example](../examples/time-travel/README.md) before
integrating the APIs below. It closes its live Stores, loads real files, compares reducers,
and experiments without re-running the command handler. See [installation](../README.md#installation)
for dependency setup and current release availability.

Replay for [Actron](../README.md)'s replay-ready machines: record a run of a `MachineStore`, decide
it again step by step with the pure machine, move back and forward over it, and branch off any
position with new inputs on a virtual clock. Stage 5 of the
[Time Travel handoff](../doc/internal/design/2026-09-29-time-travel-logging-handoff.md): one
Store, in memory.

- **Recording.** `MachineRecorder` is a `DecisionObserver` of a `MachineStore`; it keeps every
  machine input in processing order with the decision it produced (or the reason it was ignored,
  or the failure). `recording()` gives an immutable `Recording`: the checkpoint it starts from
  and the steps. Every position is a checkpoint: `snapshotAt` is the machine's snapshot there,
  `checkpointAt` the executor's state (commands running, queued in their lanes, or ending with
  their last input on its way), carried forward with the same `Lanes` bookkeeping the live
  executor uses. `recording.since(store.checkpoint())` is the run from a live checkpoint on:
  what a journal keeps when its ring has dropped the beginning.
- **Replay.** `ReplaySession(machine, recording)` is a cursor: `seek`, `stepBackward` (recorded
  snapshots, no deciding), `stepForward` and `verify` (the machine decides the recorded input
  again and every field of the decision is compared). The first difference is a `ReplayMismatch`
  that names the step, the input and each field that differs. A timer recorded as firing before
  its deadline diverges too.
- **Compatibility.** A recording is `Replayable` by the same definition and version,
  `InspectableOnly` by another version of the same definition (snapshots can be shown, not
  decided), `Unsupported` by another definition. Versions are declared, never guessed.
- **Branch.** `session.branch()` continues from the current position, and
  `Branch(machine, store.checkpoint())` from where the live executor is: `dispatch` new actions,
  `advance` the virtual clock to fire timers at their deadlines, `answer`/`complete`/`fail`
  the commands that are `awaiting`, or `reuseRecordedAnswers` for a command equal to a recorded
  one (same value, same node, same lane). Commands `queued` in a lane start when the lane lets
  them, under every policy, exactly as the executor would; they cannot be answered before. No
  handler ever runs; a command with no answer stays awaiting rather than reaching any network.
  The live store is untouched, and `branch.checkpoint` is where the branch is now.

- **Codec.** `RecordingCodec` writes a recording as canonical JSON (`RECORDING_FORMAT_VERSION`,
  fields in a fixed order, maps sorted, commands sorted by id, durations in ISO-8601) with the
  application's kotlinx-serialization serializers for its context, actions, commands and events,
  and reads it back. A newer format or an older one without a `FormatMigration` is
  `Unsupported`, never guessed; a payload the serializers reject or an inconsistent checkpoint
  is `Invalid` at its position. Format 5 begins at the start checkpoint with the executor's
  lanes and the mailbox's pending effects and carries bridge and external-source inputs; the
  codec migrates every older format itself
  must not carry class names.

- **Inspector.** `Inspector` (package `inspect`) is the read model of stage 4: the Stores of a
  group with their capabilities, the timeline of what happened in the group's order, each
  position with its input and cause, commits, decision, commands, timers, events and failures,
  the snapshots before and after with their difference when a `MachineRecorder` recording is
  attached and matches, and an explicit `Completeness` that names what the journal does not
  hold. `InspectorText` renders it as lines;
  [actron-timetravel-compose](../actron-timetravel-compose/README.md) is the Compose screen over it.

- **Groups.** `GroupRecorder` records a `MachineGroup` (see the statechart README): each member's
  recording and the order the decisions were made in across the group. `GroupReplaySession`
  steps the members in that order, `verify` checks every member and the bridge (a message
  received before it was sent, sent by nobody, delivered twice, where no route ever led, or over
  the reply route of a pair whose sender had received no request from the receiver is a
  `GroupMismatch`, as is an input from a source the group had not attached; the recording keeps
  every route the bridge had, removed ones included, with its request/reply pair), `since(cut)`
  begins at a consistent cut with its messages in flight and its sources' snapshots, and
  `GroupBranch` continues every member with a local bridge that delivers routed effects as
  they are decided and `feed(store, source, action)` for the scripted data of a source.

- **Files.** `RecordingFileSink` records a run to segments of a `SegmentStorage` as it happens,
  each segment beginning with the executor's checkpoint, rotated by size and bounded, so a
  ring that dropped the oldest ones still replays from what remains; `RecordingFiles.read`
  returns the last continuous range and marks every hole, which a replay never crosses. A
  step dropped at the very end of a run is absent, and only the sink's `stats` count it.
  `GroupRecordingFileSink` and `GroupRecordingFiles` do the same for a group: the members'
  files and an order file with the messages in flight at every segment's start. A cut of the
  group begins a segment in every file, the order segment's header carrying the sources'
  snapshots, so the run since a cut (`since(cut)`) knows them like the in-memory one.
  `RecordingFiles.prune` bounds what all the files take together. The formats are frozen:
  `RECORDING_FORMAT_VERSION`, `RECORDING_FILE_FORMAT_VERSION` and the order file's
  `GroupRecordingFileFormat.VERSION` bump only with a reader of the previous version, its test
  and a golden, per the [format freeze policy](../doc/internal/adr/2026-10-01-format-freeze-policy.md).

Status: the **recording formats are stable in the fork** since 5.0-3 (2026-10-01): `Recording`,
`RecordingCodec`, `GroupRecording`, `GroupRecorder`, the recording and order files and their sinks
are not `@ExperimentalActronApi` any more, `apiCheck` holds their API and the formats are frozen (above).
Replay, branches and the inspector stay **experimental**, `@ExperimentalActronApi`: debug tooling whose
model still grows. The module lives in the fork [roman-n1/koma](https://github.com/roman-n1/koma).
The [Compose inspector](../actron-timetravel-compose/README.md) supports group-wide positions:
one cursor restores every member, and forward replay stops on decision or bridge/source
causality mismatches. Seeking restores recorded checkpoints; verification remains explicit.

## Dependency

```kotlin
implementation("io.github.roman-n1:actron-timetravel:5.0.0-alpha.1")
```

The module brings `actron-statechart` and `actron-observability` with it; see the
[statechart README](../actron-statechart/README.md) for the composite build setup.

## Quick start

```kotlin
val recorder = MachineRecorder(listMachine, ListContext())
val store = MachineStore(listMachine, ListContext(), handler, scope = appScope, observers = listOf(recorder))
// ... the app runs ...
val recording = recorder.recording()

val session = ReplaySession(listMachine, recording)
session.verify()                         // null, or the first ReplayMismatch
session.seek(12)                         // the snapshot after twelve steps
session.stepForward()                    // Matched(decision) or Diverged(mismatch)

val branch = session.branch()            // what if, from here?
branch.dispatch(ListAction.Load("dogs"))
branch.awaiting                          // [Fetch("dogs")]: nothing runs, the caller answers
branch.queued                            // what waits in a lane; starts when the lane lets it
branch.answer(branch.awaiting.single().id, ListAction.Loaded(listOf("rex")))
branch.advance(10.seconds)               // fires the timers that come due
branch.effects                           // the events the branch emitted; shown, never delivered

val checkpoint = store.checkpoint()      // the live executor's state, as data
Branch(listMachine, checkpoint)          // what if, from where the app is right now
recording.since(checkpoint)              // the run from the checkpoint on: replays and branches from it
```

## Inspecting a run

Generated or shrunk model-test inputs can become a recording without running a live Store:
`machine.recordInputs(initialSnapshot, scenario.inputs)` decides each input once and retains
ignored and failed steps for `ReplaySession`. For a started workflow, supply an authentic
`ExecutorCheckpoint` with unfinished command payloads instead of a snapshot. Input time must
not move backwards. This experimental debug helper executes no commands or events; the
application owns serialization and redaction of the supplied data.

```kotlin
val inspector = Inspector.of(session, recordings = mapOf(StoreInstanceId("list-7") to recorder.recording()))
// or, after a process death: Inspector.of(JournalFiles(storage), sessionId, recordings)

inspector.stores                          // StoreView: capability, counts, recording status, completeness
inspector.completeness.isComplete         // false when anything is missing; the reasons say what
inspector.timeline                        // TimelineItem: Processing, Discarded, Pending, Rejected, Closed, Gap, Stopped, Damage, ...
val position = inspector.timeline.filterIsInstance<TimelineItem.Processing>()[12]
position.before; position.after; position.diff   // from the recording, when attached and matching
position.commits; position.decision              // what the journal kept
inspector.replayability(StoreInstanceId("list-7")) // Available, or Unavailable with the reasons

InspectorText.overview(inspector)         // session, completeness, one line per Store with its reasons
InspectorText.timeline(inspector)         // one line per position
InspectorText.detail(position)            // input and cause, outcome, active nodes, before/after, changed, commands, timers, events
```

What one position looks like, with a recording attached:

```
#14 +1.204s list-7 Processing #4 Dispatch Load(cats) -> Handled commits=1 revision=2 in 812us transitions=[T0] commands=[c1 load/Latest Fetch(query=cats)] timers=[t1+10s] active=[Loading,Root] [recorded]
  input: Dispatch Load(cats)
  outcome: Handled commits=1
  active: Loading, Root
  before: revision=1 active=[Idle,Root] context=Ctx(query=, items=[], timeouts=0)
  after:  revision=2 active=[Loading,Root] context=Ctx(query=cats, items=[], timeouts=0) commands=[c1] timers=[t1]
  changed: context; entered Loading; exited Idle; commands +c1; timers +t1
  commands: c1 Fetch(query=cats) in load (Latest) for a3
  timers: t1 T3 at +10s
```

Without a recording the same position shows the commits as the policy kept them (`-` under
the production policy) and the Store's completeness says `payloads omitted by the policy`.

## Replaying a group

```kotlin
val group = MachineGroup(session)                       // the live side, see the statechart README
val recorder = GroupRecorder(group)
val picker = MachineStore(pickerMachine, PickerCtx(), handler, scope, observers = listOf(group.member(pickerId), recorder.member(pickerId, pickerMachine, PickerCtx())))
// ... the members run and talk ...
val recording = recorder.recording()

val session = GroupReplaySession(mapOf(pickerId to pickerMachine, rootId to rootMachine), recording)
session.verify()                          // [] or the GroupMismatches, in order of position
session.seek(12); session.snapshotOf(rootId)
val cut = group.checkpoint()              // a consistent cut of the live group, or null on timeout
GroupReplaySession(machines, recording.since(cut!!))

val branch = session.branch(routes)       // every member from here, with a local bridge
branch.dispatch(pickerId, Pick("zed"))    // the pick, the root's apply, the acknowledgement back
```

## Recording to files

```kotlin
val storage = FileSegmentStorage("$filesDir/recordings")
val sink = RecordingFileSink(storeId, listMachine, ListContext(), codec, storage, appScope, RecordingFileConfig(maxSegmentBytes = 512 * 1024, maxSegments = 8))
val store = MachineStore(listMachine, ListContext(), handler, appScope, observers = listOf(sink))
// ... the app runs; on exit:
sink.close()

val contents = RecordingFiles(storage).read(storeId, codec)   // the last continuous range, from a checkpoint
contents.recording?.let { ReplaySession(listMachine, it).verify() }
contents.marks                                              // MissingSegments, StepsMissing, StartMismatch, Damaged

val groupSink = GroupRecordingFileSink(group, MachineGroupId("chat"), storage, appScope)
val picker = MachineStore(pickerMachine, PickerCtx(), handler, appScope, observers = listOf(group.member(pickerId), groupSink.member(pickerId, pickerMachine, PickerCtx(), pickerCodec)))
GroupRecordingFiles(storage).read(MachineGroupId("chat"), codecs).recording?.let { GroupReplaySession(machines, it).verify() }
```

## Serializing a recording

```kotlin
val codec = RecordingCodec(ListContext.serializer(), ListAction.serializer(), ListCommand.serializer(), ListEvent.serializer())
val text = codec.encode(recording)               // canonical JSON, format RECORDING_FORMAT_VERSION

when (val decoded = codec.decode(text)) {
    is DecodedRecording.Decoded -> ReplaySession(listMachine, decoded.recording)
    is DecodedRecording.Unsupported -> log("format ${decoded.formatVersion}: ${decoded.reason}")
    is DecodedRecording.Invalid -> log("${decoded.reason} at ${decoded.at}")
}
```

When the format changes, `RECORDING_FORMAT_VERSION` is bumped and a `FormatMigration(from, to)`
turns the older JSON into the newer one; the codec carries the migrations of its own formats
(1, an `initial` snapshot, to 2, a `start` checkpoint), `migrations` is for formats it does not
know. `RecordingCodecGoldenTest` holds the golden fixtures of the current format and of the
previous one, so a change of the text is a deliberate change of the version.

## Files after interruption

A successful storage flush hands complete frames to the operating system; a new process can
read them after the writer is killed. Unflushed buffered bytes may be absent. A frame cut short
is discarded and reported as `TruncatedTail`; an intact segment without END is `Unfinished`.
Rotation is not atomic: the preceding segment remains readable if the next header is interrupted.
Recording and group readers return the continuous range their files still cover, for replay.
The group reader trims member steps beyond the surviving order. If rotation removed the
checkpoint needed for a member with no entries in that range, it reports `StartMismatch` and
returns no group recording, instead of substituting a newer state.

This is a **process interruption contract**, not a power-loss guarantee. JVM/Android use
`BufferedOutputStream.flush()` and iOS uses `fflush()`; neither requests `fsync`. A failure from
write, flush or close stops the affected sink and releases its handle. A disk-full failure must
be handled through the journal session's failure reporter or recording's `onFailure`; it does
not imply that queued decisions reached disk. Recording failures leave the segment without END.

`RecordingDurabilityJvmTest` kills child JVM writers at buffered/flushed frame boundaries,
inside a frame, and during rotation, then checks the files and replay in a fresh JVM. The
`Recording filesystem durability` CI job also fills an isolated 8 MiB tmpfs and verifies actual
ENOSPC for journal, member and group writers. It never fills a developer's filesystem. These
tests validate JVM process death and disk full; iOS interruption and power loss remain separate
platform validation work.

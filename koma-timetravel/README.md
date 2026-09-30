# koma-timetravel

Replay for [Koma](../README.md)'s replay-ready machines: record a run of a `MachineStore`, decide
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
  is `Invalid` at its position. Format 2 begins at the start checkpoint; the codec migrates
  format 1 itself. Give the application's sealed types explicit `@SerialName`s: the wire must
  not carry class names.

Status: **experimental**, `@ExperimentalKomaApi`, in the fork [roman-n1/koma](https://github.com/roman-n1/koma).
Not yet: a file format with segments and checksums, and group replay of several stores.

## Dependency

```kotlin
implementation("io.github.roman-n1:koma-timetravel:4.0.0-sc.1")
```

The module brings `koma-statechart` and `koma-observability` with it; see the
[statechart README](../koma-statechart/README.md) for the composite build setup.

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

# koma-timetravel

Replay for [Koma](../README.md)'s replay-ready machines: record a run of a `MachineStore`, decide
it again step by step with the pure machine, move back and forward over it, and branch off any
position with new inputs on a virtual clock. Stage 5 of the
[Time Travel handoff](../doc/internal/design/2026-09-29-time-travel-logging-handoff.md): one
Store, in memory.

- **Recording.** `MachineRecorder` is a `DecisionObserver` of a `MachineStore`; it keeps every
  machine input in processing order with the decision it produced (or the reason it was ignored,
  or the failure). `recording()` gives an immutable `Recording`: the initial snapshot and the
  steps; every position is a checkpoint.
- **Replay.** `ReplaySession(machine, recording)` is a cursor: `seek`, `stepBackward` (recorded
  snapshots, no deciding), `stepForward` and `verify` (the machine decides the recorded input
  again and every field of the decision is compared). The first difference is a `ReplayMismatch`
  that names the step, the input and each field that differs. A timer recorded as firing before
  its deadline diverges too.
- **Compatibility.** A recording is `Replayable` by the same definition and version,
  `InspectableOnly` by another version of the same definition (snapshots can be shown, not
  decided), `Unsupported` by another definition. Versions are declared, never guessed.
- **Branch.** `session.branch()` continues from the current position: `dispatch` new actions,
  `advance` the virtual clock to fire timers at their deadlines, `answer`/`complete`/`fail`
  the commands that are `awaiting`, or `reuseRecordedAnswers` for a command equal to a recorded
  one (same value, same node, same lane). No handler ever runs; a command with no answer stays
  awaiting rather than reaching any network. The live store is untouched.

Status: **experimental**, `@ExperimentalKomaApi`, in the fork [roman-n1/koma](https://github.com/roman-n1/koma).
Not yet: serialized recordings with codecs and versions, checkpoints of the executor's own state
(running commands, lane queues), and group replay of several stores.

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
branch.answer(branch.awaiting.single().id, ListAction.Loaded(listOf("rex")))
branch.advance(10.seconds)               // fires the timers that come due
branch.effects                           // the events the branch emitted; shown, never delivered
```

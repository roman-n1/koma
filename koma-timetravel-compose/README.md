# koma-timetravel-compose

A Compose Multiplatform screen over the [koma-timetravel](../koma-timetravel/README.md)
inspector: the debug UI of the
[Time Travel handoff](../doc/internal/design/2026-09-29-time-travel-logging-handoff.md) §11, for
debug builds only. It shows what `Inspector` computed and moves a `ReplaySession`; it runs no
Store and reaches no network.

- **`InspectorScreen(state)`**: the mode and the group's completeness on top (Live, Inspect,
  Replay and Branch in their own colours), the Stores with capability, counts, recording status,
  completeness reasons and why a replay is unavailable on the left, the timeline in the middle
  (a click narrows it to a Store, a click selects a position), the selected position on the
  right: input and cause, outcome, active nodes, the snapshots before and after with what
  changed when a recording is attached, commands, timers, events, failures. Stacked on a
  narrow screen.
- **`InspectorState`**: the inspector, the selection, the Store filter and the mode, as Compose
  state; `rememberInspectorState(inspector)` for a history, `rememberLiveInspector(session)`
  for a running session, rebuilt every half second.
- **`ReplayControls(store, session)`** and the replay bar: back, forward, seek, verify; a
  disabled button says why (the end of the recording, a divergence). The position panel then
  shows the machine's definition as Mermaid with the replay's active states highlighted
  (`toMermaid(active)`), selectable for pasting into a renderer.
- **`BranchControls(branch, machines, inputs)`** and the branch panel: every member's
  snapshot, clock, awaiting and queued commands with buttons to complete, fail or answer
  them, the scripted `BranchInput`s the application allows (a `Dispatch`, a `Feed`, an
  `Answer` with a label), the clock's advance, the definition with the active states, and
  every decision made with what it changed. The branch decides with the pure machines;
  nothing runs.

Status: **experimental**, `@ExperimentalKomaApi`, in the fork [roman-n1/koma](https://github.com/roman-n1/koma).
Not yet: group-wide positions.

## Dependency

```kotlin
// debug source set only: this module must not be in a release dependency graph
implementation("io.github.roman-n1:koma-timetravel-compose:5.0.0-alpha.1")
```

The library's CI runs `checkDebugGraph`: no production module of koma depends on
`koma-timetravel` or this module, so what an app keeps in a debug source set stays there.

## Quick start

```kotlin
@Composable
fun DebugInspector(session: RecordingSession, recordings: Map<StoreInstanceId, Recording<*, *, *, *>>) {
    val state = rememberLiveInspector(session, recordings)        // mode Live, refreshed as the journal grows
    InspectorScreen(state)
}

@Composable
fun ReplayOf(recording: Recording<Ctx, Act, Cmd, Ev>, journal: JournalFileContents) {
    val state = rememberInspectorState(Inspector(journal.events, mapOf(storeId to recording)), InspectorMode.Replay)
    val controls = remember { ReplayControls(storeId, ReplaySession(machine, recording)) }
    InspectorScreen(state, replay = controls)
}

@Composable
fun BranchOf(session: GroupReplaySession, machines: Map<StoreInstanceId, Machine<*, *, *, *>>) {
    val branch = remember { BranchControls(session.branch(routes), machines, listOf(BranchInput.Dispatch(storeId, "Load", Act.Load))) }
    InspectorScreen(state, branch = branch)                       // mode Branch: the panel decides what the buttons send
}
```


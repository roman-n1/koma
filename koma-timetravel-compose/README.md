# koma-timetravel-compose

The debug screen for recorded Machines: select a position, move every group member together,
compare decisions, or experiment in a branch. Choose `GroupTimeTravelControls` for the whole
replay → experiment → return workflow, `GroupReplayControls` for replay only, or
`ReplayControls` for one Store. Add this to a debug dependency graph; setup follows the
[installation guide](../README.md#installation).

For a complete runnable recording → disk → replay → experiment workflow, see the
[Time Travel desktop example](../examples/time-travel/README.md).

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
- **`GroupReplayControls(session)`**: one cursor for all recorded machines, with back,
  forward, seek and group verification. The timeline follows `GroupRecording.order`;
  filtering by Store only changes the displayed rows. The detail panel shows the selected
  input's recorded before/after snapshots and every member's current snapshot, executor
  checkpoint and active states. A divergence identifies the failing input and member and
  stops forward movement before changing their state.
- **`BranchControls(branch, machines, inputs)`** and the branch panel: every member's
  snapshot, clock, awaiting and queued commands with buttons to complete, fail or answer
  them, the scripted `BranchInput`s the application allows (a `Dispatch`, a `Feed`, an
  `Answer` with a label), the clock's advance, the definition with the active states, and
  every decision made with what it changed. The branch decides with the pure machines;
  nothing runs.
- **`GroupTimeTravelControls(replay, routes, inputs)`**: the complete replay/experiment
  workflow. Branch Here creates an isolated branch from every member's checkpoint at the
  current global position. The screen switches to Branch mode, shows the origin and pauses
  replay navigation. Return to Replay discards the experiment and restores the replay UI at
  the original cursor, including any divergence and verification result.

Status: **experimental**, `@ExperimentalKomaApi`, in the fork [roman-n1/koma](https://github.com/roman-n1/koma).

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
fun ReplayGroup(recording: GroupRecording, machines: Map<StoreInstanceId, Machine<*, *, *, *>>, journal: JournalFileContents) {
    val state = rememberInspectorState(Inspector(journal.events, recording.members), InspectorMode.Replay)
    val controls = remember(recording, machines) { GroupReplayControls(GroupReplaySession(machines, recording)) }
    InspectorScreen(state, groupReplay = controls)
}

@Composable
fun ExperimentWithGroup(recording: GroupRecording, machines: Map<StoreInstanceId, Machine<*, *, *, *>>, journal: JournalFileContents) {
    val state = rememberInspectorState(Inspector(journal.events, recording.members))
    val controls = remember(recording, machines) {
        GroupTimeTravelControls(
            GroupReplayControls(GroupReplaySession(machines, recording)),
            routes = routes, // application-supplied GroupBranch.Route mappings; empty means no local delivery
            inputs = listOf(BranchInput.Dispatch(storeId, "Load", Act.Load)),
        )
    }
    InspectorScreen(state, timeTravel = controls) // mode comes from the workflow
}

@Composable
fun BranchOf(session: GroupReplaySession, machines: Map<StoreInstanceId, Machine<*, *, *, *>>) {
    val branch = remember { BranchControls(session.branch(routes), machines, listOf(BranchInput.Dispatch(storeId, "Load", Act.Load))) }
    InspectorScreen(state, branch = branch)                       // mode Branch: the panel decides what the buttons send
}
```

Group replay requires a recording and a matching machine definition/version for each member.
Its cursor counts inputs in the group recording, independently of the diagnostic journal:
partial journals and Store filters do not change those positions. Seeking and going back
restore recorded checkpoints without verifying skipped inputs. Forward re-decides the next
input and checks bridge/source causality; Verify checks the whole group without moving the
cursor. Timers, command queues and pending effects are displayed as recorded state; replay
does not start command handlers, timers or network requests.

Experiments begin from recorded executor checkpoints, so awaiting commands can be answered
and recorded timers can fire when the virtual clock advances. Application-supplied routes
map branch effects to local member actions; recorded bridge metadata cannot reconstruct
those functions. External sources use scripted `BranchInput.Feed` data. While the workflow
screen owns the cursor, do not move its replay session externally. Returning leaves all
recorded checkpoints untouched; starting another experiment creates a fresh branch.

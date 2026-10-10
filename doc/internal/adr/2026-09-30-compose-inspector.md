# The debug UI is a Compose screen over the inspector's read model, in its own module

- Updated: 2026-09-30

## Background

Handoff §11 describes the debug UI: v1 shows the tree of group and Store instances, a selected
position with the snapshot before and after and their difference, the active nodes, the input
and its cause, commands, timers, failures and an indicator of completeness; v2 adds seek, back
and forward, virtual clocks, branches, a preview and the return to live, with the modes Live,
Inspect, Replay and Branch visually distinct and every disabled button saying why. §3 places it
in `actron-timetravel-compose`, late and optional, attached only to a debug application; §12 asks
CI to check that no debug UI is in a release dependency graph. The
[inspector](./2026-09-30-inspector-read-model.md) already computes everything v1 shows.

## Decision

- **A new module, `actron-timetravel-compose`,** on `actron-timetravel` and Compose Multiplatform
  (runtime, foundation, material3), with the targets of `actron-compose` (Android, iOS, JVM, JS
  and Wasm browsers). It is the only module with a UI dependency on the time-travel side, so
  keeping it out of a release build is keeping one artifact out.
- **The screen shows the read model, and only it.** `InspectorScreen` renders `Inspector`:
  the mode bar with the group's completeness, `StoresPanel` (capability, counts, recording
  status, every completeness reason in a sentence, and `replayability` as "replay unavailable:
  why"), `TimelinePanel` (one line per position, `InspectorText.line`, coloured by kind:
  discards and refusals amber, gaps, damage and stops red, cuts blue), `PositionPanel`
  (`InspectorText.detail`, and a note when the snapshots are the journal's rather than a
  recording's). Wide screens get three columns, narrow ones a stack. No Store, machine or
  session is touched by the screen.
- **`InspectorState`** is the screen's state: the inspector, the selected index, the Store filter
  and the mode. `rememberLiveInspector(session)` rebuilds it from the session's retained
  records every half second, in `Live`; `rememberInspectorState(inspector)` shows a history.
- **v2 begins with the replay bar.** `ReplayControls` wraps a `ReplaySession` for one Store:
  position, back, forward, seek, verify, the last divergence; the bar disables forward at the
  end or on a divergence and prints why, as §11 asks. The session decides and compares; nothing
  executes.
- **Modes are colours,** green, blue, purple, orange, in the bar's background, so a replayed or
  branched history is never mistaken for the live one.

Not adopted:

- Putting the screen into `actron-compose`: that module is the `ViewStore` of every app; the
  inspector would drag Compose foundation and the time-travel modules into release graphs.
- A branch panel in this round: dispatching into a `GroupBranch` and answering its commands
  needs typed actions from the application; the screen would need an adapter per app. The mode
  and the model are ready for it.
- Mermaid of the definition: §11 allows it; it needs `StateChartDefinition.toMermaid()` and a
  renderer, neither of which belongs to this round.

## Notes

- Tests: `InspectorStateTest` (common: selection, the filter, a refresh that keeps a selection
  where it still exists) and `InspectorScreenTest` (JVM, `runComposeUiTest` on the desktop
  runtime: the Stores, the completeness and its reasons, a gap, a selected position's detail;
  the replay bar stepping, seeking back, verifying, and saying why forward is disabled at the
  end). The browser targets compile the screen and run the common test only; the UI test runs
  on the JVM, where Skiko needs no browser.
- Left for later: group-wide positions.

## Addendum 2026-09-30: the branch panel, the definition as Mermaid, and the graph check

- **The branch panel takes scripted inputs from the application.** `BranchControls(branch,
  machines, inputs)` wraps a `GroupBranch`: the members' snapshots, clocks, awaiting and
  queued commands, and every decision made through it with what it changed
  (`BranchDecision`, `SnapshotDiff`); `BranchInput` is what the application lets a viewer send,
  a `Dispatch` or a `Feed` with a label for the button, or an `Answer` for whichever awaiting
  command the viewer picks. Completing and failing a command and advancing the clock need no
  script. A request the branch refuses (a command not awaiting) is a `problem` shown in the
  panel, not a decision. `BranchPanel` takes the third column, or a fourth section on a narrow
  screen, when the screen is given a branch. The branch decides with the pure machines;
  nothing runs, and the live group is untouched.
- **The definition is Mermaid text, not a drawing.** `StateChartDefinition.toMermaid(active)`
  closes the diagram with a `classDef actron_active` and a `class` statement naming the active
  states (by alias when they have one). `ReplayControls.mermaid()` draws the replay's position,
  `BranchControls.mermaid(store)` a member's; `DefinitionPanel` shows the text selectable, for
  pasting into a renderer. §11 allows Mermaid and forbids blocking the live runtime with
  diagram generation: text from the model only, and no renderer in the module.
- **CI checks the direction of the graph.** `checkDebugGraph` in the root build fails when a
  production module declares a dependency on `actron-timetravel` or `actron-timetravel-compose`
  in any configuration; it runs in the `apiCheck` job and under `check`. An app keeps the two
  modules in a debug source set; the library side of §12's promise is that nothing production
  pulls them in.

Not adopted: rendering the diagram in Compose (a renderer is a dependency and a runtime cost
the debug module need not carry; a viewer pastes the text); building actions from text in the
panel (the application's action types are its own, so the application scripts them).

Tests: `BranchControlsTest` (common: a scripted dispatch decides and registers a command, the
answer and the completion move the machine on, a refused completion is a problem and not a
decision, the clock fires the timer, the Mermaid highlights the active states);
`InspectorScreenTest` (JVM: the replay's position panel carries the definition with the active
states; the branch panel decides a scripted input, lists the decision, advances the clock);
`StateChartDefinitionTest` (the highlighted diagram is the plain one plus the two statements,
unknown ids ignored, aliased ids named by their alias).

## Related

- [The inspector is a read model](./2026-09-30-inspector-read-model.md)
- [A run is replayed by deciding its recorded inputs again](./2026-09-30-single-store-replay.md)
- [Time Travel and structured logging handoff](../design/2026-09-29-time-travel-logging-handoff.md), §3, §11, §12

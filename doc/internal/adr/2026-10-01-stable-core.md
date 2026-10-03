# The core of the journal and of the machine is stable API: the experimental marker comes off, module by module

- Updated: 2026-10-01

## Background

`5.0.0` without a suffix requires (roadmap, "Version 5.0") that `@ExperimentalKomaApi` is off
the core of the machine and the journal, held by `apiCheck`, and that the formats are frozen
([format freeze policy](./2026-10-01-format-freeze-policy.md)). The marker was on every
declaration of `koma-statechart`, `koma-observability` and `koma-timetravel` since they were
written: it said the API moved with every stage. The stages are over (tracks A to C); what the
messenger will build on must not require an opt-in that says "this may change".

## Decision

The marker comes off in three steps, one PR each, in dependency order, so each is reviewable
and `apiCheck` holds each module's surface from then on:

- **5.0-3a, this ADR:** `InputId` in `koma-core` (the identity every journal record carries; a
  stable journal cannot expose an experimental id) and every declaration of `koma-observability`:
  the record model (`JournalRecord`, `JournalEntry` and its vocabulary), `RecordingSession` and
  `JournalConfig`, the payload policy, `JournalFormat`, the probe, the file format, `JournalFiles`,
  `JournalFileSink` and `SegmentStorage` with its implementations.
- **5.0-3b:** `koma-statechart`, the chart model and the machine package: `Machine`,
  `MachineStore`, `MachineSnapshot` and the inputs, `Decision`, `Lanes`, `ExecutorCheckpoint`,
  `MachineGroup` and its routes, the mailbox, the observers, the identities and the sources'
  data types (`SourceId`, `SourceSnapshot`). `ExternalSource` (the contract an adapter
  implements), `MachineStore.feed` and `MachineGroup.source` stay experimental: the sources'
  place in the cut is the least exercised part (no adapter runs in the messenger yet).
- **5.0-3c:** the recording formats of `koma-timetravel`: `Recording`, `RecordedStep`,
  `RecordingCodec` with `FormatMigration` and `DecodedRecording`, `GroupRecording` and
  `GroupRecorder`, `RecordingFileFormat`, `RecordingFileSink`, `RecordingFiles`,
  `GroupRecordingFileFormat`, `GroupRecordingFileSink`, `GroupRecordingFiles`. Replay,
  branches, the inspector and `koma-timetravel-compose` stay experimental: they are debug
  tooling, and the inspector's model is still growing (group-wide positions).

What stays experimental keeps the marker on the declaration, not on the file, so the boundary
is readable in the source. A marker on a function is enough where a type must be stable but
one operation on it is not (`MachineStore.feed`).

Not adopted:

- Removing the marker everywhere at once: the tooling modules change with every inspector
  feature, and a stable API there would make `apiDump` churn part of every such PR.
- Keeping `InputId` experimental and opting in inside the journal: every consumer naming an
  `InputId` (every journal reader) would still need the opt-in, which is the thing 5.0 removes.
- A `@RequiresOptIn` of the fork's own (`ForkApi`): the distinction the messenger needs is
  stable versus moving, which the existing marker already draws.

## Notes

- Verification of each step: the dumps under `*/api/` (unchanged by the marker itself: BCV
  dumps do not carry it, so the surface was already held; what changes is that consumers no
  longer opt in), all JVM tests with `apiCheck` and `checkDebugGraph`, the module on Android
  host, JS Node and iOS Simulator.
- After 5.0-3c, criterion (1) is met and `5.0.0` waits only for Roman's tag (5.0-4).

## Related

- [Format freeze policy](./2026-10-01-format-freeze-policy.md)
- [Statechart roadmap](../design/2026-09-28-statechart-roadmap.md), "Version 5.0"

# An external source is paused before the cut and snapshotted after the members settle, and its inputs are identified as its own

- Updated: 2026-09-30

## Background

The [handoff](../design/2026-09-29-time-travel-logging-handoff.md) puts into a group's
checkpoint "the snapshots of the connected external sources" (§8) and names the pagination
engine, the socket reader and the repository subscription as such sources (§10): adapters over
existing engines record the generation, the visible items and their order, the load states,
placeholders, the window and the pending focus, never the engine's caches; an unrecorded range
needs scripted data and is marked. In a replay the network and the repository are unreachable
(§9.1), a missing result is `AwaitingExternalResult`, and a disabled button says "a missed
external source" (§11). Stage 6's acceptance lists "adapters of external sources" next to the
cut and the bridge. Until now an input from a source was a plain dispatch: a recording could
not tell which source it needed, and a cut said nothing about where the source stood.

## Decision

- **A source's inputs are `MachineInput.External`.** `MachineStore.feed(source, action)` admits
  an input of the source [id] under the admission policy like a dispatch (a source refused
  decides itself whether to retry or drop); the machine decides it like a dispatch. So a
  recording knows every input a source fed, `RecordingCodec` format 5 writes it, and the
  journal gets `ExternalReceived` from the decision journal.
- **A source is paused before the cut and snapshotted after the members settle.**
  `ExternalSource` is `id`, `kind`, `pause()`, `snapshot()`, `resume()`. `MachineGroup.source`
  attaches one; `checkpoint` pauses every source first (once `pause` returns, no input is being
  fed and none will be), then freezes the members, waits until each has finished what it had
  accepted, takes the executors' checkpoints, the messages in flight and every source's
  `snapshot()`, and only then thaws the members and resumes the sources. The order is what
  makes the snapshot consistent: everything the source fed before pausing entered a member
  before the freeze and was decided before the snapshot, so the source's state is the members'
  view of it, neither ahead nor behind. A source that does not pause within the timeout aborts
  the cut like a member that does not settle; whatever was paused is resumed. A successful cut
  is journaled as `CheckpointCreated` with its members, sources and messages in flight, a
  record of the session itself.
- **`SourceSnapshot` is the source's own vocabulary, already safe.** `kind`, `version` and
  `fields` (strings): a pagination adapter writes its generation, window and load state; the
  library does not know what a page is. The snapshot travels with `GroupCheckpoint.sources`
  into `GroupRecording.since(cut).sourceSnapshots` and into a `GroupBranch` as `sources`, for
  the caller to script the next data from with `feed(store, source, action)`; a `Branch` gets
  `feed(source, action)` for the same. Nothing in a replay or a branch reaches a source.
- **A source the group did not attach is a missed source.** `GroupRecording.sourceIds` is what
  the group had attached (the recorder reads them, a cut adds its own); `GroupReplaySession.verify`
  reports an `External` input from any other source as `UnknownSource`: the run depended on
  something that was not instrumented, and its record is partial (§6).
- The inspector shows a processing's source (`from=`) and a cut as a `Checkpoint` item.

Not adopted:

- Snapshotting sources without pausing them: a source mid-feed at the snapshot would be
  counted by itself and not by the member, or the reverse; the cut would not be a cut.
- A typed source state in the library: the adapters' states are the application's (Paging3's
  generation and window are not Actron's business); strings keep them serializable and safe by
  construction, at the price of no typing.
- Pausing sources by freezing their targets only: the members' gates already hold what a source
  feeds, but the source would keep advancing its own generation; the pause is what stops the
  source's state, not the input.

## Notes

- Tests: [`ExternalSourceTest`](../../../actron-statechart/src/commonTest/kotlin/actron/statechart/machine/ExternalSourceTest.kt)
  (a paged source feeding a member: `External` inputs decided and journaled with their source,
  admission applied to a source, a cut pausing the source before the members and resuming it
  after, the snapshot equal to what the member decided, a source that does not pause aborting
  the cut with the group resumed) and, in `actron-timetravel`,
  [`ExternalSourceCutStormTest`](../../../actron-timetravel/src/commonTest/kotlin/actron/timetravel/ExternalSourceCutStormTest.kt)
  (a source feeding from its own thread while four threads dispatch and thirty cuts are taken:
  at every cut the source's snapshot equals the member's decided pages, and the recording since
  the cut replays without a mismatch, sources included), plus the format golden with an
  `external` input and `UnknownSource` in `GroupReplaySessionTest`.
- Left for later: the messenger's adapters themselves (Paging3, the socket reader) and their
  field vocabularies; a source's own journal of what it fed and dropped; sources in a
  single-store checkpoint (today a source belongs to a group).

## Related

- [Group replay](./2026-09-30-group-replay.md)
- [The effect mailbox](./2026-09-30-effect-mailbox.md)
- [Time Travel and structured logging handoff](../design/2026-09-29-time-travel-logging-handoff.md), §8, §9.1, §10, §11

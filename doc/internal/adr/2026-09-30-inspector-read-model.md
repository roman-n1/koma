# The inspector is a read model over the journal, enriched by recordings, that names what it cannot show

- Updated: 2026-09-30

## Background

Stage 4 of the [handoff](../design/2026-09-29-time-travel-logging-handoff.md) is the inspector:
"history and snapshots with explicit capabilities", accepted when "the user tells a full
recording from a partial one". §11 lists the minimum: the tree of group and Store instances,
a selected position with the snapshot before and after and their difference, the active
nodes, the input and its cause, commands, timers, failures, and an indicator of completeness;
§6 adds that the journal is not complete when payloads, inputs, runtime records or a part of
the group are missing, and that the inspector shows the concrete reason. §13 says not to begin
with a debug UI, and §3 keeps the Compose timeline in a late, optional
`actron-timetravel-compose`.

## Decision

The inspector is `actron.timetravel.inspect`, pure data over data, with a text renderer; the
Compose UI comes later and only reads this model.

- **The journal is the source of order and of what was kept.** `Inspector` takes the journal
  as read from a `RecordingSession` (with its counters) or from `JournalFiles` (with the marks
  of what could not be read) and builds one `timeline` in `GroupSeq` order. A position is one
  processing of one input, from `ProcessingStarted` to `ProcessingFinished`, with the commits,
  events, failures and the machine's decision the journal attributed to it, and the cause of a
  transaction or a recovery. Around the processings: registrations, discards, refusals,
  closes, gaps, the stop, damage marks, and two things the journal does not say in so many
  words but the inspector must: an input accepted with no end (`Pending`) and a record that
  belongs to no processing (`Unattributed`). Nothing is dropped; what does not fit is shown
  where it is.
- **A recording enriches; a wrong one is refused.** A `MachineRecorder` recording attached for a
  Store is aligned with the journal's decisions (the startup and every dispatch of the Store,
  in order, after the revision a checkpoint-trimmed recording begins at) and checked step by
  step: a committed step against the journal's revision, an ignored one against the ignore
  reason, a failed one against the outcome. When it matches, every decision gets the snapshot
  before, the snapshot after and a `SnapshotDiff` computed from the two (context, entered and
  exited nodes, commands and timers registered and ended); when it does not, the Store's
  `RecordingStatus` is `Mismatch` with the step and the reason, nothing is enriched, and the
  completeness says so. Two identical runs match by content: recordings are values.
- **Completeness is a list of reasons, per Store and for the group.** `Incompleteness` names
  each thing missing: payloads omitted or unavailable by the policy (unless a matching
  recording supplies them), records dropped for the sinks (the gaps), evicted from memory or
  published after the stop (from the session's counters), damaged or missing segments (from
  the file marks), a Store whose registration is gone, a session stopped while Stores ran,
  inputs pending, records unattributed, a recording that does not match. `Completeness.isComplete`
  is "no reasons"; `InspectorText` puts every reason into a sentence. This is the stage's
  acceptance criterion made data.
- **Buttons are not disabled silently.** `replayability(store)` is `Available` or
  `Unavailable(reasons)`: replay decides a recording again, so it needs a matching one; the
  journal alone is `InspectOnly`, which can be shown, not replayed, and the reason says so.
- **`InspectorText`** renders the overview (session, completeness, the Stores with capability,
  counts, recording status and their reasons), the timeline (one line per position) and the
  detail of a position (input and cause, outcome, active nodes, before and after from the
  recording or the commits as the policy kept them, the difference, commands, timers, events,
  failures). It prints what the items hold, as `JournalFormat` does.

Not adopted:

- Building the timeline from recordings and decorating it with the journal: a recording knows
  one Store's decisions and nothing of discards, refusals, transactions, group order, gaps or
  damage; the journal knows all of that and is what production has.
- Matching a recording by identity or by a token: values match by content, and a recording
  read from a file has no identity to match.
- A Compose module now: the model is the deliverable of this stage; a UI that reads it is the
  next one, and keeping it out of this module keeps `actron-timetravel` free of Compose.

## Notes

- Tests: [`InspectorTest`](../../../actron-timetravel/src/commonTest/kotlin/actron/timetravel/inspect/InspectorTest.kt)
  inspects a journaled, recorded run of one and two tabs (a load answered, a stale answer, a
  refresh, a refused action under bounded admission, a timeout, a failing guard, a close): the
  Stores with capabilities and counts, every kind of position in order, snapshots and
  differences from the recording, the partial journal without one and the complete one under
  `retainAll`, a recording of another run refused with the step, files with a deleted and a
  damaged segment, and hand-made records with a gap, a pending input, an unattributed record
  and a stop while open.
- Left for later: the Compose timeline (`actron-timetravel-compose`), a Mermaid rendering of the
  definition with the active nodes highlighted, group-wide positions across Stores (stage 6),
  and seek/branch from a position, which `ReplaySession` and `Branch` already give the UI.

## Related

- [Journal identity, ordering and payload policy](./2026-09-29-journal-identity-ordering-and-payload-policy.md)
- [The journal file format](./2026-09-30-journal-file-format.md)
- [A run is replayed by deciding its recorded inputs again](./2026-09-30-single-store-replay.md)
- [Time Travel and structured logging handoff](../design/2026-09-29-time-travel-logging-handoff.md), §6, §11, §13

# A run is replayed by deciding its recorded inputs again; a branch decides new ones on a virtual clock

- Updated: 2026-09-30

## Background

Stage 5 of the [handoff](../design/2026-09-29-time-travel-logging-handoff.md) is the replay of
one Store (§9): restore a checkpoint into a replay runtime without live startup hooks, repeat
the inputs in the recorded processing order with the recorded external results, compare
snapshots, transitions, commands, cancellations and effects with the record, stop at the first
`ReplayMismatch`, keep the network, the repository and the bridge out of the replay container
by construction, and let a branch continue from a historical position with scripted answers
and a virtual scheduler, while the live runtime goes on.

`ReplayDeterminismTest` (tenth round) had already shown that a `MachineStore`'s recorded inputs,
decided again by the pure `Machine`, reproduce every snapshot. Stage 5 turns that test into a
module.

## Decision

`koma-timetravel` is a new module on `koma-statechart` (and, through it, `koma-observability`);
no Compose, no Store, no coroutines in its API.

- **The recording is the run.** `MachineRecorder`, a `DecisionObserver`, keeps each machine
  input in processing order with its `Decision`, ignore reason or failure descriptor
  (`DecisionObserver.onFailed` was added for the last). `Recording` is immutable: the initial
  snapshot (revision 0) and the steps. Every position is a checkpoint because every committed
  decision carries its snapshot; stepping backward and seeking take the recorded snapshot and
  decide nothing. A recorder given the wrong initial context notices at the first `Start`
  decision and says so (`problem`).
- **Replay decides, it does not execute.** `ReplaySession.stepForward` and `verify` call
  `Machine.decide` on the snapshot at the position and compare the whole decision with the
  recorded one: snapshot field by field (revision, configuration, context, activations,
  commands, timers, counters), transitions, exited and entered activations, command
  registrations, cancelled scopes, timers scheduled and cancelled, effects; an ignored step must
  be ignored for the same reason, a failed step must fail with the same failure type. A
  `TimerFired` recorded before its deadline is a divergence too, checked against the snapshot's
  timers. `ReplayMismatch` names the step, the input, the expected step and the actual decision
  with one line per difference. A divergence does not advance the cursor.
- **No handler can run.** The session and the branch hold a `Machine` and a `Recording`, nothing
  else: there is no `CommandHandler`, no clock, no scope to run anything in. The container has
  no way to reach the network, as §9.1 asks, rather than a flag the UI checks.
- **Compatibility is declared.** The same definition and version is `Replayable`; another
  version of the same definition is `InspectableOnly` (snapshots can be shown, the machine must
  not decide them); another definition is `Unsupported`. `ReplaySession` refuses anything but
  `Replayable`.
- **A branch is a pure run on a virtual clock.** `Branch` continues from the recorded snapshot
  and the clock of the last recorded input: `dispatch` decides a new action, `advance` moves the
  clock and fires due timers at their own deadlines in deadline order, registered commands wait
  in `awaiting` until the caller answers, completes or fails them, or `reuseRecordedAnswers`
  applies the recording's answers for a command that is equal in value, registered by the same
  node in the same lane; anything else stays awaiting, never a fallback. Of the lane policies
  only `Latest` is emulated (a new command in the lane abandons the awaiting ones, decided as
  `CommandAbandoned(Superseded)` like the live executor feeds it); the others queue live and are
  answered here in any order. Effects accumulate and are never delivered. The ids of a branch
  continue the counters of its starting snapshot, so they follow the recorded ids without
  colliding, and the live store the recording came from is untouched.

Not adopted:

- Replaying through a `MachineStore` with a stubbed handler and scheduler: the executor's
  ordering (queues, lanes, the actor) would then be part of what is replayed, while the
  recording is already the processing order the store chose; deciding again is enough and has
  nothing to get wrong.
- Checkpoints every N steps with replay between them: recordings hold every snapshot, shared
  by reference, so checkpoints are free. When recordings are serialized, sparse checkpoints and
  a replay between them become the memory trade-off to make.
- Matching recorded answers by command type alone: §9.2 forbids it; the match is the command
  value, the node and the lane.

## Notes

- Tests: [`ReplaySessionTest`](../../../koma-timetravel/src/commonTest/kotlin/koma/timetravel/ReplaySessionTest.kt)
  records a live run (a superseded load whose careless handler answers on cancellation, a
  refresh, a timeout, a failing guard) and replays it: every step matches and no handler runs;
  seek and stepping back land on what a sequential replay reproduces; a machine with a changed
  effect under the same version diverges at the first affected step and the session stays
  there; a timer recorded before its deadline diverges; version and definition compatibility;
  a recorder with the wrong initial context is flagged; a recorded failure matches by type.
  [`BranchTest`](../../../koma-timetravel/src/commonTest/kotlin/koma/timetravel/BranchTest.kt)
  branches from an earlier position with the live store untouched, answers awaiting commands by
  hand or from an equal recorded command and refuses an unequal one, fires timers by advancing
  the clock, fails a command, and emulates `Latest`.
- Still to come for the handoff's stage 5: serialized recordings (codecs, format versions,
  migrations), checkpoints of the executor's state (running commands, lane queues, remaining
  timer time) so a branch can start where a live run was, and the group replay of stage 6.
- The recording keeps live objects; it is a debug tool and grows with the run. The journal's
  `DecisionCommitted` entries remain the production trace.

## Related

- [A replay-ready statechart is a pure decision function](./2026-09-30-replay-ready-decision-machine.md)
- [MachineStore commit protocol](./2026-09-30-machine-store-commit-protocol.md)
- [Time Travel handoff](../design/2026-09-29-time-travel-logging-handoff.md), §8, §9

## Addendum (2026-09-30): codecs and golden fixtures

`RecordingCodec` gives a recording a durable form: canonical JSON with an explicit
`RECORDING_FORMAT_VERSION`, the structure (snapshots, inputs, decisions, ids, timers, failures)
owned by the codec, and the context, actions, commands and events written by the application's
kotlinx-serialization serializers as embedded JSON. Canonical means: fields in declaration order,
maps sorted by key, no defaults, no nulls, durations in ISO-8601, so equal recordings encode to
equal text. `decode` refuses a newer format, migrates an older one only through a registered
`FormatMigration`, and reports a payload the serializers reject as `Invalid` with the step and
field; nothing is ever filled in with defaults, as the handoff §8 asks.

Golden fixtures pin the formats: `RecordingCodecGoldenTest` holds the JSON of a recording that
exercises every input and outcome kind (format 1), decodes it back to an equal recording that
replays, and checks the refusals and a migration from a pretend format 0; `JournalGoldenTest`
holds the text lines of a scripted Store's journal, with the initial values the handoff §5 wants
fixed by a golden test: the startup is input #1 and processing 1, the first commit is revision 1,
a group's first record is #1 and a store's first record is 1.

Two things the fixture made visible: kotlinx-serialization's default discriminator for sealed
types is the fully qualified class name, which the handoff forbids on the wire, so the golden
types carry explicit `@SerialName`s and the README says so; and a migration that touches
embedded payloads by shape (the first draft added a context field to every object with a
`query`, commands included) is caught by the strict serializers, which is the point of not
ignoring unknown keys.

Not adopted: a serialization-free wire model of koma's own (a second JSON, for no gain) and
`ignoreUnknownKeys` (it would turn a format drift into silently dropped data).

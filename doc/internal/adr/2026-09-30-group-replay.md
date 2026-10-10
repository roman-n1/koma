# A group is cut by freezing its members' queues, bridged by routed effects, and replayed in the order its decisions were made

- Updated: 2026-09-30

## Background

Stage 6 of the [handoff](../design/2026-09-29-time-travel-logging-handoff.md): several machines
of one screen (root, Main, Bridge, adapters) recorded and replayed together, with a consistent
cut (§8.1), a bridge that carries message ids and whose deliveries a replay applies once (§10),
and the acceptance that nothing is delivered twice, no instances are mixed, and live keeps
running. Until now `actron-timetravel` replayed one Store, and Stores talked through whatever the
application wired between their events and dispatches, which no recording could relate.

## Decision

The group lives in `actron-statechart` (`MachineGroup`, the live side) and `actron-timetravel`
(`GroupRecorder`, `GroupRecording`, `GroupReplaySession`, `GroupBranch`, the replay side).

- **A bridge message is the effect it was routed from.** `MachineGroup.route(from, to, map)`
  turns a member's effects into another member's actions; when the sender commits a decision
  (its `Member` is a `DecisionObserver`), each effect a route maps is delivered as
  `MachineInput.BridgeReceived(MessageId(sender, effectId), action)`. The machine decides it
  like a dispatch; admission never refuses it, as refusing would lose a message another Store
  already sent. The same effect gives the same `MessageId` in a replay, so the id is a
  correlation reference (§9), never a second live delivery. The journal gets `BridgeSent` (with
  `delivered = false` when the receiver is not attached: the group's record is then partial and
  the inspector says so) and `BridgeReceived` when the receiver decides the message; the
  message is `inFlight` between the two.
- **A cut freezes queues, not Stores.** Every input of a `MachineStore`, dispatched, delivered
  or fed by its executor, goes through one gate. `MachineGroup.checkpoint(timeout)` closes the
  gate of every member (new inputs wait in a controlled queue, in arrival order), waits until
  each member has finished or discarded what its inner Store had already accepted (a probe on
  the inner Store counts them), takes every executor's `ExecutorCheckpoint` and the messages in
  flight, and opens the gates, letting the held inputs in ahead of any new one. No Store lock
  is held while waiting and no member waits on another's user code; a member that does not
  settle within the timeout aborts the cut and every gate opens, so the group resumes with
  nothing lost. A command's result that arrives during the cut waits and is applied once after
  it. The cut is consistent because sends are effects of processing and no member processes
  while every gate is closed: a message in flight at the cut was sent before it and is decided
  after it, and nothing else crosses. `GroupCheckpoint` is the members' checkpoints, the
  messages in flight, the held counts and the journal boundary. Calling it from a handler or a
  plugin of a member would wait for that member's own processing; it is documented, not
  detected.
- **A group recording is the members' recordings and one order.** `GroupRecorder` wraps a
  `MachineRecorder` per member and assigns, under one lock as the members report, the order the
  decisions were made in across the group. `GroupRecording.since(cut)` trims each member's
  recording to its checkpoint and remembers the cut's messages in flight, so their deliveries
  after the cut are expected without their sends.
- **Group replay steps members in the group's order and checks the bridge.**
  `GroupReplaySession` holds a `ReplaySession` per member and a cursor over the order: at every
  position each member is at the snapshot it had when the group was there; `seek` and
  `stepBackward` cost nothing. `verify` decides every member again and walks the order for
  bridge causality: a `BridgeReceived` whose message was not sent by an earlier step and was not
  in flight at the start is `ReceivedBeforeSent`; a sender that is not a member is
  `SentByNobody`; the same message decided twice by one member is `DeliveredTwice`; a delivery
  with no route from its sender to its receiver is `NoRoute`, which is what mixed instances look
  like. No bridge delivers during a replay: the recorded inputs already hold every delivery, and
  the replay checks that they could have happened.
- **A group branch has a local bridge.** `GroupBranch` is a `Branch` per member and the routes
  as pure functions: a decision's routed effects are delivered to the other members' branches
  at once, depth first, in emission order, each delivery's own effects routed in turn. The live
  group and its bridge are untouched.
- **Format 3 of `RecordingCodec`** writes the `bridgeReceived` input with its message; the codec
  migrates format 2 itself. `JOURNAL_FORMAT_VERSION` is 2 for the two bridge entries, and the
  journal file format writes them under new tags.

Not adopted:

- A barrier that takes the Stores' locks: a handler of one member could then wait for another
  member's user code, which §8.1 forbids; freezing admission needs no lock and lets what was
  already accepted finish.
- Delivering bridge messages during a replay: a replay of one member already has the receive in
  its inputs; a second delivery would be the double delivery §10 forbids.
- Message ids of their own (a counter of the bridge): the effect id is already unique per
  sender and reproduced by a replay; a counter would differ between live and replay.
- `actron-message` as the bridge: it is process-wide and unaddressed (§10); a group's bridge is
  addressed and recorded.

## Notes

- Tests: [`MachineGroupTest`](../../../actron-statechart/src/commonTest/kotlin/actron/statechart/machine/MachineGroupTest.kt)
  (routing with the sender's message id and one decision per message, an undelivered message
  journaled, delivery past the admission bound, a cut that freezes and lets inputs in after it
  in order, a result arriving during the cut applied once after it, a member that does not
  settle aborting the cut with the group resuming);
  [`GroupReplaySessionTest`](../../../actron-timetravel/src/commonTest/kotlin/actron/timetravel/GroupReplaySessionTest.kt)
  (a recorded run replays in order, deliveries the recording cannot have produced are
  mismatches, a recording since a cut with a message in flight replays);
  [`GroupBranchTest`](../../../actron-timetravel/src/commonTest/kotlin/actron/timetravel/GroupBranchTest.kt);
  [`GroupCheckpointStormTest`](../../../actron-timetravel/src/commonTest/kotlin/actron/timetravel/GroupCheckpointStormTest.kt)
  (thirty cuts of two members exchanging messages under a four-thread storm: every cut's
  recording since it replays without a mismatch, which is the consistency of the cut proven
  by the recording, and every pick reached the root).
- Left for later: adapters of external sources in the cut (§8: snapshots of connected sources),
  a group container in the recording file format, the retained effect mailbox with delivery
  and acknowledgement (§10), and the inspector's group-wide positions across members (it shows
  the bridge entries per Store today).

## Related

- [MachineStore commit protocol](./2026-09-30-machine-store-commit-protocol.md)
- [A run is replayed by deciding its recorded inputs again](./2026-09-30-single-store-replay.md)
- [The inspector is a read model](./2026-09-30-inspector-read-model.md)
- [Time Travel and structured logging handoff](../design/2026-09-29-time-travel-logging-handoff.md), §8.1, §9, §10

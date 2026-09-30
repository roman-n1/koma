# A request and its reply are a named pair of routes, checked by the journal and by a replay

- Updated: 2026-10-01

## Background

koma-kt/koma#189 (item 6) names request/reply among the inter-store patterns. The fork's bridge
([group routes ADR](./2026-10-01-group-routes-and-membership.md)) routes effects to actions and
records every delivery with the sender's message id; a request and its answer were two routes
like any other, and nothing related them: the journal could not say what a send replied to,
and a replay could not tell a reply to nothing from a reply. The messenger's root asks a picker
and waits for its answer; that wait must be readable in the journal and checkable in a replay
of a recording since a cut.

## Decision

- **`requestReply(requester, responder, name, request, reply)`** registers two routes as a pair:
  the request route (requester to responder) and the reply route (responder to requester),
  each carrying `RoutePair(name, role)`; both are ordinary routes otherwise (removable, in the
  history, typed by their members). It is asynchronous by construction: the requester waits in
  a state of its own, with a timer if it must, and correlates by what the actions carry; there
  is no `suspend ask()` and no reply future.
- **The journal names the request a reply replies to.** `BridgeSent.cause` is the bridge
  message the sender was deciding when it emitted the effect, when it was one: for a reply
  decided in the same step as the request, the request. A reply decided later (after a
  command's result) has no cause; its correlation is in the actions. `JOURNAL_FORMAT_VERSION`
  7: a field appended to a variant, written from 7 on and read only from a segment of 7 on
  (`JournalFileFormat` threads the segment's record version into the decoder). The inspector
  shows it (`TimelineItem.Sent.cause`, `reply-to=` in the line).
- **A replay checks that a reply follows a request of the pair.** The recording keeps the pair
  with each route (`GroupRoute.pair`, order file format 3; a format 2 segment reads as routes
  without pairs). `GroupReplaySession.verify` walks the order and keeps, per member, the
  requesters it has received a request from, and, per emitted message, that set at the time
  of emission; a message received over a reply route whose sender had not heard from the
  receiver is `GroupMismatch.ReplyWithoutRequest(position, store, message, pair)`. This is a
  check of the pair's causality, not of a message-level correlation: the recording does not
  know which route carried an effect, and a delayed reply's correlation lives in the actions.
  A reply is told by its direction, so between two members a pair's direction should carry the
  pair alone; a plain route alongside a reply route in the same direction is checked as a
  reply too. A run since a cut cannot see the requests received before it: a member whose
  recording begins at a checkpoint counts as having heard from every member, and a message in
  flight at the start is not checked (the storm found both: the first design flagged every
  reply whose request was before the cut).

Not adopted:

- A reply future or `suspend ask()` inside a decision: a decision stays a pure function of its
  snapshot and input, and a wait is a state with a timer.
- A correlation id carried by the bridge (a `requestId` on the message): the effect id already
  identifies the request, `cause` records the immediate case, and the actions carry the rest;
  an id of the bridge's own would differ between live and replay.
- Message-level correlation in `verify` (this reply to that request): would need the route
  each effect took in the recording; the pair-level check catches what mixed instances and
  doctored recordings produce, which is what `verify` is for.

## Notes

- Tests: `MachineGroupTest` (a pair is two routes with their roles; a reply decided from the
  request names it, the request names nothing); `GroupReplaySessionTest` (a paired run verifies;
  the responder's receives turned into dispatches make every acknowledgement a reply to
  nothing); `GroupRequestReplyStormTest` (four sending threads, thirty cuts over a pair: the whole
  run and the run since every cut replay without a mismatch, every reply in the journal names
  the pick it was decided from and every pick was replied to once); `JournalFileFormatTest`
  (`cause` round-trips; a segment of record format 6 reads `BridgeSent` without it; the golden
  re-pinned for the header's version); `GroupRecordingFilesTest` (paired routes round-trip in
  the header; a format 2 segment reads); `InspectorTest` (a send with a cause).
- The journal's rule at `JOURNAL_FORMAT_VERSION` now covers fields: new variants only at the
  end under new tags, new fields only at the end of a variant, and the decoder reads a segment
  by its own version. The format freeze policy (roadmap 5.0-2) will make it a test.

## Related

- [Group routes and membership](./2026-10-01-group-routes-and-membership.md)
- [Group replay](./2026-09-30-group-replay.md)
- [Time Travel and structured logging handoff](../design/2026-09-29-time-travel-logging-handoff.md), §10

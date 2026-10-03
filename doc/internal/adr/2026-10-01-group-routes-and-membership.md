# A group's routes are typed and removable, and a member that closes or leaves drops what was in flight to it

- Updated: 2026-10-01

## Background

koma-kt/koma#189 lists feature composition (item 4: child, optional, for-each, parent–child
wiring) and inter-store communication (item 6: typed and scoped channels, request/reply,
explicit lifecycle ownership). The fork's answer to both is `MachineGroup` ([group replay
ADR](./2026-09-30-group-replay.md)): a bridge of routed effects with journaled message ids, a
consistent cut, a recorded order. The roadmap plan of 2026-10-01 kept that answer and named
what it lacked for the messenger's tabs: a route whose receiver type is checked where it is
written; a way to remove a route; and members that leave. Until now a member could only be
attached: a closed member kept its messages in flight forever, a cut with a closed member
failed, and `BridgeSent(delivered = true)` to a closed store was a lie.

## Decision

- **Typed routes.** `route(from: Member<*, *, *, E>, to: Member<*, A, *, *>, map: (E) -> A?)`
  checks at compile time that the mapped action is one the receiver accepts; the id overload
  stays for routes wired from configuration. Both register the same `Route`.
- **Routes can be removed; a recording keeps every route the bridge had.** `removeRoute(route)`
  stops carrying effects decided from then on. `routeHistory` lists every route ever
  registered, and that is what `GroupRecorder` and the group's order file keep as the
  recording's `routes`: `verify` then tells a delivery that never had a route (instances mixed,
  `NoRoute`) from one whose route was removed later. A finer check (the route existed at that
  position) would need the removal in the order; a live group cannot produce such a delivery,
  so the check stays about mixed instances.
- **A message is in flight until the store it was delivered to decides it or closes.** The
  group books, with each message, the store instance it delivered to. When that store closes,
  what it had not decided never will be: the messages are removed from `inFlight` and journaled
  as `JournalEntry.BridgeDropped(message, to, reason)`, reason `StoreClosed`, a record of the
  receiver (`JOURNAL_FORMAT_VERSION` 6, file tag 24), whatever store the member attached since.
- **A member takes part from `attach` until it detaches or its store closes.** `Member.detach()`
  stops routing its effects, makes a message sent to it undelivered (journaled so, as to a
  member never attached) and leaves it out of the cut; it drops nothing, because the store may
  still decide what it holds, and a decision of the group's message is journaled as received
  whether the member is in or out. Close the store to end what it holds. `Member.isAttached`
  says whether a message can reach it now; `attach` with a new store makes it take part again.
- **The store tells the group when it closed.** `MachineStoreImpl.close()` runs listeners
  registered with `onClose` after the inner store, the executor and the mailbox closed, when
  nothing can be decided any more (the fork's core refuses a commit after close). The member's
  listener drops what is in flight to that store, under the group's lock; a send books its
  message under the same lock together with the receiver's reachability, so a store that closes
  meanwhile finds the message booked and drops it, and one that closed already gets nothing. A
  decision that reaches the member after the drop is not journaled as received: the group's
  story of that message is the drop. `DecisionObserver.onClosed` was not enough: it is called
  only when commands were left unfinished.

Not adopted, on purpose:

- A child store, `scope` or `forEach` in the library (TCA's composition): the messenger's
  owner of composition is Decompose (retained instances, `InstanceKeeper`, a `StoreInstanceId`
  per tab); the library gives the wiring (routes) and the membership, not the tree.
- A global typed bus: `koma-message` stays process-wide and outside replay (handoff §10); a
  group's bridge is addressed and recorded.
- `suspend ask()` inside a decision, or reading another member's `currentState` in `decide`:
  a decision is a pure function of its own snapshot and input; what another member knows
  arrives as a message.
- Auto-wiring by event type (every effect of type `X` to whoever accepts `X`): a route is a
  named, recorded decision of the application.
- Dropping in flight lazily (at the next cut or read of `inFlight`): `inFlight` would lie in
  between, and a cut would carry a message no one can decide.
- Dropping on `detach` (the first design): the storm showed the store keeps deciding what it
  holds between the detach and the close that follows it, so the journal called dropped four
  messages the store had applied. A drop is only true when the store closed.

## Notes

- Tests: `MachineGroupTest` (a typed route carries like an id route; a removed route carries
  nothing more and stays in the history; a member whose store closed gets no more messages and
  what was in flight to it is dropped as `StoreClosed`, a cut leaves it out; a detached member
  leaves the group, what its store held still in flight and received when the store decides it,
  its effects not routed, and takes part again when attached; a detached member whose store
  then closes drops what it held); `GroupMembershipStormTest` (twenty recreations of the
  receiver under four sending threads and ten cuts, half of them leaving first, half closing
  while attached: every delivered message received or dropped exactly once, nothing in flight,
  what the stores hold is exactly what was received); `GroupReplaySessionTest` (a route removed during
  the run stays in the recording, so what it carried verifies); `JournalFileFormatTest`
  (the new variant round-trips; a segment of the previous record format still reads; the golden
  segment re-pinned for the header's record version); `InspectorTest` (a drop is a
  `TimelineItem.Dropped` with its line, and not an incompleteness: it is a fact of the record).
- The journal's rule is now written at `JOURNAL_FORMAT_VERSION`: new variants only at the end,
  under new tags, a reader of a version reads every earlier one. The format freeze policy
  (roadmap 5.0-2) will make it a test.
- Left for C2: request/reply as a correlated pair of routes, `BridgeSent.cause`, `GroupRoute.pair`.

## Related

- [Group replay](./2026-09-30-group-replay.md)
- [The inspector is a read model](./2026-09-30-inspector-read-model.md)
- [Time Travel and structured logging handoff](../design/2026-09-29-time-travel-logging-handoff.md), §8.1, §10

## Review corrections on 2026-10-03

`close()` requests cancellation; a committed non-suspending observer can still finish after it
returns. The member stops accepting deliveries immediately, but its remaining in-flight
messages become dropped only at the inner Store's `StoreClosed` trace. This avoids classifying
a committed delivery as both received and dropped when the same member attaches another store.

An in-flight delivery is keyed by both the sender's effect id and its destination. One effect
routed to two members has two independently completed deliveries. The actual receiving store
is retained in the key's value, so reattachment does not change which close drops a delivery.

Every sending decision reaches all observers before the bridge delivers its effects. The
`BridgeSent` record is published while booking the delivery and before passing it to the
receiver. Both the group recording and the journal therefore place a send before an immediate
receive, regardless of observer registration order.

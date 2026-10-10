# Journal records get their order at publication and their payload from a policy that runs first

- Updated: 2026-09-29

## Background

The [handoff](../design/2026-09-29-time-travel-logging-handoff.md) §5 to §7.1 fixes what a
diagnostic journal for Actron Stores must guarantee before any file format or replay exists:

- Records of one group are ordered by a sequence assigned when the record is published into the
  shared in-memory journal, not when it is later written; allocation and publication are ordered
  together, since an atomic counter followed by unordered sends is not enough. Wall-clock time
  never restores an order.
- Payload rules (allowlist, masking, truncation, omission) apply before anything reaches a
  retained buffer or a sink. Storing `toString()` and hiding it in the UI is ruled out. Failures
  are kept as safe descriptors, not as `Throwable`s.
- The hot path only appends a bounded record to memory. One asynchronous writer, owned by the
  recording session rather than a Store, keeps the sequence and survives a Store's close. Loss is
  counted separately from the overflowing queue, and a missing gap record must not hide it.
- A failing logger, codec or sink is isolated from business processing and cannot recurse.

[`StoreProbe`](./2026-09-29-store-probe-processing-observation.md) supplies the traces; this ADR
records how `actron-observability` turns them into a journal.

## Decision

`actron-observability` is a new module on `actron-core` (no Compose, no logging dependency).
[`RecordingSession`](../../../actron-observability/src/commonMain/kotlin/actron/observability/RecordingSession.kt)
is the journal of one `MachineGroupId` in one `RuntimeSessionId`.

Identity and order:

- `publish` runs one short critical section per record: it increments `GroupSeq` and the Store's
  `StoreSeq`, reads the monotonic `elapsed` time, appends to the retained ring and offers the
  record to the writer's bounded queue. Nothing else happens inside: no callback, no I/O, no Store
  lock. Because sequence, retention and queueing are one section, the retained records, the sinks
  and the counters agree on the order, whatever threads published.
- The section is entered without suspending (`Mutex.tryLock` in a spin), since probes are plain
  functions called under Store locks. It holds for a few field writes.
- `elapsed` is read under the lock, so it never decreases along the sequence; it is diagnostic
  only. `InputId`, `ProcessingStarted.ordinal` and `StateCommitted.revision` come from the Store
  unchanged.
- Wire names are Kotlin enums and constants (`DiscardKind`, `OutcomeKind`, `Capability`,
  `JOURNAL_FORMAT_VERSION`), not class names of core types.

Payload:

- `PayloadPolicy` runs in the probe, on the Store's thread, once per payload, before the record
  exists. It returns `Payload.Retained` (the object; an explicit debug choice), `Projected` (a
  label and allowed fields), `Omitted` or, when it threw, `Unavailable`. Failures become
  `FailureDescriptor`s that keep class names, causality and suppressed exceptions and, by default,
  drop messages. `PayloadPolicy.metadataOnly()` is the production default; `retainAll()` is for
  tests and local debugging. Nothing downstream re-decides: a sink prints exactly what the
  record holds.
- A policy that throws makes the payload `Unavailable`, is counted and reported to the session's
  `onFailure`, and the record is still published. The Store's `ExceptionHandler` never hears of
  it.

Writer, sinks and loss:

- One writer coroutine per session, in the scope the application passes, drains the queue and
  calls each `JournalSink` in order. A sink may block or suspend; it delays the sinks after it and
  the queue, never a Store. A sink that throws is counted and reported to `onFailure`; the record
  is skipped for that sink only, and no record is written about the failure. A throwing
  `onFailure` is swallowed.
- When the queue is full the record is dropped for the sinks and counted (`droppedForSinks`); the
  next publication that finds room is preceded by a `JournalGap(dropped)` record with its own
  `GroupSeq`. A gap record that does not fit is counted as dropped too, so the hole a sink sees is
  always fully explained by the next gap. The retained ring is a contiguous suffix of the journal
  and knows its own loss as `evicted`.
- `close()` publishes `RecordingStopped`, drains the queue and stops the writer; later
  publications are counted (`publishedAfterStop`) and dropped.

Capability: every recording is `Capability.InspectOnly`. `DeterministicReplay` exists in the
vocabulary and nothing grants it until the replay-ready pipeline of stage 2.

Budgets: `JournalConfig(retainedRecords = 2_000, writerQueueCapacity = 4_096)` are placeholders,
documented as such, to be replaced by measured values in the application.

Not adopted:

- A coroutine per record, or logging from the Store hook with a dispatcher: the stability review
  showed both reorder entries and lose them on close.
- Ordering by timestamps, or an atomic sequence without the lock: neither gives an order that the
  retained records, the sinks and the counters share.
- Sanitizing in `JournalFormat`, a sink or the inspector: too late, the object would already sit
  in memory.
- Reporting journal failures through the Store's `ExceptionHandler`: it would let a broken sink
  drive `recover {}` or a crash reporter, and invite the logger/handler recursion the handoff
  forbids.
- Building the journal on `Plugin` or `simpleLogging`: see the probe ADR. `simpleLogging` stays
  for quick local debugging of one Store; the journal is the structured path.

## Notes

- The session locks with a spin on `Mutex.tryLock`. Under heavy contention from many Stores on
  many threads this burns CPU rather than parking; measure before raising the number of Stores
  per session, or shard sessions by group.
- Retained records hold whatever the policy kept. With `retainAll()` that is the live objects, so
  the ring's memory is the policy's responsibility.
- Not in this stage: file segments with framing and checksums, rotation, export, the group cut of
  §8.1, checkpoints, and the replay session. `JournalGap` and `RecordingStopped` are the only
  session-level records so far.
- Tests: [`RecordingSessionTest`](../../../actron-observability/src/commonTest/kotlin/actron/observability/RecordingSessionTest.kt)
  (dense sequences from many threads, bounded retention, gap invariants, sink isolation, close),
  [`JournalProbeTest`](../../../actron-observability/src/commonTest/kotlin/actron/observability/JournalProbeTest.kt)
  (entries of a running Store, policy before retention, durations from the session's time source,
  two Stores in one group, recording changes nothing about the Store),
  [`PayloadPolicyTest`](../../../actron-observability/src/commonTest/kotlin/actron/observability/PayloadPolicyTest.kt)
  and [`LoggerJournalSinkTest`](../../../actron-logging/src/commonTest/kotlin/actron/logging/LoggerJournalSinkTest.kt)
  (no secret reaches the logger under the default policy).

## Related

- [Store processing is observed through an internal probe](./2026-09-29-store-probe-processing-observation.md)
- [Time Travel and structured logging handoff](../design/2026-09-29-time-travel-logging-handoff.md)
- [Stability review](../notes/2026-09-29-stability-review.md), logging findings of rounds one and two

## Addendum (2026-09-30): entries a probe cannot produce

The journal now takes entries from producers other than the probe, through
`RecordingSession.publish(store, entry)` for a Store the session records. The first producer is
the replay-ready machine: `JournalEntry.DecisionCommitted` (transitions, activations, commands
with lane and policy, cancelled scopes, timers, the count of events; ids and node names are the
machine's, the command payload is what a describer keeps, nothing by default) follows the
`StateCommitted` of the same input and revision; `DecisionIgnored` carries the machine's reason
where the store's trace only says "unchanged"; `InputRejected` records an action refused at
admission, which never became an input and so has no `InputId`. The input of a decision comes
from `currentInputId()` of `actron-core`, the id carried by the coroutine that processes it, so the
imprecision documented there (a startup processed inside the first dispatch) applies.

## Addendum (2026-09-30): budgets from measurements

`JournalBudgetJvmTest` (JVM, Apple Silicon, 2026-09-30) measured the production policy:

| Measure | Value |
|---|---|
| Retained record (envelope, entry, descriptors, payloads omitted) | about 117 bytes |
| One `publish` (ring of 2 000, queue of 4 096, no sinks) | about 0.33 µs, 3 M records/s |
| Dispatch of a trivial Store, without / with the journal | 10 µs / 11 µs, ratio 1.12; four records per dispatch |

The defaults are now `retainedRecords = 4_000` (about 0.5 MB, the last ~1 000 dispatches of a
group) and `writerQueueCapacity = 4_096` (about 0.5 MB when full). They replace the placeholders
of the first version. Still open: the same measurement on Android and iOS devices (the test is
JVM-only because it reads the JVM heap), and the cost with a retaining policy, which is the
policy's to bound.

## Addendum (2026-09-30): the gap always comes first

`JournalConcurrencyTest` showed that the writer could hand a record to the sinks without the gap
that should precede it: the gap record failed to enter the full queue, the writer then freed a
slot, and the record took it. The rule is now: a record is offered only after its gap entered
the queue; when the gap does not fit, both are dropped and counted, and the next publication
tries again. `close()` sends the final gap and `RecordingStopped` with the suspending `send`
after the session stopped accepting records, so a sink always ends with a complete account of
what it missed.

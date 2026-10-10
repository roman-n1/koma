# Store processing is observed through an internal probe, not through plugins

- Updated: 2026-09-29

## Background

The [Time Travel and structured logging handoff](../design/2026-09-29-time-travel-logging-handoff.md)
needs a journal of what a Store actually did: which inputs it accepted, which it dropped and why,
what each processing committed, which failures it recovered and which it reported, and which input
a transaction or an event belongs to. Its stage 1 starts with "minimal core probes" and asks to
prove them on the boundaries a `Plugin` cannot see (§14, item 4).

`Plugin` cannot serve as that source:

- `onAction` runs after admission: an action discarded by `dispatchIf`, by
  `PendingActionPolicy.ClearOnStateExit`, by `clearPendingActions()` or by `close()` never
  reaches it, and neither does an action dropped because the startup it triggered failed.
- `onState` runs only when the snapshot changed: a handler that leaves the state equal, and an
  action no handler matches, look the same as each other and as nothing at all.
- One dispatch may commit several snapshots (`action {}` moves to a state whose `enter {}` moves
  on), and `recover {}` commits too; nothing ties those `onState` rounds to the input, and there
  is no end-of-processing signal or outcome.
- A `transaction {}` from a launched coroutine commits outside any action, and an event emitted
  from a launch has no visible cause.
- Failures reach the `ExceptionHandler` without the input they belong to.
- Hooks are suspending and may run concurrently under `PluginExecutionPolicy.Concurrent`; they
  are an extension point for reacting to the Store, with their own error semantics (`onAction`
  and `onStart` abort, `onState` and `onEvent` report and continue).

Reconstructing any of this from `Store.state` is ruled out by the handoff: a `StateFlow`
conflates, equal snapshots are not emitted, and a dropped action leaves no trace.

## Decision

`actron-core` gets an internal, additive observation API: [`StoreProbe`](../../../actron-core/src/commonMain/kotlin/actron/core/StoreProbe.kt)
receives [`StoreTrace`](../../../actron-core/src/commonMain/kotlin/actron/core/StoreProbe.kt) values
at the processing boundaries of `StoreImpl`. Probes are registered with `StoreBuilder.probe()` or,
before startup, through `StorePatch`. The API is `@InternalActronApi`: it is the foundation for the
`actron-observability` journal and the inspector, not a public extension point, and it is not an
obligation for third-party `Store` implementations.

Every mutation of a Store is the processing of one of four inputs, each with an `InputId`:

| Input | Accepted when | Processed |
|---|---|---|
| `Startup` | `start()`, the first dispatch or state collection requests startup | plugin `onStart` hooks and the initial `enter {}` chain; processed again, with a new ordinal, when a plugin's `onStart` failed and a later call retries |
| `Dispatch(action)` | `dispatch()` / `dispatchIf()` is called | the matching `action {}` handler and the transition it causes |
| `Transaction(origin)` | `transaction {}` is called from a launch | the transaction block and the transition it causes |
| `Recovery(error, origin)` | a launched coroutine fails | the `recover {}` pass for that failure, under the lock |

For one processed input the probe sees `InputAccepted`, `ProcessingStarted`, zero or more
`StateCommitted`, `EventEmitted` and `FailureReported`, then exactly one `ProcessingFinished`
with an outcome: `Handled(commits)`, `Unchanged`, `Ignored` (no `action {}` matched),
`Recovered(error, commits)`, `Failed(error)` or `Cancelled` (closed mid-processing). An input
that is never processed gets `InputDiscarded` with a reason: `StoreClosed`, `ClearedOnStateExit`,
`ClearedExplicitly`, `Stale`, `Rejected(error)`, `StartupFailed(error)` or `LaunchInactive`.
`StoreClosed` is traced once the Store's root scope has completed.

Identity and ordering, as the handoff §5 asks for, come from the Store itself:

- `InputId` is unique per Store and increases in allocation order (a CAS from the dispatching
  thread). It is not dense and not the processing order.
- `ProcessingStarted.ordinal` is dense and is the processing order; a retried startup gets a new
  ordinal for the same `InputId`.
- `StateCommitted.revision` is dense and counts committed snapshots; equal snapshots are not
  committed by the generic Store, so they are not revisions. The replay-ready pipeline of stage 2
  defines its own revision on top (an accepted decision with an equal snapshot still counts).
- The input a coroutine works for travels in its `CoroutineContext`: dispatch, startup,
  transaction and recovery coroutines carry their own input; the coroutines a handler or a plugin
  launches carry the input being processed when they were launched. Events, transactions,
  recoveries and reported failures are correlated through it.

Rules of the probe:

- `record` is a plain function called inline at the boundary, mostly under the Store lock: no
  suspension, no I/O, no calls back into the Store. `InputAccepted` and the discards of queued
  inputs are recorded from other threads, so a probe that keeps state is thread safe.
- A probe that throws is reported to the `ExceptionHandler` and the boundary proceeds; the
  failure of a probe is never traced, so a failing probe cannot recurse. A throwing handler
  propagates as anywhere else.
- Without probes the only cost is the id allocation and two counters; no trace is built.
- Traces carry the live state, action, event and error objects. Retention, sequence numbers per
  group, payload policy and sanitization are the journal's job, outside `actron-core`.

Not adopted:

- Widening `Plugin` to these boundaries: its hooks would gain non-suspending, lock-held,
  pre-admission calls with different failure semantics, and every plugin would pay for them.
- A suspending probe or a probe with its own dispatcher: ordering would then depend on the
  dispatcher, which the handoff forbids for the journal.
- Making probes public now: the trace vocabulary will move with stages 1 and 2 (commands, timers,
  effects, group sequence). It stays `@InternalActronApi` until the journal format is fixed.

## Notes

- Test coverage: [`StoreProbeTest`](../../../actron-core/src/commonTest/kotlin/actron/core/StoreProbeTest.kt)
  pins the boundaries listed in the handoff §14: startup as an input, equal state versus
  `Ignored`, several commits for one input, recovered and unrecovered failures, saver failure
  under its input, launch failure as a recovery input, plugin start failure with the dropped
  dispatch and the retried startup, every discard reason, transaction origin and `LaunchInactive`,
  event correlation across later inputs, a failing probe, and a multi-threaded run checking
  unique ids, dense ordinals and revisions, and that every accepted input ends.
- Known imprecision: a failure that escapes a dispatch coroutine is reported under the dispatch
  input, also when the failure was the startup's (the `ProcessingFinished(startup, Failed)` and
  `InputDiscarded(dispatch, StartupFailed)` traces name it). The startup input has no discard
  trace: a `StoreClosed` before its `ProcessingStarted` means it never ran.
- In `actron-statechart`, activities and timers run in a work loop launched from the initial
  `enter {}`, so their transactions currently name the startup input as origin. Stage 2 replaces
  that path with the replay-ready command pipeline.
- `Ignored` costs one pass over the action predicates per dispatch, only while probes are
  registered.
- Follow-up in stage 1: `actron-observability` with the record envelope, `StoreSeq`/`GroupSeq` at
  publication, bounded retention, `PayloadPolicy` before retention, and the `Logger` adapter in
  `actron-logging` that replaces `simpleLogging`'s `toString()` output.

## Related

- [Time Travel and structured logging handoff](../design/2026-09-29-time-travel-logging-handoff.md)
- [`Plugin` design memo](../notes/2026-05-02-plugin-design.md)
- [Exception handling at framework boundaries](./2026-05-07-framework-boundary-exception-handling.md)
- [Unhandled action behavior](../notes/2026-04-25-unhandled-action-behavior.md)

# Contract review of gap-roadmap APIs

Review scope: automatic transitions, DurableMachine and InvokedMachine in PR #83, plus the
failing Time Travel example CI test. This is a source/contract review with executable regression
tests; it is not independent human approval or messenger integration certification.

## Findings and fixes

1. **Receipt suppression lost automatic work.** A duplicate completed durable key triggers
   CommandCompleted. Its automatic transition could register ordinary work, which persist
   omitted from payload classification. The transaction failed with a missing-payload error.
   Persistence now drains newly generated registrations, filters ordinary work against the final
   snapshot and bounds receipt feedback with maxReceiptCompletions (default 100).
2. **Receipt cleanup hid the failure cause.** Failed automatic cleanup retained its command,
   then failed checkpoint validation with a different error. The original Machine failure now
   propagates, with no storage change or returned intents.
3. **Restore accepted malformed outbox aliases.** Aliases beyond the Machine's command counter
   passed validation. Validation now also checks original registration membership, positive
   issued ids, unique payload ownership, absence of live completed-receipt commands and ordinary
   registration scope/lane. A durable checkpoint must already be started.
4. **Invocation restore accepted an unstarted child.** A live owner paired with a revision-zero
   child passed validation and ignored every subsequent child input. Such snapshots are rejected.
5. **The CI example test raced buffered journal visibility.** The test held a committed observer
   and polled for an unfinished file while a group checkpoint's five-second deadline ran. The
   BufferedOutputStream need not expose the journal before that deadline. An internal test seam
   now signals a real writer write+flush; the test checks the unfinished journal after that signal,
   while still proving finalization waits for the observer. Runtime deadlines are unchanged.

The first four findings were reproduced by failing regression tests before production fixes.
The original example test passed locally but failed on the CI runner; inspecting the real buffered
storage implementation identified the invalid visibility assumption.

## Contracts exercised

* Automatic Machine failure rolls back context/configuration and discards commands, effects,
  timers and cancellations. ChartStore action failure retains the old activity and suppresses
  launches staged in transient states. Existing startup, parallel completion, internal timer,
  livelock and pending-revision tests remain in place.
* Durable reservation CAS conflicts prevent external IO. Failure while deciding an external
  result keeps the outbox pending and the prior snapshot intact; another attempt can complete.
  Existing crash-after-side-effect, alias deduplication, state-exit-during-IO, timer recovery and
  semantic migration tests remain in place.
* Invocation filters temporary parent/child commands and timers, discards queued events from an
  expired child owner, and rolls back both snapshots and every intent when feedback exceeds its
  bound or an event mapper throws. Existing activation-qualified stale-input and child-start
  rollback tests remain in place.

## Retained application boundaries

Machine is the pure atomic intent path. ChartStore's hooks still emit live events immediately;
already delivered events and arbitrary IO inside hooks cannot be rolled back by a later failure.
Use Machine commands when the complete macrostep must succeed before effects execute.

DurableMachine needs transactional storage, stable business idempotency and application-owned
receipt retention, serialization, migrations, cross-process drain leases and logical-time recovery.
External IO remains at-least-once with idempotency; live event intents are not a durable mailbox.

Invocation is a pure composite decision boundary. Its application executor must commit both
snapshots before executing filtered work, qualify child ids with their owner, apply cancellations
and restore payloads for unfinished commands. It is not an independently running child executor.

## Verification

The follow-up adds 13 common contract tests. Local root `jvmTest` passed, including the Time
Travel and durable-effects examples and resource-harness smoke. Statechart passed 471 tests
on each of Android host, JS Node, Wasm Node and iOS Simulator Arm64; root `apiCheck` and
`checkDebugGraph` passed. The original three-argument DurableMachine constructor is retained;
the configurable bound is an additive overload, held by JVM/klib dumps.

Final GitHub check results are recorded in PR #83; successful local tests do not substitute
for the full CI matrix.

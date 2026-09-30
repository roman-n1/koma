# A test receives the recorded events one by one, and ends by asserting nothing is pending

- Updated: 2026-10-01

## Background

`StoreRecorder` keeps every state and event a Store produced, and a test compares its lists
with what it expects. koma-kt/koma#189 (item 2) asks for `receiveEvent(...)` and
`assertNoPendingWork()` in a TestStore-like API: a test that accounts for each event as it goes,
and a test that cannot end with an event it did not expect or with work still running. With
`awaitIdle` and `pendingWork()` ([ADR](./2026-10-01-await-idle-and-subscriptions.md)) the second
half became possible; the first needed only a cursor.

## Decision

- **A cursor on `StoreRecorder`.** `receiveEvent<E2>()` returns the next unconsumed event, which
  must be an `E2`, and moves the cursor; `receiveEvent(predicate)` does the same for a predicate;
  a wrong type, a failed predicate or an empty tail throw `AssertionError` naming the next event
  and the unconsumed tail, and leave the cursor where it was. `unconsumedEvents` is the tail;
  `assertNoUnconsumedEvents()` fails listing it; `clear()` resets the cursor with the history.
  `events` keeps every event, received or not: the record is the record, the cursor is the test's
  bookkeeping over it (Information Expert: the recorder owns the list, so it owns the cursor).
- **`Store.assertNoPendingWork(recorder?)`** in koma-test: `pendingWork()` idle and, with a
  recorder, no unconsumed events; otherwise `AssertionError` with both. It does not wait: a test
  calls `awaitIdle()` first when launches may still be running.
- Assertion helpers throw `kotlin.AssertionError` (the stdlib's), so koma-test needs no
  dependency on a test framework; misuse of the API keeps throwing `IllegalStateException`.

Not adopted:

- Consuming on read (`events` shrinking as received): tests that compare the whole list, the
  README's own examples, would break; the two views coexist.
- An exhaustive mode that fails on the next dispatch when events are unconsumed (TCA's
  `TestStore`): the end-of-test check says the same without a mode.

## Notes

- Tests: [`StoreRecorderCursorTest`](../../koma-test/src/commonTest/kotlin/koma/test/StoreRecorderCursorTest.kt):
  events received in order by type and by predicate while `events` keeps them all; a wrong type,
  an empty tail and a failed predicate fail and leave the cursor; the tail is listed and `clear()`
  resets the cursor; `assertNoPendingWork` fails on an unreceived event and on a running launch
  and passes with a subscription; the cursor inside `record {}`; fifty random sequences received
  exactly once, in order.
- Upstream: proposed with `awaitIdle` (inventory row d7).

## Related

- [awaitIdle and subscriptions](./2026-10-01-await-idle-and-subscriptions.md)
- [A MachineStore is a Store koma-test can drive](./2026-10-01-machine-store-test-bridge.md)

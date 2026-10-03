# A test waits for the Store to settle with `awaitIdle`, and a `subscribe {}` is what it does not wait for

- Updated: 2026-10-01

## Background

The author's note [`2026-05-10-await-launch-completion-in-tests.md`](../notes/2026-05-10-await-launch-completion-in-tests.md)
proposes `awaitIdle()`: a test API that waits for the launches of `enter {}` and `action {}`
without a test dispatcher, so a `launch(Dispatchers.IO)` in production code can be awaited too;
its condition is a way to tell long-lived launches (a Flow collection) apart, or every such test
hangs. koma-kt/koma#189 (item 2) lists `advanceUntilIdle()` and `assertNoPendingWork()` among the
TestStore-like API. The fork met the same need from the other side: a storm test that read an
observer as soon as the state settled was one decision early on the CI runner (#48), and
`MachineStore`'s group cut waited for its inputs through a private probe counter.

## Decision

- **`StoreInternalApi.awaitIdle(timeout): StorePendingWork`** in `koma-core`, implemented by
  `StoreImpl`: wait until no accepted input is queued or being processed, then join every active
  child of every state runtime's scope that is not a subscription, and repeat until a round finds
  nothing, since a launch may dispatch or transact before it ends. Inputs are counted next to the
  traces that already say when one is accepted, finished or discarded (`pendingInputs`, no
  allocation without probes); launches are the runtime scopes' children, read under the lock and
  joined outside it. On a timeout the counts are reported, and the report never waits for the
  lock: a handler stuck under it is the reason the Store is not idle. From inside a handler,
  plugin hook or transaction it fails fast like `dispatchAndAwait`. A closed Store is idle once
  its coroutines ended. The author's open questions, answered: enter launches are tracked (they
  are children of the same scope); the iteration is bounded by the timeout; a cancelled state's
  launches join at once; timer-driven work of a chart is data, not a launch (below).
- **`subscribe {}`** on `EnterScope` and `ActionScope`: a `launch` in every way but one, `awaitIdle`
  leaves it out. The distinction is a property of the definition, readable by whoever reads the
  Store (a socket reader is not a request that finishes), which is the note's first option; a flag
  on `launch` would be forgotten in exactly the tests that hang, and quiescence is timing.
  `koma-statechart`'s task runner, the Store-lifetime coroutine that runs activities and timers,
  is subscribed: activities and timers are the chart's data.
- **`MachineStoreImpl.awaitIdle`** is a fixpoint over the inner store and the executor: inner
  idle, then a `checkpoint()` of the scheduler (the actor answers after every decision queued
  before it, so every result it fed is an input already counted), then inner idle again. Commands
  still running, timers scheduled and effects pending are data of the checkpoint, not work to
  wait for. The group cut uses it instead of the probe counter, which is gone: `MachineStore`
  no longer uses `StoreProbe` to count inputs. A lifecycle probe still waits for
  `StoreClosed` before group close listeners classify undecided bridge deliveries as dropped;
  `close()` requests cancellation and may return before a committed observer finishes.
- **koma-test**: `Store.awaitIdle(timeout = 10.seconds)` throws with the counts when the Store
  is not idle in time; `Store.pendingWork()` reports without waiting. The timeout runs on the
  caller's clock: under `runTest`'s virtual time it expires as soon as the body suspends, so a
  Store on a real dispatcher is awaited under `withContext(Dispatchers.Default)`.

Not adopted:

- Counting launches through `actionLaunchJobs` only (the note's sketch): enter launches and
  untracked action launches are not in it; the scope's children are complete.
- A plugin's `launch` as work to wait for: it runs in the Store's root scope for the Store's
  lifetime, as a plugin's subscription would.
- `awaitIdle` waiting for a machine's running commands: a socket reader is a command that never
  ends; `checkpoint()` lists it.

## Notes

- Tests: [`StoreAwaitIdleTest`](../../koma-core/src/commonTest/kotlin/koma/core/StoreAwaitIdleTest.kt)
  (a fire-and-forget launch and what a launch dispatches or transacts are waited for; a
  subscription is not and counts as no launch; a timeout reports the pending launch and the Store
  is idle once it ends; an exited state's launches join at once; inside the Store it fails fast; a
  closed Store is idle; a storm of eight threads and eight hundred dispatches whose launches on
  `Dispatchers.Default` increment a counter, ten rounds: `awaitIdle` returns only when every
  launched effect happened); [`StoreAwaitIdleExtensionTest`](../../koma-test/src/commonTest/kotlin/koma/test/StoreAwaitIdleExtensionTest.kt)
  (the wrappers' failure message and report); `MachineStoreTestBridgeTest` (the executor's
  results are decided before `awaitIdle` returns, a command that never answers is data);
  [`StateChartAwaitIdleTest`](../../koma-statechart/src/commonTest/kotlin/koma/statechart/StateChartAwaitIdleTest.kt)
  (idle while an activity runs and while a timer waits); `RecordingFileStormTest` now waits with
  the public `awaitIdle` instead of a probe.
- Upstream: `awaitIdle` and `subscribe` are proposed as one change on the author's note
  (`notes/2026-10-01-upstream-series.md`); inventory rows b7 and c7.

## Related

- [A MachineStore is a Store koma-test can drive](./2026-10-01-machine-store-test-bridge.md)
- [StoreProbe: observing the processing of a Store](./2026-09-29-store-probe-processing-observation.md)
- [Time Travel and structured logging handoff](../design/2026-09-29-time-travel-logging-handoff.md), §12

# A MachineStore is a Store actron-test can drive

- Updated: 2026-10-01

## Background

actron-test's `startAndAwait()`, `dispatchAndAwait()`, `patch {}` and `createRecorder()` speak to a
Store through `StoreInternalApi`, which only Actron's own `StoreImpl` implemented. A `MachineStore`
wraps such a Store but did not implement the bridge, so every one of these threw "only supported
for Store instances created by Actron DSL" on it, and the machine's tests reached the inner store
through an `internal` cast (`(store as MachineStoreImpl).inner.startAndAwait()`), which only
tests inside `actron-statechart` can do. The author's roadmap (koma-kt/koma#189, item 2) asks for a
TestStore-like API; the first step is that the store a messenger feature runs is reachable by
the testing API at all.

## Decision

`MachineStoreImpl` implements `StoreInternalApi<MachineSnapshot<C>, A, E>`, with the machine's
meaning for each member (Information Expert: the admission counter, the cut's gate and the
executor live in the impl, so no adapter outside it could answer honestly).

- **`startAndAwait`** is the inner store's.
- **`dispatchAndAwait(action)`** books the action with the admission policy like `dispatch`; a
  rejected action throws `IllegalStateException`, since it never became an input (a core Store
  returns normally on a later discard, which is a different thing). Outside a cut it is the inner
  store's `dispatchAndAwait` of the `MachineInput.Dispatch`, so it returns after the decision was
  committed and observed, before the commands the decision started run. During a cut the input
  waits in the controlled queue; the call returns after the thaw, once the inner store finished
  what it held. For that the frozen flag became a `MutableStateFlow` that turns after the drain,
  so a waiter sees the held inputs already counted.
- **`dispatchIf`** throws `UnsupportedOperationException`: an admitted action is counted by the
  admission policy and a predicate cannot give the count back honestly; a stale input is the
  machine's decision (`IgnoreReason`), not the store's.
- **`patch`** passes the settings to the inner store and appends the plugins adapted
  (`AdaptedPlugin`): `onStart` and `onState` pass through; `onAction` reaches the plugin for the
  inputs that carry an action of the store's callers (`Dispatch`, `External`, `BridgeReceived`),
  not for the executor's own inputs (results, completions, timers); `onEvent` never comes from the
  inner store, whose events bypass it (`MachineStore.event` is the mailbox's transient flow), so the
  executor's plugin delivers every effect of a committed decision to the adapted plugins, transient
  and retained alike, in decision order, after the observers. A plugin's `dispatch` and a launch's
  `dispatch` go through admission. Replacing or clearing the plugins is refused (the executor is
  one of them); probes are refused (the inputs are machine inputs; the journal is `recordTo` and
  `decisionsOf`).
- **`matchActionHandlers`** reports the inner store's single handler for a dispatch of the
  action; the chart's tools diagnose transitions.

Consequences: `createRecorder()` on a `MachineStore` records the initial snapshot, every commit
and every effect; the six `.inner.startAndAwait()` casts in tests became `store.startAndAwait()`;
raw machine inputs (a stale command result, a timer firing) still go through `inner` in the
module's own tests, where they belong.

Not adopted:

- An adapter object outside the impl (`MachineStore.asTestable()`): the counter, the gate and the
  executor are private to the impl; an adapter would either duplicate them or be wrong during a cut.
- Forwarding a command's result action to `onAction`: it is the executor's input, already visible
  to a `DecisionObserver`; a plugin written for the store's actions would otherwise see actions
  nobody dispatched.
- Delivering effects through the inner store's `onEvent` by emitting them there: the mailbox is
  the effects' owner (transient to the event flow, retained until acknowledged); a second delivery
  path would be a second owner.

## Notes

- Tests: [`MachineStoreTestBridgeTest`](../../actron-statechart/src/commonTest/kotlin/actron/statechart/machine/MachineStoreTestBridgeTest.kt):
  `startAndAwait` starts and `dispatchAndAwait` returns after the commit and before the command
  runs; a recorder records every snapshot and every effect, transient and retained, in decision
  order; an appended plugin sees the callers' actions, every state and every effect and dispatches
  from a launch through admission; `patch` after start and replacing, clearing or probing are
  refused; a refused admission throws and the refused action is not decided; `dispatchIf` is
  unsupported; a dispatch during a cut waits for the thaw and is decided once; and, hands cannot
  reproduce it, four threads of `dispatchAndAwait` under thirty group cuts: every call returns,
  every action is decided exactly once, and the recorder holds one commit per action.
- Next: `awaitIdle` for the commands and launches a decision started (the author's note
  `2026-05-10-await-launch-completion-in-tests.md`), then a recorder cursor and
  `assertNoPendingWork`, then a `actron-statechart-test` module with a driver.

## Related

- [The commit protocol of MachineStore](./2026-09-30-machine-store-commit-protocol.md)
- [Group replay](./2026-09-30-group-replay.md), for the cut's controlled queue
- [Time Travel and structured logging handoff](../design/2026-09-29-time-travel-logging-handoff.md), §12

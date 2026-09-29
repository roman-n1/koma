# Stability review before production use

- 更新日: 2026-09-29

## 背景

We plan to build the state machines of a KMP messenger on Koma, so the whole project was reviewed
for bugs and missing tests. Each issue below was reproduced with a failing test before it was
fixed; the tests stay as regression tests.

## Fixed

Core (`koma-core`)

- `store.state.first()`, `first {}` and `take(n)` never returned: `Store.state` collected in a
  child coroutine and then waited in `awaitCancellation()`, so an early stop only cancelled the
  child. It now collects in the caller's coroutine and requests startup after the first value.
- When `exit {}` threw, the transition was aborted but the state's runtime was already cancelled
  and removed, so every later `launch {}` in that state failed with "State scope is not found".
  The runtime is now removed only after `exit {}` succeeds.
- With `PendingActionPolicy.ClearOnStateExit`, pending actions were cleared after plugins saw the
  new state, so an action a plugin (or an unconfined state collector) dispatched in reaction to it
  was dropped. Pending actions are now cleared before the new state is committed.

Messages (`koma-message`)

- An exception in a `receiveMessages {}` block ended that Store's subscription for good. It is now
  reported to the Store's exception handler and the subscription continues.
- The subscription was registered asynchronously, so on `Dispatchers.Default` messages sent right
  after `start()` or from the Store's own startup `enter {}` could be lost. `onStart` now waits
  until the subscription is registered.
- The bus was an unbuffered `SharedFlow`, so one slow receiver stalled every sender, and senders
  cancelled while suspended hit an internal kotlinx.coroutines assertion
  (`SharedFlowImpl.updateBufferLocked`, visible with `-ea`) that lost the next message. The bus
  now has a buffer of 64 messages.

Logging (`koma-logging`)

- Every entry was logged in its own coroutine, so on `Dispatchers.Default` entries came out of
  order and pending ones were lost on `close()`. Without a dispatcher, entries are now logged from
  the hook itself.

Statecharts (`koma-statechart`)

- Work started with `launch {}` in `onEnter` ran even when the step failed, if the hook suspended
  before failing. Such work is now sent only after every hook of the step has succeeded.
- `StateChartStore` accepted charts with duplicate ids, an undeclared initial state or transitions
  from or to undeclared states; a typo in a target silently exited the whole configuration. The
  Store now rejects them when it is built (the pure `StateChartRuntime` still steps such charts, as
  its property tests specify; call `validate()` when using it directly).
- Timers without a positive delay that restart themselves (directly or through each other) were
  accepted and fired forever. The Store now rejects such loops.
- A configuration restored by a `StateSaver` was never checked, so a snapshot from an older chart
  could leave the Store in states no transition leaves. An inconsistent configuration now starts
  over from the initial one and keeps the restored context.
- Mermaid labels were written raw; line breaks and `;` split statements.

Compose (`koma-compose`)

- A callback created in `stateContent<S2> {}` (for example a double-tapped button) that ran after
  the Store moved to another state type but before recomposition threw `ClassCastException`. The
  narrowed `ViewStore` now returns the last `S2` state.

Build

- `koma-compose`, `koma-logging`, `koma-message` and `koma-test` expose core types in their public
  API but declared `koma-core` (and `compose.runtime`) as `implementation`; they are now `api`.

## 未解決事項

Known behavior that is by design or needs a decision; take it into account when writing
messenger features.

- Store events use an unbuffered `SharedFlow` (see the event SharedFlow ADR): events emitted with
  no collector are lost (for example from startup `enter {}` before `eventEffect` subscribes), and
  a slow collector blocks the Store while it holds its lock. Model anything that must not be lost
  (errors, navigation) as state.
- The lambda overload of `rememberViewStore` defaults to `autoClose = false`, although the
  composable creates the Store; pass `autoClose = true` or the Store (and its message
  subscription) outlives the screen.
- `rememberStateSaver()` survives configuration changes only, not process death.
- A transition whose source is a region of a parallel state exits the whole parallel state (SCXML
  semantics; there are no internal transitions).
- A restored Store restarts every timer of the active nodes, including one a false guard had
  stopped.
- `dispatchAndAwait` on a closed Store returns without running anything.
- `StoreRecorder` uses plain lists; read them only after the Store is idle when it runs on a
  multi-threaded dispatcher.
- `simpleLogging` logs full `toString()` of actions, events and states; do not enable it in release
  builds for messenger data.

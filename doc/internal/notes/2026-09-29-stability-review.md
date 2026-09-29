# Stability review before production use

- Updated: 2026-09-29

## Background

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

## Fixed in the second round

Core (`koma-core`)

- Actions were not processed in dispatch order on a multi-threaded dispatcher (the default,
  `Dispatchers.Default`): every `dispatch()` launched its own coroutine, and coroutines scheduled
  on different worker threads reached the Store's lock in arbitrary order. Two hundred sequential
  dispatches from one thread came out with several pairs swapped. Each dispatch now waits for
  its predecessor before it competes for the lock; the lock already serialized them, so nothing
  gets slower.
- A `transaction {}` queued by a launch that `LaunchControl.CancelPrevious` or `cancelLaunch()`
  then cancelled still committed. The transaction runs in the Store's root scope so that it is
  atomic once started, but it only checked that the state was still active, not that its caller
  was. In the README's search example this commits the result of the previous query after the
  new one was requested. A transaction is now skipped when its caller was cancelled before it
  got the lock.
- When `StateSaver.save` or a plugin's `onState` threw while a new state variant was committed,
  the rest of the transition was aborted: the state was already visible, but its `enter {}`
  never ran and its runtime was never created, so every later `launch {}` in that state failed
  with "State scope is not found". Such errors are now reported to the exception handler and the
  transition finishes. The same applies to `onEvent`: the handler that emitted the event
  continues. `onAction` and `onStart` failures still abort as before (nothing is committed yet).

Statecharts (`koma-statechart`)

- The zero-delay timer loop check approximated what a firing enters (target, its descendants,
  its ancestors) and so missed loops through a parallel state re-entered from one of its
  regions and through a history state, while rejecting a valid chart whose timer entered a
  compound state with a zero-delay timer in a child that is not its initial one. It now follows
  the real entry set from every reachable configuration.
- A timer whose step failed (an exit or enter hook or an effect threw) was silently spent: it
  never fired again but stayed listed in `ChartState.timers.running`, so a restored Store would
  have restarted it while the live one did not. Such a timer is now removed from the running
  timers when the error is reported.
- When an enter hook threw on a fresh start, the Store stayed in the initial configuration
  without any activation, so its activities never ran and its timers never fired. The
  activations, activities and timers of the initial configuration now exist even when a hook
  failed; the failed hooks' context changes and launches are dropped, and the error reaches
  `recover {}` after the state is committed.

Startup retry (`koma-core`, `koma-message`, `koma-test`)

- A startup whose initial `enter {}` failed was retried on the next dispatch, and the retry ran
  every plugin's `onStart` again: `receiveMessages {}` subscribed a second time, so every message
  was handled (and dispatched) twice after one transient failure, and `StoreRecorder` recorded the
  start state once per attempt. Plugins are now started once per Store; only when a plugin's own
  `onStart` throws does the retry start the plugins again.

Logging (`koma-logging`)

- A `Logger` that threw (or a state whose `toString()` threw) aborted the action or transition
  being logged; after a variant change the new state was left half-entered (see the core fix
  above). `simpleLogging` now reports logger exceptions to the exception handler and lets the
  Store continue.

Compose (`koma-compose`)

- The narrowed `ViewStore` of `stateContent<S2> {}` remembered the last `S2` state only once
  `state` had been read while the Store was in `S2`. A callback that read `state` only when
  invoked (a click handler) still threw `ClassCastException` after the Store moved on. The last
  `S2` state is now captured when the narrowed `ViewStore` is created. (Not run locally: the
  Compose JVM tests need artifacts from Google's Maven repository, which this environment's
  network policy denies; CI runs them.)

## Fixed in the third round

The third round targeted long-lived, application-scope Stores: a multi-threaded soak test
(`StoreSoakJvmTest`: eight threads dispatching through variant changes, recover handlers,
cancelled launches and transactions) found no lost update, deadlock or growth of internal
bookkeeping. The findings below came from probing lifecycle edge paths.

Core (`koma-core`)

- A `CancellationException` thrown by user code was swallowed everywhere: an expired
  `withTimeout {}` in a handler, a launch or a transaction ended the work silently, without
  `recover {}` or the exception handler, and a transaction so cancelled let its caller continue
  as if it had committed. Only the cancellation of the current coroutine (the Store closing, a
  state exiting) is now non-recoverable; any other `CancellationException` is a failure of that
  handler. Under `PluginExecutionPolicy.Concurrent` a plugin hook timing out no longer ends its
  own coroutine silently either; it fails the step as in registration order.
- A failing initial `enter {}` that no `recover {}` handled left the Store unstarted: every later
  dispatch ran the handler again and dropped its action while it kept failing (with an enter
  loop, that meant a stack overflow on every dispatch). Startup now happens once: the error is
  reported, the Store counts as started and the actions are processed, as after a failed
  `enter {}` of a later state. A plugin's `onStart` failure still retries startup.
- When `recover {}` itself threw, the exception it was handling was lost. It is now attached to
  the reported one as a suppressed exception.
- `enter {}` handlers moving to each other forever overflowed the stack, or spun while holding
  the lock when they suspended. A chain of more than 500 entered states in one transition now
  fails with an error naming the loop, and the Store keeps working.

## Open questions

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
- A plugin's `onAction` or `onStart` that throws still aborts the action or the startup (nothing
  is committed yet at that point); `onState` and `onEvent` failures are only reported. A plugin
  whose `onStart` can throw must make its own `onStart` idempotent, since a failed startup runs
  every plugin's `onStart` again.
- Dispatches are processed in dispatch order, but a `transaction {}` from a launched coroutine
  is not ordered against them: it takes the lock whenever it gets its turn.
- `rememberStateSaver()` (rin's `rememberRetained`) needs a `ViewModelStoreOwner` in the
  composition; a plain `Composition` in tests or some desktop hosts has none.
- `Store.patch {}` and `createRecorder()` are for tests and must be called before `start()` or
  the first dispatch: on a multi-threaded dispatcher a patch right after `start()` can still slip
  in before the startup coroutine takes the lock and mutate the plugin list while it is read.
- The message bus is process-wide: a receiver that falls 64 messages behind stalls every sender
  in every Store, not only the ones that talk to it. A `receiveMessages {}` block must never
  wait for its own Store's state (for example `state.first { }` after a `dispatch`): a handler
  of that Store sending more than 64 messages then holds the lock while its own receiver waits
  for it, and the bus is stuck for every Store in the process. Dispatch and return.
- Non-`Exception` throwables (`AssertionError`, `StackOverflowError`, out of memory) are fatal:
  they propagate untouched, so one thrown by `StateSaver.save` or a plugin hook still aborts the
  transition it interrupts.
- `dispatchAndAwait` returns normally when its action was discarded by
  `PendingActionPolicy.ClearOnStateExit` or `clearPendingActions()`; check the state or a
  `StoreRecorder`.
- `Store.state` is a `StateFlow`, so an external collector does not complete on `close()`;
  collect it in a lifecycle scope. `collectState` and `collectEvent` callbacks are cancelled.
- `rememberViewStore` starts the Store from `collectAsState()` before `eventEffect` subscribes,
  so events from the startup `enter {}` race the subscription on `Dispatchers.Default` and are
  lost: another reason to model navigation and errors as state.

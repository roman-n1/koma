# Stability review before production use

- Updated: 2026-09-30

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
- Events emitted from launched coroutines are processed outside the Store lock, so their plugin
  `onEvent` rounds ran concurrently with each other and with the hooks of handlers: a recorder
  with plain lists lost about 1% of 20 000 events from four activities. Plugin hook rounds are
  now serialized by their own lock; a plugin may keep plain state in its hooks.

Statecharts (`koma-statechart`)

- On a fresh start, a failing enter hook of an outer node stopped the loop that created the
  activations, so the inner nodes had none: `startActivities` then failed with a
  `NoSuchElementException` that reached `recover {}` instead of the hook's error, the inner
  activities never ran and their timers stayed listed but never fired. Every active node now gets
  its activation before any hook runs.
- An expired `withTimeout {}` in the step a timer fired left the timer listed as running (the
  round-2 removal only covered other exceptions), and one in an activity or launch ended the
  work silently. Both now follow the core rule: only the task's own cancellation ends it.
- `updateContext` returned `true` when its transform threw; `ChartLaunchScope.isActive` stayed
  `true` after `close()`; `launch {}` called after the enter hook returned was dropped silently.
  Fixed: `false`, `false`, and an error naming the hook.

## Fixed in the fourth round

The fourth round audited the documentation against the behaviour, the test helpers under
misuse, the throughput of the round-3 locks, and ran an end-to-end messenger scenario and
property-based fuzzing of the statechart tooling against the runtime.

Core and helpers

- `dispatchAndAwait`, `startAndAwait` and the suspending `diagnoseActionMatches` called from a
  handler, plugin hook or transaction of the same Store deadlocked it silently and for good: the
  call waits for the lock its caller holds. Such calls now fail with an error naming the
  problem; from a `launch {}` they still work.
- One `StoreRecorder` registered on two Stores lost events (hook rounds are serialized per
  Store, the lists are plain). A recorder now rejects a second Store. `createRecorder()` after
  startup names itself in its error.
- The round-3 plugin lock cost about 80% of the event throughput from launched coroutines
  under contention (four launches streaming events into one plugin: 800k to 140k events/s).
  Hook rounds with no plugins now skip the lock, and a single plugin runs without the
  `coroutineScope`/`async` round. The lock itself stays: correctness first, and 140k events/s
  is far above what a UI consumes. Dispatch throughput (55-60k/s) is unchanged since round 2.

End-to-end messenger scenario (core + statechart + message + logging + test)

- A plugin hook that threw in one round hid that round from the plugins registered after it
  (`coroutineScope` cancelled the sibling hooks; registration order stopped at the failure): a
  recorder or a conformance checker behind a failing analytics plugin missed the state. Every
  plugin now sees every round; the first failure is reported with the others suppressed. As a
  consequence, when a plugin's `onStart` fails, the other plugins' `onStart` still ran, and runs
  again on the retried startup.
- A handler or transaction past its last suspension point when `close()` was called still
  committed: state updated, saver called, plugins run after close. A screen closed and reopened
  at once could restore a snapshot and then have it overwritten by the old Store. Nothing
  commits after close now: the commit checks the handler's own cancellation first.
- In `StateChartStore`, an expired `withTimeout {}` in an initial enter hook was treated as the
  Store's own cancellation, so the initial configuration lost its activities and timers (the
  round-3 fix covered other exceptions only). It now follows the core rule everywhere.
- `koma-message`'s `message()` could not be called from statechart hooks and activities:
  `StoreScope` was sealed, so `ChartHookScope` and `ChartLaunchScope` could not implement it.
  `StoreScope` is now an open marker interface (binary compatible for callers) and the chart
  scopes implement it.

Statechart tooling (property-based fuzzing, 1200 random charts per property)

- The runtime, `validate()`, `instantTimerCycles()`, the path builders and the Mermaid output
  held every property tried (exit and entry order, consistent configurations, history records,
  timers to start and cancel, path replay, Mermaid 11 parsing); no finding there.
- `StateChartConformance` reported false violations in parallel charts: it explained a change
  leaf by leaf, so when one transition of a region exited the whole parallel state it picked a
  region-local transition for the first changed leaf and had nothing left for the others (5 of
  700 random walks). It now asks the runtime first: every guard assignment over the transitions
  matching the action is tried through the runtime's own selection, and the change is explained
  when one ends in the observed leaves.
- It also rejected a transition into a history state that restored what a self-loop had
  recorded, because Koma does not show a self-loop that keeps the leaves. The history such a
  self-loop may have recorded is now kept as a possibility next to the previous one.
- `StateConfiguration`'s hash was the plain sum of its ids' hashes, so states named by a pattern
  (`R1_S2`) collapsed onto a few hash codes and the configuration graph degenerated to
  quadratic time (an 8 x 4 parallel chart took minutes). The hash now mixes each id first.

Documentation

- Two README samples were wrong: `nextState { state.copy(count = loadCount()) }` does not
  compile (`nextState {}` is not suspending), and `launch {}` inside `withContext {}` resolves
  to `CoroutineScope.launch`, so the handler waited for the collection and the Store never
  started. Corrected, with the rule spelled out.
- Corrected README claims: a transaction is not ordered against queued dispatches; launches are
  cancelled on a state *variant* change only; the default launch lane is the action type, not
  the `action {}` block; non-`Exception` throwables are fatal, not "handled" by the exception
  handler. Documented that a launch failing after its state exited is not reported (changed in
  the eighth round: it is reported to the exception handler, not to `recover {}`).

## Fixed in the fifth round

The fifth round was a fresh-eyes review of everything the earlier rounds changed, as if it were
someone else's pull request, plus a soak test in `commonTest` (`StoreSoakTest`) so that the
multi-threaded scenario runs on iOS, JS and Wasm in CI too. Four of its findings were
regressions of this review's own fixes.

Core and companions

- The fail-fast check for awaiting a Store from its own hooks (round 4) did not cover the
  `onEvent` round of an event emitted from a launched coroutine, which runs outside the handler
  coroutines: `dispatchAndAwait` from there still deadlocked (the round holds the plugin lock).
  That round now carries the marker.
- The dispatch-ordering gate (round 2) waited for the predecessor's whole coroutine, so a
  handler that left a child coroutine behind in its own `Job` stalled every later dispatch. The
  gate now waits for the predecessor's work under the lock only.
- With "every plugin sees every round" (round 4), a fatal error from a plugin registered after
  a failing one was attached as suppressed and swallowed. A fatal error from any plugin stays
  fatal.
- The enter-loop guard (round 3) fired before the last entered state got its runtime, leaving
  the Store in a state where every `launch {}` failed. The guard now refuses the next
  transition instead, so the state the Store stays in is fully entered.
- A throwing exception handler (`ExceptionHandler.Rethrow`) was called twice for one saver or
  plugin failure; the error a `recover {}` was handling was lost when the state it moved to
  failed in its `enter {}`. Both fixed.
- `receiveMessages {}` ended its subscription silently on an expired `withTimeout {}` inside the
  block; it now follows the core rule and reports the failure.
- `StateChartConformance`: without a trigger, a timer was credited before an earlier-declared
  action transition, against the class documentation; a region self-loop taken together with
  another region's transition was not covered. The runtime's selection with every guard true
  is tried first now, and the class documentation describes the runtime-first explanation.

## Fixed in the sixth round

The full-library review included inherited code as well as the fork. All 529 original JVM tests
passed before the changes. Additional deterministic regressions reproduced these failures:

- A successful plugin's `onStart` ran again when another plugin failed startup. A message
  subscription therefore delivered each message twice. Only unsuccessful plugin registrations
  now retry, under both execution policies.
- An exit hook completing `NonCancellable` cleanup after `close()` could still commit and save
  the next state. Cancellation is checked again after exit processing.
- Completed tracked launches retained their explicit `LaunchLane` keys until a state variant
  change. Completion now removes its own entry under the Store lock, without removing a newer
  replacement in that lane.
- Restoration checked the names in history records but not their structure. A snapshot from a
  parallel chart restored after its parent became compound could activate two exclusive children
  at once. Shallow and deep records now have to describe valid configurations of their current
  parents; otherwise startup uses the initial configuration and preserves context.
- A chart's raw `recover { nextState { ... } }` could replace configuration or timer tokens
  without running the chart lifecycle. Recovery now permits context updates only; a declared
  action must drive a node change. Invalid recovery is rejected before commit and the original
  error remains attached to the reported failure.
- An activity could enqueue a result while its node was exiting, and that result would run in a
  later activation. Activity dispatch now checks its activation under the Store lock, before
  plugin hooks and handlers, and discards expired actions.
- When the exception handler itself threw while an initial enter event was reported, startup
  called it again. Already-reported failures now propagate without another report.
- A patch could slip in after `start()` or dispatch but before their coroutine ran, or after
  partially successful plugin startup. Patching now closes as soon as startup is requested.

The regression tests live in `StoreShutdownRegressionTest`, `StoreLaunchRetentionJvmTest`,
`StoreStartupRegressionTest`, `StoreStartupExceptionJvmTest`, `MessageDeliveryTest`,
`StateChartRecoveryRegressionTest` and `StateChartActivityDispatchTest`. The final JVM suite has
543 passing tests, including all existing property and multithreaded soak tests.

Cross-platform checks also cover core, message and statechart on Android host, iOS Simulator
Arm64, JavaScript/Node and Wasm/Node (468 tests per target). Node's Mocha timeout now matches the
existing browser limits for core and statechart property tests (JS in this round; Wasm/Node got
the same timeout in the eighth round; the GitHub CI matrix runs the browser targets only). The common close-during-load
test uses an explicit producer barrier: with `ClearOnStateExit`, waiting for a count threshold
could hang on single-threaded dispatchers because the counted actions may all be discarded.
Its producer and Store are now cleaned up even when an assertion or timeout fails.

## Fixed in seventh round (second stability-review wave)

This pass rechecked launch cancellation, transaction reentrancy and the lifetime of statechart
callbacks, then reviewed the surrounding runtime, restoration, conformance, validation, diagram,
Compose, message, logging and test-helper code. Five additional defects were reproduced:

- A cancelled action launch could commit a stale result from `NonCancellable` cleanup even after
  `cancelLaunch(lane)` had applied a newer result. Launch scopes now retain the original job's
  cancellation identity and check it before starting a transaction, in addition to the caller
  job and owning state runtime. Normally completed launches retain their existing callback use.
- An exception thrown while a cancelled launch was cleaning up could invoke `recover {}` over
  the next request's state. Such exceptions now go to the exception handler without recovering
  the cancelled request; queued recovery also checks the original job before it runs.
- Launch event calls from cleanup could reach collectors and plugins after state exit or Store
  closure, or after lane cancellation while the state remained active. They now check the same
  launch lifetime before emitting, including when cleanup uses `NonCancellable`.
- A transaction could call another transaction through its captured launch scope, wait for its
  own lock and leave queued work that ran after the outer call timed out. Both enter and action
  launch scopes now reject reentrant transactions before enqueueing them.
- A callback retaining an old `ChartLaunchScope` could emit an event into a later activation of
  the same node. Chart event calls now check the originating activation, as context updates and
  dispatch already do. Callbacks for the current activation continue to work.

The first four regression tests failed before the core fixes, and the chart regression failed
before its activation check. Six new tests in `StoreLaunchCancellationTest` and
`StateChartActivityEventTest` cover these failures, including both transaction scope types,
event delivery to plugins and collectors, explicit lane cancellation, state exit and close.

Three additional tests cover two tabs displaying separate instances of the same component,
including two tabs for the same chat. `StoreInstanceIsolationTest` checks a shared explicit lane
and the default action-type lane across two Stores. `StateChartInstanceIsolationTest` checks
context, activities, events and timer deadlines with a shared chart definition, then closes one
Store while the other continues. `ViewStoreJvmTest` reorders and closes keyed Compose tabs and
checks their separate ViewStores and retained StateSavers. No additional isolation defect was
found in these scenarios.

Keep each tab's Store and StateSaver owned by that component instance. Repeated Compose content
needs `key(tabInstanceId)`, including when two tabs use the same chat id. A shared chart definition
or lane object is safe; sharing one Store shares its state and close lifecycle. `koma-message`
is deliberately process-wide: include and filter a tab-instance id for tab-specific commands,
or a chat id for domain updates intended for every open view of that chat.

The expanded JVM suite has 552 passing tests. Before the three tab-isolation tests were added,
core and statechart also passed 465 tests on each of Android host, iOS Simulator Arm64,
JavaScript/Node and Wasm/Node, and all five GitHub CI targets passed. The initial local iOS
incremental link hit a Kotlin/Native deserializer error; a full rebuild without the build cache
passed. The final PR revision reruns the full CI matrix with the additional isolation tests.

## Fixed in the eighth round (whole-project review after the sixth and seventh)

Three reviewers re-read the whole project after the sixth and seventh rounds, one each for
core/message/logging/test, statechart and Compose/build/CI/docs, with scratch probes run on the
JVM. The sixth and seventh rounds' changes held up under the probes (lane tracking under load,
partial plugin-start retry, the owner check of launch scopes, recovery validation, restore of
history records of the same chart, gated dispatch). Five defects were reproduced and fixed:

- A restored snapshot whose active nodes were valid was discarded whenever one history record no
  longer fit the chart (a leaf that became compound, a region added to a parallel state, a
  removed leaf): the user landed on the initial screen after an app update although the runtime
  itself tolerates such records. `start()` now keeps the active nodes and drops only the
  unrestorable records (`consistentPart`); the next transition into that history state takes the
  default target. Falling back to the initial configuration still happens when the active nodes
  are not a configuration of the chart.
- A statechart activity or timer step whose failure raced the exit of its node lost the report:
  the failure was rethrown inside a transaction requested from the task itself, and a transaction
  whose caller is cancelled is skipped. Reports are now requested from the chart's own work scope
  (`report`), so they run after the step that exits the node and `recover {}` sees the committed
  state. The timer path reports from inside the transaction that spends the timer, so a queued
  action can no longer cancel the report between the two.
- A core `launch {}` failure whose state exited before the report got the lock vanished (neither
  `recover {}` nor the exception handler), while the failure of a launch that was already
  cancelled was reported to the handler. Both now reach the exception handler; `recover {}` still
  runs only while the state is active.
- `recover {}` ran after `close()` when an `exit {}` finished its `NonCancellable` cleanup and
  then threw: `onErrorOccurred` now checks for cancellation before running the handlers, so
  nothing of the user's runs in a closed Store (the transition could not have been committed).
- `project.group` and `project.version` stayed at `io.github.koma-kt` / `4.0.0` while the
  publications used `io.github.roman-n1` / `4.0.0-sc.1`, so a composite build substituted a
  different "group:artifact" than the published POMs name (the POMs themselves were consistent:
  `koma-statechart-jvm` depends on `io.github.roman-n1:koma-core-jvm:4.0.0-sc.1`, checked with
  `publishToMavenLocal`). The publish convention now sets both from the fork properties.

Smaller items from the same review: `wasmJs { nodejs() }` got the same Mocha timeout as JS; the
`~/.konan` cache key in CI hashed a file that does not exist (`**/.lock`) and now hashes the
version catalog and wrapper properties; the close-mid-work soak test waits until the Store has
processed at least one action of the storm before closing; `Conformance.explain` no longer
returns the configuration it never used; `StoreInternalApi.dispatchIf` documents a throwing
predicate; `createRecorder()`'s message names state collection; `rememberStateSaver` documents
the owners rin needs, the removal rule and `key()` for repeated content; `eventEffect` documents
which `enter {}` events it misses; `CLAUDE.md` names the Gradle tasks that exist (`allTests`,
`jvmTest`, `iosSimulatorArm64Test`; there is no `test` task or iosX64 target); the README sample
`rememberStateSaver<CounterState>()` compiles; the statechart README states the published
coordinates, the `@InternalKomaApi` bridge, the recover-from-outside pattern and the heartbeat
rule; the semantics, roadmap and comparison documents and three ADRs got addenda for what the
rounds changed (timer removal on failure, restore checks, `MessageHub` buffer, report-and-continue
at the persistence and observer boundaries, the `CancellationException` rule).

Reviewed and left as is: the tracked-lane bookkeeping costs one coroutine and one lock
acquisition per launch completion (about 73k launches/s against 173k untracked on the JVM);
`ExceptionHandler.Rethrow` as the default turns a handler report into an uncaught exception, so
the messenger must set a handler; `patch()` after `collectState {}` is rejected only once the
collector has run (it is rejected deterministically after `state.first()`); the messenger example
re-sends a message when `MessageDelivered` arrives while Settings is open (the send should live
in a region that Settings does not exit); rin drags `compose.ui` 1.6.10 into a consumer's graph,
so a JS/Wasm consumer needs a newer Compose Multiplatform of its own; `publish.yml` fires on
pre-releases only and signs only with the Central credentials in the environment.

## Fixed in the ninth round (time-travel foundation: probes, journal, machine, executor)

A fresh-eyes review of what stages 1 to 3 of the
[Time Travel handoff](../design/2026-09-29-time-travel-logging-handoff.md) added: the core
probes, `koma-observability`, the pure machine and its executor. Each defect was reproduced with
a failing test in `MachineStoreTest` before it was fixed, and a multi-threaded soak
(`MachineStoreSoakTest`: eight threads of superseding loads and transition-less ticks on
`Dispatchers.Default`) found no lost update, stale result or leaked bookkeeping.

Executor (`koma-statechart`, `koma.statechart.machine`)

- A command waiting in a `Sequential` or `Parallel` lane whose activation exited was still
  started once the lane freed up: the decision had deregistered it, so its results were ignored
  as stale, but the work ran. Queued entries of exited activations are now dropped with the
  decision.
- `Latest` cancelled the running commands of its lane but left the queued ones, which started
  later and were still registered in the snapshot. `Latest` now supersedes the queue too, and the
  machine hears about each queued command as `CommandAbandoned(Superseded)`.
- `enter {}` and `action {}` handlers registered in the `MachineStore` configuration ran instead
  of the machine's own (first-match order), so a configured handler could commit a snapshot the
  machine never decided. The machine's handlers are registered first now; `recover {}` from the
  configuration still applies and still cannot change the snapshot.
- A `StateSaver` restoring a snapshot of another machine or version started over silently
  (because the snapshot was "already started"). It still starts over with the restored context,
  and the exception handler is told which version was restored into which.

Reviewed and left as is: the journal's critical section spins on `Mutex.tryLock` (no parking
lock in common code); `records()` copies the retained ring under it, so keep the ring small on
hot paths; a `RecordingSession.close()` waits for a sink that never returns; `InputId`s from
concurrent dispatchers are not in processing order (the ordinal is); the executor reports a
handler failure through `PluginScope.launch`, so the journal attributes it to the input being
processed when the launch happened.

## Tenth round: tests for what hands cannot reproduce

Coverage of the time-travel foundation aimed at the scenarios no one reproduces by hand: thread
interleavings, events on the same instant, faults inside the diagnostics, and a first replay.
Each test states an invariant and drives the code with a storm, a seed or an exact schedule.

- `ReplayDeterminismTest` (statechart): a `MachineStore` under a six-thread storm of loads,
  ticks, results and timers on `Dispatchers.Default`, while a `DecisionObserver` records every
  machine input in processing order with the snapshot it produced (the observer's `onCommitted`
  now receives the machine input for that purpose). The recording is then decided again from
  the initial snapshot by the pure machine: every snapshot equal, every ignored input ignored
  again. The first real replay check; it passed unchanged.
- `CloseRaceTest` (statechart): `close()` racing a storm, twenty-five times: the snapshot never
  changes after close, every processing that started finished, every command that started
  ended, no handler body runs in a cancelled scope, nothing runs after `StoreClosed`.
- `TimerExitRaceTest` (statechart): a timer due at the very instant a result exits its source,
  in both orders the scheduler can pick: exactly one wins, the other is ignored with its reason
  (`UnknownTimer` or `StaleCommand`), and a repeat of either changes nothing.
- `FaultInjectionTest` (statechart): a probe, a payload policy, a journal sink and a decision
  observer all throwing at random over three seeds: the committed snapshots equal those of a
  run without any journal, the faults are counted and reported, nothing else changes.
- `JournalConcurrencyTest` (observability): eight publishers against a queue of sixteen and a
  sink that is slow and throws at random: what the writer hands to the sink is in order, every
  hole is preceded by exactly one gap that explains it, published equals delivered plus dropped.
- `StoreProbeTest.manyThreads_withClearsAndAClose_…` (core): eight dispatching threads, handlers
  clearing the queue, a variant change clearing it by policy, a close mid-storm: every accepted
  input ends exactly once, processed or discarded with a reason.

Found and fixed by them:

- `RecordingSession`: when the gap record did not fit the writer's queue but the writer freed a
  slot between the two offers, the record itself went out without the gap in front of it, so a
  sink saw a hole with no explanation until a later record. A record is now dropped together
  with the gap it could not be preceded by, and `close()` sends the final gap and
  `RecordingStopped` with the suspending `send`, so they reach the sinks even when the queue is
  full.

Test-side lessons, recorded so the next tests do not repeat them: wait for the `StoreClosed`
trace before judging what happened around a close (queued coroutines end asynchronously after
`close()` returns); a `withTimeout` inside `runTest` measures virtual time, so waits on real
threads go through `withContext(Dispatchers.Default)`; a test clock over `runTest`'s scheduler
must be relative to its creation when several stores run in one test; a sink that throws
consumes the record it threw on, gap records included, so a journal invariant is about what the
writer delivered, received or not.

## Eleventh round: the executor as data, checkpointed under a storm

The scheduler's bookkeeping of commands left the actor and became `Lanes`, a pure value with
the rules of every lane policy; the actor keeps the jobs, the timers, the last snapshot it
carried out and the commands `ending` (finished here, their last input not yet decided by the
machine). `MachineStore.checkpoint()` is that state as data (`ExecutorCheckpoint`), taken at a
message boundary of the actor and checking itself: every command of its snapshot in exactly one
place. A `koma-timetravel` recording begins at a checkpoint, carries it forward with the same
`Lanes`, trims itself to a live checkpoint (`since`), and a branch runs the lanes with jobs that
end when told to. The tests again aim at what no hand times:

- `LanesTest` (statechart): each policy on admission, a lane freeing its place only when the
  job has ended, an exited activation cancelling the running and dropping the queued.
- `ExecutorCheckpointTest` (statechart): on a machine that fills every kind of lane on entry,
  the partition of the snapshot's commands into running, queued and ending; a checkpoint
  requested behind the decision's turn at the actor, so it still sees the dropped command
  ending; the lane moving on the moment a job ends; timers' remaining time on the checkpoint's
  clock; a re-entry; a pending request failed by `close()`.
- `ExecutorCheckpointStormTest` (statechart): three hundred checkpoints taken while four threads
  re-enter and exit an activation whose entry registers commands under `Latest`, `Sequential`,
  `DropIfRunning` and `Parallel(2)`, with handlers ending at random: each a consistent cut, each
  lane within its policy, nothing queued behind a free lane, revisions never going back, the
  last one empty, nothing reported.
- `CheckpointTest` (timetravel): once the executor has settled, its checkpoint equals the one the
  recording carries forward, after an entry, two completions, a re-entry and an exit; the
  recording shows a dropped command ending until the step that decides its abandonment; a
  recording since a live checkpoint replays without a difference, branches with the checkpoint's
  running and queued commands, and refuses a checkpoint of another run or with forged commands;
  under a storm on `Dispatchers.Default`, two hundred live checkpoints, each the start of a
  recording that replays: whatever the actor's lag behind the store, its cut is one of the run.

Found and settled by them:

- `StoreProbeTest.manyThreads_withClearsAndAClose_…` failed once on the Android host job with an
  accepted input that had no end. A dispatch that arrives after `close()` is discarded from its
  own cancelled coroutine, which needs a thread; on a loaded runner that comes after
  `StoreClosed`, and the test drained the channel once at `StoreClosed`. Measured: 200 of 200
  post-close dispatches trace their discard after `dispatch()` returned. The test now waits until
  every accepted dispatch has its end, bounded so a real gap still fails by name; the `StoreClosed`
  KDoc says a post-close dispatch's traces may follow it.
- A cancelled command holds its place in its lane until its job has ended (the actor promoted
  the next queued command on `Finished`, never on the cancellation). The pure `Lanes` made the
  consequence visible: when an activation is re-entered, its `DropIfRunning` commands are dropped
  because the exited activation's command still occupies the lane. Parity with the actor before
  this round, kept and documented; changing it would start a `Sequential` successor while the
  predecessor's `finally` still runs.
- A branch's `awaiting` includes the commands that are ending, in start order: a live result can
  arrive before the abandonment the executor fed, so the caller may still answer them.

Test-side lessons: an `async` that fails inside `runTest` fails the test before `assertFailsWith`
sees it, so a request that is expected to fail is awaited through `runCatching`; "the storm never
exercised X" is a property of the platform's threads, so it is printed, not asserted (the
tenth round's lesson, relearned); a golden harvested from a JUnit failure message must be cut at
the end of the JSON, as the message carries `expected:<…> but was:<…>` on the same line; two
runs identical in every input give equal checkpoints, as checkpoints are values, not tokens, so
a "foreign checkpoint" comes from a run that differs.

## Twelfth round: the journal on disk, damaged every way a disk can

`koma-observability` gained its file format ([ADR](../adr/2026-09-30-journal-file-format.md)):
segments of checksummed frames over a `SegmentStorage` the platform supplies, a sink that
rotates them, a reader that marks instead of throwing. What hands cannot reproduce here is the
damage: a process dying at every byte of a write, a disk flipping any bit.

- `JournalFileFormatTest.everyCut_ofASegment_…` (observability): a segment with every entry
  kind is cut at every length from 0 to its size; each cut decodes to a prefix of the records
  with a mark, `Unfinished` exactly at the frame boundaries, `TruncatedTail` inside a frame,
  `NotASegment` before the magic ends; never an exception, never a record that was not written.
- `JournalFileFormatTest.everyFlippedBit_…` (observability): every bit of the same segment is
  flipped in turn, eight times the size of the segment decodes; every flip is caught by the
  checksum, the length check or the magic, except the bits of the end frame's trailing bytes,
  and no flip ever yields a wrong record.
- `JournalFileSinkTest` (observability): rotation with the oldest segments deleted and the hole
  marked, a crash three bytes short of a frame losing that frame only, a crash at a boundary
  losing nothing, a damaged frame skipping the rest of its segment while the next segment is
  read with the hole between them marked, a `JournalGap` record explaining a hole and an
  unexplained one marked, pruning that never touches the newest session.
- `FileSegmentStorageJvmTest` and `FileSegmentStorageIosTest`: the same over real files, the
  JVM one with the file cut by `RandomAccessFile.setLength` after a flush.

Found and settled by them:

- A segment cut right after its magic, before the header frame, is a truncation with zero
  trailing bytes, not a corruption: the header was never written. The mark says so.
- The reader's notion of "missing" starts at segment 0: a session's first present segment at a
  higher index means the earlier ones rotated away, which the reader marks; a first draft
  started counting at the first present segment and hid the rotation.
- A `Payload.Retained` object cannot cross a file; it is written as its `toString()` and read back
  as `Payload.Described`, a new variant, so a record read from a file never claims to hold an
  object. `JournalFormat` prints it as the text.

## Thirteenth round: a group cut under a storm, proven by its recording

Stage 6 ([ADR](../adr/2026-09-30-group-replay.md)) joins machines into a `MachineGroup` with a
bridge and a consistent cut, and replays a group in the order its decisions were made. The cut
freezes admission instead of taking locks; whether that is consistent under real
interleavings is exactly what no hand times.

- `GroupCheckpointStormTest` (timetravel): two members exchanging messages under a four-thread
  storm on `Dispatchers.Default`, thirty cuts taken meanwhile. Each cut's recording since it
  (`GroupRecording.since`) must replay without a `GroupMismatch`: every delivery after the cut
  is of a message sent after it or in flight at it. A message sent before the cut and delivered
  after it that the cut had not listed as in flight would be `ReceivedBeforeSent`; a member
  whose checkpoint was not of the run would be refused by `since`. Live went on: every pick
  reached the root and every acknowledgement the picker; no cut timed out.
- `MachineGroupTest` (statechart): a result of a command arriving while its member is frozen
  waits in the controlled queue and is applied once after the cut; a member stuck in a plugin
  hook makes the cut time out, and the group resumes with the held inputs entering in order.

Found and settled by them:

- A cut with a message in flight is produced by the barrier itself: freezing the receiver
  while the sender still finishes what it had accepted delivers the message into a frozen
  queue. The test that first tried to stage it by hand needed the store's internals; the
  barrier's own timing is the honest way and needs none.
- A recording since a cut must carry the cut's messages in flight, or their deliveries look
  unsent. `GroupRecording.inFlight` seeds the causality check.

## Fourteenth round: subscribers that die with effects in hand

The retained mailbox ([ADR](../adr/2026-09-30-effect-mailbox.md)) promises that a retained
effect reaches a subscriber and is acknowledged once, whatever the UI does between. The UI's
worst behaviour is to be recreated mid-handling: the subscriber that took the effect is
cancelled before it acknowledged, while the next subscriber is already collecting.

- `EffectMailboxStormTest` (statechart): four hundred retained effects dispatched from four
  threads on `Dispatchers.Default`, three subscribers that each live a few milliseconds,
  acknowledge some of what they take and are cancelled with the rest in hand, until every
  effect is acknowledged. Every effect acknowledged exactly once, none lost, none discarded,
  one `EffectQueued` and one `EffectAcknowledged` per effect in the journal with its
  `EffectHandlingStarted` attempts counting up, the mailbox empty at the end.

Settled by it: the doorbell of the mailbox is read before the queue is checked, so a ring
between the two is not missed and no subscriber waits for an effect that is already there; a
subscriber's cancellation releases what it held under the lock and rings, so the next one
takes it at once.

## Fifteenth round: a source that never stops feeding, cut thirty times

External sources join the cut ([ADR](../adr/2026-09-30-external-sources-in-the-cut.md)): paused
before the members are frozen, snapshotted after they settle. Whether the pause and the freeze
line up so that the snapshot is the members' view of the source is a question of interleaving.

- `ExternalSourceCutStormTest` (timetravel): a paged source feeds a member from its own thread
  while four threads dispatch and thirty cuts are taken. At every cut the source's snapshot
  (its pages fed) equals the pages the member's snapshot had decided, and the group's
  recording since the cut replays without a mismatch, `UnknownSource` included. A source
  mid-feed at the snapshot, or a cut that snapshotted before the members drained what the
  source had fed, would show as a page counted on one side only.

Settled by it: the order of the cut. Sources pause first; then the members freeze; a source
paused after the freeze could still have fed into a frozen gate, and its snapshot would count a
page the member had not decided.

## Sixteenth round: a recording written under a storm through a queue too small

Recordings go to disk as they happen ([ADR](../adr/2026-09-30-recording-files.md)); the writer
sits behind a bounded queue and the ring drops old segments. The promise: whatever was dropped
or rotated away, the files hold a tail of the run that begins at a checkpoint and replays.

- `RecordingFileStormTest` (timetravel): six threads storm a `MachineStore` recorded by both the
  in-memory recorder and a file sink with a queue of thirty-two and a ring of four segments of
  sixteen kilobytes. Read back, the files hold exactly the recorder's steps from some index on,
  the range begins with the checkpoint the recorder carries at that index, and it replays.
  Written plus dropped equals recorded.
- `RecordingFileFormatTest` (timetravel): every cut of a segment is a prefix of its steps with
  a mark, every flipped byte is caught.

Settled by them: a dropped step must not leave a segment whose steps do not lead to the next
one; the sink begins a new segment at the next step with the checkpoint that already includes
the dropped one, so the reader sees a hole, not damage, and a range after it. And a range that
ends in damage at the last segment is still the last range: a first draft of the reader
forgot it when it stopped continuing.

Found by CI, not locally: the storm read the in-memory recorder as soon as the state settled,
but a Store publishes a state before its plugins run, and the decision observers run from a
plugin, so on a two-core runner the recorder's snapshot lacked a step the file sink went on
to write. A test that compares two observers reads them only once every input the Store
accepted has finished (`ProcessingFinished`, counted through a probe the way the machine
store's own idle gate counts); a snapshot taken at the settled state is one decision early.
Every test task now logs a failed test's assertion and stack, so the next such failure can be
read from the CI log.

The group's files remember cuts (ADR addendum): `GroupRecordingFilesTest` checks that the run
since a cut read from the files equals the in-memory recording since the same cut, sources'
snapshots included, and that a ring which kept only the segments since the cut reads the run
from it.

## Open questions

Known behavior that is by design or needs a decision; take it into account when writing
messenger features.

- Store events use an unbuffered `SharedFlow` (see the event SharedFlow ADR): events emitted with
  no collector are lost (from the startup `enter {}` before `eventEffect` subscribes, and from
  the `enter {}` of a state when `eventEffect` sits inside that state's `stateContent` block, which
  is composed only after the commit), and a slow collector blocks the Store while it holds its
  lock. Keep `eventEffect` at screen level and model anything that must not be lost (errors,
  navigation) as state.
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
  whose `onStart` can throw after launching work must clean that work up or make its own retry
  idempotent. Successful plugin registrations are not restarted.
- Dispatches are processed in dispatch order, but a `transaction {}` from a launched coroutine
  is not ordered against them: it takes the lock whenever it gets its turn.
- The fail-fast check for `dispatchAndAwait`, `startAndAwait` and `transaction` is carried by the coroutine
  context, so a coroutine created from a handler with `CoroutineScope(currentCoroutineContext()
  + Job())` is rejected too although it would not deadlock; use `launch {}` for work started
  from a handler.
- `rememberStateSaver()` (rin's `rememberRetained`) needs a `ViewModelStoreOwner` and
  `LifecycleOwner` in the composition; a plain `Composition` in tests or some desktop hosts has
  neither.
- `Store.patch {}` and `createRecorder()` are for tests and must be called before startup is
  requested by `start()`, the first dispatch or state collection.
- The message bus is process-wide: a receiver that falls 64 messages behind stalls every sender
  in every Store, not only the ones that talk to it. A `receiveMessages {}` block must never
  wait for its own Store's state (for example `state.first { }` after a `dispatch`): a handler
  of that Store sending more than 64 messages then holds the lock while its own receiver waits
  for it, and the bus is stuck for every Store in the process. Dispatch and return.
- The GitHub CI matrix runs the JS and Wasm tests in a browser (Karma) and never `jsNodeTest` or
  `wasmJsNodeTest`, never compiles `iosArm64` (the device architecture is first built by
  `publish.yml`), and runs no Android device tests. Since the tenth round CI runs `apiCheck`
  (binary-compatibility-validator, JVM and klib dumps under `*/api/`), so a change of the public
  surface, such as the abstract member `StoreInternalApi` gained in the sixth round, is a
  deliberate `apiDump` in the same change.
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
- `toMermaid()` leaves out a top-level state that no transition, initial or history default
  mentions (`validate()` reports it as unreachable). Declaring it would break the diagram's
  stability property (appending a transition only appends a line), so it stays out.
- The configuration graph behind `validate()`, the path builders, `reachableStates()` and the
  zero-delay timer check enumerates every reachable configuration, which grows exponentially
  with the number of parallel regions (8 regions of 4 states: 65 536 configurations). Keep
  charts that go through these tools to a handful of regions.
- `instantTimerCycles()` ignores guards, so a guarded zero-delay retry loop
  (`Retry --after 0--> Retry [attempts < 3]`) is rejected at Store construction; use a small
  positive delay for immediate retries.
- `StateChartConformance` credits a timer firing to the first declared timer of the source when
  several timers with different guards share it (documented as "first such timer"); the coverage
  report then lists the other timer as uncovered although it fired. A snapshot of an older chart
  restored by `start()` is reported as `UndeclaredState` plus an `UndeclaredTransition` from the
  vanished state to the initial configuration; the second is noise.
- A self-loop timer exits and re-enters its source, as SCXML requires, so an `activity` of that
  node restarts on every firing, and since the sixth round an action the activity dispatched
  just before the firing is discarded with its activation (a disconnect noticed as the heartbeat
  fires is lost and the node stays put; pinned by
  `anActionQueuedByAnActivityBeforeASelfLoopTimerFires_isDiscarded`). Keep a heartbeat timer in
  its own region, apart from a socket reader. Gating on "the node is still active" instead of
  the activation would keep such actions but let an action from a previous activation through
  after a quick exit and re-entry; a decision for the messenger's design.
- An invalid `recover {}` in a `StateChartStore` (one that changes configuration or timers) is
  reported as the `IllegalArgumentException` of the validation with the original failure attached
  as a suppressed exception; a logger that does not print suppressed exceptions shows only the
  validation message.
- Collectors and `currentState` see a new state before `StateSaver.save` runs; a process death
  in that window persists the previous state after the UI reacted to the new one.

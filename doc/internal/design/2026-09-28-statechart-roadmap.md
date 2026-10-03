# Statechart layer on top of Koma: roadmap

- Updated: 2026-10-01 (plan B dropped; full divergence inventory vs 4.0.0; version 5.0 decision; 2026-09-28: phase status after wave 6)

## Background

Koma 4.0 already works as a flat state machine over sealed classes. It has
`state<S2> { enter / action<A2> / exit / recover<T> }`, first-match in registration
order, effects with state lifetime (`launch`, `LaunchControl`),
business errors as transitions (`recover {}`) and `Plugin` for observation.

What is missing for Harel statecharts:

- hierarchy: a phase change is determined only by `state::class != nextState::class`
  (`StoreImpl.onActionDispatched` / `onStateChanged`), so a transition between
  child variants of one sealed parent does exit/enter "wholesale", without LCA;
- parallel (orthogonal) regions and history;
- a guard as a separate entity (the condition lives in the handler body);
- **a transition as data**. The handler registry in `StoreBuilder` is private
  lists of "predicate lambda + handler lambda" pairs. The types `S2` / `A2` exist
  only inside the reified predicate `it is S2` and never get out.

Because of the last point, introspection, validation,
visualization, transition coverage and model-based testing are impossible today.

## Policy

### Do not rewrite Koma

- The statechart is an optional layer in a separate module. The existing `Store` API and
  semantics do not change.
- `Koma State != statechart node`. The layer uses composition: `Store`
  remains the source of `StateFlow` for the UI, and the statechart is responsible for the structure
  of transitions.
- The statechart is applied only where there is `State + Event -> Transition`.
  Ordinary data (`toolbarTitle`, `scrollPosition`, input field values) is not
  turned into nodes.

### DSL != Runtime

```text
Statechart DSL
      |
      v
StateChartDefinition (immutable data)
      |
      +--> Validator
      +--> Exporter (Mermaid / DOT)
      +--> Test path generator
      +--> Runtime --> Koma Store adapter
```

The DSL builds an immutable model; the runtime executes it. Everything the tools
need (validation, diagrams, coverage) is taken from the model without runtime
reflection.

### Semantics before API

The hardest part is deterministic semantics: the exit/enter order by LCA,
transition priority, guard order, simultaneous transitions in regions, coroutine
lifetime, exceptions, persistence. Each phase begins with
semantics-first tests; the public API is polished last. Do not aim straight for
`StateChartDefinition<S, A, E, ...>` with many generic parameters.

Expected orders:

```text
Connected / ChatOpened -> Disconnected
  exit ChatOpened, exit Connected, enter Disconnected

Connected / Idle -> Connected / ChatOpened
  exit Idle, enter ChatOpened
```

## Phases

### Status (after wave 6)

All phases 0–7 are done in the fork; phase 8 partially. Numbers are PRs in `roman-n1/koma`.

| Phase | What | PR | Status |
|---|---|---|---|
| 0 | Architectural contract (this document) | #1, #6 (fork path) | merged |
| 1–2 | Model, validation, Mermaid | #5 | merged |
| 3 | Flat runtime | #7 | merged |
| 8 (part) | Conformance plugin; shortest paths and coverage paths | #8, #9 | merged |
| 5 | Hierarchy (compound, LCA) — wave 1 | #10 | merged |
| 7 | History (shallow/deep) — wave 2 | #11 | merged |
| 6 | Parallel regions — wave 3 | #12 | merged |
| — | Timers `Trigger.After` — wave 4 (there was no phase for it in the original plan) | #13 | merged |
| 4 | `StateChartStore` adapter — wave 5 | #14 | merged |
| — | "Messenger" example as a test, `koma-statechart/README.md`, comparison with koma-strict — wave 6 | #15 | merged |

Core steps 1–3 from the upstream strategy are #2, #3, #4 (merged into the fork). The semantics and decisions of each
wave are in [`2026-09-28-statechart-semantics.md`](./2026-09-28-statechart-semantics.md), sections
"Wave 1–5 decisions". Phase 4 was done last rather than fourth: the adapter is easier to write once the
semantics of hierarchy, regions, history and timers are already fixed.

Not done from phase 8: invariant checking, shrinking of a failing sequence, debug
timeline (see "Remaining work").

### Phase 0 — architectural contract

This document. No code.

### Phase 1 — introspectable model

A new KMP module `koma-statechart` (modeled on `koma-test`: android, iosArm64,
iosSimulatorArm64, jvm). In the first iteration, one module with the packages `model`,
`validation`, `tooling`, `runtime`, `dsl` instead of six separate artifacts.

Minimal model: `StateId` (value class), `StateNode` / `AtomicState`,
`Transition(source, target, on: ActionMatcher)`, `StateChartDefinition`.
`ActionMatcher` carries a `KClass<out Action>` and a stable name, because
`qualifiedName` is not available on all KMP targets. The dependency on `koma-core`
is only for the marker interfaces. Unit tests.

### Phase 2 — validation and Mermaid

Duplicate IDs, transitions to nonexistent states, unreachable states,
missing initial, conflicting transitions without guards. Export to Mermaid
`stateDiagram-v2`.

### Phase 3 — flat runtime

A pure function `step(configuration, action) -> TransitionResult` with metadata
of the transition that fired. No coroutines and no Koma.

### Phase 4 — integration with Koma

An adapter that assembles a `Store` from the definition. The preferred variant, without
core changes: a single catch-all `state<S> { action<A> { runtime.step(...) } }`.
The Koma state keeps the active configuration as a field. The transition trace for
coverage is captured through `Plugin` and `koma-test` (`dispatchAndAwait`,
`StoreRecorder`).

### Phase 5 — hierarchy

Compound states, initial transitions, the LCA exit/enter algorithm. If the layer keeps
the Koma state as a single class, Koma itself does no exit/enter, and then the
coroutine lifetime of substates is managed by the layer.

If this runs into limitations, the minimal hook in the core is a "phase key"
strategy `(S) -> Any` (default `it::class`) instead of `state::class` in
`StoreImpl`, marked `@ExperimentalKomaApi`. It touches
`stateRuntimes`, `PendingActionPolicy` and `LaunchControl`, so only through a
separate ADR.

### Phase 6 — parallel regions

`ParallelState` / `Region`, the configuration as a set of active leaves,
a deterministic order of region processing.

### Phase 7 — history

Shallow / deep history.

### Phase 8 — tooling

Path generation for model-based testing, transition coverage, invariant
checking, shrinking of a failing sequence, debug timeline.

## Definition of Done for the first milestone (Phase 1–2)

- the existing Koma API is unchanged;
- the model is immutable, transitions are first-class objects, state IDs are stable;
- the model can be traversed in full;
- a flat graph can be described and its unreachable states found;
- the Mermaid export is built without runtime reflection;
- there are unit tests.

## Upstream strategy: small steps

The goal is for the Koma author (`koma-kt/koma`) to accept changes one at a time. For
that, each step:

- is useful to Koma on its own, even if the statechart layer never appears;
- builds on what the author has already written down in `doc/internal/`;
- is additive and does not change the behavior of existing Stores;
- starts as a feature request and only after agreement turns into a
  small PR.

Order and placement:

| # | Step | Where | Benefit for Koma on its own | What it gives the statechart layer |
|---|---|---|---|---|
| 1 | Keep matcher metadata (`StateType(S2::class)` / `AnyState`, `ActionType(A2::class)` / `AnyAction`) next to the predicates in the `StoreBuilder` registry. The public API does not change. | core, internal | The basis for the routing diagnostics the author proposes in `notes/2026-04-25-unhandled-action-behavior.md` (the section on `build()` checks names exactly this as the obstacle) | The first data about the structure: which state and action types are declared |
| 2 | Routing diagnostics in `:koma-test`: `diagnoseActionMatches`, asserting the number of matches on dispatch | `koma-test` | Exactly items 2–3 from the same note by the author | Checking that a transition in the model and a handler in the Store agree |
| 3 | A read-only description of all handlers (`describeHandlers()`); in the core only an `@InternalKomaApi` method, the public API in `koma-test`, because the author prefers not to widen the public surface of `koma-core` | `koma-test` + core internal | Store documentation, handler coverage in tests | Coverage checking and a first visualization without our own DSL |
| 4 | The reason for a state change, for observers (action / enter / launch transaction / recover) | core, contentious | Debug timeline and trace (upstream #189), `receiveTransition` in the test driver (upstream #176) | Transition metadata for coverage and MBT |
| 5 | The `koma-statechart` module: model, validation, Mermaid, path generation | separate module (in the fork) | — | Phases 1–2 and part of Phase 8 |
| 6 | Runtime and adapter to Store through the ordinary DSL | separate module | — | Phases 3–4 |
| 7 | Opt-in hierarchical state scopes: `enter` / `exit` / `launch` of a sealed parent survive transitions between its child variants (LCA over the sealed hierarchy) | core, RFC + ADR | Today `state<Parent> { enter {} }` restarts on every change of child variant, which is inconvenient even without statecharts. Shaped as a policy enum, following `doc/internal/adr/2026-05-07-runtime-policy-enum.md` | Phase 5 without our own coroutine management |
| 8 | Parallel regions, history | separate module | — | Phases 6–7, not proposed for the core |
| 9 | RFC "optional introspectable statechart model for Koma": the module as a companion or an external artifact | upstream issue | — | Official status for the layer |

Notes on risks:

- Step 4 contradicts the author's current position: in
  `notes/2026-05-02-plugin-design.md` a Plugin observes only the Store's boundaries
  (input and output), and there is no separate hook for a type change. So step 4 comes
  after 1–3 and is proposed as data, not as a new hook. If the author
  declines, the layer correlates `onAction` → `onState` itself (under one mutex) or
  provides the metadata from its own runtime.
- Step 7 changes scope semantics, so only opt-in and only after a
  separate ADR.
- Steps 5, 6 and 8 require nothing from upstream and proceed in the fork in parallel
  with 1–4.
- The upstream issue numbers (#175, #176, #189) are taken from the handoff and the author's
  notes and were not verified in this session.

### Plan B (dropped 2026-10-01)

Roman's decision (2026-09-28) was to send only the request for step 1 upstream and to keep
`koma-statechart` on the public API of Koma 4.0.0, so that the layer would work on unmodified
Koma. Dropped on 2026-10-01: the layer requires the fork's `koma-core`. It uses the
`@InternalKomaApi` bridge (`StoreInternalApi.dispatchIf`, `StoreBuilder.validateRecovery`), the
`StoreProbe` that `MachineStore`'s idle gate and `koma-observability` are built on, the public
`InputId`, and `StoreScope` opened for the chart scopes; `koma-logging` depends on
`koma-observability`. So the fork is published as one set of modules under `io.github.roman-n1`,
and the way back to upstream is per row of the divergence inventory below, not per module.

Order of work (2026-10-01): messenger-facing changes first; upstream changes are prepared in
parallel as small steps and sent without waiting for the author's answer to earlier ones.

Draft of the first feature request (step 1):
[`notes/2026-09-28-upstream-fr-handler-matcher-metadata.md`](../notes/2026-09-28-upstream-fr-handler-matcher-metadata.md);
the series that follows it: [`notes/2026-10-01-upstream-series.md`](../notes/2026-10-01-upstream-series.md).

## Using the fork in the messenger and the way back to upstream

Roman's goal: connect the fork `roman-n1/koma` to his KMP messenger now,
and return to official Koma as the author accepts the requests. The rules
follow from this.

### Rules for the fork

- **Additive, one step per PR.** `koma-core` changes in the fork are additive and each is its own
  commit, so a row of the inventory can be replaced by upstream's version of the same change or
  dropped on its own.
- **The statechart layer only in its own modules.** `koma-statechart`, `koma-observability`,
  `koma-timetravel`, `koma-timetravel-compose`, `koma-statechart-test` and
  `koma-statechart-compose` are additive modules; they are not divergence, and they are built and
  published only with the fork's `koma-core`.
- **The inventory is complete.** The tables below are the complete list of what the fork changed in
  the modules upstream owns. A row leaves when upstream ships the same change (on the next tag
  merge) or when the fork removes it; both happen in the PR that does the merge or the removal.
- **Fork-only means fork-only.** A row marked `fork-only by design` is not proposed upstream; its
  "removable when" names the alternative that would let the fork drop it.

### Divergence inventory vs upstream 4.0.0

Columns: what changed; where (files, symbols); since (stability-review round, commit); the
regression tests that pin it; upstream status (`not proposed`, `prepared: upstream-pr/<topic>`,
`issue #N`, `PR #N`, `merged in <tag>`, `declined`, `fork-only by design`); removable when.
The rounds are sections of the [stability review](../notes/2026-09-29-stability-review.md); the upstream series and its
status live in [`notes/2026-10-01-upstream-series.md`](../notes/2026-10-01-upstream-series.md).

#### (a) Stability fixes in `koma-core`

Tests are in `koma-core/src/commonTest/kotlin/koma/core/` unless marked JVM (`src/jvmTest`).
KDoc that documents these contracts (`Plugin.kt`, `Store.kt`, `StateSaver.kt`, `ExceptionHandler.kt`,
`StoreScope.kt`) travels with the row it belongs to.

| # | Change | Where | Since | Regression tests | Upstream status | Removable when |
|---|---|---|---|---|---|---|
| a1 | `state.first()` / `take(n)` no longer hang: collected in the caller's coroutine, startup requested after the first value | `StoreImpl.state.collect` | [round 1](../notes/2026-09-29-stability-review.md#fixed), 80b2018 | `StoreRegressionTest.stateFirst_returnsOnceThePredicateMatches` | prepared: `upstream-pr/state-first-hang` @ 481ae37 (U1) | merged |
| a2 | A throwing `exit {}` keeps the state's runtime; `ClearOnStateExit` clears pending actions before the commit | `processStateExit`, `commitTransition` | round 1, 80b2018 | `StoreRegressionTest.exitException_recoveredInPlace_keepsTheStateRuntimeUsable`, `clearOnStateExit_keepsActionsDispatchedByPluginsForTheNewState` | prepared: `upstream-pr/exit-failure-keeps-runtime` @ 7968e5c (U2) | merged |
| a3 | Actions are processed in dispatch order on multi-threaded dispatchers; a handler leaving a child coroutine behind does not stall later dispatches | `launchDispatch`, `lastDispatchDone`; `Store.dispatch` KDoc | [round 2](../notes/2026-09-29-stability-review.md#fixed-in-the-second-round), 4d4edcd; [round 5](../notes/2026-09-29-stability-review.md#fixed-in-the-fifth-round), 4e92b04 | `StoreMultiThreadedTest.dispatches_areProcessedInDispatchOrder`, `StoreRegressionTest.aHandlerLeavingAChildCoroutineBehind_doesNotStallLaterDispatches`, `StoreSoakJvmTest.dispatchJobs_areNotRetainedThroughTheOrderingChain` (JVM) | planned: `upstream-pr/dispatch-order` (U4) | merged |
| a4 | A cancelled launch never commits, emits or recovers, also from `NonCancellable` cleanup; nested transactions are rejected | `canRunLaunchOperation`, both launch scopes' `transaction`/`event` | round 2, 4d4edcd; [round 7](../notes/2026-09-29-stability-review.md#fixed-in-seventh-round-second-stability-review-wave), f08dcd4 | `StoreRegressionTest.cancelPrevious_skipsATransactionQueuedByTheCancelledLaunch`, `StoreLaunchCancellationTest` (five tests) | planned: `upstream-pr/cancelled-launch-transaction` (U5) | merged |
| a5 | A failing `StateSaver.save`, `onState` or `onEvent` is reported and the transition finishes | `processStateChange`, `processEventEmit`, `reportWithoutAborting` | round 2, 4d4edcd; round 5, 4e92b04 | `StoreRegressionTest.stateSaverException_onVariantChange_stillEntersTheNewState`, `pluginOnStateException_onVariantChange_stillEntersTheNewState`, `pluginOnEventException_doesNotAbortTheEmittingHandler` | planned: `upstream-pr/observer-failure-continues-transition` (U7) | merged |
| a6 | Plugins' `onStart` runs once, only failed hooks retry; completed launches release their lanes | `processPlugins(starting)`, `startedPluginIndices`, `trackActionLaunch` | round 2, 54b1ed6; [round 6](../notes/2026-09-29-stability-review.md#fixed-in-the-sixth-round), 90cafce | `StorePluginExceptionTest.pluginOnStartException_retriesInitializationOnNextDispatch`, `StoreLaunchRetentionJvmTest` (JVM) | not proposed | merged or declined |
| a7 | A `CancellationException` from an expired timeout is a handler failure; a failing initial `enter {}` counts as started; `recover {}` keeps the original error as suppressed; an enter chain longer than 500 states fails instead of looping | `rethrowIfNonRecoverable`, `onErrorOccurred`, `MAX_ENTER_CHAIN` | [round 3](../notes/2026-09-29-stability-review.md#fixed-in-the-third-round), 5366bd9; round 5, 4e92b04 | `StoreRegressionTest.expiredWithTimeout_*`, `failedInitialEnter_isReportedOnceAndTheStoreProcessesActions`, `recoverThatThrows_keepsTheOriginalErrorAsSuppressed`, `enterLoop_failsWithAnErrorInsteadOfOverflowingTheStack`, `enterLoopGuard_leavesTheStoreInAUsableState`, `aFailingEnterOfTheRecoveredToState_keepsTheOriginalErrorAsSuppressed`, `pluginHookTimingOut_failsTheActionUnderBothPolicies` | planned as an issue only (U8: a semantic choice) | merged or declined |
| a8 | Plugin hook rounds never overlap; fast path for zero or one plugin | `pluginMutex` | round 3, 3dbaf71 | `StoreMultiThreadedTest.pluginHookRounds_neverOverlap_evenForEventsEmittedFromLaunches` | planned: `upstream-pr/serialized-plugin-rounds` (U6) | merged |
| a9 | Every plugin sees every round even when one fails; a fatal error stays fatal | `hookFailure` | [round 4](../notes/2026-09-29-stability-review.md#fixed-in-the-fourth-round), 916c19c; round 5, 4e92b04 | `StoreRegressionTest.aFailingPluginHook_doesNotHideTheRoundFromOtherPlugins`, `aFatalErrorFromASecondPlugin_staysFatal` | planned: `upstream-pr/every-plugin-every-round` (U6b) | merged |
| a10 | `dispatchAndAwait` / `startAndAwait` from inside the Store's own handler or `onEvent` round fail fast instead of deadlocking | `InsideStore` context element, `checkNotInsideThisStore` | round 4, 26832ec; round 5, 4e92b04 | `StoreRegressionTest.awaitingTheStoreFromInsideItsOwnHandler_failsFastInsteadOfDeadlocking`, `awaitingTheStoreFromAnOnEventRoundOfALaunchedEvent_failsFast` | planned with the `awaitIdle` issue | merged |
| a11 | Nothing commits after `close()`; `recover {}` does not run after close; an exit failing after close does not recover | `commitTransition` (`ensureActive`), `onErrorOccurred` | round 4, 916c19c; round 6, 90cafce; [round 8](../notes/2026-09-29-stability-review.md#fixed-in-the-eighth-round-whole-project-review-after-the-sixth-and-seventh), ff28f1c | `StoreCloseJvmTest.nothingCommitsAfterClose` (JVM), `StoreShutdownRegressionTest.closeDuringNonCancellableExitDoesNotCommitOrSaveTheTransition`, `StoreRegressionTest.anExitFailingAfterClose_doesNotRunRecover` | prepared: `upstream-pr/no-commit-after-close` @ ce615da (U3) | merged |
| a12 | `patch {}` is rejected once startup is requested; the exception handler is not called twice for one error | `isStartupRequested`, `InternalError.reported` | round 6, 90cafce | `StoreStartupRegressionTest.patchIsRejectedAsSoonAsStartupIsRequested`, `patchIsRejectedAfterPartialPluginStartup`, `StoreStartupExceptionJvmTest` (JVM) | not proposed | merged or declined |
| a13 | A launch failing while its state exits reaches the `ExceptionHandler` | `executeLaunchInStateRuntime` | round 8, ff28f1c | `StoreRegressionTest.aLaunchFailingWhileItsStateExits_reachesTheExceptionHandler` | not proposed (may join U5) | merged or declined |
| a14 | Soak tests that back the fixes above without mapping to one | — | round 5 | `StoreSoakTest`, `StoreSoakJvmTest` (JVM) | travel with the rows | — |

#### (b) Observation hooks in `koma-core` (all `@InternalKomaApi`)

| # | Change | Where | Since | Regression tests | Upstream status | Removable when |
|---|---|---|---|---|---|---|
| b1 | `StoreProbe`: every accepted, discarded and processed input with its outcome, commits, events and failures; `currentInputId()`; input ids carried in the coroutine context | `StoreProbe.kt` (`StoreProbe`, `StoreTrace`, `InputKind`, `DiscardReason`, `ProcessingOutcome`), `InputOrigin` in `StoreImpl.kt`, `StoreBuilder.probe`, `StorePatchBuilder.probe`, `StorePatch.probes` | [round 9](../notes/2026-09-29-stability-review.md#fixed-in-the-ninth-round-time-travel-foundation-probes-journal-machine-executor), ccc1c1e, 2e1331a, 90a7e02, 1ccdabd | `StoreProbeTest` | planned: issue "StoreProbe", then `upstream-pr/store-probe` (a) and `upstream-pr/store-probe-correlation` (b) | merged; used by `koma-observability` (journal); `MachineStore` uses it only for the completion of close, not for `awaitIdle` |
| b2 | Matcher metadata next to the handler predicates; the old-signature constructors of `StateHandler` and `ThreadedHandler` stay public so inline code compiled against 4.0.0 keeps working | `HandlerMatcher.kt`, `StoreBuilder.StateHandler.matcher`, `ThreadedHandler.inputType` | ae8f313 (roadmap step 1) | `StoreHandlerRegistryTest`, `StoreHandlerRegistryPropertyTest` | issue koma-kt/koma#280 (open, no answer); prepared: `upstream-pr/handler-matcher-metadata` | merged |
| b3 | `StoreInternalApi.matchActionHandlers` and `diagnoseActionMatches` in `koma-test` | `StoreInternalApi.kt`, `koma-test/ActionMatchDiagnostics.kt` | 8a03bda (step 2) | `ActionMatchDiagnosticsTest` | not proposed until #280 lands | merged or declined |
| b4 | `StoreInternalApi.handlerMetadata` and `describeHandlers` in `koma-test` | `StoreInternalApi.kt`, `koma-test/StoreHandlers.kt` | 48af6ac (step 3) | `StoreHandlersTest` | not proposed until #280 lands | merged or declined |
| b5 | `StoreInternalApi.dispatchIf(action, isValid)`: the predicate runs under the lock before `onAction`, so an activity's action queued while its node exited is discarded as stale | `StoreInternalApi.kt`, `StoreImpl.kt`; used at `StateChartStore.kt` (`ChartLaunchScope.dispatch`) | round 6, 90cafce | `StoreProbeTest.dispatchIf_withAFalsePredicate_isDiscardedAsStale_andConsumesNoOrdinal`, `StateChartActivityDispatchTest` | fork-only by design | an `ActivityDispatch(action, activation)` envelope through plain `dispatch()`, unwrapped by the chart's `action<Action>` handler, replaces it |
| b7 | `StoreInternalApi.awaitIdle(timeout): StorePendingWork` and the `pendingInputs` counter next to the input traces; `StateRuntime.subscriptions` | `StoreInternalApi.kt`, `StoreImpl.kt` | 2026-10-01, [ADR](../adr/2026-10-01-await-idle-and-subscriptions.md) | `StoreAwaitIdleTest` | planned: issue on the author's note `2026-05-10-await-launch-completion-in-tests.md`, then `upstream-pr/await-idle` | merged |
| b6 | `StoreBuilder.validateRecovery {}`: a `recover {}` from `store {}` blocks may not change the chart's configuration or timers, nor a machine's snapshot | `StoreBuilder.kt`, `StoreImpl.validateRecoveredState`; used at `StateChartStore.kt` and `MachineStore.kt` | round 6, 90cafce | `StateChartRecoveryRegressionTest.recoveryCannot*` (no core test) | fork-only by design | a chart-level `recover<T> { context { … } }` replaces raw `store {}` blocks and `MachineStore` stops exposing `recover` |

#### (c) Public API of the modules upstream owns

| # | Change | Where | Since | Regression tests | Upstream status | Removable when |
|---|---|---|---|---|---|---|
| c1 | `InputId` (public; `@ExperimentalKomaApi` until 5.0-3, 2026-10-01), the id every journal record and decision observer carries | `StoreProbe.kt` | round 9, 90a7e02 | `StoreProbeTest` | proposed with b1 (as `@InternalKomaApi`) | merged |
| c2 | `StoreScope` changed from `sealed` to open, so `ChartHookScope` and `ChartLaunchScope` can be `StoreScope`s for `koma-message`'s `message()` | `StoreScope.kt` | round 4, 916c19c | — | fork-only by design | the chart scopes expose `val store: StoreScope` and delegate instead of extending |
| c3 | `StorePatch.probes`: a new property, so the data class's constructor and `copy` changed | `StorePatch.kt` | round 9, ccc1c1e | `StoreProbeTest` | with b1 | merged |
| c4 | New abstract members of `StoreInternalApi` (`dispatchIf`, `matchActionHandlers`, `handlerMetadata`): a binary change for implementors of the internal interface | `StoreInternalApi.kt` | rounds 6, 9 | — | with their rows | with their rows |
| c5 | Documented guarantee: actions are processed in dispatch order | `Store.dispatch` KDoc | round 2 | a3 | with a3 | merged |
| c7 | `subscribe {}` on `EnterScope` and `ActionScope` (a launch `awaitIdle` leaves out); `StorePendingWork` (public data); koma-test `awaitIdle()`, `pendingWork()` | `StoreScope.kt`, `StoreInternalApi.kt`, `koma-test/StoreExtensions.kt` | 2026-10-01 | `StoreAwaitIdleTest`, `StoreAwaitIdleExtensionTest`, `StateChartAwaitIdleTest` | with b7 | merged |
| c6 | `api/` dumps and `apiCheck` in CI; `checkDebugGraph`; CI for every pull request; `iosArm64` compiled in the macOS job; every test task logs failed assertions | root `build.gradle.kts`, `.github/workflows/gradle.yml` | fork build | — | fork-only by design | never |

#### (d) Companion modules upstream owns

| # | Change | Where | Since | Regression tests | Upstream status | Removable when |
|---|---|---|---|---|---|---|
| d1 | `koma-compose`: the narrowed `ViewStore` of `stateContent` keeps the last `S2`, so a callback after the state's type changed does not throw | `ViewStore.kt` (`NarrowedState`, `narrow`) | 84b0000, 54b1ed6 | `ViewStoreJvmTest.stateContent_callback*` (JVM) | prepared: `upstream-pr/compose-state-content-narrowing` @ bf047ec (C1) | merged |
| d2 | `koma-test`: `diagnoseActionMatches`, `describeHandlers`; `StoreRecorder` refuses a second Store; a clearer error from `createRecorder()` after startup; fail-fast documented | `ActionMatchDiagnostics.kt`, `StoreHandlers.kt`, `StoreRecorder.kt`, `StoreExtensions.kt` | 8a03bda, 48af6ac, 26832ec, 4b15358 | `StoreRecorderTest`, b3/b4 tests | b3/b4 after #280; the recorder fixes with a10 | merged or declined |
| d7 | `koma-test`: a cursor on `StoreRecorder` (`receiveEvent<E>()`, `receiveEvent(predicate)`, `unconsumedEvents`, `assertNoUnconsumedEvents()`) and `Store.assertNoPendingWork(recorder?)` | `StoreRecorder.kt`, `StoreExtensions.kt` | 2026-10-01, [ADR](../adr/2026-10-01-recorder-cursor-and-pending-work.md) | `StoreRecorderCursorTest` | planned with b7 (`upstream-pr/await-idle`) | merged |
| d8 | `koma-compose`: `ViewStore.select(mapper)`, a derived value read in place, readers recomposing only when it changed | `ViewStore.kt` | 2026-10-01, [ADR](../adr/2026-10-01-viewstore-select.md) | `ViewStoreJvmTest.select_*` | planned: feature request after C1 (`upstream-pr/viewstore-select`) | merged |
| d3 | `koma-logging`: `simpleLogging` logs from the hook when no dispatcher is given, so entries stay in order; a throwing logger is reported instead of aborting the action | `Plugin.kt` | bbcc6e6, 54b1ed6 | `LoggingOutputTest` | prepared: `upstream-pr/logging-inline-entries` @ 9d41c49 (C3) | merged |
| d4 | `koma-logging`: `LoggerJournalSink`, and with it a dependency on `koma-observability` (and so on b1) | `JournalSink.kt`, `koma-logging/build.gradle.kts` | round 9, 2e1331a | `LoggerJournalSinkTest` | fork-only by design | `LoggerJournalSink` moves to a `koma-observability-logging` module |
| d5 | `koma-message`: the subscription is registered before `onStart` returns; a throwing receive block is reported and the subscription continues; `extraBufferCapacity = 64` and the hub exposed as a `SharedFlow` | `Plugin.kt`, `Message.kt` | c9a11d0, 4e92b04, cb525ea | `MessageDeliveryTest` | prepared: `upstream-pr/message-subscription-before-start` @ 26a5685 (C2); the buffer size is mentioned, not proposed | merged (the buffer stays fork-only) |
| d6 | Build: `api` instead of `implementation` for `koma-core` in the companion modules; Mocha timeouts for JS and Wasm on Node; the publish convention for `io.github.roman-n1` | `*/build.gradle.kts`, `build-logic` | fork build | — | `api` prepared: `upstream-pr/companion-api-deps` @ e8d889c (C4); the rest fork-only | C4 merged; the rest never |

### Synchronization with upstream

- The fork's `main` = upstream + our merged PRs. The upstream base is recorded in
  `gradle.properties` (`koma.upstream.base`), not in the fork's version.
- On every upstream release: `git fetch upstream --tags`, then merge the release tag
  into the fork's `main` **with a merge commit** (no rebase, so as not to break
  branches and clones). Before the merge, read `git diff <base>..<tag> -- koma-core/src/commonMain/kotlin/koma/core/StoreImpl.kt`
  in full and sort its hunks against the inventory: where upstream covers a row, take upstream's
  version and keep our test; the row leaves in the same PR. The fork's tests are the oracle of the
  merge (`:koma-core:jvmTest`, `StoreProbeTest`, the statechart tests, `apiCheck`), not the shape of
  our code.
- Practice for `StoreImpl.kt`: our additions stay in cohesive blocks at the end of the class, the
  boundary calls are one-liners (`trace { … }`), upstream code and KDoc are not reformatted;
  `git config rerere.enabled true` and `merge.conflictStyle zdiff3` in the checkout; unmerged
  `upstream-pr/*` branches are re-created on the new tag.
- After the merge: `apiDump`, `koma.upstream.base` updated, the fork's CI green on all platforms,
  then a new fork version.

### How the messenger connects the fork

1. **During active development — composite build.** The fork is added as a
   git submodule, and `includeBuild("koma")` is written into the messenger's `settings.gradle.kts`.
   Gradle substitutes the fork's modules for the
   `io.github.koma-kt:*` dependencies by itself; nothing needs publishing, and edits to
   Koma are visible immediately. Downside: the messenger's CI has to fetch the submodule.
2. **Once the version has stabilized — GitHub Packages.** Publishing from the fork's GitHub
   Actions on a macOS runner (otherwise the iOS artifacts cannot be built), to the fork's own
   Maven repository. Reading requires a token with `read:packages`.
   - Coordinates: a separate group `io.github.roman-n1`, so that the fork's artifacts
     are never confused with the official `io.github.koma-kt`.
   - Version: the fork's own line, `5.0.0-alpha.N` from 2026-10-01 (decision below); the upstream
     base it was merged with is `koma.upstream.base` in `gradle.properties`, not part of the version.
   - The official `koma-core` and the fork's must not be mixed in one project:
     the classes are the same, the groups differ. Koma's group and version are set in the
     messenger's version catalog in one place.
   - The current `.github/workflows/publish.yml` publishes to Maven Central on
     prerelease and will fail in the fork without the secrets. The fork needs a separate
     publishing workflow; to be done when we get to item 2.
3. **Moving to official Koma.** When upstream has released all the core steps
   that the messenger needs: in the messenger's version catalog the group of
   `koma-core`/`koma-compose`/`koma-test` changes to `io.github.koma-kt`, and the
   version to the official one. `koma-statechart` remains our artifact (or
   moves to upstream through the RFC, step 9). Until every inventory row the layer needs is
   `merged` or removed, the messenger stays on the fork's `koma-core` (plan B is dropped).

### Version 5.0 (decision 2026-10-01)

The fork is its own line: `5.0.0-alpha.1` from this decision on (`koma.fork.version`). The number
says what the inventory says, that the modules are not "4.0.0 plus patches": `StoreScope` is open,
`StorePatch`'s constructor changed, `InputId` is new, and the statechart, journal and replay modules
are the product. `alpha` says what is still true: most of that API is `@ExperimentalKomaApi` and the
on-disk formats moved with every stage. `5.0.0` without a suffix requires: (1) `@ExperimentalKomaApi`
removed from the core of the machine and the journal (`Machine`, `MachineStore`, `Lanes`,
`ExecutorCheckpoint`, `MachineGroup`, `RecordingSession`, `JournalEntry`, `JournalFiles`,
`RecordingCodec`, `RecordingFiles`), held by `apiCheck`; (2) the formats frozen under a migration
policy: a version bump only with a migration or a reader of the old version and a golden of it.
Both are in place since 2026-10-01: (2) the [format freeze policy](../adr/2026-10-01-format-freeze-policy.md)
and `FormatVersionsTest`, which pins the five versions and names the checklist on a bump; (1) per
the [stable core ADR](../adr/2026-10-01-stable-core.md): `InputId`, the journal, the pure chart
and machine models, their snapshots/decisions/checkpoints, and the recording formats of
`koma-timetravel`. The sources' contract, evolving Store adapter scopes and raw StoreBuilder
configuration remain experimental; so do replay, branches and the inspector. The c2/b6
adapter changes below must resolve their public contract before those markers come off.
What remains for `5.0.0` is the tag (5.0-4).
The messenger pilot, device measurements and publishing remain work, not conditions of the number.

## Remaining work

1. **Publishing.** Set up: `.github/workflows/publish.yml` publishes every module to Maven Central
   as `io.github.roman-n1:<module>:5.0.0-alpha.1` from a GitHub pre-release, and the publish convention
   sets each project's group and version to the same coordinates, so a composite build
   (`includeBuild`) substitutes them (the build files keep `io.github.koma-kt` to stay close to
   upstream). No release has been published yet, so the messenger still connects the fork with a
   composite build; `koma-statechart/README.md` describes both.
2. **Known limitations of wave 5** (decisions in the semantics document, "Wave 5 decisions"):
   - after restore from `StateSaver`, enter hooks are not run again, and timers start with the full
     delay (the remaining time is not saved);
   - events emitted by the hooks of a failed step are not retracted; a timer whose step failed is
     removed from `timers.running` and does not fire until the node is entered again;
   - a restored configuration is checked against the definition: active nodes the chart cannot
     produce fall back to the initial configuration (keeping the context), an unrestorable history
     record is dropped;
   - `enter {}`/`action {}` for `ChartState` from `store {}` do not work, `initialState` there is
     ignored, `PendingActionPolicy.ClearOnStateExit` does not affect chart steps;
   - conformance does not see guards or clocks: self-loop timers are not counted (wave 4), and of two
     transitions with the same leaves before and after (for example, two timers `Backoff → Connecting`
     with different guards in the messenger example) the first is counted.
3. **Model.** No eventless (completion) transitions, final states, internal
   transitions or `invoke`. The first candidate is completion transitions: with them "the work
   is finished" is described without an artificial action.
4. **Phase 8.** Invariant checking on paths, shrinking of a failing action sequence,
   debug timeline (the data is already there: `StepResult`, `ChartState`).
5. **Bridge to koma-strict** — optional, as a separate module; design in
   [`2026-09-28-koma-strict-comparison.md`](./2026-09-28-koma-strict-comparison.md).
6. **Upstream.** The series in [`notes/2026-10-01-upstream-series.md`](../notes/2026-10-01-upstream-series.md): companion fixes, the stability fixes one by one, the `awaitIdle` and `StoreProbe` proposals; steps 2–3 and the RFC (step 9) after the author's answer on #280.

## Notes

- The handler registry, first-match and the commit order (exit → `_state` + `StateSaver`
  → `Plugin.onState` → clear pending → enter) were verified against the 4.0.0 sources.
- `Plugin.onAction` is called before the handler, `Plugin.onState(prev, next)` after the
  commit. The Plugin does not know which action caused the transition; the layer provides that
  metadata itself.

## Related

- [Statechart layer semantics](./2026-09-28-statechart-semantics.md)
- [Comparison with koma-strict](./2026-09-28-koma-strict-comparison.md)
- [Module README](../../../koma-statechart/README.md)

- [Koma design principles](./2026-04-23-design-principles.md)
- [Store surface design notes](./2026-04-29-store-api-design.md)
- [LaunchControl API design](../adr/2026-05-01-launch-control-case-naming.md)

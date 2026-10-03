# Upstream series: what the fork sends to koma-kt/koma, in what order, and what it does if declined

- Updated: 2026-10-01
- Status: planned; nothing beyond issue koma-kt/koma#280 has been sent. Roman posts every issue
  and PR himself. This note is the status board; the
  [divergence inventory](../design/2026-09-28-statechart-roadmap.md#divergence-inventory-vs-upstream-400)
  names the rows each item would close.

## Background

The author's roadmap (koma-kt/koma#189) keeps the core small and puts test tooling hooks in the
core, devtools in optional modules. The fork changed the core in three kinds of ways (stability
fixes with regression tests, observation hooks, small public changes) and the companion modules
in a few; see the inventory. Plan B (the statechart layer on unmodified Koma) is dropped, so the
way back is per row: each row is a small change the author can take on its own.

## Conventions

- A branch `upstream-pr/<topic>` is exactly one commit on tag `4.0.0` (`ae1a8da`), like the
  existing `upstream-pr/handler-matcher-metadata`; never stacked. Two branches that touch the same
  function say so in the second PR, which is rebased after the first merges.
- No cherry-picks: the fork's fixes came in round-based commits that bundle several changes. For
  each item: copy the named regression tests into a new small test file on the branch, run
  `./gradlew :koma-core:jvmTest --tests 'koma.core.<Test>'` on 4.0.0 and see them fail, port the
  final shape of the function from the fork's `StoreImpl.kt` without `trace {}`, `InputId`,
  `QueuedInput` or `InsideStore` (unless the item is about them), then `:koma-core:jvmTest` and
  `:koma-core:iosSimulatorArm64Test`. `git diff 4.0.0 upstream-pr/<topic> -- koma-core/src/commonMain`
  must fit one screen. No `api/` dumps: upstream has no `apiCheck`.
- Bugs: an upstream issue "Bug: …" with the failing test, and the PR the same day. Features
  (`awaitIdle`, `StoreProbe`): an issue first, the PR after a reply. At most two open upstream PRs
  at a time; #280 is not pinged, the first bug PR references it.
- The issue text, one paragraph: what happens (the test's name, on which dispatcher); the root
  cause at `StoreImpl.kt:<line>` of 4.0.0; the change (function, the invariant it keeps); what does
  not change (public API, handler order); the tests carried; the branch on top of 4.0.0.
- Declined: the inventory row gets `declined` and the reason; nothing else changes in the fork.
- Optional CI on the exact upstream build: a fork branch `upstream-base` = `4.0.0` plus the
  `pull_request:` trigger of `d2c2555`, and draft PRs `upstream-pr/<topic> → upstream-base` in the
  fork.

## The series, in sending order

Companion fixes first (cheap, away from `StoreImpl.kt`, independent of each other):

| Item | Branch | Scope | Tests carried | Inventory |
|---|---|---|---|---|
| C1 | `upstream-pr/compose-state-content-narrowing` | `koma-compose`: the narrowed `ViewStore` of `stateContent` keeps the last `S2` | `ViewStoreJvmTest.stateContent_callbackRunAfterStateTypeChanged_readsLastNarrowedState`, `…_callbackThatNeverReadStateDuringComposition_readsLastNarrowedState` | d1 |
| C2 | `upstream-pr/message-subscription-before-start` | `koma-message`: `onStart` waits for the subscription; a throwing receive block is reported and the subscription continues (the 64 buffer is mentioned, not proposed) | `MessageDeliveryTest.message_sentFromTheReceiversOwnStartup_isReceivedOnAMultiThreadedDispatcher`, `message_receiverKeepsItsSubscriptionAfterItsBlockThrows` | d5 |
| C3 | `upstream-pr/logging-inline-entries` | `koma-logging`: log from the hook when no dispatcher is given; a throwing logger is reported | `LoggingOutputTest` | d3 |
| C4 | `upstream-pr/companion-api-deps` | `api` instead of `implementation` for `koma-core` in the companion modules | — | d6 |

Then the stability fixes, one by one:

| Item | Branch | Scope (fork symbols) | Tests carried | Inventory | Note |
|---|---|---|---|---|---|
| U1 | `upstream-pr/state-first-hang` | `StoreImpl.state.collect` | `stateFirst_returnsOnceThePredicateMatches` | a1 | smallest; the hang is visible from 4.0.0's `awaitCancellation()` |
| U2 | `upstream-pr/exit-failure-keeps-runtime` | `processStateExit` after a successful `onExit`; `commitTransition` clears pending before the commit | `exitException_recoveredInPlace_keepsTheStateRuntimeUsable`, `clearOnStateExit_keepsActionsDispatchedByPluginsForTheNewState` | a2 | introduces `commitTransition`, which U3 and U5 build on |
| U3 | `upstream-pr/no-commit-after-close` | `ensureActive()` in `commitTransition` and `onErrorOccurred` | `StoreCloseJvmTest.nothingCommitsAfterClose`, `closeDuringNonCancellableExitDoesNotCommitOrSaveTheTransition`, `anExitFailingAfterClose_doesNotRunRecover` | a11 | matches the author's "close() cuts off" wording in his `awaitIdle` note |
| U4 | `upstream-pr/dispatch-order` | `launchDispatch`, `lastDispatchDone`; `Store.dispatch` KDoc | `dispatches_areProcessedInDispatchOrder`, `aHandlerLeavingAChildCoroutineBehind_doesNotStallLaterDispatches`, `dispatchJobs_areNotRetainedThroughTheOrderingChain` | a3, c5 | the README says "one at a time"; this makes the implicit promise true on `Dispatchers.Default` |
| U5 | `upstream-pr/cancelled-launch-transaction` | `canRunLaunchOperation`, owner job identity, checks in both launch scopes | `cancelPrevious_skipsATransactionQueuedByTheCancelledLaunch`, `StoreLaunchCancellationTest` | a4 (a13 may join) | the repro is the README's own search example committing a stale result |
| U6 | `upstream-pr/serialized-plugin-rounds` | `pluginMutex`, the single-plugin fast path | `pluginHookRounds_neverOverlap_evenForEventsEmittedFromLaunches` | a8 | |
| U6b | `upstream-pr/every-plugin-every-round` | `hookFailure`: every plugin sees every round, fatal stays fatal | `aFailingPluginHook_doesNotHideTheRoundFromOtherPlugins`, `aFatalErrorFromASecondPlugin_staysFatal` | a9 | more opinionated; separate |
| — | issue `awaitIdle` | exactly the author's note `2026-05-10-await-launch-completion-in-tests.md`, answered with the fork's experience: queued inputs plus tracked action launches, iterated until stable; enter launches excluded at first; throws from inside the Store, so the PR carries the `InsideStore` fail-fast | `awaitingTheStoreFromInsideItsOwnHandler_failsFastInsteadOfDeadlocking`, `awaitingTheStoreFromAnOnEventRoundOfALaunchedEvent_failsFast`, new `awaitIdle_*` tests | a10 | links #189 item 2 |
| U7 | `upstream-pr/observer-failure-continues-transition` | `reportWithoutAborting`, `processStateChange`, `processEventEmit`; `StateSaver`/`Plugin` KDoc | `stateSaverException_onVariantChange_stillEntersTheNewState`, `pluginOnStateException_onVariantChange_stillEntersTheNewState`, `pluginOnEventException_doesNotAbortTheEmittingHandler` | a5 | touches his ADR on the exception boundary: "not routing to `recover {}`, only not leaving a half-entered state"; after U1–U6 |
| — | issue `StoreProbe` | "minimal, observation-only processing hook: the core half of #189 item 5"; non-suspending, called inline under the lock, its failure reported and never traced, no callback into the Store, no cost without probes, `@InternalKomaApi`; six things a `Plugin` cannot see, each pinned by a `StoreProbeTest` name | `StoreProbeTest` | b1, c1, c3 | (a) `StoreProbe`, `InputId`, `InputKind`, `DiscardReason` without `Stale`/`Rejected`, `ProcessingOutcome`, the five processing traces, `probe()`; (b) `EventEmitted`, `FailureReported`, `StoreClosed`, `currentInputId()` |
| U8 | issue only | the `CancellationException` rule (`rethrowIfNonRecoverable`) | `expiredWithTimeout_*`, `pluginHookTimingOut_failsTheActionUnderBothPolicies` | a7 | a semantic choice; a PR only if he agrees |

Not proposed: `dispatchIf`, `validateRecovery`, the open `StoreScope` (fork-only by design, see the
inventory's "removable when"), steps 2–3 of the roadmap before #280 lands.

## Shrinking the fork-only core delta (fork PRs, one row each, when messenger work allows)

- b5 `dispatchIf`: an `ActivityDispatch<A>(action, activation)` envelope dispatched through the plain
  `dispatch()`; the chart's single `action<Action>` handler unwraps it and returns without
  `nextState` when the activation is no longer active. Stale envelopes then reach `Plugin.onAction`
  and a `StoreRecorder` as `Unchanged` (documented; the conformance plugin skips the envelope type),
  and `DiscardReason.Stale`/`Rejected` go away.
- b6 `validateRecovery`: the chart configuration gets its own `recover<T> { context { … } }` with a
  scope that can only replace the context, mapped to core `recover<T> { nextState { … } }`; raw
  `store {}` blocks stop being exposed; for `MachineStore` recovery is not exposed. Invalid
  recoveries become unrepresentable, the validator goes.
- c2 open `StoreScope`: `ChartHookScope`/`ChartLaunchScope` stop extending `StoreScope` and expose
  `val store: StoreScope`, so `koma-message`'s `message()` is `store.message(m)`.
- d4 `koma-logging` → `koma-observability`: `LoggerJournalSink` and its test move to a
  `koma-observability-logging` module.

## Open questions

- Whether the author answers at all: 4.0.0 shipped on 2026-08-29, later commits are maintenance,
  #280 has no reply. The series is sent regardless; the fork does not wait.
- Whether U1–U7 should be one "stability" PR instead of seven: no, each has a test and a
  one-screen diff; a single PR of 400 lines in `StoreImpl.kt` would be declined for size alone.

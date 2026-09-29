# Proposal for a test API that waits for launches inside enter / action to complete

- Updated: 2026-05-10

## Background

Inside `enter {}` and `action {}`, side-effect processing can be started with the Store DSL's `launch {}`.
There are situations where test code wants to wait for this launch to complete.
Concretely, the following premises are assumed.

- There are cases where the dispatcher is hard-coded, such as `launch(Dispatchers.IO)`
- Because of that, the test side does not want to lean on `TestDispatcher` / `runTest` / `advanceUntilIdle` etc. from `kotlinx-coroutines-test`
- The existing `startAndWait()` / `dispatchAndWait()` wait for the completion of the synchronous part of the start / dispatch itself, but do not wait for the completion of child jobs started with `launch` from inside it

If the design guarantees that the outcome of a side effect always shows up as a state transition or an event emission, tests can wait with the standard Flow API (`state.first { ... }` etc.).
However, a fire-and-forget launch that completes with side effects only (logging, metrics, external sending only, etc.) cannot be observed via state / event.
The motivation for this note is wanting to wait for "the Store has settled", including such launches.

## Current thinking

The approach is to add an API inside the Store that "waits until the currently running child jobs of launches are joined".
For convenience it is called `awaitIdle()` here.

The footing for the implementation is already in place.
`stateRuntimes` at [StoreImpl.kt:131](../../koma-core/src/commonMain/kotlin/koma/core/StoreImpl.kt) holds a `StateRuntime` per state class, and
`StateRuntime` at [StoreImpl.kt:143](../../koma-core/src/commonMain/kotlin/koma/core/StoreImpl.kt) holds a `scope` and `actionLaunchJobs`.
`launch {}` in enter / action is started on this scope via `launchInStateRuntime()` at [StoreImpl.kt:443](../../koma-core/src/commonMain/kotlin/koma/core/StoreImpl.kt).
Therefore, all currently tracked child jobs can be traversed and joined.

The conceptual implementation is as follows.

```kt
internal suspend fun StoreImpl<*, *, *>.awaitIdle() {
    while (true) {
        val jobs = stateRuntimes.values.flatMap { runtime ->
            runtime.actionLaunchJobs.values + listOfNotNull(
                // If launches inside enter are also to be tracked, arrange them so they can be referenced here
            )
        }
        if (jobs.none { it.isActive }) return
        jobs.joinAll()
        // New launches may have been queued during the join, so iterate until stable
    }
}
```

The characteristics are as follows.

- Does not depend on the kind of dispatcher. Even with a hard-coded `Dispatchers.IO`, the Job is a child of the state scope and can be tracked
- Does not use a fixed sleep. Looks directly at the fact of Job completion
- Its purpose differs from `Store.close()`. `close()` "cuts off", `awaitIdle()` "waits until finished"

### Handling launches that never finish (Flow subscriptions, etc.)

The straightforward implementation of `awaitIdle()` has a hole.
Cases where a Flow is subscribed inside a launch, for example something like `launch { repository.userFlow.collect { ... } }`,
do not complete until the state transitions and the scope is cancelled.
`joinAll()` would simply hang.

There is no way to distinguish "processing" from "suspended and waiting in `collect` etc." by looking at the `Job` alone.
`advanceUntilIdle` of `kotlinx-coroutines-test` can achieve this because
it looks directly into the TestDispatcher's queue to determine "whether there are tasks waiting to run",
and with a real dispatcher (`Dispatchers.IO` etc.) the same thing is fundamentally impossible.
Given the approach of not using a TestDispatcher, this distinction has to be brought in on the Koma side in some form.

There are several conceivable directions.

- **Separate long-lived subscriptions into a different API on the DSL side**. `launch {}` assumes short-lived side effects, long-lived subscriptions are split out into a separate API equivalent to `subscribe { ... }` and excluded from the tracking target of `awaitIdle()`. The semantics are clean, but an API addition is required
- **Add a flag to launch**. In a form such as `launch(awaitable = false) { ... }`, the caller declares exclusion from the wait target. Compatibility is easy to maintain, but a hanging test can occur if the flag is forgotten
- **Switch to a quiescence basis**. Consider idle to be "neither state nor event moves for a certain period". The implementation is simple but timing-dependent and fragile. It fits poorly with the premise of this approach (not using a TestDispatcher)
- **Give up on `awaitIdle()` itself**. Wait only for launches that are expected to finish with the standard Flow API, and do not support waiting for the whole including subscriptions. For example, one can write

  ```kt
  val loaded = store.state.first { it is Loaded || it is Error }
  ```

  The `first` / `filter` / `take` etc. used here are standard APIs on the `kotlinx-coroutines-core` side, not a dependency on `kotlinx-coroutines-test`. This does not contradict the premise.
  However, launches whose side-effect end does not necessarily show up in state / event (fire-and-forget logging, metrics, etc.) cannot be observed, so the motivation of this note is not met

### Visibility

It is natural to align with the same two-tier structure as the existing `startAndWait()` / `dispatchAndWait()`.

- Add `suspend fun awaitIdle()` to [`StoreInternalApi`](../../koma-core/src/commonMain/kotlin/koma/core/StoreInternalApi.kt) (`@InternalKomaApi`) in `koma-core`
- Implement it in `StoreImpl`
- Place a public extension in [`StoreExtensions`](../../koma-test/src/commonMain/kotlin/koma/test/StoreExtensions.kt) in `koma-test`, delegating to `requireStoreInternalApi().awaitIdle()`

This makes the test wait APIs consistent as a graded set.

- `startAndWait()`: waits up to the synchronous part of start
- `dispatchAndWait(action)`: waits up to the synchronous part of dispatch
- `awaitIdle()` (new): waits until all launches derived from start / dispatch have settled

Calling `awaitIdle()` right after `dispatchAndWait(action)` would be the typical usage.

### Current assessment

- Proceed in the direction of adding `awaitIdle()`. There is currently no option other than this proposal for waiting for the Store to settle including fire-and-forget launches
- However, the premise is that a mechanism to distinguish long-lived launches (Flow subscriptions, etc.) from the rest is added together with it. Without the distinction, tests of Stores that include Flow subscriptions easily hang
- Only if it is judged that the cost of adding the distinction mechanism is not worth it, the option remains of giving up on `awaitIdle()` itself and falling back to observation via the Flow API

## Open questions

- API name. Which of `awaitIdle()` / `awaitAllLaunches()` / `quiesce()`
- Name of the `koma-test` extension. Whether to expose it as `awaitIdle()` as-is, or under a different name matching the feel of `startAndWait()` / `dispatchAndWait()`
- Tracking of Jobs of launches inside enter. They may not be in `actionLaunchJobs`, so whether to track launches started from enter in the same map, or to have a separate tracking path
- Semantics of the iterated join. How to handle the case where new launches are queued endlessly during `awaitIdle` (timeout / upper bound on iterations / no guarantee at all)
- How to distinguish long-lived launches. Which of the 4 proposals listed in the body to adopt
- Meaning when crossing state transitions. When the state switches, the scope of the previous state is cancelled, so it needs to be verified that waiting on a join of a cancelled Job does not unintentionally take long
- Whether to expose it as `public`. Whether there will be situations where one wants to call `awaitIdle()` in production code. If it stays test-only, it is safer not to expose it in the API surface
- How to handle launches that are not triggered by dispatch, such as timer-driven ones. Whether to define it as waiting for complete idle, or limit it to those derived from dispatch

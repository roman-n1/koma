# Proposal for a Store start timing policy

- Updated: 2026-04-26

## Background

Currently the Store starts on the first `dispatch()` or the first `state.collect` / `collectState()`.

On startup, `initializeIfNeeded()` is called and the start-time processing, including the middleware `onStart`, runs.

As a result, side effects tied to start run even when the user only collects `state`.
Compose's `rememberViewStore()` calls `store.state.collectAsState()` internally, so the Store can start as soon as the screen subscribes to it.

On the other hand, the following behaviors already hold before start.

- `currentState` can be read
- State restored via `stateSaver.restore()` is also visible before start
- `attachObserver()` is only allowed before start
- `collectEvent()` is currently not a start trigger

Looking at this combination, the natural subject of discussion is not individual handlers but "when the Store is considered started".

## Current thinking

If an initialization policy is added, it should be introduced not as an individual DSL handler but as a policy that controls the Store's start timing as a whole.

The tentative name is `StoreStartPolicy`, with the following three candidates to begin with.

- `ON_FIRST_DISPATCH_OR_STATE_COLLECTION`
  - Backward-compatible default
  - Starts on the first `dispatch()` or the first collect of `state`
- `ON_FIRST_DISPATCH`
  - Starts only on `dispatch()`
  - Collecting `state` is not a start trigger
- `MANUAL`
  - No automatic start
  - Starts only when the user explicitly calls `start()`

The natural place for the configuration API is `StoreBuilder` / `StoreOverridesBuilder`, in the same style as the existing policies.

```kt
fun startPolicy(policy: StoreStartPolicy)
```

To make `MANUAL` work, a separate API for explicit start is required.

## Notes

- As with `PendingActionPolicy` and `MiddlewareExecutionPolicy`, an enum-based high-level policy keeps the meaning of the API clearer. Exposing a `Boolean` or a raw set of triggers would allow more combinations, but the meaning from the user's point of view becomes weaker.
- What the policy controls is the startup processing as a whole. Splitting it would duplicate the concept of start, and consistency between middleware, observers and tests would easily break.
- A collect-only policy such as `ON_FIRST_STATE_COLLECTION` is not needed in v1. Behavior where `dispatch()` is called but the Store does not start is likely to be counterintuitive.
- `EAGER` is another candidate that was not adopted. It would mean "start immediately after Store creation, without waiting for `dispatch()` or a collect of `state`". It is better to defer it in v1. It easily conflicts with the current premise that `attachObserver()` is only allowed before start.
- With `ON_FIRST_DISPATCH`, collecting `state` does not start the Store. Even then, the current snapshot can be read as a `StateFlow`, so the UI can more easily separate "observing the current value" from "starting side effects".
- When a one-shot event such as `enter { event(...) }` should be emitted right after start, with the current default the collect of `state` becomes the start trigger first, and a side that subscribes to `event` later can miss the initial event.
- This problem can also occur with Compose's `rememberViewStore()`. `rememberViewStore()` calls `store.state.collectAsState()` internally, while collecting events starts in the `LaunchedEffect` on the `ViewStore.eventEffect()` side, so even if both are written in the same screen there is no guarantee that the event collector is in place before start.
- A start policy can be an effective workaround for this problem. With `ON_FIRST_DISPATCH`, the Store can be started by an explicit first `dispatch()` after the `state` / `event` subscriptions are set up, and with `MANUAL` the order of calling `start()` after setting up the subscriptions can be made even more explicit.
- However, what the start policy solves is only "not starting before subscription"; it does not change the replay semantics of `Store.event` itself. Past events are not redelivered to a side that subscribes after start.
- If `dispatch()` is called before start under `MANUAL`, failing with an exception rather than starting implicitly or silently ignoring it makes bugs easier to find early.

## Open questions

- Whether to put the explicit start API on the `Store` interface or add it as an extension inside core is undecided. Adding it to the interface affects fake implementations, and with an extension we need to think about how to present the fact that it depends on the Actron implementation.
- Whether to include `collectEvent()` as a start trigger is undecided. It is currently not included, but from the user's point of view observing `event` may be expected to be tied to start as well.
- The README explanation for the case where `state` is collected before start under `MANUAL` needs to be made clear. The explanation is expected to be that the current snapshot flows, but side effects tied to start do not run yet.
- Samples for choosing `ON_FIRST_DISPATCH` or `MANUAL` when using `rememberViewStore()` need to be added to the README / the Compose-side tests.

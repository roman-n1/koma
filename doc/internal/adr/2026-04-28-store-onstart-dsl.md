# No state-independent `onStart {}` is added to the `Store{}` DSL

- Updated: 2026-04-30

## Background

In the current `Store{}` DSL, `enter {}` can use `launch {}` to subscribe to an external resource's `Flow` or a callback bridge.

However, this subscription belongs to the runtime of the current state.
So when the state transitions, the runtime tied to that state ends, and the subscription started in `enter {}` stops as well.

What was considered here was a proposal to add to the `Store{}` DSL an `onStart {}` that runs once at Store start, unaffected by state transitions.
With this, subscriptions tied to the lifetime of the whole Store rather than of each state could be written directly inside the `Store{}` DSL.

On the other hand, whether this `onStart {}` should be allowed to update state cannot be settled at this point.
Just like the decision not to add a direct state update API to `Middleware`, putting a state-writing capability on a start hook that is visible outside the state machine tends to blur the boundary of responsibilities.

Conversely, if state updates are not allowed and it is treated as a hook that only receives external input and `dispatch()`es actions, then `Middleware(onStart = { ... })` can substitute for it.
In that case, adding a dedicated `onStart {}` to the `Store{}` DSL would effectively amount to no more than adding another alias for middleware.

## Decision

At this point, no `onStart {}` that is independent of state transitions is added to the `Store{}` DSL.

- Subscriptions handled in `enter {}` continue to be treated as belonging to the runtime of the active state.
- When a subscription or observation unaffected by state transitions is needed, use `Middleware(onStart = { ... })` to receive external input and `dispatch()` the required actions.
- Until it is sorted out whether a state-independent start hook should be allowed to update state, no dedicated API is added to the `Store{}` DSL.

## Notes

- The main reason for deferring this time is that we do not want to add an API while it is undetermined whether it should be "a hook that allows state updates" or "a dispatch-only hook".
- If, in the future, a version of `onStart {}` that does not update state is provided as an alternative, the simplest implementation is syntactic sugar over `Middleware(onStart = { ... })`.
- In that case, however, how it should be reconciled with `clearMiddlewares()` and `replaceMiddlewares()` inside `overrides {}` needs to be decided first. If it is ambiguous whether the `onStart {}` of the `Store{}` DSL disappears as part of the middlewares or remains as a separate slot, the override semantics become hard to read.
- If this interaction turns out unnatural, or the implementation handling becomes complicated, the syntactic sugar approach should not be taken; instead a dedicated entry point should be provided in `StoreImpl` to make the handling explicit.

## Related

- [Store start timing policy proposal](../notes/2026-04-23-store-start-policy.md)
- [No direct state update API in Middleware](./2026-04-26-middleware-dispatch-only.md)

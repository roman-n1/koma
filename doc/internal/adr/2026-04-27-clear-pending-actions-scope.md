# `clearPendingActions()` is confined to store work / transaction

- Updated: 2026-04-27

## Background

`clearPendingActions()` is not an API that stops the currently running store work itself; it is an API that discards the dispatches waiting behind it.

On the other hand, in terms of the public surface it can be called from `enter {}`, `action {}`, `exit {}`, `recover {}`, and from `transaction {}` inside a launched coroutine.
For this reason, the following two points should be sorted out.

- Whether `clearPendingActions()` should be restricted to an even narrower set of places
- Whether it should also be callable from the `launch {}` body itself

`clearPendingActions()` is queue control, and its role differs from cancellation of state-owned asynchronous work itself.
Whether this boundary can be read from the API surface is what matters.

## Decision

`clearPendingActions()` continues to be treated as an API callable only from scopes executed on the store's serial pipeline, and from `transaction {}`, which explicitly returns to that pipeline.

- It remains exposed in `enter {}`, `action {}`, `exit {}` and `recover {}`
- Inside a launched coroutine it is callable only from `transaction {}`, and is not exposed in the `launch {}` body itself
- It is not extended to middleware or other non-store-work contexts

Also, the center of gravity of its use is placed on `action {}` and `transaction {}` inside launched coroutines.
Use in `enter {}`, `exit {}` and `recover {}` is permitted as an escape hatch, but is not the center of regular use.

## Notes

- `clearPendingActions()` is meaningful when "what is the current store work now, and what is pending behind it" is determined on the serial pipeline.
- The `launch {}` body is asynchronous work owned by the state, not the store's serial pipeline itself. Allowing the queue to be cleaned up directly there would make it possible to discard pending actions at an arbitrary point after a delay or I/O, making behavior hard to follow.
- What the `launch {}` body needs is lifetime control of state-owned jobs rather than queue control. In terms of separation of roles, that is more naturally handled by action-launch cancellation such as `cancelLaunch(lane)`.
- Once a launched coroutine enters `transaction {}`, processing returns to the store's serial pipeline. So it makes sense to decide at that moment that "if this result is adopted, the old pending actions are no longer needed" and call `clearPendingActions()`.
- `enter {}`, `exit {}` and `recover {}` are technically store work too, and discarding pending actions there has meaning. Therefore there is no need to go as far as removing them from the public surface entirely.
- However, from a readability standpoint, `action {}` and `transaction {}` make it easier to read "what has been settled, as a result of which the queue is being cut". The README and KDoc should make this center of use explicit.

## Related

- [Rejection of the PendingActionPolicy extension proposals](./2026-04-22-pending-action-policy.md)
- [No cancellation API for non-`launch` work](./2026-04-26-non-launch-cancellation.md)
- [The async boundary of `action` stays explicit](./2026-04-26-action-async-boundary.md)

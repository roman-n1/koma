# The exposure of `cancelLaunch()` stays limited to `ActionScope`

- Updated: 2026-04-30

## Background

`cancelLaunch(lane)` can currently be called only from `ActionScope`.
This is because it was introduced as an API that stops, per lane, a tracked launch started with `action { launch { ... } }`, within the currently active state runtime.

On the other hand, design-wise there are candidates for extending it to the following two places.

- `RecoverScope`: during recovery, one may want to explicitly stop related launched jobs as well
- `ActionScope.LaunchScope.TransactionScope`: when adopting the result of a launched coroutine in a transaction, one may want to stop another tracked launch

What needs to be decided here is whether `cancelLaunch()` should be extended as is to these candidates.

## Decision

At this point, the exposure of `cancelLaunch()` stays as `ActionScope`, and exposure to `RecoverScope` and `ActionScope.LaunchScope.TransactionScope` is deferred.

- `cancelLaunch()` continues to be treated as "an API that stops a tracked launch started in response to an action"
- Lane cancellation from error recovery or from a transaction inside a launched coroutine is not exposed as standard at this stage
- It will be reconsidered, including as a separate API, only when concrete use cases accumulate in the future

## Notes

- `RecoverScope` is store work, but its central role is recovery. Adding lane cancellation here tends to mix "what is being recovered as exception handling" and "at which point state-owned asynchronous work is stopped" in the same place.
- If the reason for wanting to stop a launched job is "after this error, old work should no longer be adopted", then replacing the runtime through a state transition, or cancellation through an explicit action, is easier to follow.
- `ActionScope.LaunchScope.TransactionScope` looks promising at first glance, but exposing `cancelLaunch()` there as is has a self-cancel problem. The transaction is not executed directly inside the launched job itself; it runs in a separate job that the outer side `join()`s, so when the same explicit lane is shared, it could stop its own tracked launch.
- In that case, the transaction side may proceed all the way to the state update while only the outer launched job is cancelled, which is unintuitive behavior.
- Furthermore, from the viewpoint of `ActionScope.LaunchScope.TransactionScope`, whether "another lane should be stopped" or "the lane it belongs to may also be stopped" cannot be expressed with the current `cancelLaunch(lane)` signature alone.
- Actron takes the position that "an action is the trigger that starts processing, and the owner of in-flight work is the state". On top of that, keeping the entry point for lane cancellation confined to `ActionScope` first makes it easier to read which action decision caused the stop.
- Therefore, this time, while acknowledging that there are candidates, the decision to extend the current `cancelLaunch()` to other scopes is not taken until concrete use cases become clear.

## Related

- [#190](https://github.com/koma-kt/koma/issues/190)

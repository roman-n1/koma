# `debounce` / `throttle` are not added as Store built-ins

- Updated: 2026-04-30

## Background

We considered whether to provide time-window-based control such as `debounce` or `throttle` on `dispatch()` as a Store built-in.

`LaunchControl.CancelPrevious` and `LaunchControl.DropIfRunning` also change behavior depending on whether an active job exists and on launch order, so at first glance they seem to be a similar topic to `debounce` / `throttle`.
However, what they deal with is how to arbitrate launched jobs tied to a keyed lane, and their meaning is confined to job coordination.

`debounce` / `throttle`, on the other hand, are controls that decide how multiple inputs within a time window are combined, which are dropped, and when execution happens; their meaning is more that of "a mechanism that decides input acceptance and execution timing" than of per-job control.
As a result, "which dispatches go through" and "which dispatches are lost" cannot be followed from the state and action definitions alone, and tend to become unclear unless the passage of time is also taken into account.

Also, Actron's Store places importance on the visibility of action processing order and state transitions.
Bringing in a built-in control where the acceptance and execution timing of dispatches change based on time windows means that even a seemingly simple dispatch may internally be delayed, thinned out or discarded, which lowers the readability and explainability of the API.

## Decision

At this point, `debounce` / `throttle` are not added as Store built-ins.

- Time-window-based accept/discard control is not brought into `dispatch()` itself.
- Features such as "combine dispatches within a certain period" or "drop rapid consecutive dispatches" are not shipped as standard on the Store side.
- When such control is needed, the position at this stage is to control it on the UI side before calling `dispatch()`.

## Notes

- The central reason for deferring is not simply that the control involves a time element, but that `debounce` / `throttle` behave as a mechanism that decides input acceptance and execution timing within a time window, rather than as coordination of tracked jobs.
- `LaunchControl.CancelPrevious` / `DropIfRunning` are still readable as job-tied controls, meaning "stop the previous launched job on this lane and start the next" and "do not start a new launched job while this lane has an active job".
- When a dispatch is lost due to `debounce` / `throttle`, it tends to become hard for users to judge whether that is by specification or a defect.
- The situations where such control is needed are often UI-originated event shaping, such as search input, tap-spam prevention and scroll-linked behavior. Therefore, at this stage, it is more natural to shape events on the UI side before calling `dispatch()` than to absorb this as a Store responsibility.
- Even if similar demands increase in the future, the intent of each use case and the conditions under which a dispatch may be lost should be sorted out first; the decision to add a general-purpose built-in first is not taken.

## Related

- [The async boundary of `action` stays explicit](./2026-04-26-action-async-boundary.md)

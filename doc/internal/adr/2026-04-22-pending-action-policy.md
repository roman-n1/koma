# Rejection of the PendingActionPolicy extension proposals

- Updated: 2026-04-29

## Background

The current `PendingActionPolicy.ClearOnStateExit` discards already-pending actions only when a transition to a different state variant is confirmed.

The following two proposals were considered.

- The case where only the value is updated while the state type stays the same
- The case where another `dispatch()` is called while one `dispatch()` is being processed, and the action is queued

Both proposals came from the motivation of automating "do not later execute an action that was queued under stale assumptions".

## Decision

Neither of the following two proposals goes into `PendingActionPolicy`.

- Discard pending actions when the state changes
- Discard actions that were additionally `dispatch()`ed while a `dispatch()` was being processed

The current `PendingActionPolicy` is kept, and where needed, explicit means such as `clearPendingActions()` are used.

## Notes

- The current `ClearOnStateExit` is "state exit based" behavior, not "state change based".
- "Discard when the state changes" fires too often if `state != nextState` is the criterion. Pending actions would disappear on many updates, including ordinary `copy(...)`, so it is too strong as a general policy.
- "Discard when the state changes" is also hard to read from the user's perspective. Pending actions disappear on what looks like an ordinary state update, so it is hard to track which updates cause actions to be discarded.
- That kind of requirement does not seem to be needed often either; rather than having it as a general-purpose feature, calling `clearPendingActions()` where needed, or expressing it through how states are split or through generation management, makes the intent easier to read.
- "Discard actions `dispatch()`ed while a `dispatch()` is being processed" is a timing-based rule rather than a state-based one, and its behavior is hard to read.
- Because whether the same action runs would depend on "when it was dispatched", consistency with `dispatchAndWait()`, middleware and follow-up actions also tends to suffer.
- Both would widen the responsibility of `PendingActionPolicy` too much, so neither is adopted at this point.
- As a related effort, for use cases such as search, resend prevention, double-submit prevention and suppressing multiple launches of the same asynchronous work, [PR #181](https://github.com/koma-kt/koma/pull/181) is in progress, which adds local overlap control to `action { launch(...) }` rather than to `PendingActionPolicy`.

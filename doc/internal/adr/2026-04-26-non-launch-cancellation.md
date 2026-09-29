# No cancellation API for non-`launch` work

- Updated: 2026-04-30

## Background

`#190` is considering `cancelLaunch(lane)`, which explicitly stops, per lane, work started with `action { launch { ... } }`.

On the other hand, ordinary `action {}`, `enter {}`, `exit {}`, `recover {}` and `transaction {}` that do not use `launch` have no API for stopping the currently running work midway.

What needs to be decided here is whether cancellation like that of `#190` should be extended to non-`launch` store work as well.

## Decision

No in-flight cancellation API is added for non-`launch` store work.

- Ordinary `action {}`, `enter {}`, `exit {}`, `recover {}` and `transaction {}` are treated as short-lived, serial and atomic store work.
- Asynchronous work that needs cancellation, or long-lived work, is moved out to `launch {}` and handled there.
- `clearPendingActions()` continues to be treated as "an API that discards the pending actions queued behind", and does not stop the store work currently in progress.

## Notes

- Non-`launch` store work is the completion unit of `dispatchAndWait()` and sits on the same serial pipeline as middleware execution and state transition decisions. Introducing mid-way cancellation makes it hard to read "how much has been applied", "are the middlewares considered complete" and "how are errors handled".
- Running long suspend work or I/O directly inside an ordinary handler tends to block the whole Store, regardless of whether it can be cancelled. Such work is assumed to be moved to `launch {}`.
- Koma takes the position that "an action is the trigger that starts processing, and the owner of in-flight work is the state". That is why `launch {}` work hangs off the state scope, and it is natural for explicit cancellation to be confined to that range first.
- Therefore, `#190` is treated not as an entry point for general cancellation but as a local extension to the existing state-owned asynchronous work of `action { launch { ... } }`.

## Related

- [#190](https://github.com/koma-kt/koma/issues/190)

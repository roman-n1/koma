# The async boundary of `action` stays explicit

- Updated: 2026-04-30

## Background

`action {}` is currently a handler executed on the Store's serial pipeline, and when needed, `action { launch { ... } }` can start asynchronous work that hangs off the state scope.

The following two proposals were considered.

- Make `action {}` equivalent to `action { launch { ... } }` by default, so that users' `dispatch()` calls are always easy to process concurrently
- Keep the two-tier structure of `action {}` and `action { launch { ... } }`, while steering users away from writing external I/O or long suspends directly inside `action {}`

The former makes it harder to block the Store, while the latter makes it easier to keep the async boundary explicit.
On the other hand, enforcing the latter through types might require a breaking change to the existing API.

## Decision

For the time being, the async boundary of `action` continues to be expressed through an explicit `launch {}`.

- `action {}` does not become an async / parallel handler by default
- The two-tier structure of `action {}` and `action { launch { ... } }` is kept
- An ordinary `action {}` is treated as short-lived, serial and atomic store work
- External I/O, long waits, work that requires cancellation, and continuing work that should be owned by the state are assumed to be moved out to `launch {}`
- However, at this point no breaking change that makes `action {}` non-suspend is introduced; this practice is first made clear through design guidelines and documentation

## Notes

- Making `action {}` async by default makes the order of state transitions, the completion unit of `dispatchAndWait()`, the before/after relationship of middleware, and the meaning of `PendingActionPolicy` harder to read.
- Koma takes the position that "an action is the trigger that starts processing, and the owner of in-flight work is the state". Therefore, marking the entry point of continuing work explicitly as `launch {}` is more consistent with the overall design.
- On the other hand, allowing long suspends or external I/O directly inside `action {}` makes it easy to block the whole Store. This problem can actually occur, but adopting default async as the solution has large side effects.
- The proposal to make `action {}` non-suspend is sound as a philosophy, but it tends to conflict with the current DSL shape, including `event()`, and the runtime benefit is limited relative to the cost of introduction.
- Adding `launch {}` carelessly increases the burden on users of managing consistency between multiple async jobs, preventing stale results from being adopted, and keeping event firing order readable. Therefore, `launch {}` remains an escape hatch that is used explicitly only where needed.

## Related

- [No cancellation API for non-`launch` work](./2026-04-26-non-launch-cancellation.md)

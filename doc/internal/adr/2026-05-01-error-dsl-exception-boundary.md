# The `recover {}` DSL is limited to the recovery path for `Exception`

- Updated: 2026-05-01

## Background

Koma's `recover {}` DSL (including the deprecated `error {}` alias) is the entry point for treating failures that occur inside the state machine as state transitions.
On the other hand, Kotlin's `Throwable` includes not only `Exception`, for which recovery should be attempted as an ordinary business exception, but also the `Error` family such as `AssertionError`, and non-standard failures such as custom `Throwable`s.

In the implementation so far, except for those immediately rethrown as fatal, `Throwable`s could broadly flow into the `recover {}` side.
With that shape, however, the following boundaries become vague.

- Failures that `recover {}` treats as recovery targets
- Failures that `exceptionHandler()` treats as the last resort
- Failures that may be treated as successful completion as a coroutine / job
- Failures that should be treated as job failure

Also, if the type boundary of the public DSL stays at `Throwable`, users have a hard time reading "up to which `Throwable` is a recovery target".

## Decision

The `recover {}` DSL is limited to a path for recovering `Exception`.

- The `T` of `recover<T>` accepts only `Exception`
- The type of `RecoverScope.error` is also limited to `Exception`
- The recoverable path inside the Store passes only `Exception` to `recover {}`
- A `Throwable` that is not an `Exception` is not regarded as recoverable and is treated as job failure
- A `Throwable` that is not an `Exception` flows to `exceptionHandler()`, but does not enter `recover {}`
- `Exception`s raised at framework boundaries such as middleware / observer / persistence are also not included in the recovery targets of `recover {}`

With this decision, the meanings are fixed as follows.

- `recover {}` is the recovery path
- `exceptionHandler()` is the last-resort path
- A failure handled by `recover {}` may be treated as successful completion of the Store work
- A failure that does not go through `recover {}` is not treated as success while unrecovered

## Notes

- `CancellationException` is an `Exception`, but it is also a control signal for coroutine cancellation, so it is not included in ordinary recovery targets.
- Therefore, at runtime the arrangement is not "always recoverable if it is an `Exception`", but "only `Exception`s that may be passed to the recoverable exception path are targeted".
- The recoverable / non-recoverable boundary is not determined by type alone. `Exception`s thrown inside a state handler or a launched `transaction {}` are passed to the recovery path, but `Exception`s thrown at framework boundaries such as middleware hooks, observer callbacks and `stateSaver.save()` are not re-injected into the recovery path.
- For this reason, the implementation wraps `Exception`s raised at framework boundaries in `InternalError` so they do not re-enter `recover {}`. Right before passing to `exceptionHandler()`, it is unwrapped so the user sees the original `Exception`.
- Inside `action {}` / `enter {}` / `exit {}` / launched `transaction {}`, users can write `throw Throwable(...)` under the Kotlin language specification. This can feel odd at the API level, so the README / KDoc state explicitly that `recover {}` is a recovery path dedicated to `Exception`.
- This decision is not source-compatible. Code that used the old `error<Throwable> { ... }` or treated a custom `Throwable` as a recovery target needs migration.
- This memo covers the error handling boundary in the Store's normal runtime path. Individual circumstances around `_state` initialization, such as state restore at startup, are not covered here.

## Related

- [Koma design principles](../design/2026-04-23-design-principles.md)

## Addendum (2026-09-29, stability review)

The third round of the [stability review](../notes/2026-09-29-stability-review.md) refined the
`CancellationException` rule: a `CancellationException` thrown while the current coroutine is
still active (an expired `withTimeout {}`, an `await()` on a cancelled `Deferred`) is an ordinary
failure of that handler and reaches `recover {}`; only the cancellation of the current coroutine
(the Store closing, the state exiting) is passed through untouched.

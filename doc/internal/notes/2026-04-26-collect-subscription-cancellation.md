# Proposal for individual unsubscription of collectState / collectEvent

- Updated: 2026-04-29

## Background

Currently `collectState()` / `collectEvent()` start collecting in the Store's internal coroutine scope and do not return any means of individual cancellation.
As a result, the lifetime of a callback-based subscription is effectively the same as the lifetime of the Store.
The only way to stop a subscription is `Store.close()`, which stops the whole Store.

This premise is natural for usages such as the following.

- One Store is held by one owner
- When the owner is no longer needed, the whole Store is `close()`d
- The lifetime of an observer is always the same as the Store's

On the other hand, it is weak for usages such as the following.

- Attaching and detaching only observers while keeping the Store alive
- Multiple places observing the same Store independently
- `collectState()` or `collectEvent()` possibly being called more than once
- Handling `state` and `event` with different lifetimes

Also, given the premise that this is an API for platforms where `Flow` is hard to use directly, requiring a `CoroutineScope` from the caller is hard to adopt.
The subject of discussion is whether to express individual unsubscription while keeping the API callback-based.

## Current thinking

If individual cancellation is added, the simplest form is for `collectState()` / `collectEvent()` themselves to return a subscription handle.
Since this changes the return type of an API with the same name, it is a breaking change.

```kt
fun collectState(state: (S) -> Unit): Subscription

fun collectEvent(event: (E) -> Unit): Subscription
```

Technically the handle type could be expressed as `AutoCloseable`, but what we want in this context is the meaning "unsubscribe" rather than "close a resource".
For clarity of intent, a dedicated `Subscription` type is therefore better.
In addition, `collectState()` / `collectEvent()` are callback APIs for platforms where `Flow` is hard to use directly.
From Kotlin, `AutoCloseable` itself is usable, and `Store` does in fact implement `AutoCloseable`.
However, once non-Kotlin callers such as Swift / Obj-C are considered, returning `AutoCloseable` as-is makes the intent of the API hard to convey.
In this context, returning a dedicated type that clearly says "this is an unsubscription handle" is more natural as a cross-platform API surface than following existing JVM/Android conventions.

```kt
interface Subscription {
    fun cancel()
}
```

Alternatively, if we want to align the vocabulary with `Store.close()`, the following form would also work.

```kt
interface ObservationHandle {
    fun close()
}
```

Either way, what matters is not returning `AutoCloseable` itself but
fixing the meaning as a thin Koma-specific handle type that corresponds to the callback-based API.

The implementation approach is simple: create a child job per subscription in the Store's internal scope and return a handle that stops that job.

- `collectState()` creates a child job that starts `state.collect`
- `collectEvent()` creates a child job that starts `event.collect`
- `Subscription.cancel()` cancels only the corresponding child job
- `Store.close()` cancels the whole Store scope as before, so all existing subscriptions stop as well

This change only "makes individual cancellation possible" and does not change the Store's start semantics themselves.
So, as things stand, the following is preserved.

- `collectState()` is a trigger for Store start
- `collectEvent()` is not a trigger for Store start

Therefore, even with this change, the asymmetry in start semantics remains as a separate issue.

Also, whether individual cancellation is really needed depends on the usage assumptions.
If the design assumes that the lifetime of the Store and the lifetime of observers always match, individual cancellation is unnecessary, and it is enough to state the current contract explicitly in the README and API comments.
Conversely, if we want to support, as a public API, the use case of keeping a Store long-lived while attaching and detaching observers, individual cancellation becomes a natural feature.

## Open questions

- Whether to lean the name and method of the returned handle toward `Subscription.cancel()` or `ObservationHandle.close()`
- Whether to also revisit the asymmetry in start semantics in the release that adds individual cancellation to `collectState()` / `collectEvent()`
- As a premise for adding individual cancellation to the callback-based API, how far to officially support the use case of multiple observers observing a Store
- How to explain, in the README, holding the subscription handle and the timing of cancellation

## Related

- [Proposal for a Store start timing policy](./2026-04-23-store-start-policy.md)

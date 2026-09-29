# No type-narrowing API such as `receiveMessages<MainMessage>` is added

- Updated: 2026-04-30

## Background

We considered a proposal to add message type specification to `receiveMessages()` in `koma-message`, as follows.

- `receiveMessages<MainMessage> { ... }`
- `receiveMessages { message: MainMessage -> ... }`

The aim was to let the receiving side naturally subscribe to only an arbitrary message type, instead of receiving all `Message`s and branching with `when` or `is`.

However, `koma-message` is a separate module, so it is not possible to add a dedicated DSL to `StoreBuilder` and move toward a form like `Store { receiveMessages<Hoge> { ... } }`.
Therefore, what comes under consideration is adding a type-specifying API to the top-level middleware factory `receiveMessages()`.

## Decision

No type-narrowing API such as `receiveMessages<MainMessage>` or `receiveMessages { message: MainMessage -> ... }` is added.

At this point, the existing `receiveMessages { message -> ... }` is kept.

## Notes

- `receiveMessages()` is a generic factory that returns `Middleware<S, A, E>`, so adding only a message type means handling the multiple type parameters `M` and `S / A / E` at the same time.
- In Kotlin, the form "specify only the first type argument explicitly and naturally omit the rest" is hard to achieve, so the appearance of `receiveMessages<MainMessage>` is hard to make work cleanly. Depending on the API shape, it tends to lean toward type argument completion involving `_`, and the call site tends to become awkward.
- The `receiveMessages { message: MainMessage -> ... }` form avoids the appearance of type arguments, but then it becomes "an API that switches the subscription target by the lambda parameter type". The contract is less visible than with the current `receiveMessages { message -> ... }`, and the notation carries meaning beyond a mere parameter annotation, so it is somewhat roundabout as an API.
- If a member DSL could be grown on the `StoreBuilder` side, a different presentation would be possible, but since `koma-message` is a separate module, that direction cannot be taken.
- Therefore, this time, "a call form that is natural as a top-level factory" is prioritized over "being able to narrow by type", and the existing API is kept.
- The necessary narrowing continues to be expressed with `receiveMessages { message -> when (message) { ... } }` or `if (message is MainMessage) { ... }`.

## Related

- [MessageMiddleware stays a simple built-in](./2026-04-27-message-middleware-lightweight.md)

# MessageMiddleware stays a simple built-in

- Updated: 2026-04-30

## Background

`MessageMiddleware` in `koma-message` is a built-in for exchanging simple messages between Stores.
The current implementation is based on a `MessageHub` shared within the process and a `MutableSharedFlow` with `replay = 0`.

Because of this, at least the following two characteristics remain.

- `MessageHub` is global, so multiple Stores and multiple features share the same bus
- A message sent before the receiver Store has started is not retained and is not redelivered later

What was considered here was whether these issues should be absorbed by `MessageMiddleware` itself.
For example, introducing scoped channels, per-receiver isolation, message retention, subscription-start synchronization, or explicit delivery policies could mitigate the above issues to some extent.

However, going in that direction moves `MessageMiddleware` away from "a simple built-in message bridge" and closer to a general-purpose messaging infrastructure for inter-Store coordination.
That enlarges the API surface and responsibilities, and tends to load the framework side with assumptions that differ per use case.

## Decision

`MessageMiddleware` stays a simple built-in.

Therefore, the following issues are not taken care of by `MessageMiddleware` itself.

- Insufficient isolation between Stores due to being a global bus
- Messages sent before the receiver Store starts not being retained

For use cases where these characteristics are a problem, rather than extending `MessageMiddleware` to absorb them, the user designs a separate means of inter-Store coordination.
The candidates envisioned are as follows.

- Use `Middleware` to subscribe to an external stream or callback bridge and `dispatch()` the required actions to each Store
- Provide a repository or data source shared by multiple Stores, and coordinate through shared state or streams instead of messages
- Provide a separate, dedicated coordination mechanism per domain with explicit scope, lifecycle, replay and buffering

## Notes

- `MessageMiddleware` prioritizes being "a lightweight, ready-to-use built-in". It is not regarded as something that should ship with strong delivery guarantees or isolation guarantees as standard.
- Requirements for inter-Store coordination differ greatly in meaning across cases such as inter-feature notifications, shared sessions, background sync and cross-screen coordination. Trying to solve all of these generically with a single built-in message bus tends to make the assumptions vaguer instead.
- When strong guarantees are needed, it is more natural for the user to have a dedicated design that makes explicit "who is connected to whom, with what lifetime, and with what redelivery policy".
- When supplementing the description of `koma-message` in the future, the direction also prioritizes "making the constraints explicit", and does not assume extension into a general-purpose messaging infrastructure.

## Related

- [Configuration policy for the MutableSharedFlow used for events](./2026-04-23-event-sharedflow-policy.md)

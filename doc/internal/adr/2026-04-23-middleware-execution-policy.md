# Middleware execution policy defaults to concurrent

- Updated: 2026-04-29

## Background

Koma allows registering multiple `Middleware`s.
Whether each lifecycle hook runs serially in registration order or concurrently should be made explicit as part of the specification.

`Middleware` is used as an extension point for separating concerns such as logging, message bridges, monitoring and auxiliary dispatches from the Store's core logic.
Therefore, once multiple `Middleware`s start depending on each other's side effects or execution order, the design tends to drift from its intent.

## Decision

The default of `MiddlewareExecutionPolicy` is `Concurrent`.

Even when multiple `Middleware`s are used, the basic principle is that each should be loosely coupled to the others and should not be designed on the assumption of another `Middleware`'s completion or side effects.
However, `InRegistrationOrder` remains as an official option.

## Notes

- The reason for defaulting to concurrent execution is not only performance. More importantly, it is to avoid encouraging designs where `Middleware`s interact with each other.
- If `Middleware A` cannot work correctly without assuming the result of `Middleware B`, they should be combined into a single responsibility rather than kept as separate middlewares, or expressed in the Store's own state/action design.
- If registration order is assumed as a strong contract, the ordering of `middleware(...)` calls effectively becomes part of the specification, and resilience to change tends to drop.
- With concurrent execution, it is easier to align with the expectation that "each `Middleware` behaves as an independent observer or extension".
- The Store waits for all middlewares to complete at each hook. Therefore, even with `Concurrent`, it is not fire-and-forget; waiting for completion is retained.
- Serial execution can be a natural choice in some integration or migration situations, or when the processing order should be explicit, so `InRegistrationOrder` is also kept as an option.

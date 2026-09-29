# Exception handling at framework boundaries not passed to `recover {}` stays as is for now

- Updated: 2026-05-07

## Background

As an existing decision, the `recover {}` DSL is limited to the recovery path for `Exception` in the Store's normal runtime path.
Therefore, exceptions raised at framework boundaries such as plugin hooks, observer callbacks and `StateSaver.save()` / `restore()` are not re-injected into `recover {}`.

This arrangement is natural for separating the state machine's recovery responsibility from failures on the framework side.
On the other hand, some plugin or saver failures do not necessarily break the consistency of the state transition itself and seem like they could be reported and then continued.

However, uniformly regarding this kind of exception as "continuable" is too coarse.
Some, such as `restore()` around startup and `Plugin.onStart()`, directly affect the Store's initialization state on failure and cannot be treated the same way.
There is also the separate point that exceptions not passed to `recover {}` are currently received by `exceptionHandler()`, so depending on configuration, users can easily overlook failures.

For these reasons, it needs to be decided at this point whether to extend the runtime behavior or handler boundaries.

## Decision

Exception handling at framework boundaries keeps the current code for the time being.

- A change that immediately and uniformly moves exceptions not passed to `recover {}`, such as plugin, observer and persistence ones, toward "report and continue" is not adopted
- A change that immediately adds a dedicated handler for system-side exceptions separate from `exceptionHandler()` is not adopted either
- At this point, "the recovery path inside the Store DSL" and "the last-resort path at framework boundaries" remain separated as they are

However, the following remain as future extension candidates.

- After assessing, for each individual failure point, the impact on Store consistency and the observability requirements, increase in a limited way only the places that can continue after an exception
- For system-side exceptions not passed to `recover {}`, introduce a handler separate from the current `exceptionHandler()`, so users can handle business errors and framework errors separately

These are retained as directions, but the concrete API and runtime policy will be decided once real use cases and operational shortcomings have been gathered.

## Notes

- This decision does not mean "the current implementation is complete and will never change". It is a deferral to avoid broadening changes in bulk without breaking them down by exception source.
- In particular, `Plugin.onStart()` and `StateSaver.restore()` are involved in whether initialization succeeds, unlike ordinary `onAction` / `onState` / `save()`, so even if reviewed in the future they are likely to be handled differently.
- The lack of observability and the lack of a continuation policy are separate problems. In the future, there could also be a proposal to separate and strengthen reporting alone without changing whether to continue.

## Related

- [The `recover {}` DSL is limited to the recovery path for `Exception`](./2026-05-01-error-dsl-exception-boundary.md)
- [`Plugin` design memo](../notes/2026-05-02-plugin-design.md)

# `launch` does not return a `Job`

- Updated: 2026-05-07

## Background

Koma's `enter { launch { ... } }`, `action { launch { ... } }` and `PluginScope.launch { ... }` all create a coroutine child `Job` internally.

We considered whether the public API should also return that `Job` from `launch()`, so callers can `cancel()` or `join()` it directly.

Returning a `Job` looks natural as a coroutine primitive, but Koma's `launch` is not a mere general-purpose coroutine launch; it is meant as the starting point of work owned by the state scope or the store root scope.
What needs to be decided here is whether the public DSL should expose the `Job` as is.

## Decision

The public DSL's `launch()` does not return a `Job`.

- `EnterScope.launch` and `ActionScope.launch` continue to be treated as APIs for starting state-owned background work.
- `PluginScope.launch` also continues to be treated as an API for starting store-owned background work.
- Coordination and cancellation of launched work are handled by higher-level lifecycles such as `ActionScope.cancelLaunch(lane)`, state exit and store close.
- Low-level operations such as `Job.cancel()` / `Job.join()` are not made standard operations of the public DSL.
- Even if use cases requiring individual handles accumulate in the future, a purpose-limited dedicated handle is considered first, rather than the `Job` itself.

## Notes

- Koma takes the position that "an action is the trigger that starts processing, and the owner of in-flight work is the state". Letting the caller hold the `Job` returned by `ActionScope.launch` makes it look as if the action caller owns that work, and the reading of ownership tends to waver.
- `ActionScope.launch` already has `LaunchControl.CancelPrevious(...)`, `LaunchControl.DropIfRunning(...)` and `cancelLaunch(lane)`, and coordination of tracked launches is expressed as a lane-based high-level API. Adding a raw `Job` here duplicates "should it be stopped by lane" and "should the held job be stopped directly".
- In particular, with `LaunchControl.DropIfRunning(...)`, a new launch request may be ignored. If the return value of `launch()` were a `Job`, "what is returned by this call" would need to be decided separately, making the API semantics unnecessarily heavy.
- Work from `EnterScope.launch` stops automatically on state exit, and work from `PluginScope.launch` stops automatically when the store root scope ends. Since Koma owns these lifecycles, it is more natural to preserve that ownership on the public surface as well.
- The design passes recoverable `Exception`s inside launched work to the `recover {}` recovery path, and the boundary we want users to see is the state machine's recovery path rather than job failure itself. Putting `Job` in the foreground makes the failure model more likely to be read as coroutine-primitive oriented.
- However, the possibility is not denied that requirements such as "the owner wants to explicitly stop only a specific background work while keeping the Store alive" may increase in the future. In that case, a dedicated type that makes clear "what this handle is for stopping" is more consistent with Koma's API surface than returning the `Job` as is.

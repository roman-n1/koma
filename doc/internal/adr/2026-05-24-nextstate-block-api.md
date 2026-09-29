# The state transition API makes `nextState {}` the canonical form

- Updated: 2026-05-24

## Background

As of 2026-04-30, the decision was to keep both `nextState(state)` and `nextStateBy { ... }` as the state transition API.

Based on subsequent use and implementation cleanup, both express the same contract: "register one next state adopted as the result of the handler".
On the other hand, having two forms on the public surface makes notations easy to mix in the README, KDoc, tests, reviews and conversations alike.

Also, the essence of the Store implementation is "hold one next state to finally adopt", and a single primitive is sufficient in the implementation.
For this reason, it is more natural to converge the public API into one form as well, while retaining source compatibility for existing users.

The current semantics are as follows.

- Calling `nextState { ... }` does not update `state` on the spot
- The value of the block's last expression is used as the next state
- When a next state is registered multiple times within the same handler, the last registered value is adopted

## Decision

The canonical form of the state transition API is `nextState { ... }`.

- The README / KDoc / tests treat `nextState { ... }` as the primary form
- `nextState(state)` and `nextStateBy { ... }` are kept for compatibility but are deprecated aliases
- The core implementation has only `nextState(block)` as the primitive, and delegation from the legacy APIs is held in the default implementation of `StoreScope`

The basic form of the block is as follows.

```kt
nextState { AppState.Loading }

nextState {
    val updated = state.items.map { item ->
        if (item.id == action.id) item.copy(done = true) else item
    }
    state.copy(items = updated)
}
```

No state receiver or argument such as `it` is passed to the block.
To refer to the current state, use the `state` held by the scope as is.

## Notes

- Converging both simple and complex transitions into the single form `nextState { ... }` narrows the public surface
- `nextState(state)` had the advantage of reading plainly in one line, but the same content can be written naturally enough as a single-expression block
- Deprecated aliases carry `ReplaceWith`, making it easier to migrate existing call sites gradually
- Alternative names such as `setState(...)` or `updateState { ... }` continue not to be adopted. The semantics is not "update the state immediately" but "choose one next state as the result of the handler"

## Related

- [The state transition API keeps both `nextState()` and `nextStateBy {}`](./2026-04-30-next-state-dual-api.md)

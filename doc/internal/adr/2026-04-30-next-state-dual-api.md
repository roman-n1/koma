# The state transition API keeps both `nextState()` and `nextStateBy {}`

- Updated: 2026-05-24

> [!NOTE]
> The decision in this ADR has been superseded by [The state transition API makes `nextState {}` the canonical form](./2026-05-24-nextstate-block-api.md).
> In the current public API, `nextState { ... }` is the canonical form, and `nextState(state)` and `nextStateBy { ... }` are kept as deprecated aliases for compatibility.

## Background

In the Store DSL, we want to sort out how to express the API that specifies the next state adopted as the result of a handler.

The main points under consideration are the following.

- Whether to keep both `nextState(state)` and `nextStateBy { ... }`, or consolidate into one
- Whether to pass a state receiver to the `nextStateBy {}` block
- Whether to pass an argument such as `it` to the `nextStateBy {}` block
- Whether to move to names like `setState` / `updateState` instead of the `nextState` family
- Whether to move to notations like `newState = ...` or `state.update { ... }`

In this decision, not only readability at the call site but also consistency with the actual semantics matters.

The current Store implementation does not update the state incrementally during a handler.
Each handler internally holds a single "next state to finally adopt", and that value is adopted when the handler finishes.
Therefore, even when `nextState(...)` or `nextStateBy { ... }` is called multiple times within the same handler, the intermediate states are not applied in turn; the last specified value is adopted.

Given these semantics, names and block forms that strongly suggest immediate application or cumulative updates should be avoided.

## Decision

The state transition API continues to keep the following two.

- `nextState(state)`
- `nextStateBy { ... }`

Their respective roles are as follows.

- `nextState(state)` is used when the next state value is already at hand, or for transitions that can be written plainly in one line.
- `nextStateBy { ... }` is used when building the next state while writing intermediate variables or branches.

No state receiver is passed to the `nextStateBy {}` block.
Also, an argument such as `it` is not part of the basic form.
To refer to the current state inside the block, use the `state` held by the scope as is.

```kt
nextState(AppState.Loading)

nextStateBy {
    val updated = state.items.map { item ->
        if (item.id == action.id) item.copy(done = true) else item
    }
    state.copy(items = updated)
}
```

No unification into a block version such as `nextState { ... }` is done.
Also, no renaming to `setState(...)` or `updateState { ... }` is done.
Alternative notations such as `newState = ...` or `state.update { ... }` are not adopted either.

The behavior when the state is specified multiple times remains last-wins, as it is now.
That is, when `nextState(...)` / `nextStateBy { ... }` is called multiple times within the same handler, only the last specified value is adopted.

## Notes

- The `by` in `nextStateBy {}` expresses the intent that "this is not a separate DSL scope but a place where the next state is built on the spot in Kotlin code". With `nextState {}`, it would look the same as `state {}` or `action {}`, and tend to look like a configuration DSL block.
- Passing a state receiver has the advantage of writing `copy(...)` concisely, but it tends to blur the boundary with members of the outer scope such as `action`, `error`, `event()` and `clearPendingActions()`. In a DSL with many scopes like Actron's, not adding implicit `this` is more readable.
- Using `it` or an explicit argument is also an option, but `state.copy(...)` makes it more directly readable "what the next state is built from". The block's role is computation, and shortening via a parameter is not prioritized.
- `setState(...)` tends to be read as immediate application, and `updateState { ... }` as sequential or cumulative updates. However, the actual semantics is "choose one next state adopted as the result of the handler", and these names tend to create stronger expectations than the reality.
- `newState = ...` has the advantage of looking like "ordinary Kotlin assignment", but as a public DSL it would expose a mutable result slot as is. Contracts such as whether `newState` can be read midway, what happens when it is assigned multiple times, and what an unassigned value means tend to leak into the API, so it is not adopted.
- `state.update { ... }` tends to look like an API that mutates the current `state` in place, or accumulates sequential updates onto the current value like `MutableStateFlow.update`. However, Actron's `state` is a snapshot value of the current state held by the scope, and the semantics is not "update the state itself" but "choose the next state as the result of the handler". For this reason `state.update { ... }` tends to be read as closer to mutable object manipulation than the actual behavior, and is not adopted.
- Last-wins is not a feature we want to actively encourage, but as fallback semantics that naturally determines the final result in handlers with branches or early returns, it is reasonable. If needed, this behavior is stated explicitly in the README / KDoc / tests.
- `first-wins` is not adopted either. In ordinary Kotlin code, reading top to bottom, a later assignment or specification is naturally taken as the final result. A design where `A` is adopted even though `nextState(B)` is written after `nextState(A)` is more surprising than `last-wins`, because the later statement is visible yet has no effect. If multiple specifications should be treated more strictly, detecting duplicate specification as an error is more natural than silent `first-wins`.
- While handler resolution for `state<S2>` / `action<A2>` is first-wins by registration order, `nextState(...)` / `nextStateBy { ... }` inside the selected handler is last-wins. This combination may feel slightly odd at first, but the two are behaviors at different layers. The former is routing, "which rule to adopt", while the latter is result selection, "what the result of the adopted rule should be". Rather than forcing them into the same winning rule, it is clearer to adopt what is natural at each layer: first-wins for routing and last-wins for result specification.

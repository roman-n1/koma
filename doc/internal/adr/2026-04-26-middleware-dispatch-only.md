# No direct state update API in Middleware

- Updated: 2026-04-30

## Background

The current `MiddlewareScope` exposes only `dispatch()` and `launch()`, and middleware itself cannot modify state directly.

The current picture is as follows.

```kt
interface MiddlewareScope<A : Action> {
    fun dispatch(action: A)
    fun launch(
        dispatcher: CoroutineDispatcher? = null,
        block: suspend CoroutineScope.() -> Unit,
    )
}
```

When middleware receives external input, it `dispatch()`es an action, and that action is processed on the state machine side.

```kt
override suspend fun onStart(middlewareScope: MiddlewareScope<AppAction>, state: AppState) {
    middlewareScope.launch {
        repository.observe().collect { value ->
            middlewareScope.dispatch(AppAction.ExternalValueArrived(value))
        }
    }
}
```

What was considered here was a proposal to make `transaction {}` usable inside the `launch {}` of `MiddlewareScope`, so that middleware could update state directly while receiving an external stream or callback bridge.

The picture of the proposal is something like the following.

```kt
interface MiddlewareScope<S : State, A : Action, E : Event> {
    fun dispatch(action: A)
    fun launch(
        dispatcher: CoroutineDispatcher? = null,
        block: suspend LaunchScope<S, E>.() -> Unit,
    )

    interface LaunchScope<S : State, E : Event> : StoreScope {
        val isActive: Boolean
        suspend fun event(event: E)
        suspend fun transaction(
            dispatcher: CoroutineDispatcher? = null,
            block: suspend TransactionScope<S, E>.() -> Unit,
        )
    }
}
```

```kt
override suspend fun onStart(
    middlewareScope: MiddlewareScope<AppState, AppAction, AppEvent>,
    state: AppState,
) {
    middlewareScope.launch {
        repository.observe().collect { value ->
            transaction {
                val current = this.state as? AppState.Ready ?: return@transaction
                nextState { current.copy(value = value) }
            }
        }
    }
}
```

This proposal has the advantage of not having to add relay actions solely for converting external input into actions.

On the other hand, adding a direct state update capability means middleware takes on the role of a writer that updates the Store's state, in addition to bridging and observing external input.
Also, since middleware runs concurrently by default, once multiple middlewares start behaving as direct state writers, the boundary of responsibilities and the visibility of update paths tend to weaken.

## Decision

No API for updating state directly is added to `Middleware`.

- `MiddlewareScope` continues to expose only `dispatch()` and `launch()`.
- When middleware wants to change state, it continues to `dispatch()` an action and have it processed on the state machine side.
- State update APIs such as `launch { transaction { ... } }` are not introduced into `MiddlewareScope`.

## Notes

- With this decision, the write path for state continues to be centered on state/action handlers. Middleware stays limited to the responsibilities of bridging and observing external input, and auxiliary dispatches.
- If `MiddlewareScope` gained a direct state update capability, middleware would become something close to an additional writer of the Store rather than an observer or bridge. That is one level heavier a responsibility than its current position in the design.
- Since middleware runs concurrently by default, a design where multiple middlewares update state directly tends to make ordering dependencies and division of responsibilities hard to read. If updates depend on each other, it is more natural to combine them into a single middleware or express them in the Store's own state/action design.
- If the demand to reflect an external stream or callback bridge into the Store without going through actions grows stronger in the future, it will be considered afresh as a separate API rather than as a middleware extension.

## Related

- [Middleware execution policy defaults to concurrent](./2026-04-23-middleware-execution-policy.md)

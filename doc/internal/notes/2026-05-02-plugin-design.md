# Plugin design notes

- Updated: 2026-05-09

## Background

Actron already has `Middleware`, but it has many hooks, and the internal `before/after` lifecycle is exposed directly in the public API.

On the other hand, for uses such as logging, analytics, message bridges, autosave and sync, hooks of the same granularity and nature as `Middleware` are not necessarily needed.
We therefore want to move toward adding a `Plugin` with a smaller surface, separate from `Middleware`.

The points at issue this time are as follows.

- What kind of extension point `Plugin` is for
- Which hooks to expose

## Current thinking

### The role of `Plugin`

`Plugin` is not for hijacking the Store's main pipeline; it is treated as **an extension point for observation and external integration**.

- Recording logs and analytics
- Starting subscriptions to a message bus / websocket / push, etc.
- Saving or syncing after a state update
- Auxiliary action dispatch

Conversely, interception such as replacing the action currently being processed, swallowing it, or rewriting `nextState` from the side is not a responsibility of `Plugin`.

### Surface

The current `Plugin` surface is based on the following.

```kt
interface Plugin<S : State, A : Action, E : Event> {
    suspend fun onStart(scope: PluginScope<S, A>, state: S) {}
    suspend fun onAction(scope: PluginScope<S, A>, state: S, action: A) {}
    suspend fun onState(scope: PluginScope<S, A>, prevState: S, state: S) {}
    suspend fun onEvent(scope: PluginScope<S, A>, state: S, event: E) {}
}

interface PluginScope<S : State, A : Action> {
    fun dispatch(action: A)

    fun launch(
        dispatcher: CoroutineDispatcher? = null,
        block: suspend LaunchScope<S, A>.() -> Unit,
    )

    interface LaunchScope<S : State, A : Action> {
        val currentState: S
        fun dispatch(action: A)
    }
}
```

The phase of each hook is aligned not by mechanically pairing `before` / `after`, but by **which side of the Store boundary is observed**.

- `onStart`
  - At Store start
  - Before the first `enter {}`
- `onAction`
  - Before the action handler starts
  - Reliably captures dispatch attempts
- `onState`
  - After state commit / save / observer notification
- `onEvent`
  - After event emit / observer notification

Under this arrangement,

- `onAction`
  - Is a hook that observes **input** to the Store, so it runs before
- `onState`
  - Is a hook that observes **output**, the committed state from the Store, so it runs after
- `onEvent`
  - Is a hook that observes **output**, the event emitted from the Store, so it runs after

is how the hooks line up.

`onStart` belongs to neither input nor output, so it is treated as a different kind of lifecycle hook.
If you want to see a state type change, checking `prevState::class != state::class` inside `onState` is enough, so there is no dedicated hook.

### `PluginScope`

`PluginScope` itself is made usable from all hooks.
Fire-and-forget operations that return immediately can be called from the hook body as well, and "operations that require launch", such as long-running processing, ongoing subscriptions and referencing the latest state, are confined to `launch {}`.

The following minimal set is enough for the scope.

- `dispatch(action)`
  - Enqueue an auxiliary action from the hook body
  - `Store.dispatch` is fire-and-forget to begin with, so growing a `launch {}` just for this would only create an extra coroutine
- `launch { ... }`
  - Start Store-scoped background work
- `LaunchScope.currentState` inside `launch {}`
  - Processing that runs later reads the latest committed state snapshot
- `LaunchScope.dispatch(action)` inside `launch {}`
  - Enqueue an auxiliary action from background work

Permissions such as `transaction`, `nextState`, `emit` and `cancelLaunch` are not granted.
Going that far pushes the plugin toward a hidden handler or interceptor, and its responsibility becomes too heavy.

### Cleanup leans on `launch { try/finally }`

A proposal to add `onClose` to `Plugin` is conceivable, but is not adopted at present.

The reason is that `Store.close()` is not the only termination path of a Store.
The Store's root scope hangs off a parent `Job`, so it can also terminate through cancellation on the parent scope side.
If `onClose` were called only from `Store.close()`, it would capture "explicit close" but not "the Store's lifetime has ended".

Therefore, it is more natural for plugin-side cleanup to lean on the `finally` of a coroutine started with `PluginScope.launch`, rather than an explicit close hook.

```kt
override suspend fun onStart(scope: PluginScope<S, A>, state: S) {
    scope.launch {
        val subscription = bus.subscribe { message ->
            dispatch(...)
        }

        try {
            awaitCancellation()
        } finally {
            subscription.dispose()
        }
    }
}
```

In this form, the same cleanup runs on both `Store.close()` and a parent scope cancel.
It is also clear that the owner of the cleanup hangs off the lifetime of the work itself.

In the first place, ongoing processing such as `Flow.collect` stops through the coroutine cancellation of the main Store alone, so there are few occasions to write explicit cleanup at all.
In the sense that the most representative plugin use cases have no need for a cleanup hook, the gain from introducing an explicit close hook is small.

### No onError

A proposal to add an `onError` hook to `Plugin` for exception notification is conceivable, but is not adopted at present.

The reason is that it does not fit with the responsibility of `Plugin` leaning toward observation and external integration.
`onAction` / `onState` / `onEvent` are all hooks that observe the **boundary** of the Store, looking at the Store's input (action) or output (state / event).
Exceptions, on the other hand, are events that occur on the error pipeline inside the Store, and exposing them as a hook easily turns into the plugin peeking into the Store's internal logic.

However, if a requirement arises in the future to report errors to a Crashlytics-style plugin, it is worth reconsidering.
In that case, errors that occur outside the business logic on the Store DSL, which are received by `Store.exceptionHandler`, cannot be captured even if `onError` is added to the plugin, so what to target for reporting needs to be considered together.

### It does have an execution policy

`Plugin` is given a `PluginExecutionPolicy`.
The default is `Concurrent`, and `InRegistrationOrder` can be selected only when needed.

The reason for this approach is that `Plugin`, like `Middleware`, is usually written as an outer extension that is independent of the others even when several are registered.
In particular, the current `Plugin` is observation-based, and

- `onAction` is an input hook
- `onState` / `onEvent` are output hooks

is how it is organized.
Given this nature, plugins should in principle be writable without depending on order, which fits well with the idea of making `Concurrent` the default.

On the other hand, there is no need to completely rule out compatibility during a migration period, or cases where order matters in a particular setup.
`InRegistrationOrder` is therefore kept as an escape hatch.

However, `InRegistrationOrder` is treated as an exceptional option, not the normal path.
Reusable plugins should basically aim to work safely under the assumption of `Concurrent`.

Also, for heavy or long-running work inside a plugin, it is more straightforward to offload it to `scope.launch { ... }` than to rely on the execution policy of the hook itself.
So what the execution policy controls is limited strictly to **the order of the hook calls themselves**.
It does not guarantee the completion order of background work, or the interleaving of `dispatch()` calls made from it.

The idea of making the `Middleware` default concurrent execution is to "not assume order dependence between middlewares" ([Middleware execution policy defaults to concurrent](../adr/2026-04-23-middleware-execution-policy.md)).
For `Plugin` too, `Concurrent` is made the standard on the basis that "plugins should be order-independent first".

If in the future what users really want is control such as "run this plugin first" or "see this group of plugins at a later stage", an explicit concept such as priority or phase is closer to the essence than the binary choice of `Concurrent` / `InRegistrationOrder`.
So even with an execution policy introduced, it is not regarded as sufficient for future order control.

## Open questions

- Whether to move `Plugin` toward a replacement for `Middleware` in the future, or to keep both in parallel, is undecided.
- How far to make plugin long-running work and cleanup explicit on a `launch { try/finally }` basis in the README and samples is undecided.
- Whether to keep the plugin execution policy as the binary choice `Concurrent` / `InRegistrationOrder` in the future, or to add a separate concept such as priority / phase, is undecided.
- If a requirement arises in the future to observe the reason the Store lifetime ended, there is room to consider a separate hook based on root `Job` completion rather than `onClose`.

## Related

- [Middleware execution policy defaults to concurrent](../adr/2026-04-23-middleware-execution-policy.md)
- [Framework boundary exception handling that is not routed to `recover {}` stays as-is for now](../adr/2026-05-07-framework-boundary-exception-handling.md)

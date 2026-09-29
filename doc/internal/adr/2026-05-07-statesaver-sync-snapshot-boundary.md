# `StateSaver` is limited to a synchronous snapshot API

- Updated: 2026-05-11

## Background

Koma's Store exposes `state: StateFlow<S>` and `currentState: S` as synchronous APIs.
Since a `StateFlow` is constructed with an initial value, that initial value must be determined synchronously at Store creation.
In the current implementation, if `stateSaver.restore()` returns a result, it is adopted as the initial value of `_state` / `StateFlow`.
In other words, the behavior is not "show the declared `initialState` once, then replace it with the restore result", and changing this would also confuse users working with `StateFlow`.

Making only `save()` `suspend` is another possibility, but then `StateSaver` would have both the responsibility of a "synchronous snapshot adapter" and that of an "asynchronous persistence gateway", muddying the meaning of the abstraction.

## Decision

`StateSaver` is kept as an API representing the Store's synchronous snapshot boundary.

- `StateSaver.restore()` stays synchronous
- `StateSaver.save()` also stays synchronous
- `StateSaver` is used for lightweight, immediate saving/restoring of snapshots
- `StateSaver` is not given the responsibility of heavy I/O or `suspend` read/write

When `suspend` read/write is wanted for heavy persistence such as network / database / file I/O, the user implements it separately, as follows, instead of using `StateSaver`.

- read:
  Make a `Loading` state the initialState, perform the `suspend` read from `enter {}`, and transition to the next state according to the result
- write:
  Perform the `suspend` write from a plugin, triggered by the committed state

## Notes

- This decision preserves the synchronicity of the `StateFlow` initial value and `currentState`.
- Since `enter {}` itself is `suspend`, `launch {}` is not needed for an asynchronous read, but `launch {}` is used when you want to proceed concurrently without making startup wait.
- Since `Plugin.onState` itself is `suspend`, `launch {}` is not needed for an asynchronous write, but `launch {}` is used when you do not want the Store's progress to wait for the write to complete.
- If you want only `save()` to be `suspend` (with restore being fine as synchronous), provide a separate Plugin that performs the `suspend` write from `Plugin.onState`, without splitting the responsibility of `StateSaver`. The synchronous boundary of `StateSaver` is preserved as is.

## Supplementary proposal: asynchronous persistence via a Plugin

As decided in the body of the ADR, `StateSaver` is closed to synchronous use. On the other hand, users who want persistence involving suspend read/write, such as DataStore / file I/O / network, end up hand-assembling `Loading` state + `enter {}` + `Plugin.onState` every time, leaving boilerplate.
A supplementary proposal to codify this pattern using only the existing Plugin API is recorded below (a design memo, not a decision).

### Design

No new top-level abstraction is added; it is composed solely of the existing Plugin (`onStart` / `onState` / `PluginScope.launch` / `dispatch`).

```kotlin
fun <S : State, A : Action, E : Event> AsyncStatePersistencePlugin(
    load: suspend () -> S?,
    save: suspend (S) -> Unit,
    onLoaded: (S) -> A,
    onLoadFailed: ((Throwable) -> A)? = null,
): Plugin<S, A, E> = Plugin(
    onStart = { _ ->
        launch {
            runCatching { load() }
                .onSuccess { restored -> if (restored != null) dispatch(onLoaded(restored)) }
                .onFailure { e -> onLoadFailed?.let { dispatch(it(e)) } }
        }
    },
    onState = { _, state ->
        save(state)
    },
)
```

### Usage picture

```kotlin
Store {
    initialState = MyState.Loading
    plugin(
        AsyncStatePersistencePlugin(
            load = { dataStore.data.first() },
            save = { dataStore.updateData { _ -> it } },
            onLoaded = MyAction::Restored,
        )
    )
    state<MyState.Loading> {
        action<MyAction.Restored> {
            nextState { action.state }
        }
        action<MyAction.RestoreFailed> {
            nextState { MyState.Empty }
        }
    }
    state<MyState.Loaded> {
        // ordinary handlers
    }
}
```

### Consistency with the ADR

- The synchronous boundary of `StateSaver` is not broken. Since it is provided only as a combination of Plugins, it does not contradict the decision in the body.
- It packages the policy of "move heavy I/O out to `enter {}` / `Plugin.onState`" so users do not have to assemble it by hand.

### Constraints and trade-offs

- `PluginScope` has no API for writing state directly (only via `dispatch`), so users need to define a "restore succeeded" Action and, if needed, a "restore failed" Action. This is an intentional constraint to preserve one-way data flow.
- In Koma's DSL, `action {}` can only be written under `state {}`, so the handler for the restore Action is written only under the initial state (typically `Loading`). This is consistent, at the DSL level, with the intent that "the restore Action is meaningful only in the initial state".

### Placement and delivery policy

- No dependencies such as DataStore are brought into `koma-core` itself. Accepting `load` / `save` as `suspend` lambdas keeps it generic.
- Proposal to provide it as a separate module (e.g. `koma-persistence`) or as a sample.
- Release it under `@ExperimentalKomaApi` and decide on promotion to the mainline after observing actual usage.

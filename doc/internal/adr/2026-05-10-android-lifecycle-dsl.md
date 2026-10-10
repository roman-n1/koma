# No Android lifecycle hooks are introduced into the `Store{}` DSL

- Updated: 2026-05-10

## Background

We considered a proposal to make Activity / Fragment lifecycles such as Android's `onResume` / `onPause` (or `onStart` / `onStop`) directly observable from the `Store{}` DSL.

If implemented in the DSL, it would take a form like the following, for example.

```kt
state<Hoge> {
    onResume {
        ...
    }
}
```

Here, there is already an alternative route: subscribe to the lifecycle on the UI side or Plugin side and `dispatch()` `onResume` as an action.

```kt
state<Hoge> {
    action<Fuga> {
        ...
    }
}
```

If `Fuga` is defined as "the action representing `onResume`", there is little difference in convenience from writing `onResume {}` inside the DSL.

Nevertheless, placing a dedicated hook in the `Store{}` DSL means the `Store` itself observes an Android lifecycle source. That is too large for the current responsibility of `Store{}` (a state machine centered on state, action and event).

In addition, Actron is a library premised on Kotlin Multiplatform, and the `Store{}` DSL is designed as a common API not limited to Android. Bringing Android-specific concepts such as `onResume` / `onPause` into the core DSL is itself inconsistent with the library's positioning.

## Decision

No hooks that directly observe the Android lifecycle (`onResume` / `onPause` / `onStart` / `onStop`, etc.) are added to the `Store{}` DSL.

- When the lifecycle should be reflected in the Store, subscribe to the lifecycle on the UI side or Plugin side, `dispatch()` the corresponding action, and handle it with the ordinary `state<...> { action<...> { ... } }`.
- The `Store{}` DSL does not depend on Android APIs. Android-specific lifecycle observation logic is not brought into the Store itself.

## Notes

- There is no large difference in expressiveness on the user side between having a dedicated hook and `dispatch()`ing an action. So there is little value in having the `Store{}` DSL internally hold a mechanism for monitoring the lifecycle.
- Lifecycle monitoring itself is more naturally confined to layers that know the external lifecycle source, such as Activity / Fragment or Plugins. Lifting that responsibility up to the `Store{}` DSL would make the Store both "the subject of state transitions" and "a lifecycle observer", widening the boundary of responsibilities too much.
- Actron is a library premised on multiplatform, and bringing platform-specific concepts (here, Android's `onResume` / `onPause`, etc.) into the core DSL breaks the neutrality of the library as a whole. Platform-specific matters are absorbed on the user side or outside the Store, such as in Plugins / Middleware.
- On the other hand, if a lifecycle-like concept common to all platforms can be abstracted, introducing it into the `Store{}` DSL may be reconsidered. Since this rejection depends on it being "Android-specific", that premise no longer holds if it can be modeled in a platform-neutral way.

## Related

- [No state-independent `onStart {}` is added to the `Store{}` DSL](./2026-04-28-store-onstart-dsl.md)
- [No direct state update API in Middleware](./2026-04-26-middleware-dispatch-only.md)

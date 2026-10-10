# Draft feature request: keep matcher metadata in the handler registry

- Updated: 2026-09-28 (thank-you opening, links to the working change in the fork)
- Status: draft, not filed. Target: koma-kt/koma. Roman posts it himself.

---

**Title:** Proposal: keep matcher metadata in the handler registry (groundwork for routing diagnostics)

Hello, and thank you for Actron.

I use it and like it very much. I especially like the direction of the recent
releases: explicit state variants, state-scoped work, `LaunchControl`,
`recover {}`, plugins and `actron-test`. Each step makes the state machine side of
Actron stronger, and I would like to help in the same direction.

Here is what I would like to propose as a first, small step.

### Background

`doc/internal/notes/2026-04-25-unhandled-action-behavior.md` describes a
diagnostics gap. From the outside, it is hard to tell an unhandled action from a
handled action that did not change the state. Shadowing by first-match-wins is
also hard to see. The note proposes routing diagnostics in `:actron-test`
(`diagnoseActionMatches`, and an assert on the match count at dispatch time). It
also mentions checks at `build()` time as a later option.

The note also explains what blocks the `build()`-time checks: `StoreBuilder`
keeps only predicate lambdas (`it is S2`, `it is A2`). The information about
which types a handler was declared for is lost at registration.

### Proposal

When `state<S2> {}`, `action<A2> {}` and `recover<T> {}` register a handler, keep
a small matcher next to the predicate:

```kotlin
@PublishedApi
internal data class HandlerMatcher(
    val stateType: KClass<*>,      // S2
    val inputType: KClass<*>? = null, // A2 for action {}, T for recover {}, null for enter {} / exit {}
)
```

`reified` already gives `S2::class`, `A2::class` and `T::class` at the call
site, so no reflection is needed and it works on every KMP target.

In this first step, nothing public changes:

- Handler selection is still first-match-wins by predicate, exactly as today.
- The matchers are internal. The built Store keeps them as a list per handler
  kind (`enter`, `action`, `exit`, `recover`), in registration order, so index
  `i` in the list is handler `i` in first-match order.
- `StateHandler` and `ThreadedHandler` keep their old constructors. Inline code
  that was compiled against 4.0.0 still works; its handlers simply have no
  matcher.

### Working change

I made this change in my fork so you can look at real code:

- Branch: https://github.com/roman-n1/actron/tree/feature/handler-matcher-metadata
- Diff: https://github.com/roman-n1/actron/compare/main...feature/handler-matcher-metadata

It touches `StoreBuilder.kt` and `StoreImpl.kt` and adds `HandlerMatcher.kt`.
`StoreHandlerRegistryTest` checks that the matchers record the right types in
first-match order for `enter`, `action`, `exit` and `recover`, including a broad
`state<AppState> { action<AppAction> {} }` fallback registered last. The
existing `actron-core` tests pass without changes.

### What it enables later

1. The `:actron-test` routing diagnostics from the note. A failing test could say
   "handler #3 `state<Loading> / action<Retry>` is shadowed by handler #1
   `state<AppState> / action<Retry>`" instead of only an index.
2. Optional checks for possible shadowing at `build()` time or in tests. They
   would report facts, not errors, as the note suggests.
3. If you want it later, a read-only routing table ("which action types each
   state type handles") for documentation or for handler coverage in tests.

### Non-goals

- No change to runtime behavior, handler order or the public DSL.
- No new runtime policy for unhandled actions. I agree with the note on this.

If this direction is fine for you, I can open a small PR with this change first,
and then a separate PR for the `:actron-test` diagnostics. I am also happy to
change the naming or the shape to fit your plans.

Thank you for your time.

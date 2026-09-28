# Draft feature request: keep matcher metadata in the handler registry

- 更新日: 2026-09-28
- Status: draft, not filed. Target: koma-kt/koma.

---

**Title:** Proposal: keep matcher metadata in the handler registry (groundwork for routing diagnostics)

### Background

`doc/internal/notes/2026-04-25-unhandled-action-behavior.md` identifies a
diagnostics gap: from the outside, users cannot tell an unhandled action apart
from a handled action that left the state unchanged, and first-match-wins
shadowing is invisible. That note proposes `:koma-test` routing diagnostics
(`diagnoseActionMatches`, a dispatch-time match-count assert) and mentions
`build()`-time checks as a later option.

It also points out what blocks the `build()`-time checks: `StoreBuilder`
currently keeps only predicate lambdas (`it is S2`, `it is A2`), so the
information about which matcher a handler came from is lost at registration.

### Proposal

Keep a small matcher descriptor next to each predicate when `state<S2> {}` and
`action<A2> {}` register handlers, for example:

```kotlin
internal sealed interface StateMatcher {
    data object AnyState : StateMatcher
    data class StateType(val type: KClass<out State>) : StateMatcher
}

internal sealed interface ActionMatcher {
    data object AnyAction : ActionMatcher
    data class ActionType(val type: KClass<out Action>) : ActionMatcher
}
```

`reified` already gives us `S2::class` and `A2::class` at the call site, so this
needs no reflection and works on every KMP target.

In the first PR, nothing public changes:

- handler selection stays first-match-wins by predicate, exactly as today;
- the descriptors are internal and only exposed to `:koma-test` through
  `StoreInternalApi` (the same bridge `startAndAwait` / `patch` already use).

### What it unlocks

1. The `:koma-test` routing diagnostics that the note already sketches:
   `diagnoseActionMatches(state, action)` returning matched and selected handler
   indices, plus their matcher descriptors, so a failing test can say *"handler
   #3 `state<Loading> / action<Retry>` is shadowed by #1 `anyState / action<Retry>`"*
   instead of printing only an index.
2. Optional `build()`-time or test-time checks for possible shadowing, reported
   as facts, not errors, in line with the note's position that the library
   should not decide whether an overlap is intentional.
3. Later, if wanted, a read-only routing table ("which action types each state
   type handles") that tools can render as documentation or use for handler
   coverage in tests.

### Non-goals

- No change to runtime semantics, handler ordering or the public DSL.
- No new runtime policy for unhandled actions (the note argues against that and
  this proposal agrees).

### Compatibility

The change is purely additive and internal. Existing stores behave identically.

If this direction works for you, I am happy to open a small PR with the
registry change and tests first, and follow up with the `:koma-test`
diagnostics as a separate PR.

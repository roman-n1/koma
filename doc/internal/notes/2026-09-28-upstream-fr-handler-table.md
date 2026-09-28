# Draft feature request: describe all handlers in koma-test

- 更新日: 2026-09-28
- Status: draft, not filed. Target: koma-kt/koma. File after the routing
  diagnostics step. Roman posts it himself.

---

**Title:** Proposal: `describeHandlers()` in koma-test, a readable view of a Store's handlers

Hello again, and thank you for the discussion so far.

This is a small follow-up to the routing diagnostics. The diagnostics answer
"which handlers match this state and this action?". This step answers a simpler
question: "which handlers does this Store have at all?".

### Proposal

```kotlin
fun <S : State, A : Action, E : Event> Store<S, A, E>.describeHandlers(): StoreHandlers
```

`StoreHandlers` has four lists in first-match order: `enter`, `action`, `exit`
and `recover`. Each entry shows the declared types. `toString()` prints one
handler per line:

```
enter:
  #0 state<Loading>
action:
  #0 state<Main> / action<Increment>
  #1 state<Main> / action<Fail>
  #2 state<AppState> / action<Reset>
exit:
  #0 state<Error>
recover:
  #0 state<Main> / recover<IllegalStateException>
```

This helps in three ways:

- A test can compare this text with an expected text. A change in handler
  order, which changes first-match behavior, then shows up in review.
- It is a quick way to read a large Store while debugging.
- It can be pasted into documentation.

It does not show transition targets, because `nextState {}` is ordinary code.
No handler runs and the Store is not started.

### Why in koma-test and not in core

`doc/internal/notes/2026-05-02-plugin-design.md` and
`doc/internal/notes/2026-04-25-unhandled-action-behavior.md` both prefer to keep
the public surface of `koma-core` small. So this step only adds one
`@InternalKomaApi` method to `StoreInternalApi` (`handlerMetadata()`), and the
public API lives in `:koma-test`, next to `diagnoseActionMatches`.

### Working change

- Branch: https://github.com/roman-n1/koma/tree/feature/handler-table
- Diff on top of routing diagnostics:
  https://github.com/roman-n1/koma/compare/feature/routing-diagnostics...feature/handler-table

`StoreHandlersTest` covers all four handler kinds, the text format, empty
Stores, and that describing does not start the Store.

Thank you. I am happy to adjust names or the text format.

# Draft feature request: routing diagnostics in koma-test

- 更新日: 2026-09-28
- Status: draft, not filed. Target: koma-kt/koma. File only after step 1
  (handler matcher metadata) is accepted, ideally as a comment on that issue or
  as a follow-up issue that links to it. Roman posts it himself.

---

**Title:** Proposal: routing diagnostics in koma-test (`diagnoseActionMatches`)

Hello again, and thank you for looking at the matcher metadata proposal.

This is the next small step. It builds on that change and follows section 2 of
`doc/internal/notes/2026-04-25-unhandled-action-behavior.md` very closely.

### Proposal

Add a routing check to `:koma-test` that does not dispatch and does not run any
handler:

```kotlin
fun <S : State, A : Action, E : Event> Store<S, A, E>.diagnoseActionMatches(
    state: S,
    action: A,
): ActionMatchDiagnostics<S, A>

suspend fun <S : State, A : Action, E : Event> Store<S, A, E>.diagnoseActionMatches(
    action: A,
): ActionMatchDiagnostics<S, A> // awaits startup, then uses currentState
```

`ActionMatchDiagnostics` has `handlers` (all action handlers in first-match
order, each with its state type and action type), `matchedHandlerIndices`,
`matchedHandlerCount` and `selectedHandlerIndex`. As the note says, it reports
facts only: 0 matches is unhandled, 1 match is handled, 2 or more matches may be
intended or may be shadowing, and the library does not decide which.

Thanks to the matcher metadata, a failing assertion is easy to read:

```
ActionMatchDiagnostics(state=Main(count=0), action=Reset: selected #1 state<Main> / action<Reset>, shadowed #2 state<AppState> / action<Reset>)
```

### How it is wired

- `StoreInternalApi` gets one method, `matchActionHandlers(state, action)`. It
  evaluates the same predicates the Store uses and returns one
  `ActionHandlerMatch` per handler. Both are `@InternalKomaApi`, like the rest
  of that bridge.
- `:koma-test` turns this into the public `ActionMatchDiagnostics`. The
  `(state, action)` overload does not start the Store.

### Working change

- Branch: https://github.com/roman-n1/koma/tree/feature/routing-diagnostics
- Diff on top of the matcher metadata change:
  https://github.com/roman-n1/koma/compare/feature/handler-matcher-metadata...feature/routing-diagnostics

`ActionMatchDiagnosticsTest` covers handler order, one match, no match, several
matches with a broad fallback registered last, that the check does not start the
Store, and that the suspend overload uses the state after startup.

### Not in this step

- The dispatch-time assert from section 3 of the note
  (`dispatchAndRequireMatchCount` or similar). I kept it for a separate PR, as
  the note suggests building it on top of this observation API.
- No change to runtime behavior.

If you prefer different names or a different result shape, I am happy to change
them. Thank you.

# Summary of unhandled action behavior

- Updated: 2026-04-25

## Background

Currently, for a dispatched action, the Store selects and executes exactly one handler that matches both the current `state` and the `action`.

Action handlers are evaluated in registration order, and only the first match is adopted.
If no handler matches at all, the action simply ends without doing anything, and the state does not change.

This behavior itself is not unnatural for a state machine.
Because of UI circumstances or the timing of asynchronous processing, it is quite normal for "an action that is invalid in the current state" to arrive.

On the other hand, with the current behavior the following two cases are hard to tell apart from the outside.

- An action that may genuinely be ignored in that state
- An action that is accidentally ignored because of an omission in the Store DSL, a wrong order when multiple handlers match, or a mistaken assumption

## Problem summary

The main issue with `Unhandled action behavior` is not a missing runtime policy but the following diagnostic gaps.

### 1. `unhandled` and `handled but state unchanged` are hard to distinguish from the outside

Currently, an unhandled action is simply observed as state unchanged.
But state unchanged occurs not only when unhandled, but also in ordinary situations such as the following.

- A handler matched but does not call `nextState { ... }`
- A handler only emits an event
- A handler only performs side effects such as `clearPendingActions()`

Because of this, it is hard for the user to tell whether the action "was unhandled" or "was handled but the state did not change".

### 2. The DSL surface does not strongly convey first-match-wins

The current `state<S2>` / `action<A2>` / `anyState` / `anyAction` look declarative, and are easy to read as adding a set of handlers that match the conditions.
However, the actual resolution is first match wins according to registration order, and semantically it is closer to an ordered rule chain.

`anyAction` and `anyState` in particular easily look like "adding a handler with a broad condition".
In reality, however, depending on where they are placed, they can cover the handlers that follow.

As a result, it is easy for a mismatch to occur where the user wrote the DSL with additive intent, while in reality first-wins order dependence is in effect.

### 3. The meaning of multiple matches cannot be decided on the library side

When multiple handlers match at the same time, whether that is a mistake or an overlap the user intended cannot be determined by the library alone.
What the library can know is only the facts: "there were 0", "there was 1", "there were 2 or more", and "which one was actually selected".

Therefore, the main focus is not a change in runtime semantics but the lack of diagnostics/debug support.
Also, dispatching an action is asynchronous, and adding a policy such as `IGNORE / LOG / THROW` as a general feature of the production runtime has the following problems.

- With `THROW`, it is unclear how and to which call site the failure is returned
- `LOG` easily becomes noise, picking up even normal ignores in large quantities
- It is easy to over-assign meaning through a runtime policy to an ignore that is natural for a state machine

## Current thinking

It is better not to add a general runtime policy such as `UnhandledActionPolicy`, and to keep the default behavior as the current ignore.

Instead, provide opt-in diagnostics so that only the teams that need it can make action routing visible at test/debug time.
Furthermore, as a low-cost improvement, the semantics of the DSL should be stated more explicitly than now in the README / KDoc.

In terms of priority, the following order is natural.

### 1. State first-match-wins explicitly in the README / KDoc

As the minimum improvement, at least the following should be stated explicitly.

- Action handler resolution follows registration order
- When multiple handlers match, only the first one is adopted
- `anyAction` / `anyState` do not have additive meaning, and depending on their placement can cover the handlers that follow
- When used as a fallback, placing them at the end is clearer

This alone somewhat closes the gap between "a DSL that looks declarative" and "actually first-wins ordered rules".

### 2. Routing diagnostics in `:actron-test`

Provide an API that checks "how many handlers match for this `state` and `action`" without dispatching.

Example:

```kt
fun <S : State, A : Action, E : Event> Store<S, A, E>.diagnoseActionMatches(
    state: S,
    action: A,
): ActionMatchDiagnostics<S, A>

suspend fun <S : State, A : Action, E : Event> Store<S, A, E>.diagnoseActionMatches(
    action: A,
): ActionMatchDiagnostics<S, A>
```

Returning `matchedHandlerCount`, `matchedHandlerIndices` and `selectedHandlerIndex` allows the following distinctions.

- `0`: unhandled
- `1`: uniquely handled
- `2 or more`: there are multiple matches

What matters here is that the library does not immediately declare `2 or more` to be abnormal.
Multiple matches may be an accidental overlap, or may be a fallback configuration the user intended.
This API should be treated as returning an observation, not a verdict.

This does not change runtime behavior and allows the intent of the routing definition to be tested directly.

### 3. Assert at dispatch time in `:actron-test`

Provide an API that asserts the expected number of matches at the same time as dispatching.

Example:

```kt
suspend fun <S : State, A : Action, E : Event> Store<S, A, E>.dispatchAndWait(
    action: A,
    expectedMatchCount: Int,
)
```

Or, with a separate name,

```kt
suspend fun <S : State, A : Action, E : Event> Store<S, A, E>.dispatchAndRequireMatchCount(
    action: A,
    expected: Int,
): ActionDispatchDiagnostics<S, A>
```

would also work.

This checks the match count for the current `state` and `action` before dispatching, and fails with an `AssertionError` if it differs from the expectation.
After that, it runs the normal `dispatchAndWait()`.

It is natural to position this as a convenience on top of the routing diagnostics, and it is easier to organize if it is layered on top of a pure observation API that exists first.

In tests, `expectedMatchCount = 1` is expected to be the most commonly used.
Specifying `0` allows checking that "this action should be unhandled in the current state".

### 4. Opt-in reporter in core

Separately from the test-only API, there is a proposal to provide an opt-in reporter in core, for cases where unhandled actions should be reported only in debug builds or verification environments.

Example:

```kt
fun <S : State, A : Action, E : Event> StoreBuilder<S, A, E>.unhandledActionReporter(
    reporter: UnhandledActionReporter<S, A>,
)

fun <S : State, A : Action, E : Event> StoreOverridesBuilder<S, A, E>.unhandledActionReporter(
    reporter: UnhandledActionReporter<S, A>,
)
```

Use cases are logging, debug fail-fast, telemetry and so on.
However, this handles only `unhandled`, and cannot cover diagnostics of multiple matches or shadowing.
Its priority can therefore be lower than the match diagnostics in `:actron-test`.

## Notes

- The proposal to add action diagnostics to `StoreObserver` easily makes the responsibility heavy relative to how much it widens the public surface.
- The proposal to detect unhandled after the fact in middleware is hard to distinguish from the case of handled but state unchanged.
- For both `unhandled` and `multiple matches`, the library should not go as far as deciding the meaning; it is better to first make the facts observable.

## Related proposals in other directions

The following are proposals that handle the same ambiguity at a different layer, but they fall somewhat outside the main subject of #175, which is runtime/test diagnostics.

### Compile-time resolution through types

Complete compile-time resolution is difficult.

The reason is that action handler resolution depends not only on the type of the `Action` but also on "the current state at the time of dispatch".
For the same `Action`, the result can be `0 / 1 / 2 or more` depending on which state we are in.

For this reason, it is hard to ask the compiler for unique resolution while keeping the ordinary `Store.dispatch(action)` as the premise.
If we wanted to handle this strictly at compile time, it would likely require rebuilding into a state-scoped dispatch API, or generating a separate typed API via KSP / a compiler plugin.

This is closer to a different design than an extension of the current Store DSL.

### Validation at Store `build()` time

Validation at `build()` time is more realistic than compile-time resolution through types.

The current DSL ultimately registers `state<S2>` / `anyState` and `action<A2>` / `anyAction` as predicates.
However, in the current implementation, what remains at build time is mainly the predicate lambdas, and the higher-level information about "which matcher it came from" is not retained.

Therefore, to validate at `build()` time, matcher metadata such as the following needs to be retained in addition to the predicates.

- `AnyState`
- `StateType(S2)`
- `AnyAction`
- `ActionType(A2)`

With this, checks such as the following become easier at `build()` time.

- Whether there are combinations in which multiple matches can occur
- Whether a subsequent handler can be covered by a preceding handler
- Whether there are places where the selection order depends on registration order

However, here too what the library can know is only the structural facts.
It cannot decide whether that is an accidental overlap or a fallback configuration the user intended.

Therefore, even if validation at `build()` time is added, it is more natural to lean toward warnings or debug assertions rather than hard errors at first.

## Open questions

- How much information to put in `ActionMatchDiagnostics` / `ActionDispatchDiagnostics` is undecided. At least `matchedHandlerCount` and `selectedHandlerIndex` are likely to be needed.
- Whether to add an assert API such as `dispatchAndWait(expectedMatchCount)` at the same time from the start, or to layer it on top of the routing diagnostics later, is undecided.
- Whether to present `first match wins` as a term as-is in the README / KDoc, or to explain it centered on `registration order`, is undecided.
- If the core reporter is added, it needs to be confirmed whether it is acceptable to route an exception thrown by the reporter to the `ExceptionHandler` side rather than `recover{}`.

## Related

- [#175](https://github.com/koma-kt/koma/issues/175)

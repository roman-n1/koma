# On the behavior of AutoStartPolicy.OnDispatchOrStateCollection or AutoStartPolicy.OnDispatch

- Updated: 2026-05-10

## Background

With both `AutoStartPolicy.OnDispatchOrStateCollection` (default) and `AutoStartPolicy.OnDispatch`, the Store's startup processing can run with `dispatch()` as the trigger.
Here we summarize the premises, as seen from the UI side, about the relationship between startup processing and dispatch, and in particular the interaction with `PendingActionPolicy.ClearOnStateExit`.

## Current thinking

### How startup processing appears at dispatch time

If the Store has not yet started at the time of `dispatch()`, the mechanism is that the startup processing is executed, and then **this dispatch and the dispatches queued during startup processing are applied to the state after startup processing has completed**.

In other words, from the point of view of the UI side that dispatches, regardless of whether the Store's startup processing has actually happened or not, the code can be written on the premise that "startup processing has finished" at the time of `dispatch()`.
The UI side is not made aware of behavior where its own dispatch is dropped because startup processing is delayed.

"Dispatches queued during startup processing" include not only dispatches from outside (UI, etc.) but also **dispatches fired from inside `Plugin.onStart`**. `Plugin.onStart` runs as part of startup processing, so what is dispatched from there rides the same rail and is applied to the state after startup processing has completed. Plugin authors can also write without being aware of edge cases such as "dispatching in onStart disappears / is dropped".

### When triggered by collect / `Store.start()`

The same view holds when the trigger of startup processing is something other than `dispatch()`.

- When a collect of `state` / `event` causes startup (`OnDispatchOrStateCollection`): the collect merely becomes the trigger of startup processing, and the premise from the user's point of view is the same as when triggered by dispatch.
- When `Store.start()` is called explicitly: it merely runs startup processing explicitly, and the subsequent behavior of `dispatch()` / collect is as usual.

In either case, in the same way that "code can be written on the premise that startup processing has finished at the time of `dispatch()`", code can be written without being aware of the internal behavior of startup processing.

### Relationship with PendingActionPolicy.ClearOnStateExit

Even when the state changes during startup processing and the policy is `PendingActionPolicy.ClearOnStateExit`, **state class transitions during startup processing do not remove this dispatch or the dispatches made during startup processing**.

In the implementation, `clearPendingActionsOnStateExitIfNeeded()` clears pending dispatches only when `isInitialized == true` ([StoreImpl.kt:727](../../../koma-core/src/commonMain/kotlin/koma/core/StoreImpl.kt:727)).

#### Implementation notes

- Currently, even when triggered by collect / `Store.start()`, dispatches are not removed on state class transitions during startup processing, but if in the future the behavior were to apply only when triggered by dispatch, the condition at [StoreImpl.kt:727](../../../koma-core/src/commonMain/kotlin/koma/core/StoreImpl.kt:727) would be changed as follows.
  - `if (pendingActionPolicy == PendingActionPolicy.ClearOnStateExit && !(activeDispatchJob != null && !isInitialized))`
- If in the future a requirement arises to drop dispatches on state class transitions during startup processing, simply removing the `isInitialized == true` check in `clearPendingActionsOnStateExitIfNeeded()` is not enough; it is also necessary to prevent the execution of onActionDispatched() for a dispatch that has already entered the mutex at [StoreImpl.kt:194](../../../koma-core/src/commonMain/kotlin/koma/core/StoreImpl.kt:194).

### Rationale: ClearOnStateExit does not apply to the configuration phase in the first place

The Store lifecycle is divided into 2 phases.

- **Configuration phase**: from Store creation until startup completion. Includes restoring and settling the initial state, fixing observers / overrides, and the first `enter {}` chain. During this period, `currentState` is not the operating state but a placeholder in the process of settling the initial state.
- **Operating phase**: from startup completion onward. The state becomes the target driven by `dispatch()`.

This division is not new; it is the same line as `stateSaver.restore()` / `attachObserver()` / `applyOverrides()` all being "allowed only before startup".

The intent of `ClearOnStateExit` is "**discard old dispatches that were queued targeting a certain state class during operation, once that state is exited**" (e.g. discard a `Save` queued in EditPost once transitioned to PostList). This holds only in the operating phase. For `Loading → Main` in the configuration phase, the user has not yet entered the operating window, so "state exit" itself cannot be defined.

Therefore, transitions in the configuration phase are **outside the scope of** `ClearOnStateExit`, **not an exception to it**. That it does not depend on the trigger (dispatch / collect / `Store.start()`) is also because it is determined solely by the phase boundary.

### Specification text (draft)

> `PendingActionPolicy.ClearOnStateExit` applies only to state class transitions in the **operating phase** (from startup completion onward). Transitions in the **configuration phase** (until startup completion) are part of the process of settling the initial state and are out of scope.
>
> From the user's point of view, regardless of the progress of startup, code can be written on the premise that the Store has entered the operating state at the time of `dispatch()`.

### Note: handler matching acts as an implicit safety valve

The risk that "the configuration phase branches and a dispatch lands on an unexpected state class" is theoretically possible, but Koma's handler matching matches on the `(state class, action class)` pair, and if nothing matches it is a **silent no-op** ([StoreBuilder.kt:124-127](../../../koma-core/src/commonMain/kotlin/koma/core/StoreBuilder.kt:124)).

So, for example, even if `OpenPost` is dispatched during a branching startup such as `Loading → if authenticated then Home else Login`, it is simply discarded if the `Login` side has no `action<OpenPost>`. An accident occurs only in the case where "the same action is intentionally handled in multiple state classes, and the side effects differ per state", which is a setup in which the user explicitly takes on the risk.

In other words, the practical harm of `ClearOnStateExit` not working in the configuration phase is largely absorbed by the per-state class design of handler matching.

## Notes

- There is room to add `Never` or `OnStateCollection` to AutoStartPolicy, but the priority is low. Concerns if introduced are as follows.
  - `Never`: actions dispatched before the Store starts would be discarded, or an exception would be thrown. Either increases the burden on the UI side.
  - `Never`: forgetting to start is hard to notice.
  - `OnStateCollection`: behavior where `dispatch()` is called but the Store does not start is likely to be counterintuitive.

## Open questions

- The above behavior, "dispatches during startup processing are excluded from ClearOnStateExit", is not stated explicitly in the README or the reference, so whether to add a note on the documentation side needs consideration.
- If `AutoStartPolicy.Never` is added in the future, in addition to the handling of dispatches before start (exception / discard / keep in queue), several points need to be worked out, such as whether to queue dispatches during startup and whether state changes during startup are subject to `ClearOnStateExit`.

# Store surface design notes

- Updated: 2026-05-07

## Background

Points of discussion about the Store surface, such as the `Store()` overloads, the naming of the `Store{}` DSL and the Store's start semantics, tend to be small and scattered.

On the other hand, splitting every such point out into its own ADR or notes can actually make things harder to survey.
If they are left only in PRs or conversations, it becomes hard to read back later "why the current surface API has this shape".

Here we keep design notes about the Store's public API and DSL surface, adding them section by section.
Each section may deal with an independent small point, and the granularity and nature of the sections need not be fully uniform.
Points for which we want to record a clear accept/reject decision individually, or which grow into a larger investigation, are split out into a separate ADR or notes as needed.

## `Store()` overloads

The `Store()` overloads exist as an API surface that makes the two input axes, `initialState` and `CoroutineContext`, easy to work with.
Even though the combinations are simple, we prioritize leaving room to write the call naturally depending on the caller's style and the context it sits in.

The number of overloads is not increased beyond what is needed, but we do not reduce them just because "they could theoretically be merged".
In Actron, letting users write declarations without strain weighs more than minimizing the surface for its own sake.

## `Store{}` DSL naming

The naming of the `Store{}` DSL prefers declarative words such as `state {}` `action {}` `event()` over `onXxx` or verb-centric hook names.
We want Actron to be seen as a DSL that describes "which state handles what" rather than one that imperatively enumerates "when what happens".

Therefore the DSL chooses naming that foregrounds the structure of the state machine over naming that strongly evokes a timeline or a callback sequence.

## Store start semantics

By default, a Store keeps starting automatically on the first `dispatch()` or on collection of `state`.
Compared with requiring an explicit `start()`, this makes it easier to reduce forgotten calls by users and ambiguity about how things are handled before and after start.

Explicit `start()` is not the default not only because of forgotten `start()` calls, but because it easily brings in other design debt: "where to start" and "how to treat a `dispatch()` before start".

Making `dispatch()` alone the start trigger would make loading of the first state depend on an action being fired.
Conversely, making `state` collection alone the start trigger could lose a `dispatch()` issued before collection.
The default leans toward neither.

Not including `event` collection among the start triggers is by design.
In Actron, loading of the first state should be tied to observing state, and subscribing to `event` is positioned purely as subscribing to side effects.

Eager start, where loading of the first state proceeds before the UI is ready, is not the default.
It is more natural for a Store to be created first as a declaration and to start moving at the point where it is needed.

With the current default, a one-shot `event` emitted right after `enter {}` may be missed by the subscriber.
However, a design that depends strongly on such an initial event is exceptional, and if it is needed, it is clearer not to lean on `enter {}` but to make the ordering explicit with a dedicated start action.

This does not rule out room for adding start semantics options in the future.
A `StoreStartPolicy` such as the [Store start timing policy proposal](../notes/2026-04-23-store-start-policy.md) is conceivable as a way to handle exceptional requirements without changing the default.
Even in that case, however, the default that takes priority is the current "automatic start on the first `dispatch()` or `state` collection".

For tests, `:actron-test` may provide `startAndWait()` to explicitly wait for startup to complete.
This does not replace the existing "automatic start on the first `dispatch()` or `state` collection"; it is positioned as an auxiliary API used alongside it, for checking startup on its own or for tests that want to observe the post-startup state before the first action.

Likewise, there is room to add a public `start()` for production as an explicit start API that can be used alongside the existing auto-start.
At this point, however, it is undecided and is not included in the Store surface default.
Taking the form that starts naturally through `dispatch()` or `state` collection as the baseline, it may be reconsidered as needed if requirements grow for running startup alone ahead of any action, or for users to make the start order explicit.

## Related

- [Store start timing policy proposal](../notes/2026-04-23-store-start-policy.md)

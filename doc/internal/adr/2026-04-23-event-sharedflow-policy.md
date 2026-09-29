# Configuration policy for the MutableSharedFlow used for events

- Updated: 2026-04-23

## Background

`Store.event` is used to deliver one-shot events for the UI. Currently `MutableSharedFlow()` is used as is, which effectively means `replay = 0`, `extraBufferCapacity = 0` and `onBufferOverflow = BufferOverflow.SUSPEND`.

The following three points were considered here.

- Whether `replay` should be increased, or at least set explicitly
- Whether `buffer` and `overflow` should be set explicitly internally
- Whether users should be able to choose those behaviors as a policy

## Decision

At this point, no additional `replay`, `buffer` or `overflow` settings are applied to the `MutableSharedFlow` of `Store.event` and `MessageHub`.

Likewise, no API is added that exposes the raw `SharedFlow` settings for users to choose from.

The adopted assumptions are as follows.

- Keep `replay = 0`
- Keep `extraBufferCapacity = 0`
- Do not specify `onBufferOverflow` explicitly

## Notes

- `replay = 0` is appropriate for not redelivering past events to subscribers that start later. Redelivery of UI events tends to cause one-shot events such as navigation, toasts and snackbars to fire again every time the screen is recreated or resubscribed.
- Setting `replay` to 1 or more was also considered, but it is not adopted because it would change the meaning of `Store.event` from "a notification delivered to whoever was subscribed at that moment" to "a notification whose most recent event may be re-notified".
- `extraBufferCapacity` is a setting for "letting the producer run slightly ahead when there is an already-subscribed but slow collector", not a setting for "retaining events from before subscription".
- `onBufferOverflow` only has practical meaning when a buffer is present. `DROP_OLDEST` and `DROP_LATEST` are policies that silently drop events, so they are too strong as a general-purpose event delivery policy.
- The current behavior where `emit` suspends takes backpressure directly when there is a slow collector, but in exchange it does not silently drop events. As the default behavior of `Store.event`, this is preferred.
- Exposing the settings as they are tends to require users to understand `SharedFlow` internals, and the consistent meaning gained is weak relative to the API surface. `replay` in particular is closely tied to redelivery of UI events, so it is an item that should be designed in terms of higher-level meaning rather than by exposing a raw number.
- If, in the future, a problem of "Store-side processing stalling due to a slow event handler" is confirmed in real use, the proposal to add a small `extraBufferCapacity` as an internal implementation detail will be reconsidered first. Even then, the first candidate is a small-capacity buffer while keeping `SUSPEND`.
- A change that makes it explicit, such as `MutableSharedFlow(replay = 0, extraBufferCapacity = 0)`, for readability is possible, but that is treated as making the intent explicit, not as a behavior change.
- Introducing an internal buffer is re-evaluated only when a concrete case emerges in real use where event collector delay becomes a problem.

## Addendum (2026-09-29, stability review)

- `MessageHub` now uses `extraBufferCapacity = 64` (first round of the
  [stability review](../notes/2026-09-29-stability-review.md)): a `sendMessage` from inside a
  Store handler no longer suspends on a slow subscriber while the handler holds the Store lock.
  Overflow still suspends (`SUSPEND`), so nothing is dropped.
- `Store.event` stays unbuffered as decided above.

# PascalCase replacement of `ViewStore.render` / `handle` is not adopted

- Updated: 2026-05-25

## Background

`ViewStore.render` / `ViewStore.handle` are `@Composable`, and if we followed Compose's naming guideline directly, we would want PascalCase names for them as public composables returning `Unit`.

For this reason, the following two directions were considered.

- Top-level functions `StateContent(viewStore) {}` / `EventHandler(viewStore) {}`
- Member functions of `ViewStore`: `viewStore.StateContent {}` / `viewStore.EventHandler {}`

However, both raised the concern of breaking the naturalness as a DSL that the current `viewStore.render {}` / `viewStore.handle {}` has.

## Decision

The proposal to replace `ViewStore.render` / `ViewStore.handle` with separate PascalCase APIs is not adopted.

At this point, the existing lowerCamelCase API is kept.

- `viewStore.render<...> { ... }`
- `viewStore.handle<...> { ... }`

## Notes

- The top-level function approach tends to make the appearance of type arguments awkward. It sometimes cannot be written plainly as `StateContent<MainState>(viewStore)`, and the call does not come together without considering type argument completion involving `_` or additional parameter design.
- `viewStore.StateContent {}` / `viewStore.EventHandler {}` are easier to align with Compose's naming guideline, but as member calls with an explicit receiver they look unnatural. Unlike top-level composables or implicit-receiver DSLs, the PascalCase of `viewStore.Some()` tends to look like a type name or property name.
- Therefore, this time, "naturalness as the `ViewStore` DSL" is prioritized over "alignment with the Compose guideline".
- The existing API requires `@Suppress("ComposableNaming")`, but this cost is judged to be smaller than accepting the unnaturalness above.
- If, in the future, a more natural API shape is found that is neither top-level nor member, it may be reconsidered.

## Addendum 2026-05-25

Since then, the API names have been renamed from `render` / `handle` to `stateContent` / `eventEffect`.
With this rename, the meaning of the API is more aligned with the Compose context, but the question itself of whether member composables should be PascalCase is unchanged.

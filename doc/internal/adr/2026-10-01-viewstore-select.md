# A composable selects what it needs from the state with `ViewStore.select`

- Updated: 2026-10-01

## Background

koma-kt/koma#189 (item 3) lists derived state and selector helpers among the Compose
ergonomics. The fork's messenger maps a machine's snapshot to a UI model with a pure `UiMapper`
(`MachineSnapshot<C> -> UiModel`, handoff §3); reading `viewStore.state` and mapping in the
composable recomposes everything on every change of the snapshot, most of which the model does
not show.

## Decision

`ViewStore.select(mapper: (S) -> T): T`, a composable member: `rememberUpdatedState(mapper)`
and a `derivedStateOf` remembered per call site for the `ViewStore`, read in place. The
derivation runs when the state changes; what reads the result recomposes only when the derived
value changed, by structural equality, which is `derivedStateOf`'s policy. The mapper is pure and
may run on the snapshot's thread. Lower camel case, a member: the naming rule of
`2026-04-30-viewstore-compose-naming.md` (a value-returning composable is lower case anyway).

Not adopted:

- A mapped `ViewStore` (`viewStore.map(mapper): ViewStore<UiModel, A, E>`): `S` is bounded by
  `State`, a UI model is not one, and a second `ViewStore` per projection would carry `dispatch`
  and events it does not own.
- Two-way binding helpers (`bind(value, onChange)`): `TextField(value, onValueChange = {
  dispatch(...) })` is one line, and a binding hides the action, against the author's rule that
  behaviour is readable from the action definitions.
- A selector with a custom equality: `derivedStateOf(policy)` exists for the case; add it when a
  model needs it.

## Notes

- Tests: `ViewStoreJvmTest.select_derivesFromTheState_andItsReadersRecomposeOnlyWhenTheDerivedValueChanges`
  (a reader of a bucket of the value recomposes for a new bucket, not for a change within it,
  and the parent that reads nothing never does) and `select_usesTheLatestMapper`.
- Upstream: a feature request after the companion fixes (`notes/2026-10-01-upstream-series.md`);
  inventory row d8.

## Related

- [The Compose inspector](./2026-09-30-compose-inspector.md)
- [Time Travel and structured logging handoff](../design/2026-09-29-time-travel-logging-handoff.md), §3, §11

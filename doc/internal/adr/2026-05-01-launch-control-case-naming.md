# Design of the `LaunchControl` API

- Updated: 2026-05-01

## Background

`LaunchControl` is an API that expresses, for `action { launch { ... } }`, how launched jobs are arbitrated on a tracked lane.

This control does not discard the dispatch itself, but it may not start the launched work triggered by the dispatch, or may cancel the previous launched work.
So while it is convenient, unintended use can lead to behavior that "looks at first glance as if the action were ignored".

The API is required to be usable by users without confusion.

## Decision

`LaunchControl` is exposed as an API with the following cases.

- `LaunchControl.Untracked` (the default; normally omitted.)
- `LaunchControl.CancelPrevious`
- `LaunchControl.DropIfRunning`

The canonical notation is `launch(control = LaunchControl.CancelPrevious(...)) { ... }` and `launch(control = LaunchControl.DropIfRunning(...)) { ... }`.

## Notes

- The behavior can be read directly from the call site: `CancelPrevious` means "stop the previous tracked launch and start the next", and `DropIfRunning` means "do not start a new launch while a tracked launch is running".
- Replacement with a family of APIs such as `launchCancelPrevious()` / `launchDropIfRunning()` was also considered, but it is not adopted because it increases the `launchXxx` syntax users have to remember.
- Adding `launchCancelPrevious()` / `launchDropIfRunning()` as aliases rather than replacements was also considered, but providing multiple entry points for the same control mixes notations in the README, reviews, conversations and searches alike, raising cognitive load. So the public surface is narrowed to one notation.
- Even with a slightly longer notation such as `launch(control = LaunchControl.CancelPrevious(searchLane)) { ... }`, being easier to spot in code makes it harder to overlook during code review.

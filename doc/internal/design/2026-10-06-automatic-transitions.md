# Automatic transitions and macrosteps

- Updated: 2026-10-06

Machine and StateChartStore commit a stable configuration once per accepted external input.
The low-level StateChartRuntime continues to expose individual microsteps; `automaticStep`
lets other adapters stabilize their own context between them.

* `FinalState` is a leaf. It has no outgoing transitions. A compound is complete when its
  active direct child is final; a parallel is complete when every region is complete. A
  completed nested compound requires its own completion transition to a final sibling before
  its enclosing compound completes. Reaching a top-level final marks the workflow complete.
* After Start, an action/command handler or a timer transition, take enabled eventless
  transitions first. When none is enabled, take enabled completion transitions. Selection,
  region declaration order and exit conflicts are unchanged. Repeat until neither fires.
* Guards use the snapshot/context at the beginning of the current microstep. Exit hooks,
  effects and enter hooks update context before the next microstep. Automatic hooks receive
  `AutomaticTransition(completion)` as their action and keep the original external MachineInput.
* Internal transitions are targetless in effect: represented as `source == target`, they run
  effects while preserving configuration, activation, activities, commands and timers. Local
  transitions to descendants are not part of this feature. A fired internal timer is spent
  and does not restart. Internal transitions still participate in priority and exit conflicts.
* One macrostep advances revision once. Every automatic guard sees that same pending
  revision, independent of the number of internal microsteps. Intents are aggregated in microstep order; commands
  and timers created and cancelled before stability never execute. Events remain ordered.
  Runtime invariants inspect the final stable snapshot only. Any failed Machine microstep or
  limit overflow rolls back the whole macrostep and discards all intents.
* The default bound is 100 automatic microsteps, configurable on Machine and StateChartStore.
  Context-changing loops may legitimately revisit configurations, so configuration repetition
  alone is not an error. Bound errors carry the transition trace for diagnosis.
* ChartStore hooks retain their existing immediate-event contract; lifecycle mutations and
  activity/timer starts are staged until stability. Its existing fresh-start recovery contract
  remains: a failed initial enter has no prior active configuration to roll back to.

Structural graph tools still describe potential microstep paths with guards ignored. Executable
sequence exploration uses Machine macrosteps and therefore only commits stable snapshots.

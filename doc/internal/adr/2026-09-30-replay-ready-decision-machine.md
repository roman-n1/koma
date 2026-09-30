# A replay-ready statechart is a pure decision function; execution is a separate concern

- Updated: 2026-09-30

## Background

Stage 2 of the [handoff](../design/2026-09-29-time-travel-logging-handoff.md) asks for a
replay-ready pipeline: pure decisions, typed commands, a scheduler and a commit protocol (§4).
Its contract is `decide(MachineSnapshot, InputEnvelope) -> Decision`, where a decision names the
next snapshot, the transitions taken, the activations exited and entered, the commands to
register and the scopes to cancel, the timers to schedule and cancel and the effects to enqueue.
Guards, reducers and descriptions must not read clocks, random or repositories; a command handler
never sets a state, it returns an input. Equal business data is not "ignored" and does not
suppress commands.

Today's `StateChartStore` runs the same chart semantics but with suspending hooks and activities
that do I/O and dispatch from inside the step, `launch {}` and `updateContext` from anywhere, and
timers as coroutines with real delays identified by transition indices and tokens. That is the
legacy path the handoff keeps; it is not replayable by construction.

## Decision

`koma-statechart` gets a second, opt-in way to run a chart: the package
[`koma.statechart.machine`](../../../koma-statechart/src/commonMain/kotlin/koma/statechart/machine/Machine.kt).
This ADR covers the pure half (stage 2a). The executor, the commit protocol and the scheduler are
stage 2b and get their own ADR.

- **`Machine<C, A, CMD, E>`** wraps a `StateChartDefinition` with pure guard implementations
  `(MachineSnapshot<C>, Action) -> Boolean`, pure transition effects `(C, Action) -> C` (the
  chart's existing effect labels) and pure enter and exit rules per node. Selection, exit and
  entry order, history and parallel regions are `StateChartRuntime`'s, unchanged.
- **`MachineSnapshot<C>`** is a Koma `State`: definition id and version, revision,
  configuration, context, the activation of every active node, the registered commands (scope
  and lane), the scheduled timers (transition, activation, deadline) and the counters the next
  ids come from. It is the machine's part of a checkpoint; nothing in it is a coroutine, a
  callback or a handle.
- **`MachineInput`** is the closed set of things the machine decides on: `Start`, `Dispatch`,
  `TimerFired`, `CommandResult`, `CommandCompleted`, `CommandFailed`. Every input carries the
  machine's clock (`MachineTime`); the machine never reads one. A command's expected failure is a
  typed result; an unexpected one arrives as `CommandFailed` and steps with a `CommandFailure`
  action a chart may declare a transition for.
- **`decide(snapshot, input)`** is total over its inputs: it returns `Handled` with a new
  snapshot (revision + 1, even when the business data is equal), `Ignored(reason)` with the same
  snapshot object, or `Failed(descriptor, cause)` with the same snapshot when a guard, reducer or
  rule threw. It never registers anything for an ignored or failed input. Ids
  (`ActivationId`, `CommandId`, `TimerId`, `EffectId`) come from the snapshot's counters, so a
  replay issues the same ids.
- **Activations** are the unit of scoping. Every entered node gets a new `ActivationId`, also on
  a self-loop or a transition into an ancestor. Commands registered by an enter rule belong to
  that activation; exiting it cancels them (they leave the snapshot and the executor gets the
  scope in `cancelledScopes`) and cancels its timers. A `CommandResult` whose command is not
  registered is `Ignored(StaleCommand)`: a late result of a cancelled load cannot complete a newer
  one, without any token bookkeeping in the executor.
- **Timers** are data: a scheduled timer has a `TimerId`, the transition, the activation of its
  source and a deadline of `now + delay`. `TimerFired` for a timer that is not scheduled is
  `Ignored(UnknownTimer)`; a fired timer is spent whether or not its guard holds (as in the legacy
  Store); a self-loop timer is cancelled and scheduled again with a new id.
- **Lanes and policies** (`Latest`, `Sequential`, `DropIfRunning`, `Parallel(limit)`) are declared
  with the command and recorded in the decision; the machine does not interpret them.
- **Identity of the definition** is explicit: `DefinitionId` and `DefinitionVersion` are given by
  the application, and a snapshot of another version is refused with an exception, not decided.
- **Purity by construction.** Rules and guards are plain functions that receive only the
  snapshot, the input, the action, the clock and a scope that collects commands and events. The
  API offers no clock, no scope to launch in, no way to dispatch. What they can still do wrong
  (read a global) is checked by the determinism tests, not prevented.

Not adopted:

- Extending `StateChartStore` in place: its hooks are suspending and imperative; making them
  optionally pure would leave one API with two incompatible contracts. The legacy Store stays as
  is for existing charts; a chart is either legacy or a `Machine`.
- Executing inside `decide` (starting coroutines, real delays): the machine would stop being a
  function and stop being replayable.
- Throwing from `decide` on a failing rule: the executor needs the failure as a decision it can
  commit nothing for, report, and journal.
- Hashing lambdas or the chart to derive the version: as the handoff says, a graph hash does
  not prove that guards and reducers are compatible; the version is declared.

## Notes

- Tests: [`MachineTest`](../../../koma-statechart/src/commonTest/kotlin/koma/statechart/machine/MachineTest.kt)
  pins start, steps, self-loops, equal business data with a command, effects, stale and completed
  and failed commands, timers (taken, guard false, cancelled on exit), throwing guards and rules,
  determinism and construction checks;
  [`MachinePropertyTest`](../../../koma-statechart/src/commonTest/kotlin/koma/statechart/machine/MachinePropertyTest.kt)
  walks random hierarchical, parallel, history and timer charts and checks determinism and the
  snapshot invariants after every step.
- The `koma-statechart` module now depends on `koma-observability` for `FailureDescriptor`.
- Stage 2b builds the executor on this: a Koma Store whose state is the `MachineSnapshot`, a
  handler that decides under the lock and commits the snapshot, and a scheduler that registers
  commands after the commit and outside the lock, runs `CommandHandler`s with the lane policies,
  feeds their results back as inputs, and fires timers through a swappable clock. The commit and
  close linearization the handoff §4.2 demands is that ADR's subject.
- Open for stage 2b: whether a command may also be registered by an exit rule (unscoped,
  outliving the node), and how the group barrier of §8.1 stops several machines at input
  boundaries.

## Related

- [Time Travel and structured logging handoff](../design/2026-09-29-time-travel-logging-handoff.md), §4, §5, §8
- [Statechart semantics](../design/2026-09-28-statechart-semantics.md)
- [Store processing is observed through an internal probe](./2026-09-29-store-probe-processing-observation.md)
- [Journal identity, ordering and payload policy](./2026-09-29-journal-identity-ordering-and-payload-policy.md)

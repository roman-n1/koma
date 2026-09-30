# MachineStore commits a decision, then a scheduler actor carries it out outside the lock

- Updated: 2026-09-30

## Background

Stage 2b of the [handoff](../design/2026-09-29-time-travel-logging-handoff.md) executes the
decisions of the [pure machine](./2026-09-30-replay-ready-decision-machine.md) with the
guarantees of §4.2 and §4.3: one owner for preparation, commit and registration of intents;
commands start only after the commit and after the store's lock is released; a failed decision
publishes no partial snapshot and cancels no running work; a fast result comes back through the
input queue; the scheduler never runs user code or waits for the network under the lock; close
and commit have one linearization point; lanes with `Latest`, `Sequential`, `DropIfRunning` and
`Parallel(limit)`; late results are checked by id under the store's serialization.

Koma is the only runtime (handoff §1), so the executor must be built from a Koma Store, whose
lock, commit and plugin rounds already serialize processing.

## Decision

[`MachineStore`](../../../koma-statechart/src/commonMain/kotlin/koma/statechart/machine/MachineStore.kt)
is a `Store<MachineSnapshot<C>, A, E>` that wraps an inner Koma Store whose state is the
snapshot and whose action type is `MachineInput<A>`.

Under the inner store's lock:

- A dispatched action is accepted as `MachineInput.Dispatch(action, clock.now())`; the startup
  `enter {}` decides `MachineInput.Start`. The clock is read at acceptance, so the input carries
  its time and the machine reads none.
- The handler calls `Machine.decide` (no I/O) and, for a `Handled` decision, stores it in a
  single pending slot and registers the snapshot with `nextState {}`. `Ignored` commits nothing.
  `Failed` throws the cause: nothing is committed or registered, running commands are untouched,
  and the failure reaches `recover {}` and the exception handler like any handler failure.
- Koma commits the snapshot and runs the plugins' `onState` after the commit, still under the
  lock. The store's own plugin, registered last, takes the pending decision (checking it is the
  committed snapshot by identity), queues its events and hands it to the scheduler. Handing over
  is one `trySend` on an unbounded channel; nothing else runs under the lock.
- `recover {}` handlers from the configuration may not change the snapshot (`validateRecovery`
  rejects the commit): the machine is the only writer. A restored snapshot that is already
  started lost its commands (they are not part of the snapshot), so the store starts over from
  the initial configuration with the restored context.

Outside the lock, in the execution scope the application passes:

- **One actor coroutine** owns all scheduler state (running commands, lanes, timers), so no lock
  is needed and every state change is ordered. `Latest` cancels the lane's running commands and
  starts the new one; `Sequential` and `Parallel(limit)` queue; `DropIfRunning` drops. An
  abandoned command (superseded, dropped) is reported to the machine by the actor at once as
  `MachineInput.CommandAbandoned`, because a job cancelled before it started never runs its body.
  A command cancelled with its exited activation is not reported: the decision that exited it
  already deregistered it.
- **Commands** run as coroutines of that scope through the `CommandHandler`, which sends results
  through a `ResultSink` (each a `CommandResult` input), completes on return (`CommandCompleted`)
  and fails on exception (`CommandFailed`, reported to the store's exception handler through the
  store's own scope). A command sees the committed snapshot in `currentState`. The machine
  rejects results of commands it no longer holds, so the executor keeps no tokens.
- **Timers** wait on the `MachineClock` (`delayUntil(deadline)`) and feed `TimerFired`; the
  machine rejects a timer it no longer holds. The clock is an interface so a replay can drive a
  virtual one.
- **Events** of a decision go through a channel drained by one coroutine into the store's
  `event` flow: delivered after the commit, in decision order, never from the handler before the
  commit (which is when Koma's own `event()` would emit).
- **Close.** `close()` closes the inner store and cancels the execution scope's children. Koma's
  commit already checks the handler's cancellation before committing, so close before commit
  publishes nothing; close after commit cancels the actor before its turn, so nothing starts and
  running commands are cancelled. The scope must not be `Dispatchers.Unconfined`: launching
  would then run handlers inline, under the lock.

Not adopted:

- Emitting events or launching commands from the handler with `event()` and `launch {}`: both
  act before the commit (and, under an unconfined dispatcher, inline under the lock).
- Driving execution from the internal `StoreProbe`: it is observation, not control.
- A lock-based scheduler: the actor gives the same ordering with less to get wrong under the
  store's lock.
- Bounded admission and an `Abandoned(StoreClosed)` journal record for commands that never
  started: deferred at first; both are in the addenda below.

## Notes

- Tests: [`MachineStoreTest`](../../../koma-statechart/src/commonTest/kotlin/koma/statechart/machine/MachineStoreTest.kt)
  covers commands starting only after the commit with the committed snapshot visible, results
  feeding back, activation exit cancelling commands, the three lane policies, timers on the clock
  and their cancellation, handler failure, failed decision leaving running work alone, `recover`
  rejected, close between commit and scheduling, events after the commit in order, a late result
  ignored, restore of a started snapshot, the unconfined scope rejected and a declared
  `CommandFailure` transition.
- Test clocks: a `MachineClock` over `TestCoroutineScheduler.currentTime` makes timers run on
  virtual time; `runCurrent()` drives the pipeline without advancing time, `advanceTimeBy` fires
  timers.
- Known limits, for the next stages: the journal records the snapshot commits but not the
  decision (transitions, commands, timers) as such; a machine-level journal entry is the next
  observability step. `CommandFailed` reports through `PluginScope.launch`, so the journal
  attributes the failure to the input being processed when the launch happened, not to the
  command.
- `StateChartStore` is untouched; a chart is either legacy or a `Machine`.

## Related

- [A replay-ready statechart is a pure decision function](./2026-09-30-replay-ready-decision-machine.md)
- [Store processing is observed through an internal probe](./2026-09-29-store-probe-processing-observation.md)
- [Time Travel and structured logging handoff](../design/2026-09-29-time-travel-logging-handoff.md), §4.2, §4.3

## Addendum (2026-09-30): observers and bounded admission

- `MachineStore` takes `DecisionObserver`s: `onCommitted(input, decision)` runs from the store's
  plugin after the commit, `onIgnored` from the handler, `onRejected` from the dispatching
  thread; all short, none calling back into the store; a throwing observer is reported and the
  store continues. `RecordingSession.decisionsOf(store)` is the observer that journals decisions.
- `AdmissionPolicy.Bounded(maxPending)` refuses a dispatched action when that many accepted
  actions still wait for processing; `admit(action)` returns `Accepted` or `Rejected(pending,
  limit)`, and `dispatch` is `admit` with the answer dropped, as the handoff §4.3 asks
  ("`dispatch()` with `Unit` proves nothing"). The count is kept in the store (incremented at
  admission, decremented when the input's handler starts) because Koma's own queue is unbounded.
  Inputs the machine's own work sends (results, completions, abandonments, timers) are never
  refused: refusing a result would leave the machine waiting for a command that already
  answered. A refused action is reported to the observers and never becomes an input.

## Addendum (2026-09-30): the lanes are data, and the executor has a checkpoint

- The scheduler's bookkeeping of commands moved out of the actor into `Lanes`, a pure value:
  the commands running and the ones queued per lane, with `admit` (each policy's rule),
  `finished` (frees the lane's place and starts what waited) and `exited` (cancels the running
  commands of exited activations, drops their queued ones). Each returns a `LaneChange`: the
  next lanes and the effects for whoever runs the commands. The actor applies a change by
  cancelling, reporting the abandonments and launching, in that order, and owns nothing but the
  jobs, the timers, the last snapshot it carried out and the commands `ending` (finished here,
  their last input not yet decided by the machine). A `koma-timetravel` recording carries the
  same lanes forward, and a branch runs them with jobs that end the moment they are told to; one
  rule set, three executors. A cancelled command holds its place in the lane until its job has
  ended, so a `DropIfRunning` lane still busy with the exited activation's command drops the new
  one; `Latest` removes the superseded at once, as that lane never queues.
- `MachineStore.checkpoint()` is the executor's state as data (`ExecutorCheckpoint`, handoff §8):
  the snapshot of the last decision carried out, the clock, the lanes and the ending commands.
  It is taken at a message boundary of the actor, so it is consistent with itself; the store may
  already have committed later decisions, which are the steps after it. The lanes are given as
  they settle once the ends the machine has already decided are booked: a running command the
  snapshot no longer holds has ended, whatever its job is still doing. The checkpoint checks
  itself: every command of its snapshot is in exactly one place, with the snapshot's scope and
  lane. Not in it: the store's queue of accepted, undecided inputs, undelivered events, and
  anything that is not data. After `close()` there is no checkpoint.
- `MachineStore` now carries its command type (`MachineStore<C, A, CMD, E>`): the checkpoint's
  registrations are typed, and a store does run commands of that type.
- Tests: `LanesTest` (each policy, finishing, exiting), `ExecutorCheckpointTest` (the partition
  of the snapshot's commands, ending until the machine decides, lanes moving on, timers'
  remaining time, re-entry, close) and `ExecutorCheckpointStormTest` (checkpoints taken while
  four threads re-enter and exit an activation with every lane policy: each a consistent cut,
  lanes within policy, nothing queued behind a free lane, revisions monotonic).

## Addendum (2026-09-30): what the executor had not finished at close is journaled

Handoff §4 wants the commands a close leaves behind told: those registered and never started
get `Abandoned(StoreClosed)`, those running are cancelled by their policy. The actor knows
both, and only it: its `finally` runs once the scope is cancelled, reads the lanes (queued and
running) and drains the decisions it did not get to (their commands never started either), and
calls `DecisionObserver.onClosed(queued, running)` once, only when there is something to tell.
`session.decisionsOf(store)` journals it as `JournalEntry.CommandsAbandoned("StoreClosed",
queued, running)`, record model version 5; the inspector shows it as `TimelineItem.Abandoned`.
The store's own `StoreClosed` is traced when every coroutine of the core store has ended, so
the two records may come in either order; the inspector does not merge them.

Not adopted: feeding `MachineInput.CommandAbandoned(StoreClosed)` to the machine, which is
closed and would discard it; a journal record per command, where one record per close says
the same with less.

Tests: `MachineJournalTest` (two Fetches in one sequential lane, the store closed with the
first running and the second waiting: one `CommandsAbandoned` with the second queued and the
first running, rendered by the text format, next to the store's own `StoreClosed`);
`JournalFileFormatTest` and `InspectorTest` carry the new record.


# A machine is tested through a driver that settles after every step and executes no command itself

- Updated: 2026-10-01

## Background

With the bridge ([ADR](./2026-10-01-machine-store-test-bridge.md)), `awaitIdle`
([ADR](./2026-10-01-await-idle-and-subscriptions.md)) and the recorder's cursor
([ADR](./2026-10-01-recorder-cursor-and-pending-work.md)) a `MachineStore` can be driven with
actron-test. What was left of koma-kt/koma#189's TestStore-like API for a machine: a virtual clock
(fourteen tests carried their own copy of one), a settle that ends in the machine's data, a
handler whose commands the test answers, and one object that puts them together. Handoff §12
says the harness does not execute commands by itself.

## Decision

A module of its own, `actron-statechart-test`, on `actron-statechart`, `actron-test` and
`kotlinx-coroutines-test`, because `actron-test` (upstream's) cannot depend on the statechart and
the virtual clock needs the test library as an `api` dependency, which `actron-statechart`'s main
source set must not carry.

- **`VirtualMachineClock(scheduler, originMillis = 0)`**: a `MachineClock` on the scheduler's
  virtual time; `TestScope.machineClock()`; `startingNow(scheduler)` for stores created one after
  another on one scheduler. The time-travel tests' six copies use it; the statechart's own eight
  stay (below).
- **`MachineStore.settle(timeout)`**: `awaitIdle` then `checkpoint()`. Idle for a machine is a
  fixpoint over inputs and the executor; what remains is data: `ExecutorCheckpoint.pendingWork()`
  lists the commands running, queued and ending, the timers with what is left, the effects
  pending, and `MachineStore.assertNoPendingWork(recorder?)` fails naming all of it and the
  events not received. It resolves over actron-test's `Store.assertNoPendingWork` by the more
  specific receiver.
- **`ScriptedCommandHandler`**: runs nothing; every command waits until the test answers,
  completes or fails it, as a replay's `Branch` does; what the executor started and cancelled is
  recorded. A test that needs a real handler passes one.
- **`MachineTestDriver(machine, context, scope, …)`**: the store on the test's scheduler with the
  virtual clock and the scripted handler, a recorder created before the start, failures collected.
  `start`, `send` (admitted and awaited), `sendLater` (the admission's answer), `settle`,
  `advanceBy`, `advanceUntilIdle`, `answer`, `complete`, `fail`, `runningCommands`,
  `receiveEvent`, `pendingEffects`, `acknowledge`, `assertActive`, `assertContext`,
  `assertNoPendingWork`, `close`. Every step returns the checkpoint after it settled: a test
  reads the machine's data, never a moment in between.
- **`actron-timetravel`'s tests depend on the module; `actron-statechart`'s own tests do not.** A
  module's test compilation may depend on a module that depends on its main (Gradle has no
  cycle: tests depend on the other module's main), and the JVM and Android builds run it, but
  on JS the test bundle of `actron-statechart` and `actron-statechart-test` import each other's
  module and Mocha fails at load with `Cannot read properties of undefined`: a circular ES
  module import. So the statechart's tests keep their private `TestClock`; the module serves
  everyone downstream of `actron-statechart`, which is where a messenger's tests are.

Not adopted:

- Putting the driver in `actron-test`: it cannot see a machine.
- Using the module from `actron-statechart`'s own tests: the circular JS module import above.
- A driver that runs commands through the real handler by default: a test would then depend on
  the handler's timing; scripting is the default, the real handler an option.
- Assertions on the checkpoint inside the driver (`assertRunning`, `assertTimer`): the checkpoint
  is data the test asserts on with its own equality; the driver only names what is pending.

## Notes

- Tests: [`MachineTestDriverTest`](../../actron-statechart-test/src/commonTest/kotlin/actron/statechart/test/MachineTestDriverTest.kt):
  a send decides and registers a command the test answers, the effects are received one by one,
  the retained one acknowledged; the end-of-test check names a running command, a timer with its
  remaining time and an unreceived event; advancing the clock fires the timer and cancels the
  command with its node; a failed command reaches the exception handler and a refused send is
  reported; under a storm of four producers, a hundred concurrent `settle` calls return
  checkpoints at or before the store's revision and the last one at it.
- Next: `actron-statechart-compose` (track B2), then the routes and ownership of track C.

## Related

- [A MachineStore is a Store actron-test can drive](./2026-10-01-machine-store-test-bridge.md)
- [awaitIdle and subscriptions](./2026-10-01-await-idle-and-subscriptions.md)
- [The recorder's cursor](./2026-10-01-recorder-cursor-and-pending-work.md)
- [Time Travel and structured logging handoff](../design/2026-09-29-time-travel-logging-handoff.md), §12

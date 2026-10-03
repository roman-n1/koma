# koma-statechart-test

Test support for [koma-statechart](../koma-statechart/README.md)'s `MachineStore`, on top of
[koma-test](../koma-test): the TestStore-like API of the upstream roadmap (koma-kt/koma#189, item 2)
with the machine's meaning.

- **`MachineTestDriver(machine, context, scope)`**: the store on the test's scheduler with a
  `VirtualMachineClock`, its commands waiting for the test through a `ScriptedCommandHandler`, its
  snapshots and effects recorded. `start()`, `send(action)`, `settle()`, `advanceBy(duration)`,
  `advanceUntilIdle()`, `answer(command, result)`, `complete(command)`, `fail(command)`,
  `receiveEvent<E>()`, `pendingEffects` / `acknowledge(id)`, `assertActive(nodes)`,
  `assertContext(expected)`, `assertNoPendingWork()`, `close()`. Every step returns the executor's
  `ExecutorCheckpoint` once the store settled.
- **`MachineStore.settle(timeout)`**: `awaitIdle` then `checkpoint()`; **`ExecutorCheckpoint.pendingWork()`**:
  the commands running, queued and ending, the timers with what is left, the effects pending;
  **`MachineStore.assertNoPendingWork(recorder?)`**: fails naming all of it and the events not received.
- **`VirtualMachineClock(scheduler)`**, `TestScope.machineClock()`: a `MachineClock` on virtual time.
- **`ScriptedCommandHandler`**: runs nothing by itself; records what the executor started and
  cancelled; the test answers, completes or fails each command, as a replay's `Branch` does.

Status: **experimental**, `@ExperimentalKomaApi`, in the fork [roman-n1/koma](https://github.com/roman-n1/koma).

## Dependency

```kotlin
// test source set only
implementation("io.github.roman-n1:koma-statechart-test:5.0.0-alpha.1")
```

## Quick start

```kotlin
@Test
fun aLoad_fetches_andShowsTheContent() = runTest {
    val driver = MachineTestDriver(machine, Ctx(), this)
    driver.start()

    driver.send(Act.Load)                                   // decided: Loading, the Fetch command registered
    val fetch = driver.runningCommands.single()
    driver.assertActive(setOf(root, loading))
    assertEquals(Ev.Started, driver.receiveEvent<Ev.Started>())

    driver.answer(fetch.id, Act.Loaded)                     // the result decided: Content
    driver.complete(fetch.id)
    driver.assertActive(setOf(root, content))
    assertEquals(Ev.Done, driver.receiveEvent<Ev.Done>())

    driver.assertNoPendingWork()                            // no command, no timer, no effect, nothing unreceived
    driver.close()
}
```

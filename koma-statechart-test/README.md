# koma-statechart-test

Use this to test **what a Machine decides** when commands complete or virtual time advances.
The test supplies command answers instead of calling a real repository. Start with the
[pure Machine example](../README.md#3-a-machine-makes-decisions-io-runs-afterward); for an
ordinary Store, [koma-test](../koma-test/README.md) is sufficient. Dependency availability
and composite-build setup follow [installation](../README.md#installation).

The driver owns a Store on your `TestScope` scheduler. `send` supplies an action, `answer`
supplies a command result, and `advanceBy` moves virtual time. Assertions check context,
active nodes and leftover work rather than waiting for real wall-clock delays.

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

## Model-based testing

Declare pure predicates once on the machine; tests, replay and debug tools share them:

```kotlin
val machine = Machine<Context, Act, Command, Ev>(id, version, chart) {
    invariant("signed-out-has-no-chat") { snapshot ->
        !snapshot.isActive(signedOut) || !snapshot.isActive(chatOpen)
    }
    // Optional in debug runtime: a violation rolls back the whole macrostep before IO.
    // enforceInvariants()
}

val inputs = MachineInputGenerator<Context, Act> { snapshot, now ->
    buildList {
        add(MachineInput.Dispatch(Act.Logout, now))
        if (snapshot.isActive(signedIn)) add(MachineInput.Dispatch(Act.OpenChat, now))
        // Supply valid typed payloads / command results here; no reflection or real IO.
    }
}.withTimers()

val initial = machine.initialSnapshot(Context())
val report = machine.explore(initial, inputs, maxDepth = 20, maxDecisions = 10_000)
println(report.coverage.states.percent)
println(report.coverage.transitions.missing)
println(report.coverage.guards.percent) // true/false outcomes per transition

report.failures.firstOrNull()?.let { failure ->
    val minimal = machine.shrink(initial, failure.inputs)
    println(minimal.failure.inputs) // actual inputs, payloads, virtual times
    check(!minimal.truncated)       // otherwise minimality has not been established
}
report.assertSuccess()             // also rejects an exhausted decision budget
```

`ExplorationStrategy.RandomWalk(seed = 42, runs = 1_000)` selects reproducible random input
choices. Breadth-first search keeps different contexts/history even when active nodes are equal.
`report.scenarios` contains executable prefixes that cover the transitions actually reached;
`machine.replaySequence(initial, scenario.inputs)` reruns one and returns its first failure.
Neither coverage nor a successful bounded run proves safety beyond supplied payloads and depth.
`MachineTestDriver` also checks every recorded stable snapshot on settle, including transient
commits between two calls. Runtime-enforced violations appear in the driver's `failures` list.

## Actual test coverage and generated plans

`driver.coverage` accumulates actual transitions, successful macrostep entries, timer transitions
and guard true/false outcomes without evaluating guards twice. Merge reports from tests of the
same definition/version with `coverage.merge(other)`, print `coverage.describe(machine.chart)`
and assert all or selected transitions with `coverage.assertTransitionsCovered(required)`.
`MachineCoverageRecorder(machine)` is the equivalent observer for a directly constructed Store.

```kotlin
val plan = machine.generateTestPlan(initial, inputs, maxDepth = 10, maxDecisions = 10_000)
plan.assertReady(requireOptimal = true)
val result = machine.runPlan(initial, plan)
result.assertSuccess()
```

The planner minimizes scenario count within the discovered executable prefixes. Selection has
its own budget and reports `selectionOptimal`; exhausted discovery, unreachable declarations,
missing payloads and invariant failures remain explicit. `ExplorationStrategy.AllTransitions`
stops as soon as every declared transition has actually fired; breadth-first search can discover
longer scenarios combining branches. `driver.generateTestPlan(inputs)` and `driver.verifyPlan(plan)`
are independent pure-model operations, leaving its live Store and scripted IO untouched.

`machine.runScenario(initial, MachineScenario(name, inputs))` also runs saved regression prefixes.
Shrinking accepts an optional `preservesFailure` matcher for distinct bugs of the same exception
type. Match domain context/inputs/identities; exception messages are omitted by default.
See the [contracts and examples](../doc/internal/design/2026-10-06-derived-behavioural-features.md).

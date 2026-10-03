# koma-statechart

Statecharts for [Koma](../README.md): describe a screen's behaviour as plain data, check it,
draw it, generate test paths from it, and run it as an ordinary Koma `Store`.

- **Model.** `StateChartDefinition` is an immutable list of states and transitions: atomic,
  compound (hierarchy), parallel (regions) and history states; transitions fired by actions or
  timers, with guard and effect labels.
- **Tools.** `validate()`, `toMermaid()`, `shortestPathTo()`, `transitionCoveragePaths()`, and the
  `StateChartConformance` plugin, which checks a running Store against the chart.
- **Runtime.** `StateChartStore` runs a definition as a `Store<ChartState<C>, A, E>`, so Compose,
  `koma-test`, plugins and state savers work as with any Store. `StateChartRuntime` is the pure
  step function underneath, if you want to hold the configuration yourself.
- **Machine.** `koma.statechart.machine` decides a chart as a pure function over snapshots, with
  commands, timers and events as data: the replay-ready path of the time-travel work (see below).

The semantics follow SCXML (Harel statecharts): external transitions, exit innermost first, enter
outermost first, inner transitions take priority over outer ones. The module uses the fork's
`koma-core` (`dispatchIf`, `validateRecovery`, `StoreProbe` for `MachineStore`'s idle gate,
`InputId`, the open `StoreScope`; see the
[divergence inventory](../doc/internal/design/2026-09-28-statechart-roadmap.md#divergence-inventory-vs-upstream-400)),
so it is built and published together with it and is not meant to run against another version.

Status: **experimental.** Every declaration is `@ExperimentalKomaApi`, and the module lives in the
fork [roman-n1/koma](https://github.com/roman-n1/koma), not in upstream Koma.

## Dependency

The fork publishes every module as `io.github.roman-n1:<module>:5.0.0-alpha.1` to Maven Central from
a GitHub pre-release (`.github/workflows/publish.yml`); until a release is published, include the
fork as a Gradle composite build (for example as a git submodule), which substitutes the same
coordinates:

```kotlin
// settings.gradle.kts
includeBuild("koma")
```

```kotlin
// build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.roman-n1:koma-statechart:5.0.0-alpha.1")
        }
    }
    compilerOptions {
        optIn.add("koma.core.ExperimentalKomaApi")
    }
}
```

Gradle substitutes the included project for these coordinates. The module brings the fork's
`koma-core` with it (as an `api` dependency, `io.github.roman-n1:koma-core`); do not also depend
on the official `io.github.koma-kt:koma-core` in the same app. Targets: Android, iOS (arm64,
simulator arm64), JVM, JS and Wasm.

## Quick start

A list screen that loads, retries a failed load up to three times, and shows an error after that.

```kotlin
sealed interface ListAction : Action {
    data object Load : ListAction
    data class Loaded(val items: List<String>) : ListAction
    data object Failed : ListAction
}

sealed interface ListEvent : Event {
    data object GaveUp : ListEvent
}

data class ListContext(val items: List<String> = emptyList(), val attempts: Int = 0)

val idle = StateId("Idle")
val loading = StateId("Loading")
val content = StateId("Content")
val error = StateId("Error")

// The first transition whose guard holds is taken, so retry comes before giveUp.
val retry = Transition(loading, loading, ActionMatcher.of<ListAction.Failed>("Failed"), guard = "canRetry", effect = "countAttempt")
val giveUp = Transition(loading, error, ActionMatcher.of<ListAction.Failed>("Failed"))

val listChart = StateChartDefinition(
    initial = idle,
    states = listOf(AtomicState(idle), AtomicState(loading), AtomicState(content), AtomicState(error)),
    transitions = listOf(
        Transition(idle, loading, ActionMatcher.of<ListAction.Load>("Load")),
        Transition(loading, content, ActionMatcher.of<ListAction.Loaded>("Loaded"), effect = "storeItems"),
        retry,
        giveUp,
        Transition(error, loading, ActionMatcher.of<ListAction.Load>("Load"), effect = "resetAttempts"),
    ),
)
```

The Store gives the labels their implementations and says what each state does:

```kotlin
fun listStore(
    load: suspend () -> List<String>,
    initial: ListContext = ListContext(),
    coroutineContext: CoroutineContext? = null,
) = StateChartStore<ListContext, ListAction, ListEvent>(listChart, initial, coroutineContext) {
        guard("canRetry") { state, _ -> state.context.attempts < 3 }
        effect("storeItems") { context, action -> context.copy(items = (action as ListAction.Loaded).items) }
        effect("countAttempt") { context, _ -> context.copy(attempts = context.attempts + 1) }
        effect("resetAttempts") { context, _ -> context.copy(attempts = 0) }

        // Runs while Loading is active; cancelled when it is exited. The self-loop on Failed exits
        // and re-enters Loading, so each retry starts a new load.
        activity(loading) {
            val items = try {
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            dispatch(if (items != null) ListAction.Loaded(items) else ListAction.Failed)
        }
        onEnter(error) { event(ListEvent.GaveUp) }

        // The usual Koma Store configuration: state saver, plugins, exception handler, recover {}.
        store {
            exceptionHandler(ExceptionHandler { /* log */ })
        }
    }
```

In the UI, the state is `ChartState<ListContext>`: where the chart is, and the data.

```kotlin
val store = listStore(load = { api.items() })
store.dispatch(ListAction.Load)
store.state.collect { state ->
    when {
        state.isActive(loading) -> showSpinner()
        state.isActive(content) -> showItems(state.context.items)
        state.isActive(error) -> showRetryButton()
    }
}
```

What the builder offers:

| Call | What it does |
|---|---|
| `guard(label) { state, action -> Boolean }` | Implements a guard label. Reads the state before the step. |
| `effect(label) { context, action -> context }` | Implements an effect label: a pure context update. |
| `onEnter(id) { }` / `onExit(id) { }` | Hooks: read and assign `context`, read `action`, `event(e)`; `onEnter` can `launch { }` work that lives while the state is active. |
| `activity(id) { }` | Work that runs while the state is active, including after a restore. |
| `store { }` | The underlying `StoreBuilder`. |

In one step: exit hooks (innermost first), effects, enter hooks (outermost first), then work of
exited states is cancelled and activities and timers of entered states start. The UI sees one new
state. Building the Store fails fast when a guard or effect label has no implementation, or a hook
names an undeclared state.

Actions dispatched from an activity or `onEnter { launch { } }` are tied to that activation of
the node. If the node exits before the queued action is handled, the action is discarded, even
if the same node has already been entered again. This prevents an old load or connection attempt
from completing a newer one.

In `store { state<ChartState<C>> { recover<Exception> { ... } } }`, recovery may update `context`.
It cannot replace `configuration` or `timers`, since that would bypass exit/entry hooks and leave
the old activities running; a recovery that tries is reported as an `IllegalArgumentException`
with the original error attached as a suppressed exception. To recover into another node, dispatch
an action declared in the chart from outside the handler: `recover {}` cannot dispatch itself, so
either emit an event there and dispatch from a plugin's `onEvent`, or let the error reach the
Store's `exceptionHandler` and dispatch from it. In-place recovery can use
`nextState { state.copy(context = ...) }`.

A heartbeat modelled as a self-loop timer on a node restarts that node's activities on every
firing, and an action one of them dispatched just before the firing is discarded with the old
activation. Keep such a timer in a parallel region of its own, apart from the node whose
activities dispatch.

## Hierarchy, history, parallel regions, timers

The full example is the messenger in
[`src/commonTest/kotlin/koma/statechart/example/MessengerChart.kt`](src/commonTest/kotlin/koma/statechart/example/MessengerChart.kt):
sign-in, a session with a connection region and a UI region, reconnect backoff, message resend,
and settings that return to the same chat. It is the file to copy from. The pieces:

```kotlin
val states = listOf(
    // Hierarchy: a compound state has children (nodes whose parent is it) and an initial child.
    CompoundState(app, initial = signedOut),
    AtomicState(signedOut, parent = app),
    AtomicState(signingIn, parent = app),

    // Parallel regions: every child of a parallel state is active at the same time.
    ParallelState(session, parent = app),
    CompoundState(connection, initial = connecting, parent = session),
    AtomicState(connecting, parent = connection),
    AtomicState(online, parent = connection),
    AtomicState(backoff, parent = connection),
    CompoundState(ui, initial = chats, parent = session),

    // History: a transition into ChatsHistory restores what was active in Chats when it was left.
    CompoundState(chats, initial = chatList, parent = ui),
    HistoryState(chatsHistory, parent = chats, deep = true),
    AtomicState(chatList, parent = chats),
    AtomicState(settings, parent = ui),
)

val transitions = listOf(
    // A transition from a compound state leaves it from any of its descendants.
    Transition(session, signedOut, ActionMatcher.of<MessengerAction.SignOut>("SignOut")),
    Transition(chats, settings, ActionMatcher.of<MessengerAction.OpenSettings>("OpenSettings")),
    Transition(settings, chatsHistory, ActionMatcher.of<MessengerAction.CloseSettings>("CloseSettings")),
    // Timers: fire after the source has been active for the delay; cancelled when it is exited.
    Transition(backoff, connecting, Trigger.After(2.seconds), guard = "quickReconnect"),
    Transition(backoff, connecting, Trigger.After(30.seconds), guard = "slowReconnect"),
)
```

- **Hierarchy.** Transitions are looked up from the active leaf outwards, so an inner transition
  beats an outer one. Every transition is external: a self-loop exits and re-enters its source.
- **History.** Shallow history (`deep = false`) restores the active child; deep history restores
  the active leaves. Until the parent has been exited once, the history enters its `default`, or
  the parent's initial child.
- **Parallel.** One action may fire one transition per region, all in one step. A transition out
  of the parallel state (like `SignOut`) exits every region. `ChartState.activeLeaves(definition)`
  lists one leaf per active region.
- **Timers.** `StateChartStore` runs timers as coroutines in the Store, so `runTest` virtual time
  drives them in tests. A timer's guard is asked when it fires, with `TimerFired` as the action.

## Replay-ready machine

`koma.statechart.machine` runs a chart as a pure decision function instead of a Store with
suspending hooks. It is the foundation of the time-travel work
([handoff](../doc/internal/design/2026-09-29-time-travel-logging-handoff.md), stage 2): a `Machine`
decides, and a `MachineStore` (below) executes its decisions.

```kotlin
sealed interface ListCommand {
    data class Fetch(val query: String) : ListCommand
}

val listMachine = Machine<ListContext, ListAction, ListCommand, ListEvent>(DefinitionId("list"), DefinitionVersion("3"), listChart) {
    guard("canRetry") { snapshot, _ -> snapshot.context.attempts < 3 }
    effect("storeItems") { context, action -> context.copy(items = (action as ListAction.Loaded).items) }
    effect("countAttempt") { context, _ -> context.copy(attempts = context.attempts + 1) }
    effect("resetAttempts") { context, _ -> context.copy(attempts = 0) }

    // A rule is a pure function: it reads the context and the input, assigns the context,
    // registers commands as data and emits events as data. Nothing runs here.
    onEnter(loading) { command(ListCommand.Fetch(context.query), LaneId("load"), ConcurrencyPolicy.Latest) }
    onEnter(error) { event(ListEvent.GaveUp) }
}

var snapshot = listMachine.initialSnapshot(ListContext())
val started = listMachine.decide(snapshot, MachineInput.Start(now = MachineTime.Zero))
snapshot = started.snapshot

val decision = listMachine.decide(snapshot, MachineInput.Dispatch(ListAction.Load, now = MachineTime(1.seconds)))
decision.outcome        // Handled
decision.transitions    // [T0]: Idle -> Loading
decision.commands       // [CommandRegistration(c1, Fetch(query), scope = a3, lane = load, policy = Latest)]
decision.timersScheduled // the timers of Loading, due at now + delay
decision.snapshot.revision // 2
```

What a decision holds and what the machine guarantees:

| Piece | Meaning |
|---|---|
| `MachineSnapshot` | Configuration, context, the activation of every active node, registered commands, scheduled timers, id counters. Plain data, a Koma `State`; the machine's part of a checkpoint. |
| `Lanes`, `ExecutorCheckpoint` | The executor's part: which commands run and which wait in which lane, as a pure value with the policies' rules; the checkpoint is a snapshot, the clock and the lanes, and checks that its commands are the snapshot's. |
| `MachineInput` | `Start`, `Dispatch`, `TimerFired`, `CommandResult`, `CommandCompleted`, `CommandFailed`; each carries the clock (`MachineTime`), so the machine never reads one. |
| `Decision` | `Handled` (revision + 1, also when the business data is equal), `Ignored(reason)` (the same snapshot object) or `Failed` (a rule threw; nothing registered). Plus the transitions, exited and entered activations, commands to register, scopes to cancel, timers to schedule and cancel, events to deliver. |
| Activations | Every entry into a node, a self-loop included, is a new `ActivationId`. Commands of an enter rule belong to it and are cancelled with it; a late `CommandResult` of a cancelled command is `Ignored(StaleCommand)`. |
| Timers | Data with a `TimerId` and a deadline of `now + delay`; a fired timer is spent whether or not its guard holds, a self-loop timer restarts with a new id. |
| Determinism | The same snapshot and input give an equal decision; ids come from the snapshot's counters, so a replay issues the same ids. |

An unexpected command failure arrives as `CommandFailed` and steps with a `CommandFailure`
action: declare `ActionMatcher.of<CommandFailure>("CommandFailure")` on a transition to react to
it. An expected failure of a service (a 404, an offline error) is not this; the handler turns it
into a typed `CommandResult`.

A chart is either a legacy `StateChartStore` or a `Machine`; the two are not mixed. Building a
`Machine` fails fast for the same reasons `StateChartStore` does: a missing guard or effect
implementation, a rule for an undeclared node, undeclared endpoints, an instant timer loop.

### Handling an action without leaving a node

A self-loop exits and re-enters its node: its activation ends, its commands are cancelled and its
timers restart. For an update that must not do that (a selection while results are shown, a
filter remembered while idle) add an action handler:

```kotlin
onAction(search, ActionMatcher.of<SearchAction.ToggleSelect>("ToggleSelect")) {
    context = context.toggleSelect((action as SearchAction.ToggleSelect).contactId)
}
```

It runs only when no transition of the active configuration takes the action, so a declared
transition always wins; among handlers, the innermost active node with a matching handler wins,
then the first added. It may update the context, register commands (scoped to the node's current
activation) and emit events. The decision is `Handled` with a new revision and no transitions.

The stage 3 pilot,
[`AddressBookSearchMachine`](src/commonTest/kotlin/koma/statechart/example/picker/AddressBookSearchMachine.kt)
with [its scenarios](src/commonTest/kotlin/koma/statechart/example/picker/AddressBookSearchPilotTest.kt),
shows a whole screen built this way: a debounced search that the latest query supersedes, stale
answers ignored, closing during a request, selection without touching the search, a `UiMapper`
from the snapshot, and two tabs of the same screen recorded into one journal.

### Running a machine

`MachineStore` runs a `Machine` as a Koma `Store<MachineSnapshot<C>, A, E>`: decisions are
committed under the store's lock, and after each commit a scheduler starts the registered
commands through your `CommandHandler`, fires timers on the clock and delivers the events.

```kotlin
val handler = CommandHandler<ListCommand, ListAction> { command, results ->
    when (val c = command.command) {
        is ListCommand.Fetch -> results.result(ListAction.Loaded(api.items(c.query))) // an expected failure would be a typed result too
    }
}

val store = MachineStore(listMachine, ListContext(), handler, scope = appScope) {
    exceptionHandler(ExceptionHandler { log(it) })
    recordTo(session, StoreInstanceId("list-$tabInstanceId"))   // the journal, if you use one
}

store.dispatch(ListAction.Load)          // accepted as MachineInput.Dispatch(Load, clock.now())
store.state.collect { snapshot -> render(snapshot) }
store.event.collect { event -> toast(event) } // delivered after the commit, in decision order
```

What holds:

- A command starts only after the decision that registered it was committed, outside the lock,
  in `scope` (which must not use `Dispatchers.Unconfined`); it sees the committed snapshot in
  `currentState`. Its results, completion and failure come back as inputs; a result of a command
  the machine no longer holds is ignored.
- A command is cancelled when its activation exits, when a newer command supersedes it in a
  `Latest` lane, or when the store closes. `Sequential` and `Parallel(limit)` lanes queue,
  `DropIfRunning` drops; a dropped or superseded command is reported to the machine as
  `CommandAbandoned`. The bookkeeping is `Lanes`, a pure value the scheduler, a recording and a
  replay branch all run: a cancelled command holds its place in the lane until its job has ended.
- A decision that fails (a rule threw) commits nothing and cancels nothing; its cause reaches the
  exception handler. `recover {}` may not change the snapshot.
- A restored snapshot that was already started starts over with its context: commands are not
  part of the snapshot. They are part of `store.checkpoint()`, the executor's state as data
  (`ExecutorCheckpoint`): the snapshot of the last decision it carried out, its clock, and every
  command of that snapshot in exactly one place, running or queued in its lane or ending with
  its last input on its way to the machine. `koma-timetravel` branches from it and records from it.
- `AdmissionPolicy.Bounded(n)` refuses a dispatched action while `n` accepted actions still wait;
  `store.admit(action)` says whether it was accepted, `dispatch` drops the answer. Inputs the
  machine's own commands and timers send are never refused.
- Effects reach the UI through `store.event` (transient: delivered to whoever collects now, or
  lost) or, when `MailboxConfig.policy` says `Retained` or `Latest(key)`, through
  `store.mailbox`: they wait there, with their `EffectId`, until a subscriber of
  `mailbox.subscribe()` acknowledges the `Delivery`; a subscriber that goes away hands what it
  was handling to the next one, up to the policy's `maxAttempts` (then the effect is discarded
  as `Exhausted`), `Latest` keeps only the newest of its key, the mailbox is bounded, and the
  pending effects are in the checkpoint. `session.effectsOf(id)` journals what the mailbox
  does. See the [ADR](../doc/internal/adr/2026-09-30-effect-mailbox.md); in Compose, the
  subscription is `MailboxEffect` (below).
- Closing a store with commands unfinished tells its observers once (`onClosed`): which never
  started and which were cancelled; `session.decisionsOf(id)` journals it as `CommandsAbandoned`.
- A `MachineGroup` joins stores that talk to each other: `route(from, to, map)` delivers a
  member's effects to another as `MachineInput.BridgeReceived` with a `MessageId` of the sender
  and the effect, decided like a dispatch and never refused; `group.checkpoint(timeout)` is a
  consistent cut of the whole group (every member's `ExecutorCheckpoint` and the messages in
  flight), taken by freezing the members' input queues, waiting for what they had accepted, and
  letting the held inputs in afterwards, in order; a member that does not settle aborts the cut
  and the group resumes. Give each member's store `group.member(id)` as an observer and
  `attach` the store once built. An `ExternalSource` (a pagination engine, a socket reader)
  feeds a member with `store.feed(source, action)`, an input the recording knows as the
  source's; attached with `group.source(it)`, it is paused before every cut and its
  `SourceSnapshot` (its own fields: generation, window, load state) taken after the members
  settle, so a branch can script its next data. `koma-timetravel` records and replays a group.
- With `koma-observability`, `recordTo(session, id)` in the configuration journals the store's
  inputs and commits, and `observers = listOf(session.decisionsOf(id))` adds the decisions
  themselves: transitions, activations, commands, timers, ignored inputs with their reason,
  refused actions. Commands and refused actions carry only what the describers you pass keep.

### Effects in Compose

[koma-statechart-compose](../koma-statechart-compose/README.md) subscribes a composition to the
mailbox: `MailboxEffect(store.mailbox) { delivery -> … }` runs the block for each `Delivery`, one
at a time, oldest first, and the block acknowledges it once the UI did what the effect asked. The
subscription lives as long as the composition: what was queued before it entered is delivered
when it enters; what it was handling when it left, unacknowledged, comes back to the next
subscriber with `attempt + 1`, up to the policy's budget. One `MailboxEffect` per mailbox at a
time, at the screen that owns the store, handling every kind of retained effect with a `when`.
The UI model is `viewStore.select { it.toUiModel() }` from `koma-compose`.

```kotlin
@Composable
fun ChatScreen(store: MachineStore<ChatContext, ChatAction, ChatCommand, ChatEffect>, navigator: Navigator) {
    val viewStore = rememberViewStore(store)
    val uiModel = viewStore.select { it.toUiModel() }
    MailboxEffect(store.mailbox) { delivery ->
        when (val effect = delivery.event) {
            is ChatEffect.OpenThread -> navigator.open(effect.threadId)
        }
        delivery.acknowledge()
    }
    ChatContent(uiModel, onSend = { viewStore.dispatch(ChatAction.Send(it)) })
}
```

### Testing a machine

A `MachineStore` is a Store koma-test can drive: `startAndAwait()` starts it, `dispatchAndAwait(action)`
admits the action like `dispatch` and returns once the machine decided it (the commit and the
observers, not the commands the decision started; a rejected action throws, and during a group cut
the call returns after the thaw), `awaitIdle()` waits until the executor carried out every
decision and every result it fed was decided (a command still running, a timer, a pending effect
are data of `checkpoint()`, not work to wait for), `patch {}` before the start sets the exception
handler, the saver or the policies and appends plugins, and `createRecorder()` records the initial
snapshot, every commit and every effect the decisions emitted, transient and retained alike. A plugin appended
this way sees the callers' actions (dispatched, fed, delivered), not the executor's inputs; its
`dispatch` goes through admission. Replacing or clearing the plugins and probing through a patch are
refused; `dispatchIf` is not supported. [koma-statechart-test](../koma-statechart-test/README.md)
adds the machine's own test kit on top: `MachineTestDriver` (a virtual clock, a scripted command
handler the test answers, a settle after every step, the recorder's cursor over the effects),
`settle()`, `pendingWork()` and `assertNoPendingWork()` that name the commands, timers and effects
still in hand.

```kotlin
@Test
fun aLoad_fetches_andShowsTheContent() = runTest {
    val store = MachineStore(machine, Ctx(), handler, backgroundScope, clock, StandardTestDispatcher(testScheduler))
    val recorder = store.createRecorder()
    store.startAndAwait()

    store.dispatchAndAwait(Act.Load)            // decided: Loading, the Fetch command registered
    runCurrent()                                // the executor runs the command, its result is decided

    assertTrue(store.currentState.isActive(content))
    assertEquals(listOf(Ev.Started, Ev.Done), recorder.events)
}
```

## Validation, Mermaid, paths and conformance

Check the chart in a unit test. Pass one sample of every action type so that overlapping matchers
(a supertype and a subtype matcher on one state) are found too:

```kotlin
assertEquals(emptyList(), listChart.validate(sampleActions = listOf(ListAction.Load, ListAction.Failed)))
```

`validate()` reports duplicate ids, unknown states, broken hierarchies, bad history states,
unreachable states, unguarded transitions that compete for one action or one delay, and more; see
`ValidationIssue`.

Draw it with `listChart.toMermaid()`, which returns a `stateDiagram-v2` you can paste into a
Markdown file. Asserting it in a test keeps the diagram in the docs honest.
`toMermaid(active)` highlights the given states (a snapshot's `configuration.active`), which is
how the inspector draws a replay's or a branch's position.

Generate paths and replay them against the Store with `koma-test` and the conformance plugin:

```kotlin
@Test
fun everyPathReplays() = runTest {
    for (path in listChart.transitionCoveragePaths()) {
        val conformance = StateChartConformance.withActiveLeaves<ChartState<ListContext>, ListAction, ListEvent>(listChart) {
            it.activeLeaves(listChart).toSet()
        }
        val store = listStore(load = { awaitCancellation() }, contextFor(path), backgroundScope.coroutineContext)
            .patch { plugin(conformance) }
        store.startAndAwait()
        path.transitions.forEachIndexed { i, transition ->
            val delay = transition.after
            if (delay != null) advanceTimeBy(delay) else store.dispatchAndAwait(sampleFor(transition.on!!))
            runCurrent()
            assertEquals(path.activeLeaves[i], store.currentState.activeLeaves(listChart))
        }
        assertEquals(emptyList(), conformance.violations)
        store.close()
    }
}
```

Paths ignore guards, so the test makes the guards on each path true. `sampleFor` returns an action
the matcher matches (for example `samples.first { matcher.matches(it) }`), and `contextFor` a
context in which the path's guards hold: here `ListContext(attempts = 3)` for a path through
`giveUp`, since `retry` is tried first, and `ListContext()` otherwise. The messenger
test (`MessengerChartTest`) does this for a chart with timers, guards, regions and history.

- `shortestPathTo(id)` returns one path to a state; `transitionCoveragePaths()` returns paths that
  together take every reachable transition. Each `StateChartPath` has `transitions`, `triggers`,
  `actions`, `startLeaves` and `activeLeaves` (one list per step).
- `StateChartConformance` also works on a hand-written Koma Store: map your sealed state to its
  leaf with `StateChartConformance(chart) { state -> when (state) { ... } }`. It reports
  `violations` (undeclared states, undeclared transitions, a transition taken for the wrong action)
  and `coveredTransitions` / `uncoveredTransitions`.

## R8 and code shrinking

Nothing in the module reads class names, so a release build with R8 behaves like a debug build,
provided the chart does not rely on them either:

- **State ids are strings you write.** `StateId("Loading")`, never a class name.
- **Use `ActionMatcher.of<A>("Name")`.** It matches by type (`isInstance`); the name is only for
  diagrams and diagnostics. `ActionMatcher("Name")` without a type matches by the action's simple
  class name, which R8 renames: do not use it in app code.
- **Map your own states explicitly.** For `StateChartConformance` or `StateChartRuntime` on a
  hand-written Store, write the state-to-leaf mapping as a `when` over your sealed type.
- **Saved state is data.** `ChartState` holds `StateId` strings, the history and timer tokens keyed
  by transition index, so a `StateSaver` can persist it without reflection. Treat a change to the
  chart as a change to the saved format. The active nodes and history records are checked against
  the new hierarchy: a snapshot whose active nodes the chart cannot produce falls back to the
  initial configuration while retaining its context, and a history record the chart cannot restore
  is dropped on its own (the next transition into that history state takes the default target).
  Context schema migrations remain the application's responsibility.

## Limitations

- Experimental API that may change; no release has been published yet.
- No eventless (completion) transitions, final states, internal transitions or SCXML `invoke`.
  Model "when the work is done" as an action that the state's work dispatches.
- Guards and effects are labels in the model; their code lives in the Store builder, so tools see
  the label, not the condition. Paths ignore guards.
- The conformance plugin sees leaves, not guards or clocks: when two transitions lead to the same
  leaves (such as two timers from `Backoff` to `Connecting`), it credits the first one, and a timer
  self-loop is never credited.
- Enter hooks do not run again for a configuration restored by a `StateSaver`; activities do.
  Timers of a restored configuration restart with their full delay.
- A step whose hook or effect throws is not committed, but events that its hooks already emitted
  are not taken back, and a timer whose firing failed does not fire again until its state is
  re-entered.
- Inside `StateChartStore { }`, `enter {}` and `action {}` handlers registered through `store {}`
  never run (the chart owns the only handler), `initialState` there is ignored, and
  `PendingActionPolicy.ClearOnStateExit` does not apply to chart steps.
- In a hook, `context` is the hook's value; do not name a surrounding parameter `context`, or it
  shadows it.
- A chart store's activities and timers run in a task runner subscribed from the chart's
  `enter {}`, so koma-test's `awaitIdle()` returns while an activity awaits its node's exit and
  while a timer waits: they are the chart's data, not launches to wait for.

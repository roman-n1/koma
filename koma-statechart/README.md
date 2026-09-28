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

The semantics follow SCXML (Harel statecharts): external transitions, exit innermost first, enter
outermost first, inner transitions take priority over outer ones. The module uses only the public
`koma-core` API.

Status: **experimental.** Every declaration is `@ExperimentalKomaApi`, and the module lives in the
fork [roman-n1/koma](https://github.com/roman-n1/koma), not in upstream Koma.

## Dependency

Publishing is **not set up yet**: there is no artifact in any repository. Until there is, include
the fork as a Gradle composite build (for example as a git submodule):

```kotlin
// settings.gradle.kts
includeBuild("koma")
```

```kotlin
// build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.roman-n1:koma-statechart:4.0.0")
        }
    }
    compilerOptions {
        optIn.add("koma.core.ExperimentalKomaApi")
    }
}
```

Gradle substitutes the included project for these coordinates. The module brings the fork's
`koma-core` with it (as an `api` dependency); do not also depend on the official `koma-core` in the
same app. Targets: Android, iOS (arm64, simulator arm64), JVM, JS and Wasm.

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
  chart as a change to the saved format: a restored configuration is not checked against the new
  chart.

## Limitations

- Not published; experimental API that may change.
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

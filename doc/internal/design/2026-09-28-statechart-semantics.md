# Statechart layer: semantics of hierarchy, regions, history, timers and the adapter

- Updated: 2026-09-28

This document fixes the semantics of phases 4–7 from the
[roadmap](2026-09-28-statechart-roadmap.md) ahead of the code. Everything lives in the
`koma-statechart` module. It was written for the public API of `koma-core` only (plan B, dropped
on 2026-10-01); the adapter uses the fork's `koma-core` (`StoreInternalApi.dispatchIf`,
`StoreBuilder.validateRecovery`, `StoreProbe`, `InputId`, the open `StoreScope`), see the
[divergence inventory](2026-09-28-statechart-roadmap.md#divergence-inventory-vs-upstream-400) and
the [stability review](../notes/2026-09-29-stability-review.md). All new public types are marked
`@ExperimentalKomaApi`.

## Model

The model remains a flat list of nodes. The tree is given by a reference to the parent.
This way existing definitions (`AtomicState(id)`) keep working without
changes, and the validator and the export traverse a single list.

| Node | Fields | Meaning |
|---|---|---|
| `AtomicState` | `id`, `parent: StateId? = null` | Leaf |
| `CompoundState` | `id`, `initial: StateId`, `parent` | Exactly one active child |
| `ParallelState` | `id`, `parent` | All children (regions) active at the same time |
| `HistoryState` | `id`, `parent`, `deep: Boolean`, `default: StateId?` | Pseudo-node: a transition into it restores the parent's previous configuration |

`StateChartDefinition.initial` points to a root node. There may be several
roots: then the chart root behaves as an implicit compound.

The transition trigger is the sealed `Trigger`:

- `Trigger.OnAction(matcher)` — the arrival of an action, as now;
- `Trigger.After(delay: Duration)` — a timer that starts on entering
  `source` and is cancelled on exiting it.

`Transition(source, target, on: ActionMatcher, guard)` remains as a secondary
constructor, so existing code does not change. The `on` property returns the
matcher for `OnAction` and `null` for a timer.

A transition gains an optional label `effect: String?`. Like the guard, it is
only a label. The implementation is provided by the Store adapter.

## Configuration

`StateConfiguration` is an immutable value:

- `active: Set<StateId>` — the active nodes, including all ancestors;
- `history: Map<StateId, Set<StateId>>` — the remembered children/leaves for nodes
  that have a `HistoryState`.

The runtime remains a pure function. The configuration is held by the caller: the Koma Store,
in a field of the state.

## Step

Simplified SCXML semantics (a microstep without eventless transitions):

1. **Transition selection.** The active leaves are traversed in node declaration order.
   For each leaf, the first transition in declaration order is looked up: first on
   the leaf itself, then on its ancestors, from the inside out. A transition is eligible if
   its trigger fired and the guard is absent or returned `true`. Thus an inner
   transition takes priority over an outer one, as in Harel/SCXML.
2. **Conflicts.** Two selected transitions conflict if their exit sets
   intersect. Then the one selected earlier wins, unless the source of the new one
   lies strictly inside the source of the earlier one (the SCXML rule, refined in wave 3).
   So transitions in different regions fire together, and a parent's transition
   above the regions executes once.
3. **The exit set** of a transition is the active descendants of LCCA(source, target).
   The LCCA is the nearest common compound ancestor; for a self-loop it is the parent of the source,
   i.e. the transition is external: the source exits and is entered anew. Exit goes from
   the inside out, then in reverse declaration order. Before exiting a
   node with a `HistoryState`, shallow (the active child) or deep
   (the active leaves) history is recorded.
4. **The entry set**: the chain from the LCCA to the target, then recursively.
   A compound enters its `initial`. A parallel enters all regions. A `HistoryState`
   restores what was remembered, or, if there is nothing, the `default` or the parent's `initial`.
   Entry goes from the outside in, in declaration order.
5. **Result**: `StepResult.Transitioned(transitions, exited, entered,
   configuration)` or `StepResult.Ignored`. For timers the result contains
   `timersToStart` (the `After` transitions of the entered nodes) and `timersToCancel`
   (of the exited nodes).

`StateChartRuntime.initialConfiguration()` performs the entry into `initial`.
`step(configuration, state, action)` handles an action.
`fire(configuration, state, timer)` handles a timer: if its source is still
active, that transition fires, provided the guard lets it through.

## Validation

Added to the current checks:

- an unknown parent; a cycle in the parent chain;
- `CompoundState.initial` is not its child;
- a `ParallelState` with fewer than two regions;
- a `HistoryState` with children or outside a compound/parallel; a `default` outside the parent
  (the exact rules are in "Wave 2 decisions");
- a transition whose source is a `HistoryState`;
- `Trigger.After` with a non-positive delay.

## Mermaid

Nested `state X { ... }`, regions via `--`. History is output as
`[H]` / `[H*]` in the form of a labeled node. A timer is output as `after 5s`.

## Paths and conformance

- `shortestPathTo` and `transitionCoveragePaths` work on the graph of
  configurations (BFS from the initial configuration through `step`). Guards are
  treated as true, and timers as steps.
- `StateChartConformance` takes `stateIdOf`, which returns the active leaf, and
  looks for a transition on the leaf and its ancestors. A transition into a compound is considered correct
  if the new leaf is in the entry set.

## Store adapter (phase 4)

Without core changes, through a single catch-all handler:

```kotlin
data class ChartState<C>(val configuration: StateConfiguration, val context: C) : State

val store = StateChartStore<C, A, E>(
    definition = chart,
    context = initialContext,
    guards = mapOf("canRetry" to { s, _ -> s.context.attempts < 3 }),
    effects = mapOf("countAttempt" to { ctx, _ -> ctx.copy(attempts = ctx.attempts + 1) }),
    onEnter = mapOf(loading to { /* launch work, emit event */ }),
)
```

- The Koma state class does not change (`ChartState`), so Koma does no
  exit/enter. The lifetime of the work under a node is held by the adapter: `launch` in
  `onEnter` receives the node's `LaunchLane`, and on exit from the node the adapter
  calls `cancelLaunch(lane)`.
- Timers are started as `launch { delay(d); transaction { … fire … } }` with
  the transition's lane and are cancelled the same way.
- (Implemented differently: `LaunchLane` is not available from the timer's transaction, so the adapter keeps
  its own node activation `Job`s and timer tokens in the state; see "Wave 5 decisions".)
- Order within one step: exit handlers (from the inside out), then the effects
  of the transitions (in selection order), then enter handlers (from the outside in). All of it
  runs in a single Koma transaction, so the UI sees one new state.
- The Store remains an ordinary `Store<ChartState<C>, A, E>`: `koma-test`,
  `Plugin`, `StateSaver` and Compose work without changes.

## Wave 1 decisions (hierarchy)

Refinements that were not present above; code in branch `feature/statechart-hierarchy`.

- **LCCA** is the nearest compound ancestor that is a *proper*
  ancestor of both source and target (for the top level, the implicit root). So
  all transitions are external: not only self-loops, but also a transition from a node into its
  descendant (`Online → Typing`: exit Idle, Online; enter Online, Chat, Typing) and
  from a node into its ancestor (`Reading → Online`: exit Reading, Chat, Online; enter
  Online, Idle). There are no internal (`type="internal"`) transitions.
- **`initial` may be a nested node.** Then its ancestors are entered from the outside
  in, then the node itself and the `initial` chain downward. The validator does not forbid this.
- **Unknown ids** (an undeclared `initial` or target) behave as
  top-level atomic nodes. So paths and reachability on invalid
  charts work as before.
- **The runtime rejects a broken hierarchy**: the constructor throws
  `IllegalArgumentException` on `UnknownParent`, `AtomicParent`,
  `ParentCycle`, `InitialNotChild`, `EmptyCompoundState`. Validate, Mermaid,
  paths and conformance on such charts neither fail nor loop: walking up the
  parents stops on a repeat and on an undeclared parent.
- **Validator checks** come after duplicates and `initial`, before the transition
  endpoints: first an unknown or atomic parent, over nodes in
  declaration order, then cycles (each once, starting from the first declared
  node, in the order of their first nodes), then `initial` not a child, then an empty
  compound. With duplicate ids the first declaration is taken.
- **Reachability and paths** traverse configurations by BFS, as in the section on
  paths, but in each configuration *any* transition with an active source qualifies:
  guards and priority (an inner transition shadowing an outer one) are not taken
  into account. A node is reachable if it is active in at least one reachable configuration;
  `shortestPathTo(x)` leads to the first such configuration found.
- **`StateChartPath`** gained a third field `leaves` — the active leaf after
  each transition; `start` is the leaf of the initial configuration, `end` is the last
  leaf. The old constructor `(start, transitions)` remains: it checks the
  connectivity of a flat path and takes `leaves` from the targets.
- **`StepResult.Transitioned`** is now `(transitions, exited, entered,
  configuration)`. The secondary constructor `Transitioned(transition)` builds the
  result of a flat step (`exited = [source]`, `entered = [target]`,
  `active = {target}`) and equals what the runtime returns for a flat
  chart. `transition` is the first selected transition, `target` is its target (for a
  compound target the new leaf is `entered.last()`).
- **`step(state, action)`** remains and builds the configuration from
  `stateIdOf(state)` via `configurationOf(leaf)` (the leaf and its ancestors).
  The order of `active` is from the outside in.
- **Conformance**: the transition candidates from leaf `from` are the transitions of the leaf and its
  ancestors in priority order; a transition leads to `to` if `to` is in its
  entry set from `configurationOf(from)`. A self-loop is a transition after
  which the leaf is the same (for example, a self-loop of a compound ancestor whose `initial` is
  the current leaf).
- **Mermaid**: in a compound block, first `[*] --> initial`, then all children
  (atomic ones on their own line, so that they land inside the block), then the transitions
  for which this block is the nearest common proper ancestor of the endpoints. Nodes with a
  broken parent are drawn at the top level.
- **Several active leaves** (will appear with regions) are already selected by the
  rule of the "Step" section: a leaf's transition is discarded if its exit set
  intersects one already selected. A transition whose domain is the root exits everything.
- **`Trigger`** is not introduced yet: `Transition.on: ActionMatcher` is unchanged;
  the sealed `Trigger` will appear in the timers wave. `StateConfiguration.history`
  exists and is always empty until the history wave.

## Wave 2 decisions (history)

Refinements; code in branch `feature/statechart-history`.

- **Model.** `HistoryState(id, parent: StateId, deep = false, default: StateId? = null)`.
  `parent` is not nullable: there is no top-level history. For now the parent is
  only a `CompoundState`; for `ParallelState`, recording and restoring are already
  written for sets (several children, several leaves).
- **The key of `StateConfiguration.history` is the id of the `HistoryState` itself**, not
  the parent's. This way one parent can have both a shallow and a deep history: each
  remembers its own (shallow — the active child, deep — the active atomic leaves).
  An empty record is not stored: no key means nothing was remembered.
- **When it is written.** In a microstep the exit set is computed first, then for
  each exiting node all its histories are written (from the configuration *before*
  the step), and only then the entry is computed. So a transition that exits a
  parent and targets its history (for example, `Chat → ChatHistory`)
  restores what was just recorded. A transition inside the parent
  (`Sending → ChatHistory`, LCCA = `Chat`) does not exit the parent and
  restores the previous record.
- **Entry.** A history target is never entered itself: the history's ancestors
  below the LCCA are entered, then for each remembered node the chain from the parent down to it and its
  `initial` chain downward. No record — `default`; no `default` — the parent's `initial`.
  `StepResult.entered` contains only real nodes; `target` remains the
  history (the new leaf is `entered.last()`).
- **Validation** (new `ValidationIssue`s):
  `HistoryParent` (a history has children; checked together with
  unknown/atomic parent), then the history group after the hierarchy:
  `HistoryAsInitial(null, h)` — a history as the chart's `initial`,
  `HistoryAsInitial(compound, h)` — as the `initial` of its own parent (a history
  of a different parent remains `InitialNotChild`), `InvalidHistoryDefault` —
  shallow: `default` must be a child of the parent, deep: any proper
  descendant; in both cases declared and not a history (checked only
  if the parent is a declared compound). `TransitionFromHistory` —
  in the transition endpoints block, after unknown source/target of the same transition.
  A history with an unknown or atomic parent is already `UnknownParent` /
  `AtomicParent`. Several histories on one parent are allowed.
  `UnreachableState` is never reported for a history: it is never active.
- **The runtime rejects** everything that makes entry undefined: hierarchy
  issues (including `HistoryParent`) plus `HistoryAsInitial` and
  `InvalidHistoryDefault`. `TransitionFromHistory` is only a warning:
  such a transition simply never fires.
- **Invalid charts in the tools.** A history that cannot be resolved
  (the parent is not a compound, `default`/`initial` is not a descendant) is entered as an atomic
  node, like undeclared ids. Paths, reachability and Mermaid neither fail nor
  loop.
- **`step(state, action)` without a configuration** builds `configurationOf(leaf)` —
  with an empty history, so charts with history need the variant with a
  configuration (held by the caller).
- **Paths and reachability** go over full configurations (`active` + `history`):
  this way the shortest path is replayed by the runtime one to one. The graph is finite:
  every record is a subset of the declared nodes. The graph is still built
  once per definition (`configurationGraph` in `Model.kt`).
- **Conformance** tracks history itself from the leaf changes by the same rules
  (after an unexplained change, the exiting nodes are taken to be the ancestors of the old leaf that are
  not ancestors of the new one; a self-loop writes nothing). A transition into a history
  leads to `to` strictly according to the tracked record, so a Store that "forgot"
  to restore the screen gets `UndeclaredTransition`. If the plugin has not yet
  seen a record for this history (for example, the Store started from a saved
  state), any leaf the history could restore is accepted:
  `default`/`initial` and the restoration of each child (shallow) or
  atomic descendant (deep) of the parent.
- **Mermaid.** A history is a node labeled `[H]` / `[H*]`, always declared
  via `state "[H]" as ref` in the parent's block (ref is the id or a safe alias).
  `default` is drawn as an unlabeled edge `ref --> default` in the same block
  where such a transition would go, before the block's transitions.

## Wave 3 decisions (parallel regions)

Refinements; code in branch `feature/statechart-parallel`.

- **Model.** `ParallelState(id, parent = null)`. Regions are the children that are not
  `HistoryState`s. A region may be atomic (simply always active together with the
  parent), compound or a nested parallel. A `HistoryState` under a parallel is
  the history of the parallel itself, not a region, and is not counted among the regions.
- **Conflicts — the SCXML rule, not "first selected".** In wave 1 it was
  "the one selected earlier wins". That makes the outcome depend on region order:
  if the leaf of the first region selected `Online --Escape--> Offline` and the leaf of the
  third its own `Conversation --Escape--> Inbox`, the outer transition would shadow the
  inner one, although step 1 promises inner priority. So, as in
  SCXML's `removeConflictingTransitions`: a new selection replaces all earlier ones it
  conflicts with, if its source is a proper descendant of the source of each
  of them; otherwise the new one is discarded. Between unrelated sources (different
  regions, one exiting the whole parallel) the one selected earlier still
  wins. Without a parallel there is a single leaf and nothing changes.
- **Domain and transitions between regions.** The domain is still the nearest
  proper *compound* ancestor (a parallel is never the domain, like the LCCA in SCXML:
  compound or root). So a transition from region A into region B of the same
  parallel exits the whole parallel and re-enters it: B into the target,
  the other regions into their initial. This matches SCXML.
- **Entry (entry set).** Computed as in SCXML: first each target with descent
  (a compound — into `initial`, if nothing inside is being entered yet; a parallel —
  into all regions where nothing is being entered yet), then the target's ancestors below the domain;
  a parallel ancestor fills in the regions that have no entering descendants. History: first
  descent into all restored nodes, then their ancestors up to the history's parent, so
  that a parallel's deep history restores all regions rather than filling in their
  initial. The chart's `initial` inside a region enters together with the other
  regions.
- **Order.** Exit — by depth from the inside out, at equal depth in
  reverse declaration order; enter — from the outside in, at equal depth in
  declaration order. This is "level by level", not SCXML document order
  (`P, A, a1, B, b1`): ours is `P, A, B, a1, b1`. The invariants "exit from the inside
  out, enter from the outside in" hold.
- **History under a parallel.** Shallow remembers the active children — that is, all
  regions — and restores them through their `initial`. Deep remembers all active
  leaves of all regions. Without a record: `default` (for shallow — a region, for deep —
  any proper descendant; the missing regions enter their initial), without
  `default` — all regions. `InvalidHistoryDefault` is checked for a
  parallel parent too.
- **Validation.** A new `ValidationIssue.TooFewRegions(id, regions)` — a
  parallel with fewer than two regions. This is a warning: the runtime accepts such a chart
  (one region behaves as a compound, none — as an atomic
  node). Order: after the history group, before the transition endpoints, in the
  declaration order of the parallels. A parallel may be a parent, so `AtomicParent` and
  the other hierarchy checks skip it.
- **Runtime.** `step(configuration, state, action)` with an unchanged signature;
  `transitions` may contain one transition per region. `step(state, action)` and
  `configurationOf(leaf)` are not suitable for a parallel (a single leaf does not describe the
  regions): `configurationOf(leaves: Collection<StateId>)` was added.
  The runtime constructor still requires `stateIdOf`; for charts with a parallel
  it is needed only by that short `step`.
- **Paths and reachability — a step as in the runtime.** The graph step for a transition `t` with
  an active source is the runtime's selection for an imaginary action that
  matches exactly the transitions with a matcher *equal* to `t.on`, with guards true
  and everything that conflicts with `t` switched off (so `t` definitely fires, while under its
  source priority is still ignored). So a path is replayed by the
  runtime with one action per transition: for example, `Drop` in `Connected`
  also moves `Syncing` to `Paused`. Without a parallel the step is exactly `t`, as before.
  Overlapping but not equal matchers (a supertype) are not taken into account.
- **`StateChartPath`** gained `startLeaves` and `activeLeaves` (all leaves before the
  path and after each step, in declaration order) with defaults derived
  from `start`/`leaves`, so the old constructors work. `start`
  and `leaves` are the first leaves. `transitions` — one "trigger" per action.
- **Conformance.** `StateChartConformance.withActiveLeaves(definition) { leaves }`
  — a factory for a Store whose state is described by a set of leaves
  (the constructor with `stateIdOf` remains; a constructor overload with a different
  lambda conflicts on the JVM and in type inference). A change of leaves is explained per
  region: every departed leaf (in declaration order) requires a transition
  that exits it (first its own and its ancestors', then those of the other leaves —
  for a transition from another region), leads into new leaves within its domain
  and does not intersect by exit with those already selected. An unexplained leaf yields a
  violation with the nearest new leaf (the deepest common ancestor; on a tie
  — the first by declaration), and its domain counts as covered. A new leaf that
  was covered neither by a selected transition nor by a violation is also a violation.
  A region where the leaf did not change is not checked. Self-loops: the runtime's selection for the
  action without guards; if it returns the same leaves — pending self-loops.
  For charts without a parallel the behavior matches the previous one exactly.
- **Mermaid.** `state P { ... -- ... }`: one section per region (a region is drawn
  as an ordinary child, a compound/parallel as a nested block). The history of the parallel
  itself is drawn in the first section. There are no transitions in a parallel's block: a transition
  whose common block is a parallel (between regions, the default of a parallel's history)
  is written in the nearest enclosing non-parallel block. A parallel without children is drawn
  as atomic.

## Wave 4 decisions (timers)

Refinements; code in branch `feature/statechart-timers`.

- **Model.** `Transition(source, target, trigger: Trigger, guard = null, effect = null)` is
  the primary constructor; `Transition(source, target, on: ActionMatcher, guard = null,
  effect = null)` is secondary, so old calls (positional and with `on =`) compile and
  behave as before. `on: ActionMatcher?` is now a computed property: the matcher for
  `OnAction`, `null` for a timer. Added `after: Duration?` and `isTimer`. Equality is by
  all fields, including `trigger` and `effect`. **Source-incompatible:** `transition.on.name`
  without `?.`/`!!` and `copy(on = …)` (now `copy(trigger = Trigger.OnAction(…))`); in the module's tests
  this has been fixed. `effect: String?` is introduced now: it is only a label (the runtime ignores it,
  Mermaid prints it); the adapter will provide the implementation.
- **Timer guard.** The guard signature stays `(S, Action) -> Boolean`. For a timer the runtime
  passes `TimerFired(timer)` — a public `data class TimerFired(val timer: Transition) : Action`.
  A guard shared by an action and a timer tells them apart via `action is TimerFired`. A `TimerFired`
  passed to `step` is an ordinary action: timers fire only through `fire`.
- **Runtime.** `step` never selects timers. `fire(configuration, state, timer)`:
  `IllegalArgumentException` if `timer` is not an `After` or is not declared in the chart; `Ignored` if
  the source is not active (a late timer that the caller did not cancel) or the guard is false; otherwise
  **one** timer is taken (the other regions do nothing: the timer event is unique) by the same
  exit/history/enter rules.
- **Which timers to start.** `StepResult.Transitioned` gained `timersToStart` (the timers of the
  entered nodes in `entered` order, within a node in declaration order) and
  `timersToCancel` (the timers of the exited nodes in `exited` order), both with the default value
  `emptyList()`, so the old constructors work. The caller cancels first, then
  starts. A transition is always external, so the source of the timer that fired always exits: the
  timer itself lands in `timersToCancel` (cancelling an already completed one is harmless), and a self-loop timer
  in both lists, i.e. it is restarted (a periodic timer). Any re-entry into a node
  restarts its timers. For the start: `initialTimers()` and `activeTimers(configuration)`
  (the timers of the active nodes from the outside in — for a configuration restored from storage;
  the delays then start over). The secondary `Transitioned(transition)` contains no timers.
- **Identical timers.** Two declarations with equal fields are equal values; in the lists they
  appear twice. An adapter that needs to tell them apart has to key by index in
  `definition.transitions`.
- **Validation.** `NonPositiveDelay(transition)` — in the transition endpoints block, after
  `TransitionFromHistory` of the same transition (a warning: the runtime accepts such a chart; what
  to do with a zero delay is up to the caller). A timer from a `HistoryState` is already
  `TransitionFromHistory`. `AmbiguousTransitions` is now only for action transitions; for
  timers there is a new `AmbiguousTimers(source, delay, transitions)`: several guardless timers
  from one source with the same delay (they would fire at the same time; the start order decides). Order:
  right after `AmbiguousTransitions`, before `ShadowedTransitions`. The samples in `validate` do not
  check timers. A guardless timer with a longer delay than another one from the same source will never
  fire — this is not checked.
- **Mermaid.** The label is `after ${delay}` (`Duration.toString()`: `after 5s`, `after 1m 30s`),
  then ` [guard]`, then ` / effect` (the last one also for action transitions).
- **Paths and reachability.** A timer is a graph step, like any transition with an active source; one
  fires (as with `fire`); delays are not taken into account — only steps count. `StateChartPath.transitions`
  may contain timers; `triggers: List<Trigger>` was added (one per step), and `actions`
  now contains only action steps (for a path without timers — as before). A test replays
  a timer step through `fire` or by advancing virtual time.
- **Conformance.** The plugin does not see timers. A leaf change is attributed to a timer if no
  eligible transition matched an unused action trigger, but among the eligible ones there is an
  `After`: the first such one by priority is covered. If the whole change was explained by timers (and there are no
  violations), the action trigger remains unused for the next change. Without a trigger, as
  before, the first eligible transition is taken, timer or not. A change that is explained by both
  a matching action and a timer is attributed to the action. A timer self-loop does not change the leaves, Koma
  does not report it, and it is never covered.

## Wave 5 decisions (Store adapter)

Refinements; code in branch `feature/statechart-store` (`StateChartStore.kt`).

- **API.** `StateChartStore<C, A, E>(definition, context, coroutineContext = null) { ... }`
  returns an ordinary `Store<ChartState<C>, A, E>` created by the Koma DSL `Store(...)`, so
  `koma-test` (`dispatchAndAwait`, `patch`, `StoreRecorder`), `Plugin`, `StateSaver` and Compose
  work without changes. The builder `StateChartStoreBuilder` (`@KomaStoreDsl`):
  `guard(label) { state, action -> }`, `effect(label) { context, action -> }`,
  `onEnter(id) { }`, `onExit(id) { }`, `activity(id) { }` and `store { }` — access to the
  `StoreBuilder` (coroutineContext, stateSaver, plugin, exceptionHandler, policies, `recover {}`).
  Instead of the `mapOf(...)` from the section above — a DSL, as CLAUDE.md requires.
- **State.** `data class ChartState<C>(configuration, context, timers: ChartTimers = ChartTimers())`
  with `isActive(id)` and `activeLeaves(definition)`. `ChartTimers(running: Map<Int, Long>, issued: Long)` —
  the tokens of the running timers by index in `definition.transitions` (equal declarations are equal
  values, hence the index) and the last issued token. All of it is data, saved by `StateSaver`.
  Consequence: restarting a timer (for example, a periodic self-loop) changes the state even if
  the configuration and context are the same, and Koma commits it.
- **Fail fast at build time** (`IllegalArgumentException`): a missing guard (checked by `StateChartRuntime`),
  a missing effect, a broken hierarchy (the runtime constructor), a hook/activity for an undeclared node or
  for a `HistoryState`, re-registration of a guard or effect with the same label. Extra labels are
  allowed. `NonPositiveDelay` is not an error: a timer with a delay `<= 0` fires immediately.
- **A single catch-all handler.** `state<ChartState<C>> { enter { start }; action<Action> { step } }`.
  The action type is erased, so the handler is registered through an unchecked cast of the builder to
  `StoreBuilder<ChartState<C>, Action, E>`; only `A` arrives in the Store. The state class does not
  change, so Koma never does its own exit/enter, `enter {}` runs exactly once
  at start, and `PendingActionPolicy.ClearOnStateExit` does not affect chart steps. `store {}` blocks
  are applied after registration, so their `enter {}`/`action {}` for `ChartState` never
  fire, while `recover {}` works; `initialState` from `store {}` is ignored.
- **Step.** `runtime.step`; `Ignored` — the state does not change, no hooks are called, no commit
  (there is no separate hook for unhandled actions: a Koma plugin sees the action in `onAction`). On
  `Transitioned`, in a single handler: exit hooks (in `exited` order, from the inside out), effects
  (in transition selection order), enter hooks (in `entered` order, from the outside in); then the
  work of the exited nodes is cancelled, the activities of the entered ones are started, and timers: those of `exited` are removed, new
  tokens are issued for `entered` (as `timersToCancel`/`timersToStart`, but by index). A single
  `nextState` — the UI sees one new state. Hooks receive `context` (a var; changes
  accumulate in order), `action` (`A`, `TimerFired` for a timer, `null` at start), `node`,
  `event(e)` (emitted immediately, before the commit — as in Koma). Guards receive the state before the step.
- **An error in a hook or effect.** The exception goes to Koma (`recover {}` / exceptionHandler),
  the step is not committed; the activations created in this step are cancelled, so work started by a
  hook does not start. Events already emitted by the hooks of the failed step are not retracted. A timer whose step
  failed is spent: it is removed from `timers.running` when the failure is reported (stability
  review, second round; this document first said it stayed listed) and fires again only after
  re-entry (or a Store restart).
- **Node work without `LaunchLane`.** `cancelLaunch` exists only in `ActionScope`, and a timer
  fires in a transaction, which has neither `launch` nor `cancelLaunch`. So `enter {}` once
  starts a Koma `launch` that lives for the whole lifetime of the Store: it reads a task channel (`Channel.UNLIMITED`,
  `trySend` is thread-safe) and starts them as child coroutines in a `supervisorScope` (in the context of the
  Store, hence under `runTest` — virtual time). Each entry into a node has its own "activation"
  `Job()`; a task is tied to it via `invokeOnCompletion` and is cancelled on exit from the node, and
  if the activation is already cancelled before the start, it does not start. Closing the Store cancels everything through
  Koma. The adapter's mutable tables live in the object of the specific Store (not globally) and
  are touched only inside Koma handlers and transactions, which Koma executes one at a time under a
  mutex.
- **`ChartEnterScope.launch` and `activity`.** `launch` in `onEnter` — work for the duration of the activation.
  `activity(id)` — the declarative work of a node (like an activity in Harel / invoke in SCXML): it starts
  after the step's hooks, at start and after restore. `ChartLaunchScope`: `node`, `isActive`,
  `event(e)`, `updateContext { }` (a transaction; applied only if the activation is still alive,
  returns `Boolean`), `dispatch(action)`. An exception in the work (other than cancellation) is rethrown
  inside a transaction and reaches `recover {}` / exceptionHandler; the Store keeps working.
- **Timers.** The timer task: `delay(after)`, then a transaction: if the token in the state does not
  match — the firing is stale and is ignored (no commit); otherwise `runtime.fire`. `Ignored`
  (guard false) removes the timer from `running` (a commit), `Transitioned` — an ordinary step with
  `TimerFired`. Correctness does not depend on the moment the coroutine is cancelled: best-effort cancellation ties
  the timer task to the source's activation, but the token decides. Timers with an equal delay from one
  node fire in start (declaration) order; if the first exits the source, the second arrives
  with a stale token (messenger: `retry` and `giveUp` at 5 s each).
- **Start and restore.** The initial state is built at build time: `initialConfiguration()` and
  tokens `1..n` for `initialTimers()`. A fresh start (Koma handed back exactly this object — compared by
  reference) runs the enter hooks of the initial configuration from the outside in with `action = null`, then
  activities and timers; a commit only if the hooks changed the context. For a state restored by
  `StateSaver` (or substituted via `patch { initialState }`) — enter hooks are **not**
  run again (like entry actions in SCXML/XState: the context already contains their result),
  activities start, running timers start over with the full delay (as
  `activeTimers`). If the `running` set does not match the timers of the active nodes, tokens are issued
  anew (from `issued + 1`) and this is committed; otherwise the old tokens are reused — the coroutines
  of the previous process are gone. The adapter checks the restored configuration against the
  definition (stability review, first and sixth rounds): active nodes the chart cannot produce fall
  back to the initial configuration, keeping the context; a history record the chart cannot
  restore is dropped on its own. An exception in a hook at start goes to `recover {}` once the
  state is committed; the start's activations, activities and timers exist anyway (second and
  third rounds), only the failed hooks' context changes and launches are dropped.
- **Conformance.** `StateChartConformance.withActiveLeaves<ChartState<C>, A, E>(definition) { it.activeLeaves(definition).toSet() }`
  on the adapter yields no violations; self-loop timers remain uncovered (wave 4 decision).
- **Tests.** `StateChartStoreTest` — the messenger (parallel: a connection with `retry`/`giveUp` at 5 s,
  a chat with deep history, a 3 s typing timer, a sync job in `onEnter`, a ping activity), restore,
  plugins/koma-test/conformance, errors. `StateChartStorePropertyTest` — random charts
  `forEachTimerChart` (+ random effects) and random schedules of actions/virtual time
  advances; the reference is the pure `StateChartRuntime` with a naive scheduler (deadline, then start
  order). After every operation the configuration, the set of running timers, the hook order,
  the context and the live activities match; the same without cancelling the timer coroutines (tokens only) and after
  a restore in the middle of the walk. `RandomCharts.forEachTimerChart` became `inline` so that the block could
  suspend.

## Wave 6 decisions (example and documentation)

The semantics did not change; the library code did not change.

- **Example.** `koma-statechart/src/commonTest/kotlin/koma/statechart/example/`: `MessengerChart.kt`
  (the chart, context, services, Store factory — the file that gets copied) and `MessengerChartTest.kt`
  (scenarios in virtual time, `validate`, Mermaid, a model-based test). Adapter details already
  covered by `StateChartStoreTest` (hook order, restore, stale timers, errors) are not
  repeated.
- **Model-based test.** Every path from `transitionCoveragePaths()` is replayed on a Store with
  services that never respond: an action step — `dispatchAndAwait` of a sample action,
  a timer step — advancing virtual time by the delay; after each step the leaves are compared with the
  path's `activeLeaves`, and at the end the plugin's `violations` are empty. Paths ignore guards, so the test
  chooses a context (`RetryPolicy`) under which the path's guards are true.
- **A conformance limitation that surfaced in the example.** Two timers `Backoff → Connecting` with
  different guards lead to the same leaves; the plugin credits the first by priority
  (`quickReconnect`), even when `slowReconnect` fired. This follows from the conformance rules
  (leaves, not guards and clocks); the test records this explicitly; replaying the path itself proves
  that the slow timer fired (the leaves change only after 30 s).
- **A pitfall in hooks.** Inside `onEnter`/`onExit`, `context` is a property of the hook scope. A parameter of the
  enclosing function named `context` shadows it (in Kotlin, local names take priority over
  members of an implicit receiver), and the hook reads the initial context. In the example the factory parameter is named
  `initial`; the README warns about this.

## Wave order

1. Hierarchy: model, validation, Mermaid, runtime with a configuration, paths,
   conformance.
2. History.
3. Parallel regions.
4. Timers (`Trigger.After`).
5. The `StateChartStore` adapter with effects, onEnter/onExit and timers.
6. The "messenger" example in tests and the roadmap update.

Each wave is a separate branch and PR in the fork, with its own property tests.

# koma-strict and koma-statechart: comparison and bridge

- Updated: 2026-09-28
- Subject: [TBSten/koma-strict](https://github.com/TBSten/koma-strict) — a KSP plugin on top of
  Koma (MIT, experimental), and our `koma-statechart` module (fork `roman-n1/koma`).
- Roman's question: do they overlap, can they be used in the same messenger, and is a bridge
  between them needed.

## Source and what was checked

Read-only investigation. Material: a shallow clone of the koma-strict repository (commit
`6a1d201`, 2026-08-05) made by the coordinator outside the `koma` repository: README, `doc/internal/`,
the sources of `koma-strict-runtime`, `koma-strict-diagram`, `koma-strict-ksp/shared`. Nothing was
copied into our repository. I did not open the koma-strict build or the Maven Central site.

Labels in the text:

- **[verified]** — read in the sources or README of the clone;
- **[inference]** — my conclusion from what was read, not verified by running anything.

## What koma-strict is

- **[verified]** A KSP plugin: the user writes a sealed hierarchy of states with the annotations
  `@StoreSpec(initial = [...])`, `@OnEnter(nextState, emit)`, `@OnExit(emit)`,
  `@OnAction<A>(nextState, emit)`, `@OnRecover<E>(nextState, emit)`, `Stay`; the plugin generates a
  type-safe DSL (`createLceStore(...)`, `states(...)`, `<State>.actions(...)`,
  `nextState.toXxx()`, `emitXxx(...)`). The result is an ordinary Koma `Store<S, A, E>`.
- **[verified]** The "strict" idea: an undeclared transition or event cannot be written (the
  `toXxx`/`emitXxx` functions exist only for declared ones), and handling of a declared state and
  action cannot be forgotten (required named parameters). All of it is compile errors.
- **[verified]** Hierarchy is sealed groups: `@OnAction` on an intermediate sealed type or on the root
  is a "shared action", which the generator expands into the `state<Leaf> {}` block of every leaf
  (Koma's hierarchical dispatch is not used). The same action on an ancestor and a descendant is a
  KSP error. Transition targets are concrete leaves only.
- **[verified]** `nextState = [Stay::class, X::class]` — the handler may stay or go to
  `X`; which one is decided by the handler code at run time. Guards do not exist as an entity.
- **[verified]** Modules and coordinates: group `me.tbsten.koma.strict`, version `0.3.0` in
  `libs.versions.toml`; `koma-strict-runtime` (`api` on `io.github.koma-kt:koma-core`, `4.0.0-rc03`
  in the clone), `koma-strict-ksp`, `koma-strict-diagram` (pure KMP, no KSP/Compose, targets
  android/jvm/js/wasmJs/iosArm64/iosSimulatorArm64), `koma-strict-diagram-compose`, an IDE plugin.
  Publishing via `publishToMavenCentral()`; the README claims the modules are published and
  shows a Maven Central badge. I did not open the artifact on Central itself.
- **[verified]** Diagrams: the KSP option `koma.strict.generateDiagramModel` (default `false`)
  generates `<Root>DiagramModel: StoreDiagramModel` and `<Root>.diagramStateId(): StateId`.
  They are rendered by the Compose panel `StoreDiagramPanel`. Export to Mermaid/PlantUML is described in the README and
  `doc/internal/generate-state-diagrams.md` as "design only, not implemented".
- **[verified]** `@FlowSpec` (named paths for highlighting on the diagram) is so far only a
  declaration: the processor does not read it, and `flows` in the generated model is always empty.

### Diagram IR (`koma-strict-diagram`, package `me.tbsten.koma.strict.diagram.model`)

**[verified]** from the sources:

- `StoreDiagramModel(root: RootState, initial: List<StateId>, reachableLeafIds: Set<StateId>, degraded, unresolved, error, packageName, flows)`;
- the `DiagramStateNode` tree: `RootState` / `GroupState(enter?)` / `LeafState(enter?)`, each with
  `actions: List<ActionTrigger>`, `recovers: List<RecoverTrigger>`, `exit: ExitInfo?`;
- `StateId(segments: List<String>)` — the path from the root (`StateId("Stable", "Idle")`), `dotted`;
- `ActionTrigger(actionName, targets: List<StateId>, stay, emits, unresolvedTargets, source, actionRef)`,
  where `actionName` is the simple name of the action type and `actionRef` is the path from the package (`FeedAction.Retry`);
- `EnterTrigger(targets, stay, emits, ...)` (only on leaves in the generated model),
  `RecoverTrigger(exceptionName, targets, stay, emits, ..., exceptionRef)`, `ExitInfo(emits)`;
- `Reachability.compute(root, initial)` — the reachable leaves; the generated model computes them
  at initialization.
- `diagramStateId()` is generated as `when (this) { is X.Y -> StateId("Y", ...) }` without `else`, with
  literal names. This is robust to R8 **[inference]**: the names are written in as strings at compile time, and `is`
  survives class renaming.

## Overlap

| Capability | koma-strict | koma-statechart |
|---|---|---|
| Model source | annotations on sealed types, KSP | `StateChartDefinition` data, written by hand |
| Type-safe DSL for transitions and events | yes, compile-time | no: guard/effect labels are strings, checked when the Store is built |
| Handler completeness check | yes, compile-time | no (not needed: the runtime executes the model itself) |
| Hierarchy | sealed groups, shared actions expanded into the leaves; exit/enter as in Koma (by leaf class) | compound states, LCA, exit from the inside out, enter from the outside in |
| Parallel regions | no | yes |
| History (shallow/deep) | no | yes |
| Timers | no (by hand via `launch`) | `Trigger.After`, started by `StateChartStore` |
| Guards | no; branching inside the handler (`[Stay, X]`, several targets) | named guards in the model |
| Recover/exit | `@OnRecover`, `@OnExit(emit)` | recover — via `store { recover {} }`; exit — the `onExit` hook |
| Validation | KSP errors (structure), unreachable leaves | `validate()`: 19 kinds of issues, including ambiguous transitions and timers |
| Diagrams | IR + Compose panel with live highlighting; Mermaid/PlantUML not implemented | `toMermaid()`; no live highlighting |
| Path generation, transition coverage | no (`@FlowSpec` is so far only a declaration) | `shortestPathTo`, `transitionCoveragePaths` |
| Checking a running Store against the model | no | `StateChartConformance` (Plugin) |
| IDE plugin | yes | no |
| Where the state lives | the user's sealed type | `ChartState<C>` (configuration + context + timers) |

In short: **[inference]** koma-strict makes an ordinary Koma machine strict at compile time and
displays it; koma-statechart adds semantics that Koma does not have (regions, history, timers,
LCA) and model-based testing tools. They overlap only in "the transition model as data
+ a picture".

## Can both be used in one project

**[inference]** Yes, but not on the same Store:

- `StateChartStore` keeps its state in a single class `ChartState<C>`, while koma-strict requires a
  sealed root with `@StoreSpec`. One Store can be only one of the two.
- A sensible split for a messenger: simple screens (LCE, forms) — koma-strict; complex machines
  with regions, history and timers (connection + chat) — koma-statechart.
- **Dependencies.** Both pull in `koma-core`: koma-strict — the official `io.github.koma-kt:koma-core`
  (`4.0.0-rc03` in the clone), the fork — its own `koma-core`. In the fork, `koma-core` currently has the same group
  `io.github.koma-kt` (only `koma-statechart` has its own group `io.github.roman-n1`), so with a
  composite build (`includeBuild`) Gradle will substitute the fork's `koma-core` for koma-strict's
  transitive dependency as well — the classes will not be duplicated. I did not check API compatibility
  `4.0.0-rc03` → `4.0.0`. If the fork ever publishes `koma-core` under `io.github.roman-n1`, a
  `dependencySubstitution` rule (or a capability) will be needed, otherwise two `koma-core`s with
  identical classes end up on the classpath.
- Names: both modules declare `StateId` (`me.tbsten.koma.strict.diagram.model.StateId` and
  `koma.statechart.StateId`); in a file that needs both, use `import ... as`.

## Bridge: koma-strict IR → `StateChartDefinition`

Goal **[inference]**: for a Store written with koma-strict, get the koma-statechart tools without
rewriting: `validate()`, `toMermaid()` (which koma-strict does not have yet), paths for model-based
tests and `StateChartConformance` as a plugin on that Store.

### API

```kotlin
// separate module koma-statechart-strict: depends on koma-statechart and koma-strict-diagram
@ExperimentalKomaApi
fun StoreDiagramModel.toStateChart(
    matchers: Map<String, ActionMatcher>,   // actionRef -> ActionMatcher.of<A>("Name")
): StrictChart

@ExperimentalKomaApi
class StrictChart(
    val definition: StateChartDefinition,
    val skipped: List<String>,               // what was not carried over and why
) {
    fun stateIdOf(id: me.tbsten.koma.strict.diagram.model.StateId): StateId
}
```

Usage in a test:

```kotlin
val chart = LceStateDiagramModel.toStateChart(mapOf(
    "LceAction.Reload" to ActionMatcher.of<LceAction.Reload>("Reload"),
    "LceAction.Retry" to ActionMatcher.of<LceAction.Retry>("Retry"),
))
val conformance = StateChartConformance<LceState, LceAction, LceEvent>(chart.definition) {
    chart.stateIdOf(it.diagramStateId())
}
```

### Mapping

| koma-strict IR | `StateChartDefinition` |
|---|---|
| `RootState` | implicit root (no node) |
| `GroupState` | `CompoundState(id, initial = first leaf child in declaration order)`. The initial affects nothing: koma-strict transition targets are always leaves |
| `LeafState` | `AtomicState` |
| `StateId(segments)` | `StateId(segments.joinToString("."))`, i.e. `dotted` — a string, R8 does not touch it |
| `initial` (a list) | the first element is `initial`; the rest go only into `skipped` (`StateChartDefinition` has a single initial state). Conformance accepts a start from any declared leaf anyway |
| `ActionTrigger` on a leaf or group | one transition per target, `on = matchers[actionRef]`. A transition from a group is a transition from a compound: in koma-strict a shared action is expanded into the leaves, and the same action on an ancestor and a descendant is forbidden, so the "inner before outer" priority changes nothing |
| several targets (`nextState = [X, Y]`) | several transitions with synthetic guards `"Source->X"`, `"Source->Y"` — otherwise `validate()` reports `AmbiguousTransitions`. Guards are labels only: paths ignore them, and so does conformance |
| `stay = true` | no transition. In Koma `Stay` does not change the state, while a self-loop in a statechart is external (exit/enter); these are different semantics; recorded in `skipped` |
| `EnterTrigger` | a transition with the synthetic matcher `ActionMatcher("enter:<Leaf>", type = StrictEnter::class)`, where `StrictEnter` is an internal marker `Action` that nobody dispatches. Conformance counts such transitions as "automatic" (a change without an unused action — the rule from the `StateChartConformance` KDoc). When replaying paths, a step with such a matcher means "let `enter` run", not a dispatch |
| `RecoverTrigger` | the same with the `StrictRecover` marker and the name `recover:<Exception>` |
| `ExitInfo` | not carried over (no transitions; events are not described in the statechart model) |
| `unresolvedTargets`, `degraded`, `unresolved` | the model is not built: `IllegalArgumentException` with the reason |
| `reachableLeafIds` | checked against `reachableStates()` in the bridge test |

Why `matchers` is required: the IR stores the action only as strings (`actionName`, `actionRef`).
`ActionMatcher(name)` without a type compares `simpleName` at run time, and R8 will rename it;
so the bridge does not create matchers itself but requires `ActionMatcher.of<A>(name)` from the caller and fails
on a missing key. **[inference]** If koma-strict ever starts generating
`KClass` references in the model (KSP has them), the map could be generated.

### What the bridge will not give

- koma-strict has no parallel regions, history or timers — the bridge will not produce them either; these are
  tools for flat and grouped machines.
- Running `StateChartStore` on such a model is pointless: the Store's behavior is described by koma-strict
  handlers, not by the chart's guards and effects.
- Guards are synthetic, so paths are only "structural": which action leads to the needed
  branch is decided by the test (as in `MessengerChartTest`).

### Decision: design only

The bridge requires a dependency on `me.tbsten.koma.strict:koma-strict-diagram`, which the module does not have, and
by the rules of the wave no new dependencies are added. So no code was written. If Roman wants
it, the place is a **separate module** `koma-statechart-strict` (koma-statechart stays free of external
dependencies), about 150 lines plus tests on `SampleModels`-like fixtures, and the
`koma-strict-diagram` version in the version catalog. Risks: the koma-strict IR is marked experimental and may
change before 1.0; the generated model does not yet contain `flows`, `source` or `enter` on groups.

## What each could take from the other (ideas, not plans)

- **[inference]** From koma-strict for us: live highlighting of the current state on the diagram (we have
  `ChartState.activeLeaves`, only a renderer is needed) and a diagram snapshot/drift check in CI (we
  already have one in the form of a test comparing `toMermaid()` with the text in KDoc).
- **[inference]** For koma-strict from us: a `toMermaid()`-like export, path generation and the
  conformance plugin. This is a possible reason for an issue in koma-strict if the bridge turns out useful;
  nothing has been sent.

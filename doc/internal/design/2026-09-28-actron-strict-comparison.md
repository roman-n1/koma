# actron-strict and actron-statechart: comparison and bridge

- Updated: 2026-09-28
- Subject: [TBSten/actron-strict](https://github.com/TBSten/actron-strict) — a KSP plugin on top of
  Actron (MIT, experimental), and our `actron-statechart` module (fork `roman-n1/actron`).
- Roman's question: do they overlap, can they be used in the same messenger, and is a bridge
  between them needed.

## Source and what was checked

Read-only investigation. Material: a shallow clone of the actron-strict repository (commit
`6a1d201`, 2026-08-05) made by the coordinator outside the `actron` repository: README, `doc/internal/`,
the sources of `actron-strict-runtime`, `actron-strict-diagram`, `actron-strict-ksp/shared`. Nothing was
copied into our repository. I did not open the actron-strict build or the Maven Central site.

Labels in the text:

- **[verified]** — read in the sources or README of the clone;
- **[inference]** — my conclusion from what was read, not verified by running anything.

## What actron-strict is

- **[verified]** A KSP plugin: the user writes a sealed hierarchy of states with the annotations
  `@StoreSpec(initial = [...])`, `@OnEnter(nextState, emit)`, `@OnExit(emit)`,
  `@OnAction<A>(nextState, emit)`, `@OnRecover<E>(nextState, emit)`, `Stay`; the plugin generates a
  type-safe DSL (`createLceStore(...)`, `states(...)`, `<State>.actions(...)`,
  `nextState.toXxx()`, `emitXxx(...)`). The result is an ordinary Actron `Store<S, A, E>`.
- **[verified]** The "strict" idea: an undeclared transition or event cannot be written (the
  `toXxx`/`emitXxx` functions exist only for declared ones), and handling of a declared state and
  action cannot be forgotten (required named parameters). All of it is compile errors.
- **[verified]** Hierarchy is sealed groups: `@OnAction` on an intermediate sealed type or on the root
  is a "shared action", which the generator expands into the `state<Leaf> {}` block of every leaf
  (Actron's hierarchical dispatch is not used). The same action on an ancestor and a descendant is a
  KSP error. Transition targets are concrete leaves only.
- **[verified]** `nextState = [Stay::class, X::class]` — the handler may stay or go to
  `X`; which one is decided by the handler code at run time. Guards do not exist as an entity.
- **[verified]** Modules and coordinates: group `me.tbsten.actron.strict`, version `0.3.0` in
  `libs.versions.toml`; `actron-strict-runtime` (`api` on `io.github.koma-kt:koma-core`, `4.0.0-rc03`
  in the clone), `actron-strict-ksp`, `actron-strict-diagram` (pure KMP, no KSP/Compose, targets
  android/jvm/js/wasmJs/iosArm64/iosSimulatorArm64), `actron-strict-diagram-compose`, an IDE plugin.
  Publishing via `publishToMavenCentral()`; the README claims the modules are published and
  shows a Maven Central badge. I did not open the artifact on Central itself.
- **[verified]** Diagrams: the KSP option `actron.strict.generateDiagramModel` (default `false`)
  generates `<Root>DiagramModel: StoreDiagramModel` and `<Root>.diagramStateId(): StateId`.
  They are rendered by the Compose panel `StoreDiagramPanel`. Export to Mermaid/PlantUML is described in the README and
  `doc/internal/generate-state-diagrams.md` as "design only, not implemented".
- **[verified]** `@FlowSpec` (named paths for highlighting on the diagram) is so far only a
  declaration: the processor does not read it, and `flows` in the generated model is always empty.

### Diagram IR (`actron-strict-diagram`, package `me.tbsten.actron.strict.diagram.model`)

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

| Capability | actron-strict | actron-statechart |
|---|---|---|
| Model source | annotations on sealed types, KSP | `StateChartDefinition` data, written by hand |
| Type-safe DSL for transitions and events | yes, compile-time | no: guard/effect labels are strings, checked when the Store is built |
| Handler completeness check | yes, compile-time | no (not needed: the runtime executes the model itself) |
| Hierarchy | sealed groups, shared actions expanded into the leaves; exit/enter as in Actron (by leaf class) | compound states, LCA, exit from the inside out, enter from the outside in |
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

In short: **[inference]** actron-strict makes an ordinary Actron machine strict at compile time and
displays it; actron-statechart adds semantics that Actron does not have (regions, history, timers,
LCA) and model-based testing tools. They overlap only in "the transition model as data
+ a picture".

## Can both be used in one project

**[inference]** Yes, but not on the same Store:

- `StateChartStore` keeps its state in a single class `ChartState<C>`, while actron-strict requires a
  sealed root with `@StoreSpec`. One Store can be only one of the two.
- A sensible split for a messenger: simple screens (LCE, forms) — actron-strict; complex machines
  with regions, history and timers (connection + chat) — actron-statechart.
- **Dependencies.** Both pull in `actron-core`: actron-strict — the official `io.github.koma-kt:koma-core`
  (`4.0.0-rc03` in the clone), the fork — its own `actron-core`. The fork now publishes (and, in a
  composite build, substitutes) every module as `io.github.roman-n1:*:5.0.0-alpha.1`, so actron-strict's
  transitive `io.github.koma-kt:koma-core` is **not** substituted: two `actron-core`s with identical
  classes end up on the classpath unless the messenger adds a `dependencySubstitution` rule (or a
  capability) mapping `io.github.koma-kt:koma-core` to the fork's. I did not check API compatibility
  `4.0.0-rc03` → `4.0.0`.
- Names: both modules declare `StateId` (`me.tbsten.actron.strict.diagram.model.StateId` and
  `actron.statechart.StateId`); in a file that needs both, use `import ... as`.

## Bridge: actron-strict IR → `StateChartDefinition`

Goal **[inference]**: for a Store written with actron-strict, get the actron-statechart tools without
rewriting: `validate()`, `toMermaid()` (which actron-strict does not have yet), paths for model-based
tests and `StateChartConformance` as a plugin on that Store.

### API

```kotlin
// separate module actron-statechart-strict: depends on actron-statechart and actron-strict-diagram
@ExperimentalActronApi
fun StoreDiagramModel.toStateChart(
    matchers: Map<String, ActionMatcher>,   // actionRef -> ActionMatcher.of<A>("Name")
): StrictChart

@ExperimentalActronApi
class StrictChart(
    val definition: StateChartDefinition,
    val skipped: List<String>,               // what was not carried over and why
) {
    fun stateIdOf(id: me.tbsten.actron.strict.diagram.model.StateId): StateId
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

| actron-strict IR | `StateChartDefinition` |
|---|---|
| `RootState` | implicit root (no node) |
| `GroupState` | `CompoundState(id, initial = first leaf child in declaration order)`. The initial affects nothing: actron-strict transition targets are always leaves |
| `LeafState` | `AtomicState` |
| `StateId(segments)` | `StateId(segments.joinToString("."))`, i.e. `dotted` — a string, R8 does not touch it |
| `initial` (a list) | the first element is `initial`; the rest go only into `skipped` (`StateChartDefinition` has a single initial state). Conformance accepts a start from any declared leaf anyway |
| `ActionTrigger` on a leaf or group | one transition per target, `on = matchers[actionRef]`. A transition from a group is a transition from a compound: in actron-strict a shared action is expanded into the leaves, and the same action on an ancestor and a descendant is forbidden, so the "inner before outer" priority changes nothing |
| several targets (`nextState = [X, Y]`) | several transitions with synthetic guards `"Source->X"`, `"Source->Y"` — otherwise `validate()` reports `AmbiguousTransitions`. Guards are labels only: paths ignore them, and so does conformance |
| `stay = true` | no transition. In Actron `Stay` does not change the state, while a self-loop in a statechart is external (exit/enter); these are different semantics; recorded in `skipped` |
| `EnterTrigger` | a transition with the synthetic matcher `ActionMatcher("enter:<Leaf>", type = StrictEnter::class)`, where `StrictEnter` is an internal marker `Action` that nobody dispatches. Conformance counts such transitions as "automatic" (a change without an unused action — the rule from the `StateChartConformance` KDoc). When replaying paths, a step with such a matcher means "let `enter` run", not a dispatch |
| `RecoverTrigger` | the same with the `StrictRecover` marker and the name `recover:<Exception>` |
| `ExitInfo` | not carried over (no transitions; events are not described in the statechart model) |
| `unresolvedTargets`, `degraded`, `unresolved` | the model is not built: `IllegalArgumentException` with the reason |
| `reachableLeafIds` | checked against `reachableStates()` in the bridge test |

Why `matchers` is required: the IR stores the action only as strings (`actionName`, `actionRef`).
`ActionMatcher(name)` without a type compares `simpleName` at run time, and R8 will rename it;
so the bridge does not create matchers itself but requires `ActionMatcher.of<A>(name)` from the caller and fails
on a missing key. **[inference]** If actron-strict ever starts generating
`KClass` references in the model (KSP has them), the map could be generated.

### What the bridge will not give

- actron-strict has no parallel regions, history or timers — the bridge will not produce them either; these are
  tools for flat and grouped machines.
- Running `StateChartStore` on such a model is pointless: the Store's behavior is described by actron-strict
  handlers, not by the chart's guards and effects.
- Guards are synthetic, so paths are only "structural": which action leads to the needed
  branch is decided by the test (as in `MessengerChartTest`).

### Decision: design only

The bridge requires a dependency on `me.tbsten.actron.strict:actron-strict-diagram`, which the module does not have, and
by the rules of the wave no new dependencies are added. So no code was written. If Roman wants
it, the place is a **separate module** `actron-statechart-strict` (actron-statechart stays free of external
dependencies), about 150 lines plus tests on `SampleModels`-like fixtures, and the
`actron-strict-diagram` version in the version catalog. Risks: the actron-strict IR is marked experimental and may
change before 1.0; the generated model does not yet contain `flows`, `source` or `enter` on groups.

## What each could take from the other (ideas, not plans)

- **[inference]** From actron-strict for us: live highlighting of the current state on the diagram (we have
  `ChartState.activeLeaves`, only a renderer is needed) and a diagram snapshot/drift check in CI (we
  already have one in the form of a test comparing `toMermaid()` with the text in KDoc).
- **[inference]** For actron-strict from us: a `toMermaid()`-like export, path generation and the
  conformance plugin. This is a possible reason for an issue in actron-strict if the bridge turns out useful;
  nothing has been sent.

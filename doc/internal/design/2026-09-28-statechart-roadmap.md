# Statechart layer on top of Koma: roadmap

- Updated: 2026-09-28 (phase status after wave 6; fork divergence table; remaining work)

## Background

Koma 4.0 already works as a flat state machine over sealed classes. It has
`state<S2> { enter / action<A2> / exit / recover<T> }`, first-match in registration
order, effects with state lifetime (`launch`, `LaunchControl`),
business errors as transitions (`recover {}`) and `Plugin` for observation.

What is missing for Harel statecharts:

- hierarchy: a phase change is determined only by `state::class != nextState::class`
  (`StoreImpl.onActionDispatched` / `onStateChanged`), so a transition between
  child variants of one sealed parent does exit/enter "wholesale", without LCA;
- parallel (orthogonal) regions and history;
- a guard as a separate entity (the condition lives in the handler body);
- **a transition as data**. The handler registry in `StoreBuilder` is private
  lists of "predicate lambda + handler lambda" pairs. The types `S2` / `A2` exist
  only inside the reified predicate `it is S2` and never get out.

Because of the last point, introspection, validation,
visualization, transition coverage and model-based testing are impossible today.

## Policy

### Do not rewrite Koma

- The statechart is an optional layer in a separate module. The existing `Store` API and
  semantics do not change.
- `Koma State != statechart node`. The layer uses composition: `Store`
  remains the source of `StateFlow` for the UI, and the statechart is responsible for the structure
  of transitions.
- The statechart is applied only where there is `State + Event -> Transition`.
  Ordinary data (`toolbarTitle`, `scrollPosition`, input field values) is not
  turned into nodes.

### DSL != Runtime

```text
Statechart DSL
      |
      v
StateChartDefinition (immutable data)
      |
      +--> Validator
      +--> Exporter (Mermaid / DOT)
      +--> Test path generator
      +--> Runtime --> Koma Store adapter
```

The DSL builds an immutable model; the runtime executes it. Everything the tools
need (validation, diagrams, coverage) is taken from the model without runtime
reflection.

### Semantics before API

The hardest part is deterministic semantics: the exit/enter order by LCA,
transition priority, guard order, simultaneous transitions in regions, coroutine
lifetime, exceptions, persistence. Each phase begins with
semantics-first tests; the public API is polished last. Do not aim straight for
`StateChartDefinition<S, A, E, ...>` with many generic parameters.

Expected orders:

```text
Connected / ChatOpened -> Disconnected
  exit ChatOpened, exit Connected, enter Disconnected

Connected / Idle -> Connected / ChatOpened
  exit Idle, enter ChatOpened
```

## Phases

### Status (after wave 6)

All phases 0–7 are done in the fork; phase 8 partially. Numbers are PRs in `roman-n1/koma`.

| Phase | What | PR | Status |
|---|---|---|---|
| 0 | Architectural contract (this document) | #1, #6 (fork path) | merged |
| 1–2 | Model, validation, Mermaid | #5 | merged |
| 3 | Flat runtime | #7 | merged |
| 8 (part) | Conformance plugin; shortest paths and coverage paths | #8, #9 | merged |
| 5 | Hierarchy (compound, LCA) — wave 1 | #10 | merged |
| 7 | History (shallow/deep) — wave 2 | #11 | merged |
| 6 | Parallel regions — wave 3 | #12 | merged |
| — | Timers `Trigger.After` — wave 4 (there was no phase for it in the original plan) | #13 | merged |
| 4 | `StateChartStore` adapter — wave 5 | #14 | merged |
| — | "Messenger" example as a test, `koma-statechart/README.md`, comparison with koma-strict — wave 6 | branch `feature/statechart-finish` | PR not opened |

Core steps 1–3 from the upstream strategy are #2, #3, #4 (merged into the fork). The semantics and decisions of each
wave are in [`2026-09-28-statechart-semantics.md`](./2026-09-28-statechart-semantics.md), sections
"Wave 1–5 decisions". Phase 4 was done last rather than fourth: the adapter is easier to write once the
semantics of hierarchy, regions, history and timers are already fixed.

Not done from phase 8: invariant checking, shrinking of a failing sequence, debug
timeline (see "Remaining work").

### Phase 0 — architectural contract

This document. No code.

### Phase 1 — introspectable model

A new KMP module `koma-statechart` (modeled on `koma-test`: android, iosArm64,
iosSimulatorArm64, jvm). In the first iteration, one module with the packages `model`,
`validation`, `tooling`, `runtime`, `dsl` instead of six separate artifacts.

Minimal model: `StateId` (value class), `StateNode` / `AtomicState`,
`Transition(source, target, on: ActionMatcher)`, `StateChartDefinition`.
`ActionMatcher` carries a `KClass<out Action>` and a stable name, because
`qualifiedName` is not available on all KMP targets. The dependency on `koma-core`
is only for the marker interfaces. Unit tests.

### Phase 2 — validation and Mermaid

Duplicate IDs, transitions to nonexistent states, unreachable states,
missing initial, conflicting transitions without guards. Export to Mermaid
`stateDiagram-v2`.

### Phase 3 — flat runtime

A pure function `step(configuration, action) -> TransitionResult` with metadata
of the transition that fired. No coroutines and no Koma.

### Phase 4 — integration with Koma

An adapter that assembles a `Store` from the definition. The preferred variant, without
core changes: a single catch-all `state<S> { action<A> { runtime.step(...) } }`.
The Koma state keeps the active configuration as a field. The transition trace for
coverage is captured through `Plugin` and `koma-test` (`dispatchAndAwait`,
`StoreRecorder`).

### Phase 5 — hierarchy

Compound states, initial transitions, the LCA exit/enter algorithm. If the layer keeps
the Koma state as a single class, Koma itself does no exit/enter, and then the
coroutine lifetime of substates is managed by the layer.

If this runs into limitations, the minimal hook in the core is a "phase key"
strategy `(S) -> Any` (default `it::class`) instead of `state::class` in
`StoreImpl`, marked `@ExperimentalKomaApi`. It touches
`stateRuntimes`, `PendingActionPolicy` and `LaunchControl`, so only through a
separate ADR.

### Phase 6 — parallel regions

`ParallelState` / `Region`, the configuration as a set of active leaves,
a deterministic order of region processing.

### Phase 7 — history

Shallow / deep history.

### Phase 8 — tooling

Path generation for model-based testing, transition coverage, invariant
checking, shrinking of a failing sequence, debug timeline.

## Definition of Done for the first milestone (Phase 1–2)

- the existing Koma API is unchanged;
- the model is immutable, transitions are first-class objects, state IDs are stable;
- the model can be traversed in full;
- a flat graph can be described and its unreachable states found;
- the Mermaid export is built without runtime reflection;
- there are unit tests.

## Upstream strategy: small steps

The goal is for the Koma author (`koma-kt/koma`) to accept changes one at a time. For
that, each step:

- is useful to Koma on its own, even if the statechart layer never appears;
- builds on what the author has already written down in `doc/internal/`;
- is additive and does not change the behavior of existing Stores;
- starts as a feature request and only after agreement turns into a
  small PR.

Order and placement:

| # | Step | Where | Benefit for Koma on its own | What it gives the statechart layer |
|---|---|---|---|---|
| 1 | Keep matcher metadata (`StateType(S2::class)` / `AnyState`, `ActionType(A2::class)` / `AnyAction`) next to the predicates in the `StoreBuilder` registry. The public API does not change. | core, internal | The basis for the routing diagnostics the author proposes in `notes/2026-04-25-unhandled-action-behavior.md` (the section on `build()` checks names exactly this as the obstacle) | The first data about the structure: which state and action types are declared |
| 2 | Routing diagnostics in `:koma-test`: `diagnoseActionMatches`, asserting the number of matches on dispatch | `koma-test` | Exactly items 2–3 from the same note by the author | Checking that a transition in the model and a handler in the Store agree |
| 3 | A read-only description of all handlers (`describeHandlers()`); in the core only an `@InternalKomaApi` method, the public API in `koma-test`, because the author prefers not to widen the public surface of `koma-core` | `koma-test` + core internal | Store documentation, handler coverage in tests | Coverage checking and a first visualization without our own DSL |
| 4 | The reason for a state change, for observers (action / enter / launch transaction / recover) | core, contentious | Debug timeline and trace (upstream #189), `receiveTransition` in the test driver (upstream #176) | Transition metadata for coverage and MBT |
| 5 | The `koma-statechart` module: model, validation, Mermaid, path generation | separate module (in the fork) | — | Phases 1–2 and part of Phase 8 |
| 6 | Runtime and adapter to Store through the ordinary DSL | separate module | — | Phases 3–4 |
| 7 | Opt-in hierarchical state scopes: `enter` / `exit` / `launch` of a sealed parent survive transitions between its child variants (LCA over the sealed hierarchy) | core, RFC + ADR | Today `state<Parent> { enter {} }` restarts on every change of child variant, which is inconvenient even without statecharts. Shaped as a policy enum, following `doc/internal/adr/2026-05-07-runtime-policy-enum.md` | Phase 5 without our own coroutine management |
| 8 | Parallel regions, history | separate module | — | Phases 6–7, not proposed for the core |
| 9 | RFC "optional introspectable statechart model for Koma": the module as a companion or an external artifact | upstream issue | — | Official status for the layer |

Notes on risks:

- Step 4 contradicts the author's current position: in
  `notes/2026-05-02-plugin-design.md` a Plugin observes only the Store's boundaries
  (input and output), and there is no separate hook for a type change. So step 4 comes
  after 1–3 and is proposed as data, not as a new hook. If the author
  declines, the layer correlates `onAction` → `onState` itself (under one mutex) or
  provides the metadata from its own runtime.
- Step 7 changes scope semantics, so only opt-in and only after a
  separate ADR.
- Steps 5, 6 and 8 require nothing from upstream and proceed in the fork in parallel
  with 1–4.
- The upstream issue numbers (#175, #176, #189) are taken from the handoff and the author's
  notes and were not verified in this session.

### Plan B: if the author does not accept our path

Roman's decision (2026-09-28): for now only the request for step 1 is sent
upstream; the next ones are designed in the fork several steps ahead.

Therefore the `koma-statechart` module must not depend on the core changes from steps
1–4. It relies only on the public API of `koma-core` (marker interfaces,
`Store`, DSL, `Plugin`) and must work with unmodified Koma 4.0.0. Steps 1–3
in the fork are useful on their own (diagnostics in tests), but the statechart layer does not
require them. If some step requires a core change (for example step 7), the fork
first does a variant inside the module, and the core change remains a separate
proposal.

Draft of the first feature request (step 1):
[`notes/2026-09-28-upstream-fr-handler-matcher-metadata.md`](../notes/2026-09-28-upstream-fr-handler-matcher-metadata.md).

## Using the fork in the messenger and the way back to upstream

Roman's goal: connect the fork `roman-n1/koma` to his KMP messenger now,
and return to official Koma as the author accepts the requests. The rules
follow from this.

### Rules for the fork

- **Minimal divergence in the core.** `koma-core` changes in the fork are
  small, additive, one step per PR, each in its own commits. They
  can be discarded when moving to an upstream version where the same change already
  exists.
- **The statechart layer only in its own module.** `koma-statechart` depends only
  on the public API of `koma-core` (see plan B) and must build on top of
  official Koma without the core fork.
- **Tracking divergence.** The table below is the only list of core changes in the
  fork. Each row is removed when upstream releases the same thing or
  when we abandon the change.

| Step | What it changes in the core | Fork PR | Status in upstream |
|---|---|---|---|
| 1 | Matcher metadata in the `StoreBuilder` registry (internal) | #2 | prepared for upstream: branch `upstream-pr/handler-matcher-metadata` (one commit on top of upstream 4.0.0) |
| 2 | `StoreInternalApi.matchActionHandlers` (`@InternalKomaApi`) + `diagnoseActionMatches` in `koma-test` | #3 | fork only; depends on step 1 |
| 3 | `StoreInternalApi.handlerMetadata` (`@InternalKomaApi`) + `describeHandlers` in `koma-test` | #4 | fork only; depends on step 1 |

Divergence by group:

- **Fork only, core.** Steps 1–3 above: internal changes to `koma-core` and new functions in
  `koma-test`. Nothing else in `koma-core` was changed in the fork (except the Mocha timeout in tests, #11).
- **Depends on the step 1 request.** Steps 2 and 3 read the matcher metadata of step 1. If upstream
  accepts step 1, row 1 goes away on synchronization, and steps 2–3 can be proposed next; if it
  declines, steps 2–3 stay in the fork or are discarded; the statechart layer does not depend on them.
- **Public API only.** All of `koma-statechart` (#5, #7–#14 and wave 6): depends on the
  public API of `koma-core` 4.0.0 (`Store`, DSL, `Plugin`, `StateSaver`) and on `koma-test` only in
  tests. It builds on top of official Koma without the core fork (plan B) and does not go into this
  table.

### Synchronization with upstream

- The fork's `main` = upstream + our merged PRs.
- On every upstream release: `git fetch upstream --tags`, then merge the release tag
  into the fork's `main` **with a merge commit** (no rebase, so as not to break
  branches and clones). Conflicts in the core are resolved in favor of the upstream version if it
  covers our step; the row in the table is removed.
- After the merge: the fork's CI is green on all platforms, then a new fork version.

### How the messenger connects the fork

1. **During active development — composite build.** The fork is added as a
   git submodule, and `includeBuild("koma")` is written into the messenger's `settings.gradle.kts`.
   Gradle substitutes the fork's modules for the
   `io.github.koma-kt:*` dependencies by itself; nothing needs publishing, and edits to
   Koma are visible immediately. Downside: the messenger's CI has to fetch the submodule.
2. **Once the version has stabilized — GitHub Packages.** Publishing from the fork's GitHub
   Actions on a macOS runner (otherwise the iOS artifacts cannot be built), to the fork's own
   Maven repository. Reading requires a token with `read:packages`.
   - Coordinates: a separate group `io.github.roman-n1`, so that the fork's artifacts
     are never confused with the official `io.github.koma-kt`.
   - Version: the base upstream version plus a fork suffix, for example
     `4.0.0-sc.1`, `4.0.0-sc.2`; after synchronizing with 4.1.0 — `4.1.0-sc.1`.
   - The official `koma-core` and the fork's must not be mixed in one project:
     the classes are the same, the groups differ. Koma's group and version are set in the
     messenger's version catalog in one place.
   - The current `.github/workflows/publish.yml` publishes to Maven Central on
     prerelease and will fail in the fork without the secrets. The fork needs a separate
     publishing workflow; to be done when we get to item 2.
3. **Moving to official Koma.** When upstream has released all the core steps
   that the messenger needs: in the messenger's version catalog the group of
   `koma-core`/`koma-compose`/`koma-test` changes to `io.github.koma-kt`, and the
   version to the official one. `koma-statechart` remains our artifact (or
   moves to upstream through the RFC, step 9) and works on top of the official
   `koma-core`. If upstream did not accept some core step, the messenger
   stays on the fork only for that step, or the layer does without it (plan B).

## Remaining work

1. **Publishing.** There are no artifacts now; the messenger connects the fork with a composite build
   (`includeBuild`). Needed: a separate publishing workflow for the fork (GitHub Packages, a macOS runner for
   iOS), the group `io.github.roman-n1` for **all** fork modules (currently only
   `koma-statechart` has it, while the fork's `koma-core` and `koma-test` are under `io.github.koma-kt`, see the
   divergence table), a version of the form `4.0.0-sc.1`. Until then `koma-statechart/README.md` describes only
   the composite build.
2. **Known limitations of wave 5** (decisions in the semantics document, "Wave 5 decisions"):
   - after restore from `StateSaver`, enter hooks are not run again, and timers start with the full
     delay (the remaining time is not saved);
   - events emitted by the hooks of a failed step are not retracted; a timer whose step failed is listed as
     running but will not fire until the node is entered again;
   - conformance of the restored configuration to the definition is not checked;
   - `enter {}`/`action {}` for `ChartState` from `store {}` do not work, `initialState` there is
     ignored, `PendingActionPolicy.ClearOnStateExit` does not affect chart steps;
   - conformance does not see guards or clocks: self-loop timers are not counted (wave 4), and of two
     transitions with the same leaves before and after (for example, two timers `Backoff → Connecting`
     with different guards in the messenger example) the first is counted.
3. **Model.** No eventless (completion) transitions, final states, internal
   transitions or `invoke`. The first candidate is completion transitions: with them "the work
   is finished" is described without an artificial action.
4. **Phase 8.** Invariant checking on paths, shrinking of a failing action sequence,
   debug timeline (the data is already there: `StepResult`, `ChartState`).
5. **Bridge to koma-strict** — optional, as a separate module; design in
   [`2026-09-28-koma-strict-comparison.md`](./2026-09-28-koma-strict-comparison.md).
6. **Upstream.** The author's answer on step 1; then a decision on steps 2–3 and the RFC (step 9).

## Notes

- The handler registry, first-match and the commit order (exit → `_state` + `StateSaver`
  → `Plugin.onState` → clear pending → enter) were verified against the 4.0.0 sources.
- `Plugin.onAction` is called before the handler, `Plugin.onState(prev, next)` after the
  commit. The Plugin does not know which action caused the transition; the layer provides that
  metadata itself.

## Related

- [Statechart layer semantics](./2026-09-28-statechart-semantics.md)
- [Comparison with koma-strict](./2026-09-28-koma-strict-comparison.md)
- [Module README](../../../koma-statechart/README.md)

- [Koma design principles](./2026-04-23-design-principles.md)
- [Store surface design notes](./2026-04-29-store-api-design.md)
- [LaunchControl API design](../adr/2026-05-01-launch-control-case-naming.md)

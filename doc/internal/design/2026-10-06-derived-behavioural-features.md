# Derived behavioural features: first seven, version diff and transition matrix

Scope selected from `koma-state-machine-derived-features.md`: items 1–7, followed by 9 and 10.
These APIs share the actual Machine selector and existing pure decision/invariant semantics.

| Item | Public entry point | Contract |
| --- | --- | --- |
| 1 — Transition coverage | MachineTestDriver.coverage; MachineCoverageRecorder; merge; describe; assertTransitionsCovered | Real decisions and actual true/false guard evaluations; no guard re-evaluation. Final and transient nodes of successful macrosteps count; rolled-back transitions do not. Identity/version and declared universes must match for merging |
| 2 — Executable test cases | Machine.generateTestPlan; runScenario; runPlan; driver.generateTestPlan/verifyPlan | Typed payloads, command answers and virtual timers; each selected scenario starts independently from the same initial data. Exact bounded set-cover minimizes scenario count within the discovered prefix pool |
| 3 — Explain rejection | Machine.explainWhyRejected(explained); optional guard rejectionReason | Reuses the observed decision and guard results. Guard metadata is application-authored; opaque predicates do not yield invented human reasons |
| 4 — Available actions | Machine.declaredActions; availableActions(snapshot, actualPayloads) | Declaration-only enumeration plus selection-only eligibility. Includes active inherited transitions and handler fallback; preserves matcher types and payload-dependent guards |
| 5 — Invariants | invariant; checkInvariants; enforceInvariants | Existing predicates reused by runtime, driver, exploration, scenarios and replay. Stable-snapshot checks and whole-decision rollback remain unchanged |
| 6 — Generative testing | explore: BreadthFirst, RandomWalk, AllTransitions; withTimers | No context/history collapsing. Seeded walks are reproducible. AllTransitions stops immediately at its transition-coverage goal; finite payload choices and explicit budgets bound discovery |
| 7 — Shrinking | shrink with default identity matching or preservesFailure | Chunk/single deletion with actual values/ids/times retained. Custom semantic matching distinguishes bugs of the same exception type without retaining exception messages |
| 9 — Behavioural version diff | chart.diffTo; machine.behaviouralDiffTo | Shows guard/effect/internal changes and version obligations. Added, changed, removed or reindexed timer definitions require snapshot migration review. Executable rules are opaque |
| 10 — Transition matrix | chart.transitionMatrix: cell, toMarkdown, toTransitionTableMarkdown | Declared alternatives and zero-based transition indices, in declaration order; action/timer/eventless/completion triggers; guarded branches retained; names escaped for Markdown |

## Availability and explanations

```kotlin
// Machine builder: a static, application-authored diagnostic string, not inferred from code.
guard("online", "Connection.Online is required") { snapshot, _ ->
    snapshot.isActive(online)
}

val declarations = machine.declaredActions(snapshot) // evaluates no guards
val availability = machine.availableActions(snapshot, listOf(Act.Send(text), Act.Close))
val eligiblePayloads = availability.executable.map { it.action }
val blockedReasons = availability.blocked.map { it.rejection }

val observed = machine.decideExplained(snapshot, input)
val rejection = machine.explainWhyRejected(observed) // no second decision or guard call
```

Availability means a transition or handler is eligible for this snapshot and concrete payload.
The query runs selector guards, not reducers, enter/exit rules, handlers, automatic transitions,
invariants or command handlers. It cannot promise that the complete macrostep or later IO will
succeed. Dispatch rechecks the current snapshot. Matching a handler after rejected transition
guards counts as executable; unvisited outer guards are never invoked for a query.

The snapshot/action/time overload of explainWhyRejected simulates one **complete pure decision**.
It requires an explicit logical time and does run pure reducers/hooks, while never executing their
intents. Use the observed-decision overload when inspecting a decision already taken. Typed
DecisionTraceObserver receives that one real explained decision, including ignored/failed inputs;
it must not call the Store back. Built-in coverage retains only ids/outcomes, not payloads.

## Coverage and executable plans

```kotlin
val driver = MachineTestDriver(machine, Context(), this)
try {
    driver.start()
    driver.send(Act.Load)
    // Answer scripted commands and assert domain results as in ordinary driver tests.
    println(driver.coverage.describe(machine.chart))
    driver.coverage.assertTransitionsCovered(setOf(TransitionId(0)))
} finally {
    driver.close()
}

val generator = MachineInputGenerator<Context, Act> { snapshot, now ->
    // Supply finite, deterministic typed action payloads and command answers for this snapshot.
    generatedChoices(snapshot, now)
}.withTimers()
val initial = machine.initialSnapshot(Context())
val plan = machine.generateTestPlan(initial, generator, maxDepth = 10, maxDecisions = 10_000)
plan.assertReady(requireOptimal = true)
machine.runPlan(initial, plan).assertSuccess()
```

Discovery retains a shortest encountered prefix for each distinct transition-coverage set.
It does not merge exploration paths with different contexts/history. Breadth-first discovery
can find a longer single scenario combining several branches; AllTransitions can finish earlier
with multiple scenarios. Exact set-cover selection is bounded separately by maxSelectionAttempts.
A complete greedy fallback preserves reached coverage if selection exhausts its budget, with
selectionOptimal=false. Minimality is within the discovered finite prefix pool, not all possible
workflow inputs. It does not claim shortest total input count.

Unreachable declarations and missing payload choices remain uncovered. assertReady rejects
discovery failures, missing required transitions and exhausted discovery budgets; requireOptimal
also rejects an unfinished selection proof. runPlan recomputes actual coverage for selected
scenarios, independently from their candidate coverage, checks every stable snapshot and retains
failures. Unselected discovery failures cannot be hidden by selecting only passing scenarios.

Driver plan verification is an independent pure-model operation. It leaves the live Store and
scripted command handler untouched. To test real executor lifetime, admission or IO integration,
use the ordinary driver operations and their accumulated coverage. Coverage assertions are not
substitutes for application-specific result assertions.

## Shrinking and replay

Default matching preserves an invariant identity or decision failure type. If distinct bugs share
that type, supply a pure semantic matcher over failure context/inputs/identities. FailureDescriptor
omits exception messages by default; matching two null messages cannot distinguish two bugs.

```kotlin
val shrunk = machine.shrink(initial, failure.inputs) { original, candidate ->
    original.identities == candidate.identities &&
        original.snapshot.context.operationId == candidate.snapshot.context.operationId
}
check(!shrunk.truncated)
val regression = MachineScenario("reproduction", shrunk.failure.inputs)
val rerun = machine.runScenario(initial, regression)
// With the optional debug-only koma-timetravel module:
val recording = machine.recordInputs(initial, regression.inputs)
val replay = ReplaySession(machine, recording)
```

A completed shrink is deletion-minimal for its chosen predicate, not globally shortest.
Payloads/timer ids/command ids are preserved; deleting registration inputs may make later results
stale through normal semantics. Persisting/sharing context or action payloads uses the application's
codec and redaction policy. Pure scenario replay executes no live command handlers.
recordInputs converts a prefix to the existing Time Travel Recording, retaining ignored and failed
steps and unfinished command payloads. It decides each input once and executes no intents. A
started workflow requires an authentic ExecutorCheckpoint and nondecreasing logical times.
The debug module depends on Machine data, without introducing a dependency on test artifacts.

## Version diff and matrix

```kotlin
val diff = before.behaviouralDiffTo(after)
println(diff.describe())
check(!diff.versionBumpRequired)

val matrix = after.chart.transitionMatrix()
println(matrix.toMarkdown())
println(matrix.toTransitionTableMarkdown())
```

The version wrapper requires one logical DefinitionId. A known structural behaviour change with
an unchanged version is flagged; a changed version requires snapshot migration review even when
the graph is identical. Guard/reducer/hook/invariant code and runtime settings are not compared:
their semantic changes still require explicit versioning and review.

Matrix rows describe direct model declarations. A parent's transition appears in its own row;
it is not duplicated into every child's row. Cells list alternatives, not a single presumed winner.
Guards, priority, hierarchy and parallel conflicts determine runtime selection. Matchers sharing
a display name retain separate columns with disambiguated headers. Final/history/isolated nodes
remain visible as empty rows. Both exporters are data-only and invoke no runtime functions.

## Verification

Contract tests cover real driver guard outcomes and merge identity, selected-plan replay, bounded
selection/discovery, timer deadlines and command ids, pure driver plan verification, preserved
discovery failures, transient macrostep state coverage, custom shrinking, handler fallback,
skipped guards, false/throwing guards, version diff and safely rendered matrix alternatives.
Final platform/API and GitHub check results are recorded in the follow-up PR to #83.

Local root jvmTest, apiCheck and checkDebugGraph passed. Android host, JS Node, Wasm Node and
iOS Simulator Arm64 passed for all three changed modules: 483 statechart, 27 tooling and 82
Time Travel tests per portable target. JVM counts are 511, 27 and 86 respectively. The Native
run required clearing generated statechart build artifacts after an internal Kotlin/Native
linker error; no source/compiler-setting workaround, ignored test or longer timeout was added.
The follow-up adds 27 common regression tests and updates JVM/klib API dumps.

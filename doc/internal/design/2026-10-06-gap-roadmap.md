# Gap analysis implementation, 2026-10-06

- Updated: 2026-10-06

The six implementation waves from `actron-gap-analysis.md` are implemented in the statechart,
statechart-test and Time Travel modules. Existing ordinary Store semantics are unchanged.
The new public model/API requires updated JVM/klib dumps; users must version behavioural
Machine definitions when adopting new transition semantics or changing rules.

| Wave | Delivered | Contract / verification |
| --- | --- | --- |
| 1 — Model-based testing | Named invariants; runtime enforcement; bounded BFS; seeded random walks; virtual timers; executable coverage scenarios; shrinking; state/transition/guard/timer coverage | `ModelBasedTest`; driver checks every recorded stable snapshot; shrinking reports budget exhaustion and deletion-minimality |
| 2 — Explanation | Single-decision guard trace; rejected/skipped/conflicting candidates; handler attribution; live diagnostic observer; replay inspection; bounded command/input causality | `decideExplained`; `decisionDiagnostics`; `CausalityTracker`; replay forward no longer decides twice |
| 3 — Statechart semantics | Final states; compound/parallel completion; eventless transitions; internal self-transitions; bounded macrosteps; staged ChartStore lifetimes; macrostep conformance | `AutomaticMachineTest`; revision advances once per Machine input; transient commands/timers never execute |
| 4 — Production workflow | Atomic snapshot/outbox storage contract; stable business idempotency keys; persisted attempts/receipts; scoped ordinary vs durable work; recovery deadlines; ordinary payload restore; semantic snapshot migrations and validation | `DurableMachineTest`; storage/CAS failure prevents execution; external success followed by failed ack retries the same key |
| 5 — Composition | Pure typed invocation; owner-qualified child inputs/work; start/cancel on parent activation; FIFO event routing; bounded feedback; atomic parent/child rollback | `InvocationTest`; MachineGroup already owns production independent-store routing, cuts and failure isolation |
| 6 — Developer experience | Nested immutable definition DSL; typed guard/effect keys; conservative definition diff; interactive Compose diagram and explicit replay explanations | `DslTest`; `StateChartPanelTest`; diagram composition never executes guards or IO |

## Practical boundaries

* Model search is bounded by supplied payloads, depth and decision budget. It is not formal
  verification of arbitrary workflows. Raw graph paths remain potential microstep paths;
  executable scenarios use the actual Machine macrostep semantics.
* Runtime invariants are opt-in. Tests and replay can check the same predicates without
  changing execution. Predicates and generated payloads must be pure.
* A durable storage adapter must atomically and durably commit snapshot, outbox and receipts.
  The application owns its codec, schema migration, drain leases and logical time across
  processes. The handler's business idempotency is supplied by a transactional local receipt
  or by the remote service. The library does not promise arbitrary exactly-once external IO.
* Live event intents are not a durable event mailbox. Model crash-sensitive effects as keyed
  durable commands or as persistent desired state reconciled by the application.
* Completed receipt retry suppresses execution and command bookkeeping. It does not synthesize
  a cached action/result; model business completion explicitly in context and operation ids.
* Invocation is a pure composition/executor boundary. Its returned work must be qualified by
  owner activation and executed only after composite commit. MachineGroup remains the runtime
  coordinator for independent MachineStores; it does not claim atomic broadcast transactions.
* Definition diff is structural. It cannot verify changes inside guard/effect lambdas. Persisted
  snapshots and outbox payloads still require semantic migration review.
* Diagnostics/causality/diagram retain metadata. Production journal already defaults to omitted
  payloads; application projections and codecs redact data before retaining or exporting it.
* Full SCXML, server-driven behaviour, drag-and-drop editing, source-code generation, complex
  IDE plugins, automatic navigation replacement and an actor/distributed framework remain
  deliberately excluded, as the analysis explicitly recommends. Platform telemetry adapters,
  project-specific lint rules and navigation projections require concrete application contracts.

Semantics details: [model testing](2026-10-06-model-based-testing.md),
[automatic transitions](2026-10-06-automatic-transitions.md),
[durability](2026-10-06-durable-workflow.md), [invocation](2026-10-06-invocation.md).
Follow-up: [contract review, reproduced findings and regression coverage](2026-10-06-contract-review.md).

## Verification

The final code passed 599 JVM tests across statechart, test tooling, Time Travel and its Compose
inspector. Statechart/test tooling also passed 472 tests on each of Android host, JS Node, Wasm
Node and iOS Simulator Arm64. Root `apiCheck` and `checkDebugGraph` passed; JVM/klib API dumps
are included. `git diff --check` passed.

The iOS incremental link initially failed with a Kotlin/Native module-deserializer error; clean
module output plus `--no-build-cache -Pkotlin.incremental=false` rebuilt and passed all Native
tests. No compiler/cache workaround was added to production code.

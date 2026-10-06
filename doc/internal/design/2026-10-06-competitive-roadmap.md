# Competitive roadmap execution

Updated: 2026-10-06

## Background

The requested competitive roadmap spans all five waves. PRs #83 and #84 supplied much of
the runtime, replay, durable and verification foundation. This follow-up closes the remaining
capability gaps and ships the first IDE/ecosystem integration, without creating another runtime.

## Policy

| Wave | Capability | Implementation |
| --- | --- | --- |
| 1 | State/transition/timer/guard coverage | Existing real-decision recorder; historyTransitions, errorTransitions and describeGuards |
| 1 | Executable plans for all requested targets | CoverageTarget, CoverageRequirements, targeted generateTestPlan/runPlan; driver extensions |
| 1 | Declared/executable/blocked actions | Existing availableActions with actual typed payloads |
| 1 | whyNot / whyThisTransition | Selection query, observed decision reuse, structural validation and explicit rollback outcome |
| 2 | Invariants, discovery, shrinking, replay | Existing predicates/enforcement, bounded BFS/seeded walks and semantic shrinking; driver.explore and exploreDetailed |
| 2 | Payload domains / constraints | Typed actionGenerator and machine.inputGenerator with timers and external-response inputs |
| 2 | CI diff/coverage/diagrams | Versioned BehaviouralSnapshot, behaviouralReviewArtifacts, executable exporter and PR workflow |
| 2 | Reachability / dead ends | Bounded configuration/history search with explicit incomplete results and terminal-state distinction |
| 3 | Causal trace | New common diagnostic hub: input/root/parent, transitions, commands, timers and cross-Store effects |
| 3 | Replay/versioning/migration/durable/idempotency | Existing codecs, strict definition identities, snapshot/durable migration and atomic outbox contracts; full existing suite retained |
| 3 | Recovery contract | Programmatic recovery reports for authentic executor/durable checkpoints |
| 4 | Compact DSL / typed implementation keys / inspector | Existing nested DSL, GuardKey/EffectKey and interactive Compose inspector |
| 4 | Queries / impact / runtime graph | TransitionQuery, impactTo, active/selected model exports and IDE canvas/source navigation |
| 5 | IntelliJ integration | Installable K2 plugin: Kotlin gutter, version-scoped model references/Find Usages, source-mapped diagram navigation |
| 5 | PR / SDK integrations | Job summary/artifacts; OpenTelemetry and Sentry JVM/Android adapters; isolated Android Crashlytics adapter |

### Coverage and executable discovery

```kotlin
val generator = machine.inputGenerator(
    listOf(actionGenerator<ChatContext, SendMessage>("send") { snapshot, _ ->
        testMessages(snapshot.context).map(::SendMessage)
    }),
    extraInputs = commandAnswers,
)
val target = CoverageTarget.Custom(
    machine.chart.requirements(CoverageTarget.AllTransitions) +
        machine.chart.requirements(CoverageTarget.AllGuardOutcomes),
)
val plan = machine.generateTestPlan(initial, generator, target, maxDepth = 8)
plan.assertReady(requireOptimal = true)
machine.runPlan(initial, plan).assertSuccess()
```

Payload domains and constraints are finite, pure functions of snapshot/time. Blocked actions
remain in the default domain to cover false guards. includeBlocked=false runs an eligibility
query first; actual dispatch then rechecks guards. External responses use actual command ids.
Timer candidates use deterministic deadline/id order and never move logical time backwards.

Cover targets include active states, transitions, guard true/false branches, timer/history paths,
explicit error-state entry paths and custom target states. Error semantics are supplied by the
application; names are not classifiers. History pseudo-nodes are covered through transitions,
never counted as active states. These obligations are finite path/branch coverage, not every
possible combination of failures or histories.

Prefix coverage retains state/guard distinctions even when transition sets match. Exploration
does not merge contexts or histories. exploreDetailed returns the prefix observations without
another decision; original ScenarioCoverage constructors/copy/component ABI is preserved.
Exact bounded set-cover minimizes scenario count in the discovered pool. Missing obligations,
discovery failures and unknown optimality remain explicit. Driver discovery/plan verification
uses independent pure snapshots; a live Store and IO handlers are unaffected.

### Explanations and analysis

whyNot(snapshot, action) checks structural consistency/counters/work without evaluating
invariants, then uses the normal selection query. whyNot(explained) and whyThisTransition reuse
actual observations. An earlier automatic selection remains visible if the same transition's
guard fails on a later microstep: Selected means selected at least once during the attempt;
guard observations retain the later failure and committed=false records the rollback.

Reachability is structural potential, with opaque guards/context/handlers and automatic steps
represented as edges. It is not proof of runtime-stable reachability. Budget exhaustion makes
structurallyUnreachable unknown; unreached remains available. Terminal configurations are
separate from dead ends. Invalid hierarchy is rejected before graph enumeration. Impact is a
conservative metadata/reference set including descendants and transition identities/order.

### CI and IDE artifact contract

BehaviouralSnapshot format 1 exports model ids/kinds/hierarchy, stable trigger keys, guard/effect
labels, timers, transition order, coverage, diagrams and optional source/active/selected data.
Executable rule bodies and payload schemas are opaque. Stable actionKey identities must be
supplied when different typed matchers share names; changing dispatch types behind a stable
key still requires explicit versioning/review. Snapshot format is independent of frozen replay
recording formats, which are unchanged.

behaviouralReviewArtifacts returns behaviour.diff, coverage.diff, before.mmd, after.mmd,
matrix.md and model.koma.json. Coverage regressions and newly uncovered transitions/guard
branches are visible. Model-object property ordering does not create a behavioural change.
The sample exporter asserts actual transition and guard coverage. The workflow reads the
baseline from the PR base Git revision, publishes a summary/files, and fails known structural
changes without a version bump or lost coverage. Absence of a first baseline is explicit.

The committed sample baseline is updated intentionally from the executable exporter:

```shell
./gradlew :behaviour-review:exportBehaviour
cp verification/behaviour-review/build/behaviour-review/model.koma.json .koma/baselines/messenger-send.koma.json
```

Applications register their own machines/export tasks and baseline paths; the library cannot
discover arbitrary definitions hidden in application factories or fabricate payload values.
Repository-relative source maps use positive line numbers and reject traversal/control characters.
IDE navigation additionally checks canonical project containment, including symlinks.

The standalone plugin uses bundled Java/Kotlin/JSON APIs and declares K2 compatibility. Model
references/Find Usages are scoped to one versioned export. Kotlin gutter resolves actual Koma
DSL declarations rather than matching names from unrelated libraries. The canvas renders
text natively; double-click navigates to source or model declaration. It shows at most 200
nodes while retaining the complete transition list. SDK build/PSI tests and installable ZIP
generation run as a separate CI job; installation/publishing remains an explicit user action.

### Production diagnostics and recovery

CausalTraceHub observes actual decisions once and retains bounded metadata. Use one shared
hub and the same unique StoreInstanceIds as MachineGroup for bridge correlation. Store ids
must identify runtime instances, including restarts, rather than logical user/channel ids.
Command/timer outputs link to registration inputs, bridge inputs to effect origins. Unknown
input ids and evicted context are explicit incomplete traces. This memory window is not a
durable audit log or a replacement for application-owned recordings/codecs.

Unexpected command IO failures are retained even when the machine successfully handles its
CommandFailed input. FailureSource distinguishes decision and command failures. recordPersisted
accepts an authentic DurableCommit after successful CAS and explicit application input/command
provenance; selection=null means guard observations are unavailable, never guessed/replayed.
A rejected storage commit supplies no persisted event. Durable handler error reporting and
cross-process audit storage remain application-owned.

The common module retains no action/context/command payloads or original Throwable. SDK adapters
use metadata attributes and sanitized synthetic failures. Callbacks stay short and never call a
Store back; applications choose exporter queues, transports, consent and SDK lifecycle.
OpenTelemetry spans describe receipt of a decision, not command IO duration. Parenting is
explicit, with bounded SpanContext metadata; missing SDK parents are flagged independently.
Sentry and Crashlytics attach report-local keys rather than mutating global custom-key state.

The modules are optional: koma-diagnostics supports every core KMP target;
koma-diagnostics-sdk provides OpenTelemetry/Sentry on JVM and Android;
koma-diagnostics-crashlytics is Android-only and isolates the Firebase dependency. iOS clients
can implement the common DiagnosticSink using their application-owned native SDKs.
BCV supports the common/JVM module APIs; the AGP Android-only Crashlytics target has no BCV
extraction and is checked by host compilation and the SDK contract test.

Recovery reports distinguish cancel-on-exit ordinary work, pending durable idempotent operations
and completed receipts. Ordinary repeat safety is unknown; the application must decide it.
Durable repeat safety assumes the external business idempotency contract. Checkpoints carry
payloads/timer metadata; live events remain ephemeral and persisted mailbox effects are explicit.
Reports inspect authentic validated data; they do not confer persistence or idempotency by themselves.

## Notes

Contract regressions include guard-prefix preservation, explicit error/history targets, timers,
subtype generators, query/impact, incomplete reachability, malformed hierarchy, automatic guard
rollback explanations, codec/diff/source paths, command/bridge causality, handled IO failures,
real durable CAS/outbox provenance, SDK parenting/report isolation and IDE references/navigation.
Final platform/API/CI results are recorded in the MR.

## Related

- [Derived features](2026-10-06-derived-behavioural-features.md)
- [Gap roadmap](2026-10-06-gap-roadmap.md)
- [Kotlin K2 plugin contract](https://kotlin.github.io/analysis-api/declaring-k2-compatibility.html)
- [OpenTelemetry Java API](https://opentelemetry.io/docs/languages/java/api/)
- [Sentry scopes](https://docs.sentry.io/platforms/java/guides/log4j2/enriching-events/scopes__v7.x)
- [Crashlytics report-local keys](https://firebase.google.com/docs/crashlytics/android/customize-crash-reports)

# Null-free API and enforcement policy

Actron's nullability migration is complete. The library's domain values and contracts are
non-null. `.actron/nullability/baseline.tsv` is empty and `.actron/nullability/strict` fixes
that zero-debt milestone. Existing recording readers and the five frozen format versions
are preserved. This is a deliberate breaking source/API change; consumers must recompile.

The approach follows Elegant Objects: objects describe behavior and
real domain alternatives, rather than transferring the obligation to check an empty value.

## Design rules

- Do not introduce `null`, nullable types, `!!` or `lateinit` in library implementations.
- Do not introduce `Optional`, `Option`, `Maybe`, `Some`/`None`, primitive optional containers,
  or a renamed general-purpose value/empty wrapper. Aliased imports do not bypass the rule.
- Give objects behavior. `StateSaver.restore(initialState)` chooses the saved or initial state;
  a write-only saver returns the supplied initial state. `EmptyCoroutineContext` inherits
  execution settings. An empty list of configuration commands makes no changes.
- Describe actual lifecycle phases and domain outcomes: a writer detached from its file, a
  finished replay, a failed decision, or a readable segment with its authentic checkpoint.
  Do not disguise absence as a zero/one-element collection, a magic identifier or a fake exception.
- Use collections for genuine batches and histories, such as command answers, journaled
  decisions, exception relationships and pending work. An empty batch performs no work.
- Searches and observations can report an actual result through a callback and return whether
  it was reported. Respect documented callback cardinality and timing. Do not create a generic
  optional container around the callback.
- Invalid operations fail fast. Expected absence uses the operation's normal behavior.
- Normalize nullable dependency/platform APIs at their boundary. Preserve old protocol readers;
  an API change does not authorize changing the on-disk schema or its version.

## Consumer migration

These changes require recompilation. Concrete overloads, domain phases and callback operations
replace old nullable arguments and return values:

| Previous usage | Replacement |
|---|---|
| Unbounded context / command generic parameters | `C : Any` / `CMD : Any` in consumers and serializers |
| `Store(initialState = null) { initialState(value) }` | `Store { initialState(value) }` |
| `dispatcher = null` / `coroutineContext = null` | Omit the argument or pass `EmptyCoroutineContext` |
| `StateSaver.restore(): S?` | `StateSaver.restore(initialState: S): S` |
| `StateSaver(save = ..., restore = { null })` | `StateSaver(save = ..., restore = { initial -> initial })` |
| Nullable fields in `StorePatch` | `StorePatchBuilder` DSL or configuration commands |
| `LaunchControl.CancelPrevious(null)` | `LaunchControl.CancelPrevious()` |
| Nullable guard/effect labels | `GuardCondition.Unconditional` / `GuardKey`, `TransitionEffect.NoEffect` / `EffectKey` |
| Nullable state parent / command lane / retry limit | `StateParent.Root`, `CommandLane.Independent`, `RetryBudget.Unlimited` |
| Bridge mapping returning `Action?` | `EventRoute` / route callback `(event, carry) -> Unit`; invoke `carry` only for a routed event |
| Durable storage returning `Checkpoint?` | `read(accept): Boolean`; report exactly one authentic checkpoint when returning `true` |
| Nullable durable generation | `DurableGeneration.Fresh` or `Existing(generation)` |
| Nullable durable command result | Handler calls `emit` for a produced action; ordinary completion emits none |
| Nullable effect-key classifier | Call `classify(key)` for an outbox command; ordinary commands remain transient |
| `shortestPathTo(target): TransitionPath?` | `shortestPathTo(target, accept): Boolean` |
| Nullable failure type/message/cause | `FailureDetails`, actual failure relationships, `withType`, `withMessage`, `withCause` |
| `RecordingStatus.None` | `RecordingStatus.Unrecorded` |
| `MachineRecorder.problem` / `RecordingFileSink.problem` | `origin.withProblem`; `RecordingOrigin.InitialSnapshot` or `UnknownBeginning(reason)` |
| `ReplaySession.next` | `withNext(accept): Boolean` |
| `ReplaySession.stepForward(): ReplayStep?` | `Matched`, `Diverged` or `Finished(position)` |
| `ReplaySession.verify(): ReplayMismatch?` | `verify { mismatch -> ... }: Boolean`; `true` means a mismatch was reported |
| `ReplaySession.explainNext(): ExplainedDecision?` | `explainNext(accept): Boolean` |
| Nullable recording on `Branch` | Omit it for caller-supplied answers, or use the recording overload |
| `reuseRecordedAnswers(): List<Decision>?` | A real decision batch; empty means no recorded answer was applied |
| Nullable file header/checkpoint | `DecodedRecordingSegment.Unreadable`, `HeaderOnly` or `Readable` |
| Nullable file recording/range start | `RecordingFileContents` / `GroupRecordingFileContents.Readable` or `Unreadable`, preserving damage marks |
| Nullable segment stop mark | `SegmentEnding.Finalized` or `Stopped(issue)`; `withIssue` observes the real diagnostic |
| Nullable inspection outcome / snapshots | `ProcessingProgress`, recorded decision/provenance histories, `ProcessingAttachment` |
| Nullable causal parent / decision selection | `TraceParent`, `TraceLineage`, `TraceAssessment`; metadata remains bounded and redacted |
| Nullable UI selection / filter | `InspectorSelection.Overview` / `Position`, `StoreFilter.All` / `Single` |
| Nullable replay/branch UI state | `ReplayMovement`, verification/inspection phases, `GroupTravel.Replaying` / `Experiment` |

`Store` still defaults to `Dispatchers.Default`. Handler/launch/transaction overrides default to
`EmptyCoroutineContext`, preserving the owning execution context. A concrete dispatcher remains
a valid `CoroutineContext`. No fake actions, checkpoints, errors or identifiers replace absent data.

Routing callbacks are synchronous and may carry at most one action. A mapper throwing after
`carry` delivers nothing. Durable classifiers obey the same synchronous rule. Durable handlers
may emit at most one result during their suspended execution; publication follows successful
completion. Duplicate or late callbacks are rejected. These rules prevent partially applied work.

Tests retain projections into the old observation vocabulary where useful so existing randomized,
crash, truncation, privacy and replay assertions continue checking the same behavior. Those helpers
are test code, never published library implementations or compatibility wrappers for consumers.

## Two enforced checks

Run:

```sh
./gradlew checkNullability
```

The command checks every `actron-*/src/*Main/**/*.kt`, including published test-tool modules,
platform source sets and library compilation tasks. Tests, examples, build tools and generated
compiler ABI are outside the authored-library policy.

1. **Source check:** Kotlin PSI from the pinned compiler parses the source and rejects nullable
   syntax, null literals, `!!`, `lateinit`, unconstrained type parameters and optional-container
   names. Comments and string contents are ignored; interpolated code is checked. Malformed
   Kotlin fails. Imports and type aliases are checked too.
2. **Resolved-type check:** the compiler plugin examines authored IR before Compose and
   serialization transform signatures. It rejects nullable fields, parameters, return types,
   inferred locals, nested generic arguments and star projections with nullable upper bounds.
   Assigning `map[key]` or `firstOrNull()` to an inferred nullable local therefore fails even
   without a `?` in the source. Normalize the expression before binding it.

The plugin runs on all library Main compilations, including Android, iOS, JVM, JS and Wasm.
CI's platform matrix compiles and tests the appropriate targets; unsupported native targets
are covered on macOS. `checkNullability` also runs checker self-tests and real compiler regression
fixtures that must reject forbidden types and accept normalized boundary usage. Kotlin is pinned
to 2.3.20; changing it requires revalidating the plugin's compiler integration.

There are no file-wide suppressions or optional-container exceptions. Kotlin requires `Any?`
on `Any.equals`; only that intrinsic parameter is exempt. Copying it into a nullable local is
rejected. A directly supplied `Job.invokeOnCompletion` lambda has a dependency-imposed nullable
SPI parameter; forwarding or copying that nullable type into owned declarations is rejected.
Boundary code handles cancellation through a concrete `CancellationException` operation.
Generated ABI temporaries are not authored domain contracts. Standard-library-imposed nullable
lambda returns are accepted only when all actual returned expressions are non-null.

## The zero-debt milestone cannot regress

The migration completed through:

```sh
./gradlew :nullability-guard:finishMigration
```

It first compiles all library Main targets with compiler enforcement enabled, runs the checker
regressions and requires zero source findings. Only then does it empty the baseline and create the
strict marker. That marker now makes compiler violations errors. Passing `enforce=false` cannot
turn the marker off.

CI compares policy files to the pull request's base commit, or the previous commit on a push.
New violations, a nonempty baseline, baseline growth or removal of the strict marker fail.
`pruneBaseline` cannot add exceptions. The policy runs in the required JVM CI job; resolved-type
checking additionally runs in each actual library platform compilation.

This guarantees the enforced scope, not that foreign Java code or a dependency can never
violate its contract at runtime. Old JSON protocols can contain an actual `JsonNull` AST node;
that is a non-null protocol object, not a Kotlin nullable value. Application serializers still
own their payload schema. Repository-local checks can themselves be edited, so checker and
workflow changes remain subject to code review and branch protection. Semantic review also
rejects arbitrarily renamed optional-like wrappers; names alone cannot prove domain behavior.

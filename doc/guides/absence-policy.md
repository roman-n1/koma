# Removing null and optional containers

Actron is migrating toward behavior expressed by objects, following the
[Elegant Objects discussion of alternatives to null](https://www.yegor256.com/2014/05/13/why-null-is-bad.html).
The migration is **not complete**. Existing nullable contracts are recorded as debt, not
advertised as a guarantee of null-free execution.

## Design rules

- Do not introduce `null`, nullable types, `!!` or `lateinit` in library implementations.
- Do not introduce `Optional`, `Option`, `Maybe`, `Some`/`None`, primitive optional containers,
  or a renamed general-purpose value/empty wrapper. Imports under an alias do not bypass the rule.
- Give objects behavior. For example, `StateSaver.restore(initialState)` selects the saved or
  initial state; a no-op saver returns the initial state. `EmptyCoroutineContext` inherits
  execution settings. A patch is a list of configuration commands; an empty patch does nothing.
- Keep meaningful domain alternatives explicit. A command, lifecycle state or failure outcome
  must describe its meaning. Do not disguise an optional value as a zero/one-element collection,
  a magic identifier, a fake exception or a renamed `Some`/`None` pair.
- Fail fast for an invalid operation; do not turn expected absence into a new exceptional path.
- Isolate nullable external APIs at integration boundaries. Do not spread their contracts into
  the model. Keep readers for old recordings; an API change does not authorize a wire-format change.

## Current API migration

These changes require consumers to recompile:

| Previous usage | Replacement |
|---|---|
| `Store(initialState = null) { initialState(value) }` | `Store { initialState(value) }` |
| `dispatcher = null` | Omit the argument, or pass `EmptyCoroutineContext` |
| `coroutineContext = null` on chart/Machine Store factories | Omit the argument, or pass a concrete context |
| `StateSaver.restore(): S?` | `StateSaver.restore(initialState: S): S` |
| `StateSaver(save = ..., restore = { null })` | `StateSaver(save = ..., restore = { initial -> initial })` |
| Nullable fields in `StorePatch` | `StorePatchBuilder` DSL, or `settings = listOf(StoreSetting.…(...))` |
| `LaunchControl.CancelPrevious(null)` | `LaunchControl.CancelPrevious()` |
| `RecordingStatus.None` | `RecordingStatus.Unrecorded` |

`Store` still defaults to `Dispatchers.Default`. Handler/launch/transaction context overrides
default to `EmptyCoroutineContext`, preserving the owning execution context. Existing callers
passing a concrete `CoroutineDispatcher` can continue passing it as a `CoroutineContext`.

The journal and recording format versions and bytes are unchanged. Persistence adapters now
receive the initial state explicitly, but remain responsible for their application-owned schema.

## CI enforcement

Run `./gradlew checkNullability`. The checker uses Kotlin PSI from the project's Kotlin compiler
version, rather than text matching. It inspects every `actron-*/src/*Main/**/*.kt`, including the
published test-tool modules and all platform source sets. Tests, examples and the checker itself
are outside this implementation policy. Comments and string contents are ignored; code inside
string interpolation is checked. Malformed Kotlin fails the check.

The only intrinsic signature exemption is the `Any?` parameter of `override fun equals` required
by Kotlin. Nullable locals and null literals inside that method are still checked. There are no
file-wide suppression annotations or optional-container exceptions.

During migration, `.actron/nullability/baseline.tsv` records each remaining occurrence by kind,
file and normalized source line. Duplicate occurrences are counted separately. Line-number shifts
do not grant new exceptions. New occurrences fail. Resolved entries must be removed with:

```sh
./gradlew :nullability-guard:pruneBaseline
```

This command can only remove debt. In CI, the baseline is also compared to the PR's base commit
(or the previous commit on a push); growing it fails even if the new source matches the new file.
The policy step runs inside the existing required JVM CI job.

When every occurrence has been removed, run:

```sh
./gradlew :nullability-guard:finishMigration
```

This refuses to run while findings remain. It empties the baseline and creates the `strict`
marker. CI then rejects new findings, a nonempty baseline, or removal of the strict marker
relative to the base branch. An empty baseline cannot grow again under the comparison rule.

This is a source policy, not a proof that a dependency, Java caller or user callback can never
produce a null or exception. PSI does not infer every expression's type. Inferred nullable
results, unconstrained generic types, boundary adapters and arbitrarily renamed optional-like
abstractions still require compiler/API inspection and design review. The policy checker and
workflow changes themselves must also be reviewed; no repository-local check is immutable
against edits to its own implementation.

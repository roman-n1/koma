# Koma to Actron migration isolation

Actron's package rename breaks source and binary compatibility. This fixture verifies a
staged migration: a frozen Koma 4.0.0 client still runs with Koma while a newly compiled
Actron Store runs in the same application. It does not replace the legacy runtime.

```bash
scripts/verify-binary-consumer.sh jvm
scripts/verify-binary-consumer.sh js
scripts/verify-binary-consumer.sh wasm
scripts/verify-binary-consumer.sh ios # macOS, Xcode, ARM64 simulator
```

Use JDK 21 because upstream `io.github.koma-kt:koma-core-jvm:4.0.0` contains Java 21
bytecode. The separate [published consumer](../published-consumer/README.md) checks
Actron's Java 11 target.

The independent `legacy` build compiles a scenario against published upstream Koma 4.0.0
using its Kotlin 2.3.20 compiler. It freezes the resulting JAR/klib with SHA-256 hashes.
The producer publishes Actron to another temporary Maven repository, and `runner`
executes both the frozen scenario and an Actron Store. Resolution checks require both
distinct core artifacts and reject source substitution. The legacy binaries must remain
unchanged. No credentials, external publication or MavenLocal are used.

CI runs this on JVM, JS, Wasm and iOS before release. This protects package/library
identity isolation; it is not a compatibility promise for arbitrary historical clients.

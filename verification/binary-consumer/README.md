# Stable binary upgrade verification

Run from the repository root:

```bash
scripts/verify-binary-consumer.sh jvm
scripts/verify-binary-consumer.sh js
scripts/verify-binary-consumer.sh wasm
scripts/verify-binary-consumer.sh ios # macOS, Xcode, ARM64 simulator
```

Use JDK 21: the published upstream `io.github.koma-kt:koma-core-jvm:4.0.0`
contains Java 21 bytecode. This baseline check does not claim that an old Java 21 client
runs on Java 11. The separate [published consumer](../published-consumer/README.md)
check verifies the fork's Java 11 target with a newly compiled client.

These are two independent Gradle builds, outside root settings. `legacy` compiles a
non-inline scenario against the real Maven Central 4.0.0 core with its Kotlin 2.3.20
compiler, then publishes that client to an isolated temporary repository. SHA-256 hashes
freeze every client JAR/klib. `runner` first executes it with upstream 4.0.0 as a control.
The producer then publishes its replacement core to a second temporary repository.
Only the runner is recompiled with the current compiler; it executes the same frozen
client against the fork core. Hash verification proves the client binaries did not change.
No composite builds, source-project dependencies or MavenLocal fallback are used.
Resolution checks require the exact control/replacement core coordinates and reject
foreign source projects. Temporary repositories are removed on exit; nothing is published
externally and credentials are unnecessary.

The scenario exercises old `Store`, `StateSaver` and `Plugin` binaries: startup restore,
state/action handlers, events, launch, transaction, recovery, ordered saves, variant exit
and shutdown. JVM, JS, Wasm and ARM64 iOS simulator checks execute this scenario; the
release workflow requires them before publishing. It covers the stable upstream core
surface exercised by the fixture, not every historical experimental API or compiler.

Changing Maven group also changed Kotlin library `unique_name`. A frozen Native client
then failed with `IrLinkageError` even though source consumers compiled. The core's main
Native/JS/Wasm compilations now retain `io.github.koma-kt:koma-core` as their internal
library identity. The Maven group stays `io.github.roman-n1`; test compilations retain
their own identities. Do not ship both upstream and fork core in the same runtime graph:
this check explicitly replaces the old dependency.

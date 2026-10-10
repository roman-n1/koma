# Published-artifact consumer verification

This is an **independent Gradle build**, with its own settings and two projects. It is not
included by Actron's root settings. There are no `project(...)` dependencies on the library,
composite builds, dependency substitutions or `mavenLocal()` fallbacks. Fork coordinates are
restricted to an isolated Maven repository, so cached or remotely published releases cannot
silently substitute for the artifacts built by the check.

From the repository root:

```bash
scripts/verify-published-consumer.sh jvm
scripts/verify-published-consumer.sh android
scripts/verify-published-consumer.sh js
scripts/verify-published-consumer.sh wasm
scripts/verify-published-consumer.sh ios # requires macOS/Xcode
```

Each invocation publishes **all eleven public modules**, including test and debug tooling,
with their root metadata and the selected platform's variants. Group/version are read from
`gradle.properties`; plugin/dependency versions are read from the producer's version catalog.
It creates and deletes a unique temporary Maven repository. It does not publish to Maven
Central or modify the user's MavenLocal repository. Credentials and signing are unnecessary.

The check validates POM coordinates/dependencies, Gradle metadata redirects and dependency
coordinates, payload existence/size/SHA-256, and the published production dependency graph.
For JVM, variant metadata and actual class files must target Java 11, independently of the JDK
running Gradle. This catches a default-JVM-target problem that source-project tests can hide.

`app` consumes production modules only. Core, statechart and observability are intentionally
transitive dependencies; common code calls their APIs and compiles Compose ViewStore/Mailbox
usage. The JVM smoke executes ordinary Store, StateChartStore, MachineStore, the message bus
and a logging plugin. CI builds with JDK 17 and executes the JVM smoke with a real JDK 11, so
target-11 bytecode calling newer Java APIs is detected. Locally set
`ACTRON_CONSUMER_JAVA11_HOME=/path/to/jdk-11` to do the same; otherwise the local smoke uses the
Gradle runtime and reports that limitation. `tooling` separately compiles MachineTestDriver, actron-test helpers and
the Time Travel inspector. Graph checks require all eleven modules to be consumed and reject
source-project substitution, mixed fork versions and test/debug modules in the app graph.

The CI workflow runs JVM, Android, JS, Wasm and both iOS architectures independently. Web and
iOS checks compile consumer APIs; they do not run a browser UI or a physical-device app. JVM
executes the smoke test. This verifies packaging and consumption; it does not replace runtime
integration tests or a production pilot.

Adapter configuration contracts are checked against the **published API**, too. Positive common
fixtures compile safe MachineStore configuration, context-only chart recovery, and
`store.message(...)` from hooks/activities. On JVM, a negative fixture must fail compilation at
all seven labelled forbidden operations: MachineStore `state`/`initialState`/`recover`, chart
recovery `configuration`/`timers`/`nextState`, and treating a chart hook itself as `StoreScope`.
An unrelated compiler or dependency failure cannot satisfy the negative check; each operation
must produce its own diagnostic. The captured compiler log remains under `build/reports/contracts`.

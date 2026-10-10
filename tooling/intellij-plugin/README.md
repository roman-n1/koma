# Actron Behavioural Model — IntelliJ plugin

K2 plugin for IntelliJ IDEA 2025.3+ and compatible Android Studio builds. Uses bundled Java/Kotlin/JSON;
no Kotlin compiler internals. It provides Kotlin DSL state-navigation gutter markers, version-scoped
references/Find Usages in model.actron.json exports, and a native graph with source-map navigation.
Active/selected model data can come from runtime/debug exports; the plugin executes no machine rules.

```shell
../../gradlew -p . buildPlugin test
```

Run from this directory, or `./gradlew -p tooling/intellij-plugin buildPlugin test` from the repository.
The default test SDK is IntelliJ IDEA 2025.3.3. For a local SDK:

```shell
./gradlew -p tooling/intellij-plugin buildPlugin test -Pactron.ide.path='/Applications/Android Studio.app/Contents'
```

ZIPs are under build/distributions. Install one explicitly using the IDE's “Install Plugin from Disk”.
Open a format-1 .actron.json export, select Actron → Refresh, and double-click a node/transition to navigate.
Find usages searches references inside that definition/version. A source map uses repository-relative
paths; missing source locations fall back to the model declaration. The canvas shows at most 200 nodes;
the transition list stays complete.

Generate the repository's executable demo with `./gradlew :behaviour-review:exportBehaviour`.
See the [full contract](../../doc/internal/design/2026-10-06-competitive-roadmap.md).

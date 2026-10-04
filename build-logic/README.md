# build-logic

An included Gradle build for repository conventions, not a runtime Koma library. Its
[convention project](convention/README.md) supplies the `koma.publish` plugin used by the
published modules. The runnable Time Travel example does not apply this plugin.

Root `settings.gradle.kts` loads this build through `pluginManagement.includeBuild`.
The shared version catalog is imported by this build's [settings](settings.gradle.kts).
Publishing rules keep all library modules on the fork's group/version and supply their
artifact ids and POM metadata; see the [installation guide](../README.md#installation).

```sh
# From the repository root:
./gradlew -p build-logic :convention:validatePlugins
```

Adding a library module means defining its targets/dependencies, applying the convention,
declaring `publishConvention.artifactId`, including it in root settings and documenting its
API. A debug application can be included without publishing; it must be explicitly classified
by `checkDebugGraph` and excluded from library API dumps.

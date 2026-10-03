# convention

The implementation of the repository's `koma.publish` Gradle plugin. It configures publication
and artifact metadata consistently across Koma library modules; application code does not
depend on this project.

Existing modules apply it through the shared catalog:

```kotlin
plugins {
    alias(libs.plugins.koma.publish)
}

publishConvention {
    artifactId = "koma-core" // each library supplies its own module id
}
```

Fork group/version overrides come from root `gradle.properties`. The implementation is
[PublishConventionPlugin.kt](src/main/kotlin/koma/buildlogic/PublishConventionPlugin.kt), with
its extension in [PublishConventionExtension.kt](src/main/kotlin/koma/buildlogic/PublishConventionExtension.kt).
Validate changes with the command in the [included-build README](../README.md).
Applying the plugin configures publication; it does not mean a release is already available.

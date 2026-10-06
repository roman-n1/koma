pluginManagement {
    repositories { mavenCentral(); gradlePluginPortal() }
    plugins { id("org.jetbrains.kotlin.multiplatform") version providers.gradleProperty("compiler.version").get() }
}
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "legacy-koma-consumer"

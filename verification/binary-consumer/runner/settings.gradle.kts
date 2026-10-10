pluginManagement {
    repositories { mavenCentral(); gradlePluginPortal() }
    plugins { id("org.jetbrains.kotlin.multiplatform") version providers.gradleProperty("compiler.version").get() }
}
dependencyResolutionManagement {
    repositories {
        exclusiveContent {
            forRepository { maven { url = uri(providers.gradleProperty("binary.repository").get()) } }
            filter { includeGroup("koma.verification") }
        }
        exclusiveContent {
            forRepository { maven { url = uri(providers.gradleProperty("fork.repository").get()) } }
            filter { includeGroup(providers.gradleProperty("fork.group").get()) }
        }
        mavenCentral()
    }
}
rootProject.name = "binary-actron-runner"

pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    plugins {
        id("org.jetbrains.kotlin.multiplatform") version providers.gradleProperty("consumer.kotlin").get()
        id("com.android.kotlin.multiplatform.library") version providers.gradleProperty("consumer.agp").get()
        id("org.jetbrains.compose") version providers.gradleProperty("consumer.compose").get()
        id("org.jetbrains.kotlin.plugin.compose") version providers.gradleProperty("consumer.kotlin").get()
    }
}
dependencyResolutionManagement {
    repositories {
        // Never substitute source projects or silently obtain fork artifacts from Maven Central.
        exclusiveContent {
            forRepository {
                maven {
                    name = "IsolatedPublishedKoma"
                    url = uri(providers.gradleProperty("consumer.repository").get())
                }
            }
            filter { includeGroup(providers.gradleProperty("consumer.group").get()) }
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "published-koma-consumer"
include(":app", ":tooling")

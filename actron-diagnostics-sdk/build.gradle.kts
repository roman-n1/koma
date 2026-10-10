import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.actron.publish)
}
group = "io.github.roman-n1"
version = libs.versions.actron.get()
kotlin {
    android {
        namespace = "actron.diagnostics.sdk"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTestBuilder {}.configure {}
        compilerOptions { jvmTarget = JvmTarget.JVM_11 }
    }
    jvm {
        attributes.attribute(org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 11)
        compilerOptions { jvmTarget = JvmTarget.JVM_11 }
    }
    sourceSets {
        commonMain.dependencies { api(project(":actron-diagnostics")) }
        commonTest.dependencies { implementation(libs.kotlin.test) }
        jvmMain { kotlin.srcDir("src/jvmSharedMain/kotlin") }
        androidMain { kotlin.srcDir("src/jvmSharedMain/kotlin") }
        jvmMain.dependencies {
            api("io.opentelemetry:opentelemetry-api:1.66.0")
            api("io.sentry:sentry:8.18.0")
        }
        androidMain.dependencies {
            api("io.opentelemetry:opentelemetry-api:1.66.0")
            api("io.sentry:sentry:8.18.0")
        }
        jvmTest.dependencies {
            implementation("io.opentelemetry:opentelemetry-sdk:1.66.0")
            implementation("io.opentelemetry:opentelemetry-sdk-testing:1.66.0")
        }
    }
}
publishConvention { artifactId = "actron-diagnostics-sdk" }

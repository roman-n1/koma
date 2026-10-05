import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.koma.publish)
}

group = "io.github.roman-n1"
version = libs.versions.koma.get()

kotlin {
    // JVM and Android share the java.io file storage; the default hierarchy adds iosMain for the POSIX one.
    applyDefaultHierarchyTemplate {
        common {
            group("jvmAndroid") {
                withJvm()
                withCompilations { it.target.platformType == org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.androidJvm }
            }
        }
    }

    android {
        namespace = "koma.observability"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        // withJava() // enable java compilation support
        withHostTestBuilder {}.configure {}
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }
    iosArm64()
    iosSimulatorArm64()
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }
    js(IR) {
        browser()
        nodejs {
            testTask {
                useMocha { timeout = "300s" }
            }
        }
    }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        nodejs {
            testTask {
                useMocha { timeout = "300s" }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":koma-core"))
        }
        commonTest.dependencies {
            implementation(project(":koma-test"))
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
        }
    }
}

publishConvention {
    artifactId = "koma-observability"
}

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.koma.publish)
}

group = "io.github.roman-n1"
version = libs.versions.koma.get()

kotlin {
    android {
        namespace = "koma.statechart.test"
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
        attributes.attribute(org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 11)
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
            api(project(":koma-statechart"))
            api(project(":koma-test"))
            api(libs.coroutines.test)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

publishConvention {
    artifactId = "koma-statechart-test"
}

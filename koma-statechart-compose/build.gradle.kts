import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.koma.publish)
}

group = "io.github.roman-n1"
version = libs.versions.koma.get()

kotlin {
    android {
        namespace = "koma.statechart.compose"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        // The tests compose (a Recomposer on the test dispatcher); the Android runtime of Compose
        // calls android.os.Trace and android.util.Log, stubs in the host test's android.jar that
        // throw "not mocked" unless they return defaults.
        withHostTestBuilder {}.configure {
            isReturnDefaultValues = true
        }
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
    // Web targets are browser-only, as in koma-compose: Skiko's web runtime has no Node loader.
    js(IR) {
        browser()
    }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":koma-compose"))
            api(project(":koma-statechart"))
            api(compose.runtime)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
        }
    }
}

// Same as koma-compose: rin (a runtime dependency of koma-compose) pulls org.jetbrains.compose.ui
// at 1.6.10, whose androidx.collection klib collides with the 1.11.x one in the JS/Wasm IR
// linker. Force the ui group to the plugin version so one collection klib is on the classpath.
configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.compose.ui") {
            useVersion(libs.versions.compose.multiplatform.get())
        }
    }
}

publishConvention {
    artifactId = "koma-statechart-compose"
}

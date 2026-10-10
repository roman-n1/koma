import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library") apply false
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}
if (providers.gradleProperty("consumer.target").get() == "android") {
    pluginManager.apply("com.android.kotlin.multiplatform.library")
}
kotlin {
    when (providers.gradleProperty("consumer.target").get()) {
        "jvm" -> jvm { compilerOptions { jvmTarget = JvmTarget.JVM_11 } }
        "android" -> targets.named<KotlinMultiplatformAndroidLibraryTarget>("android") { namespace = "consumer.tooling"; compileSdk = 36; minSdk = 24 }
        "js" -> js(IR) { browser() }
        "wasm" -> @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class) wasmJs { browser() }
        "ios" -> { iosArm64(); iosSimulatorArm64() }
        else -> error("Unsupported consumer target")
    }
    sourceSets.commonMain.dependencies {
        val coordinate = providers.gradleProperty("consumer.group").get()
        val version = providers.gradleProperty("consumer.version").get()
        implementation("$coordinate:actron-timetravel-compose:$version")
        implementation("$coordinate:actron-statechart-test:$version")
        // Test tooling stays in its own consumer; it cannot mask missing production dependencies.
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${providers.gradleProperty("consumer.coroutines").get()}")
    }
}

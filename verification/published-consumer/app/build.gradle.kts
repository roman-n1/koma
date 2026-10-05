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
        "android" -> targets.named<KotlinMultiplatformAndroidLibraryTarget>("android") { namespace = "consumer.app"; compileSdk = 36; minSdk = 24 }
        "js" -> js(IR) { browser() }
        "wasm" -> @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class) wasmJs { browser() }
        "ios" -> { iosArm64(); iosSimulatorArm64() }
        else -> error("Unsupported consumer target")
    }
    sourceSets {
        commonMain {
            providers.gradleProperty("consumer.contractSources").orNull?.let { kotlin.srcDir(it) }
        }
        commonMain.dependencies {
            val coordinate = providers.gradleProperty("consumer.group").get()
            val version = providers.gradleProperty("consumer.version").get()
            // core/statechart/observability are intentionally only transitive dependencies.
            listOf("koma-compose", "koma-message", "koma-logging", "koma-statechart-compose").forEach { implementation("$coordinate:$it:$version") }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${providers.gradleProperty("consumer.coroutines").get()}")
        }
    }
}

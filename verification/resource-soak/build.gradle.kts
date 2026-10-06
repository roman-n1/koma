import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
}

val generatedConfig = tasks.register("generateSoakConfig") {
    val seconds = providers.gradleProperty("soakSeconds").map(String::toInt).orElse(3)
    val output = layout.buildDirectory.dir("generated/soak")
    val reports = layout.buildDirectory.dir("reports/resource-soak").get().asFile.absolutePath
    inputs.property("seconds", seconds)
    inputs.property("reports", reports)
    outputs.dir(output)
    doLast {
        val duration = seconds.get()
        require(duration in 3..3600) { "soakSeconds must be 3..3600" }
        val file = output.get().file("SoakConfig.kt").asFile
        file.parentFile.mkdirs()
        file.writeText("package koma.soak\nconst val SOAK_SECONDS = $duration\nconst val SOAK_REPORT_DIRECTORY = \"$reports\"\n")
    }
}

kotlin {
    android {
        namespace = "koma.resources.soak"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withDeviceTestBuilder { sourceSetTreeName = "deviceTest" }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
        compilerOptions { jvmTarget = JvmTarget.JVM_11 }
    }
    jvm { compilerOptions { jvmTarget = JvmTarget.JVM_11 } }
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain {
            kotlin.srcDir(generatedConfig)
            dependencies {
                implementation(project(":koma-statechart"))
                implementation(libs.coroutines.core)
                implementation(libs.serialization.json)
            }
        }
        commonTest.dependencies { implementation(libs.kotlin.test) }
        getByName("androidDeviceTest").dependencies {
            implementation("androidx.test:runner:1.7.0")
            implementation("androidx.test.ext:junit:1.3.0")
        }
    }
}

tasks.withType<AbstractTestTask>().configureEach { testLogging.showStandardStreams = true }
// Measurements must execute on the current runner/device, never replay another machine's cache.
tasks.matching { it.name in setOf("jvmTest", "iosSimulatorArm64Test", "connectedAndroidDeviceTest") }.configureEach {
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

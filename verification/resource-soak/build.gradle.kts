import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

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
        file.writeText("package actron.soak\nconst val SOAK_SECONDS = $duration\nconst val SOAK_REPORT_DIRECTORY = \"$reports\"\n")
    }
}

kotlin {
    android {
        namespace = "actron.resources.soak"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withDeviceTestBuilder { sourceSetTreeName = "deviceTest" }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
        compilerOptions { jvmTarget = JvmTarget.JVM_11 }
    }
    jvm {
        compilerOptions { jvmTarget = JvmTarget.JVM_11 }
        val defaultReachability = testRuns.create("defaultReachability") {
            setExecutionSourceFrom(compilations.getByName("test"))
            executionTask.configure { useJUnit(); filter.includeTestsMatching("actron.soak.DefaultReachabilityTest*") }
        }
        testRuns.getByName("test").executionTask.configure {
            useJUnit()
            filter.excludeTestsMatching("actron.soak.DefaultReachabilityTest*")
            dependsOn(defaultReachability.executionTask)
        }
    }
    iosArm64()
    iosSimulatorArm64 {
        testRuns.create("defaultReachability") { setExecutionSourceFrom(binaries.getTest(NativeBuildType.DEBUG)) }
    }
    sourceSets {
        commonMain {
            kotlin.srcDir(generatedConfig)
            dependencies {
                implementation(project(":actron-statechart"))
                implementation(libs.coroutines.core)
                implementation(libs.serialization.json)
            }
        }
        commonTest.dependencies { implementation(libs.kotlin.test) }
        getByName("jvmTest").dependencies { implementation(kotlin("test-junit")) }
        getByName("androidDeviceTest").dependencies {
            implementation("androidx.test:runner:1.7.0")
            implementation("androidx.test.ext:junit:1.3.0")
        }
    }
}

// Each execution launches a fresh process; never reuse another device's measurements.
tasks.withType<AbstractTestTask>().configureEach {
    testLogging.showStandardStreams = true
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}
tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    if (name == "iosSimulatorArm64DefaultReachabilityTest") {
        filter.includeTestsMatching("actron.soak.DefaultReachabilityTest*")
    } else if (name == "iosSimulatorArm64Test") {
        filter.excludeTestsMatching("actron.soak.DefaultReachabilityTest*")
        dependsOn("iosSimulatorArm64DefaultReachabilityTest")
    }
}
tasks.matching { it.name == "connectedAndroidDeviceTest" }.configureEach {
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

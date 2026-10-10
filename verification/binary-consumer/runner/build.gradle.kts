import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins { id("org.jetbrains.kotlin.multiplatform") }
val target = providers.gradleProperty("binary.target").get()
kotlin {
    when (target) {
        "jvm" -> jvm { compilerOptions { jvmTarget = JvmTarget.JVM_21 } }
        "js" -> js(IR) { nodejs { testTask { useMocha { timeout = "60s" } } } }
        "wasm" -> @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class) wasmJs { nodejs { testTask { useMocha { timeout = "60s" } } } }
        "ios" -> iosSimulatorArm64()
        else -> error("Unknown binary target")
    }
    sourceSets.commonTest.dependencies {
        implementation("koma.verification:legacy-koma-consumer:4.0.0-fixture")
        implementation("${providers.gradleProperty("fork.group").get()}:actron-core:${providers.gradleProperty("fork.version").get()}")
        implementation(kotlin("test"))
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${providers.gradleProperty("coroutines.version").get()}")
    }
}
tasks.register("verifyRunnerGraph") {
    doLast {
        val names = mapOf("jvm" to "jvmTestRuntimeClasspath", "js" to "jsTestCompileClasspath", "wasm" to "wasmJsTestCompileClasspath", "ios" to "iosSimulatorArm64TestCompileKlibraries")
        val resolution = configurations.getByName(names.getValue(target)).incoming.resolutionResult
        val ids = resolution.allComponents.map { it.id }
        val rootId = resolution.rootComponent.get().id
        check(ids.filterIsInstance<org.gradle.api.artifacts.component.ProjectComponentIdentifier>().all { it == rootId }) { "Source substitution: $ids" }
        val modules = ids.filterIsInstance<org.gradle.api.artifacts.component.ModuleComponentIdentifier>()
        val actron = modules.filter { it.module.startsWith("actron-core") }
        val koma = modules.filter { it.module.startsWith("koma-core") }
        check(actron.isNotEmpty() && actron.all { it.group == providers.gradleProperty("fork.group").get() && it.version == providers.gradleProperty("fork.version").get() }) { "Wrong Actron core: $actron" }
        check(koma.isNotEmpty() && koma.all { it.group == "io.github.koma-kt" && it.version == "4.0.0" }) { "Legacy runtime must retain upstream Koma: $koma" }
        check(modules.any { it.group == "koma.verification" && it.module.startsWith("legacy-koma-consumer") })
        logger.lifecycle("Migration isolation verified: Actron=$actron, Koma=$koma")
    }
}

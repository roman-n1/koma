import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins { id("org.jetbrains.kotlin.multiplatform") }
val upgraded = providers.gradleProperty("binary.upgraded").get().toBoolean()
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
        implementation(kotlin("test"))
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${providers.gradleProperty("coroutines.version").get()}")
    }
}
if (upgraded) {
    configurations.configureEach {
        resolutionStrategy.dependencySubstitution {
            substitute(module("io.github.koma-kt:koma-core")).using(module("${providers.gradleProperty("fork.group").get()}:koma-core:${providers.gradleProperty("fork.version").get()}"))
        }
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
        val core = modules.filter { it.module.startsWith("koma-core") }
        val expectedGroup = if (upgraded) providers.gradleProperty("fork.group").get() else "io.github.koma-kt"
        val expectedVersion = if (upgraded) providers.gradleProperty("fork.version").get() else "4.0.0"
        check(core.isNotEmpty() && core.all { it.group == expectedGroup && it.version == expectedVersion }) { "Wrong runtime core: $core" }
        check(modules.any { it.group == "koma.verification" && it.module.startsWith("legacy-koma-consumer") })
        logger.lifecycle("Frozen binary runtime verified: upgraded=$upgraded, core=$core")
    }
}

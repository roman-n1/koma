import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins { id("org.jetbrains.kotlin.multiplatform"); `maven-publish` }
group = "koma.verification"
version = "4.0.0-fixture"
kotlin {
    when (providers.gradleProperty("binary.target").get()) {
        "jvm" -> jvm { compilerOptions { jvmTarget = JvmTarget.JVM_21 } }
        "js" -> js(IR) { nodejs() }
        "wasm" -> @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class) wasmJs { nodejs() }
        "ios" -> iosSimulatorArm64()
        else -> error("Unknown binary target")
    }
    sourceSets.commonMain.dependencies { api("io.github.koma-kt:koma-core:4.0.0") }
}
publishing.repositories.maven { name = "Frozen"; url = uri(providers.gradleProperty("binary.repository").get()) }
tasks.register("verifyLegacyGraph") {
    doLast {
        val names = mapOf("jvm" to "jvmCompileClasspath", "js" to "jsCompileClasspath", "wasm" to "wasmJsCompileClasspath", "ios" to "iosSimulatorArm64CompileKlibraries")
        val ids = configurations.getByName(names.getValue(providers.gradleProperty("binary.target").get())).incoming.resolutionResult.allComponents.map { it.id }
        val libraries = ids.filterIsInstance<org.gradle.api.artifacts.component.ModuleComponentIdentifier>().filter { it.module.startsWith("koma-core") }
        check(libraries.isNotEmpty() && libraries.all { it.group == "io.github.koma-kt" && it.version == "4.0.0" }) { "Legacy compilation must use the official stable artifact: $libraries" }
    }
}

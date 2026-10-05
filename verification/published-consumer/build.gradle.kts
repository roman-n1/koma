import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier

plugins {
    id("org.jetbrains.kotlin.multiplatform") apply false
    id("com.android.kotlin.multiplatform.library") apply false
    id("org.jetbrains.compose") apply false
    id("org.jetbrains.kotlin.plugin.compose") apply false
}

val forkGroup = providers.gradleProperty("consumer.group").get()
val forkVersion = providers.gradleProperty("consumer.version").get()
val target = providers.gradleProperty("consumer.target").get()
val suffix = mapOf("jvm" to "jvm", "android" to "android", "js" to "js", "wasm" to "wasm-js", "ios" to "iossimulatorarm64").getValue(target)

// Resolve real published variants. POM/metadata checks alone cannot detect accidental source
// substitution, missing transitive dependencies or debug tooling entering a release graph.
tasks.register("verifyPublishedGraphs") {
    group = "verification"
    doLast {
        val productionNames = setOf("koma-core", "koma-compose", "koma-message", "koma-logging", "koma-observability", "koma-statechart", "koma-statechart-compose")
        val allNames = productionNames + setOf("koma-test", "koma-statechart-test", "koma-timetravel", "koma-timetravel-compose")
        fun modules(projectName: String): Set<String> {
            val configurationName = mapOf("jvm" to "jvmCompileClasspath", "android" to "androidCompileClasspath", "js" to "jsCompileClasspath", "wasm" to "wasmJsCompileClasspath", "ios" to "iosSimulatorArm64CompileKlibraries").getValue(target)
            val configuration = project(projectName).configurations.getByName(configurationName)
            val components = configuration.incoming.resolutionResult.allComponents.map { it.id }
            check(components.none { it is ProjectComponentIdentifier && it.projectPath != projectName }) { "Source project substitution in $projectName" }
            val koma = components.filterIsInstance<ModuleComponentIdentifier>().filter { it.group == forkGroup }
            check(koma.all { it.version == forkVersion }) { "Wrong fork version in $projectName: $koma" }
            return koma.map { it.module.removeSuffix("-$suffix") }.toSet()
        }
        val production = modules(":app")
        check(production == productionNames) { "Published production graph: $production; expected $productionNames" }
        val tooling = modules(":tooling")
        check(tooling + production == allNames) { "Some published modules are unconsumed: ${allNames - tooling - production}" }
        logger.lifecycle("Published $target graphs verified: production=$production; tooling=$tooling")
    }
}

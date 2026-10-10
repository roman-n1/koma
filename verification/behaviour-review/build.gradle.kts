import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins { alias(libs.plugins.kotlinMultiplatform) }
kotlin {
    jvm { compilerOptions { jvmTarget = JvmTarget.JVM_11 } }
    sourceSets.jvmMain.dependencies { implementation(project(":actron-statechart-test")) }
}
val runtime = configurations.named("jvmRuntimeClasspath")
val jar = tasks.named("jvmJar")
val outputDir = layout.buildDirectory.dir("behaviour-review")
tasks.register<JavaExec>("exportBehaviour") {
    group = "verification"
    dependsOn(jar)
    classpath(runtime, jar)
    mainClass.set("actron.review.ReviewKt")
    val directory = outputDir.get().asFile
    outputs.dir(directory)
    workingDir(rootProject.layout.projectDirectory)
    val baseline = rootProject.file(providers.gradleProperty("actron.behaviour.baseline").getOrElse(".actron/baselines/messenger-send.actron.json"))
    inputs.files(provider { if (baseline.isFile) listOf(baseline) else emptyList() })
    args(directory.absolutePath, baseline.absolutePath)
}

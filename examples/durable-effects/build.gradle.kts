plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm()
    sourceSets {
        jvmMain.dependencies {
            implementation(project(":actron-statechart"))
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
        }
        jvmTest.dependencies { implementation(libs.kotlin.test) }
    }
}

val mainCompilation = kotlin.targets.getByName("jvm").compilations.getByName("main")
tasks.register<JavaExec>("run") {
    group = "application"
    mainClass.set("actron.example.durable.MainKt")
    classpath(mainCompilation.output.allOutputs, mainCompilation.runtimeDependencyFiles)
    args(providers.gradleProperty("demoDir").getOrElse("build/durable-effects"))
}

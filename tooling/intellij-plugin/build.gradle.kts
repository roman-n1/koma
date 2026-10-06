import org.jetbrains.intellij.platform.gradle.TestFrameworkType
plugins {
    kotlin("jvm") version "2.3.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}
group = "io.github.roman-n1"
version = "0.1.0"
repositories { mavenCentral(); intellijPlatform { defaultRepositories() } }
dependencies {
    intellijPlatform {
        val localPath = providers.gradleProperty("koma.ide.path").orNull
        if (localPath != null) local(localPath) else intellijIdea("2025.3.3")
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("com.intellij.modules.json")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}
kotlin { jvmToolchain(21) }
intellijPlatform {
    pluginConfiguration {
        id = "io.github.roman-n1.koma"
        name = "Koma Behavioural Model"
        version = "0.1.0"
        ideaVersion { sinceBuild = "253"; untilBuild = provider { null } }
    }
}
tasks.test { systemProperty("java.awt.headless", "true") }

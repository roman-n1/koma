import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.koma.publish)
}
group = "io.github.roman-n1"
version = libs.versions.koma.get()
kotlin {
    android {
        namespace = "koma.diagnostics.crashlytics"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTestBuilder {}.configure {}
        compilerOptions { jvmTarget = JvmTarget.JVM_11 }
    }
    sourceSets {
        commonMain.dependencies { api(project(":koma-diagnostics")) }
        commonTest.dependencies { implementation(libs.kotlin.test) }
        androidMain.dependencies { api("com.google.firebase:firebase-crashlytics:20.0.4") }
        getByName("androidHostTest").dependencies { implementation("org.mockito:mockito-core:5.20.0") }
    }
}
publishConvention { artifactId = "koma-diagnostics-crashlytics" }

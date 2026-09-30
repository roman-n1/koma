import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.vanniktech.mavenPublish) apply false
    alias(libs.plugins.koma.publish) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlinx.bcv)
}

// Public API and ABI of every published module, JVM and klib alike, are dumped under each
// module's api/ directory; `apiCheck` (run by CI) fails when the code and the dumps differ, so a
// change of the public surface is a deliberate `apiDump` in the same change.
@OptIn(kotlinx.validation.ExperimentalBCVApi::class)
apiValidation {
    klib {
        enabled = true
    }
}

// A failed test's assertion message and stack trace reach the console (and so CI's log), not
// only its class and line.
subprojects {
    tasks.withType<AbstractTestTask>().configureEach {
        testLogging {
            events(TestLogEvent.FAILED)
            exceptionFormat = TestExceptionFormat.FULL
            showStackTraces = true
        }
    }
}

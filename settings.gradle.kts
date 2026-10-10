pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Actron"
include(":actron-core")
include(":actron-compose")
include(":actron-logging")
include(":actron-message")
include(":actron-test")
include(":actron-statechart")
include(":actron-observability")
include(":actron-timetravel")
include(":actron-statechart-test")
include(":actron-diagnostics")
include(":actron-diagnostics-sdk")
include(":actron-diagnostics-crashlytics")
include(":actron-timetravel-compose")
include(":actron-statechart-compose")
include(":resource-soak")
project(":resource-soak").projectDir = file("verification/resource-soak")
include(":time-travel-example")
project(":time-travel-example").projectDir = file("examples/time-travel")
include(":durable-effects-example")
project(":durable-effects-example").projectDir = file("examples/durable-effects")

include(":behaviour-review")
project(":behaviour-review").projectDir = file("verification/behaviour-review")

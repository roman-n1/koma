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

rootProject.name = "Koma"
include(":koma-core")
include(":koma-compose")
include(":koma-logging")
include(":koma-message")
include(":koma-test")
include(":koma-statechart")
include(":koma-observability")
include(":koma-timetravel")
include(":koma-statechart-test")
include(":koma-timetravel-compose")
include(":koma-statechart-compose")
include(":time-travel-example")
project(":time-travel-example").projectDir = file("examples/time-travel")
include(":durable-effects-example")
project(":durable-effects-example").projectDir = file("examples/durable-effects")

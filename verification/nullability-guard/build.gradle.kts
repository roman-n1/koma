plugins { java }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:${libs.versions.kotlin.get()}")
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:${libs.versions.kotlin.get()}")
}

val checkerClasspath = sourceSets.main.get().runtimeClasspath

val selfTest by tasks.registering(JavaExec::class) {
    dependsOn(tasks.classes)
    classpath = checkerClasspath
    mainClass.set("actron.verification.NullabilityGuard")
    args("--self-test")
}

tasks.register<JavaExec>("checkPolicy") {
    group = "verification"
    dependsOn(selfTest)
    classpath = checkerClasspath
    mainClass.set("actron.verification.NullabilityGuard")
    workingDir(rootProject.layout.projectDirectory)
    args("--check")
    providers.gradleProperty("actron.nullability.base").orNull?.let { args("--base-ref", it) }
}

tasks.register<JavaExec>("pruneBaseline") {
    group = "verification"
    description = "Removes resolved debt only; cannot add exceptions to the baseline."
    dependsOn(selfTest)
    classpath = checkerClasspath
    mainClass.set("actron.verification.NullabilityGuard")
    workingDir(rootProject.layout.projectDirectory)
    args("--prune")
}

tasks.register<JavaExec>("finishMigration") {
    group = "verification"
    description = "Enables strict policy only after every remaining violation is removed."
    dependsOn(selfTest)
    classpath = checkerClasspath
    mainClass.set("actron.verification.NullabilityGuard")
    workingDir(rootProject.layout.projectDirectory)
    args("--finish")
}

tasks.check { dependsOn(selfTest) }

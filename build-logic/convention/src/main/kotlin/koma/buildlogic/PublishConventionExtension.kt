package koma.buildlogic

import koma.buildlogic.dsl.mavenPublishing
import org.gradle.api.Project

open class PublishConventionExtension {
    @Suppress("MemberVisibilityCanBePrivate")
    var artifactId: String? = null

    internal fun applyToProject(project: Project) {
        project.mavenPublishing {
            coordinates(
                // ПОЧЕМУ: the fork publishes under its own group so its artifacts never shadow
                // official Koma; the modules keep io.github.koma-kt to stay close to upstream.
                groupId = project.findProperty("koma.fork.group")?.toString() ?: project.group.toString(),
                artifactId = artifactId
                    ?: error("`publishConvention.artifactId` don't configure in `${project.path}`."),
                version = project.findProperty("koma.fork.version")?.toString() ?: project.version.toString(),
            )
        }
    }
}


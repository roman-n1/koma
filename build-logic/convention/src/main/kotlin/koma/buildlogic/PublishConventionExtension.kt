package koma.buildlogic

import koma.buildlogic.dsl.mavenPublishing
import org.gradle.api.Project

open class PublishConventionExtension {
    @Suppress("MemberVisibilityCanBePrivate")
    var artifactId: String? = null

    internal fun applyToProject(project: Project) {
        // WHY: the fork publishes under its own group so its artifacts never shadow official
        // Koma; the modules keep io.github.koma-kt in their build files to stay close to
        // upstream. The project's own group and version follow the published coordinates, so a
        // composite build (includeBuild) substitutes the same "group:artifact" a consumer names.
        val groupId = project.findProperty("koma.fork.group")?.toString() ?: project.group.toString()
        val version = project.findProperty("koma.fork.version")?.toString() ?: project.version.toString()
        project.group = groupId
        project.version = version
        project.mavenPublishing {
            coordinates(
                groupId = groupId,
                artifactId = artifactId
                    ?: error("`publishConvention.artifactId` is not configured in `${project.path}`."),
                version = version,
            )
        }
    }
}


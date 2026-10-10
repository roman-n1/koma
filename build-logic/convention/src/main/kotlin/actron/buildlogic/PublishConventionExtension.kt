package actron.buildlogic

import actron.buildlogic.dsl.mavenPublishing
import org.gradle.api.Project

open class PublishConventionExtension {
    @Suppress("MemberVisibilityCanBePrivate")
    var artifactId: String? = null

    internal fun applyToProject(project: Project) {
        // Keep project coordinates aligned with publications for composite build substitution.
        val groupId = project.findProperty("actron.fork.group")?.toString() ?: project.group.toString()
        val version = project.findProperty("actron.fork.version")?.toString() ?: project.version.toString()
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

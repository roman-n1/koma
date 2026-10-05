package koma.buildlogic

import koma.buildlogic.dsl.alias
import koma.buildlogic.dsl.libs
import koma.buildlogic.dsl.mavenPublishing
import koma.buildlogic.dsl.plugin
import koma.buildlogic.publish.pom
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create

@Suppress("unused")
class PublishConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.alias(libs.plugin("vanniktech-mavenPublish"))
            val consumerRepository = findProperty("koma.consumer.repository")?.toString()

            mavenPublishing {
                publishToMavenCentral()

                if (consumerRepository == null && System.getenv("ORG_GRADLE_PROJECT_mavenCentralUsername") != null) {
                    signAllPublications()
                }

                pom(forkUrl = findProperty("koma.fork.url")?.toString())
            }

            // An isolated repository for external-consumer verification. Only explicitly named
            // Consumer publication tasks write here; no MavenLocal/Central uploads are involved.
            if (consumerRepository != null) {
                extensions.configure<PublishingExtension> {
                    repositories.maven {
                        name = "Consumer"
                        url = uri(consumerRepository)
                    }
                }
            }

            val publishConvention = extensions.create("publishConvention", PublishConventionExtension::class)

            afterEvaluate {
                publishConvention.applyToProject(target)
            }
        }
    }
}

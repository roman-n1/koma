package koma.buildlogic.publish

import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.publish.maven.MavenPomDeveloperSpec
import org.gradle.api.publish.maven.MavenPomLicenseSpec

/**
 * @param forkUrl repository of a fork that publishes these artifacts; when set, the POM points
 *   there and lists the fork's maintainer next to koma-kt.
 */
internal fun MavenPublishBaseExtension.pom(forkUrl: String? = null) {
    val repoUrl = forkUrl ?: "https://github.com/koma-kt/koma/"
    val repoPath = repoUrl.removePrefix("https://github.com/").removeSuffix("/")
    pom {
        name.set("Koma")
        description.set("A state management framework for Kotlin Multiplatform.")
        inceptionYear.set("2024")
        url.set(repoUrl)
        developers {
            komaKt()
            if (forkUrl != null) {
                val owner = repoPath.substringBefore("/")
                developer {
                    id.set(owner)
                    name.set(owner)
                    url.set("https://github.com/$owner/")
                }
            }
        }
        licenses {
            mit()
        }
        scm {
            url.set(repoUrl)
            connection.set("scm:git:git://github.com/$repoPath.git")
            developerConnection.set("scm:git:git://github.com/$repoPath.git")
        }
    }
}

private fun MavenPomLicenseSpec.mit() {
    license {
        name.set("MIT")
        url.set("https://opensource.org/licenses/MIT")
        distribution.set("https://opensource.org/licenses/MIT")
    }
}

private fun MavenPomDeveloperSpec.komaKt() {
    developer {
        id.set("koma-kt")
        name.set("koma-kt")
        url.set("https://github.com/koma-kt/")
    }
}

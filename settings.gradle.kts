pluginManagement {
    includeBuild("build-logic")
    repositories {
        val mirror = settings.providers.gradleProperty("songyan.mavenMirror").orNull
        if (mirror != null) {
            maven {
                url = java.net.URI.create(mirror.trimEnd('/') + "/google/")
                isAllowInsecureProtocol = true
            }
            maven {
                url = java.net.URI.create(mirror.trimEnd('/') + "/central/")
                isAllowInsecureProtocol = true
            }
            maven {
                url = java.net.URI.create(mirror.trimEnd('/') + "/plugins/")
                isAllowInsecureProtocol = true
            }
        } else {
            google()
            mavenCentral()
            gradlePluginPortal()
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        val mirror = settings.providers.gradleProperty("songyan.mavenMirror").orNull
        if (mirror != null) {
            maven {
                url = java.net.URI.create(mirror.trimEnd('/') + "/google/")
                isAllowInsecureProtocol = true
            }
            maven {
                url = java.net.URI.create(mirror.trimEnd('/') + "/central/")
                isAllowInsecureProtocol = true
            }
        } else {
            google()
            mavenCentral()
        }
    }
}

rootProject.name = "songyan-ime"

include(
    ":app",
    ":ime",
    ":settings",
    ":core:rime",
    ":core:keyboard",
    ":core:layout",
    ":core:candidate",
    ":core:appearance",
    ":core:content",
    ":core:packages",
    ":baselineprofile",
    ":benchmark",
)

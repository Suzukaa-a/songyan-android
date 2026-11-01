dependencyResolutionManagement {
    repositories {
        val mirror = providers.gradleProperty("songyan.mavenMirror").orNull
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

rootProject.name = "build-logic"

include(":convention")

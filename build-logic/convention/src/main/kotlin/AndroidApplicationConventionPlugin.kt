import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jlleitschuh.gradle.ktlint")
            pluginManager.apply("com.android.application")
            extensions.configure<ApplicationExtension> {
                configureCommon()
                defaultConfig {
                    // Release numbers live in gradle.properties so a release changes one file.
                    versionCode = providers.gradleProperty("songyan.versionCode").map(String::toInt).get()
                    versionName = providers.gradleProperty("songyan.versionName").get()
                }
            }
        }
    }
}
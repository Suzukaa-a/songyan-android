import com.android.build.api.dsl.TestExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** Instrumentation-only modules that drive the app from outside (Macrobenchmark, Baseline Profile). */
class AndroidTestConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jlleitschuh.gradle.ktlint")
            pluginManager.apply("com.android.test")
            extensions.configure<TestExtension> {
                configureCommon()
                targetProjectPath = ":app"
                defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
            }
        }
    }
}

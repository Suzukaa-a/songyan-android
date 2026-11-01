import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Applies the Compose compiler plugin and the shared Compose dependency set.
 * Modules still enable `buildFeatures.compose` in their own android block.
 */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            val handler = dependencies
            handler.add("implementation", handler.platform(catalogLibrary("compose-bom")))
            handler.add("implementation", catalogLibrary("compose-ui"))
            handler.add("implementation", catalogLibrary("compose-ui-graphics"))
            handler.add("implementation", catalogLibrary("compose-ui-tooling-preview"))
            handler.add("debugImplementation", catalogLibrary("compose-ui-tooling"))
        }
    }
}

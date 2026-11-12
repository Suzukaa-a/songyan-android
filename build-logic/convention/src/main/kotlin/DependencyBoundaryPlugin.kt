import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ComponentSelector
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentSelector
import org.gradle.api.artifacts.result.ResolutionResult
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.register

/**
 * "边界由构建看守" for the script platform (PORTING_PLAN.md, P10 / S1).
 *
 * The gate reads the dependency graph Gradle resolves for the real variants, not build scripts or
 * source text. It enforces exactly the two directions the plan asks for:
 *
 *  - :ime must not reach :script, directly or through another module;
 *  - :core:tools must stay a pure JVM module and must not depend on an Android module.
 *
 * A classpath that does not resolve completely fails the gate instead of being read as "no
 * violation": an unresolved edge is exactly where a forbidden dependency would hide.
 *
 * Each rule runs as a task of the project whose classpaths it reads: Gradle 9 only lets a project
 * resolve its own variant classpaths, and a root-level task reading them is refused as unsafe.
 * The root task aggregates them and fails when a checked project is not part of the build.
 */
class DependencyBoundaryPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        val aggregate =
            target.tasks.register<Task>("checkDependencyBoundaries") {
                group = "verification"
                description =
                    "Checks the script platform dependency direction: :ime must not reach :script " +
                        "and :core:tools must stay a pure JVM module."
                notCompatibleWithConfigurationCache("Depends on the per-project checks that resolve the graph.")
                doLast {
                    // A missing project means nothing was checked, so this task must not report success.
                    val missing = listOf(IME, TOOLS).filter { target.findProject(it) == null }
                    if (missing.isNotEmpty()) {
                        throw GradleException(
                            "门禁无法执行：" + missing.joinToString() + " 不在本次构建中，不能报告依赖边界通过。",
                        )
                    }
                    logger.lifecycle("Dependency boundaries: ok")
                }
            }
        target.gradle.projectsEvaluated {
            target.findProject(IME)?.let { ime ->
                val check =
                    ime.tasks.register<Task>("checkDependencyBoundariesIme") {
                        group = "verification"
                        description = ":ime must not depend on $SCRIPT, directly or transitively."
                        notCompatibleWithConfigurationCache("Reads this project's resolved configurations.")
                        doLast { checkIme(ime) }
                    }
                aggregate.configure { dependsOn(check) }
            }
            target.findProject(TOOLS)?.let { tools ->
                val check =
                    tools.tasks.register<Task>("checkDependencyBoundariesTools") {
                        group = "verification"
                        description = "$TOOLS must stay a pure JVM module without Android modules."
                        notCompatibleWithConfigurationCache("Reads this project's resolved configurations.")
                        doLast { checkTools(tools, target) }
                    }
                aggregate.configure { dependsOn(check) }
            }
        }
    }

    private fun checkIme(ime: Project) {
        val offenders = LinkedHashSet<String>()
        classpathConfigurations(ime).forEach { configuration ->
            val result = resolvedClasspath(ime, configuration)
            result.allComponents
                .filter { (it.id as? ProjectComponentIdentifier)?.projectPath == SCRIPT }
                .forEach { offenders.add(configuration.name + ": " + dependencyPath(result, it.id)) }
        }
        if (offenders.isEmpty()) return
        throw GradleException(
            buildString {
                appendLine("$IME 不得依赖脚本实现模块 $SCRIPT（直接或传递；计划 P10 地基规则 4）。")
                offenders.forEach { appendLine("  " + it) }
            },
        )
    }

    private fun checkTools(tools: Project, root: Project) {
        val androidPlugin = ANDROID_PLUGIN_IDS.firstOrNull { tools.pluginManager.hasPlugin(it) }
        if (androidPlugin != null) {
            throw GradleException(
                "$TOOLS 必须是纯 JVM 模块（计划 P10 地基规则 4），但它应用了 Android 插件 $androidPlugin。",
            )
        }
        if (tools.extensions.findByType(JavaPluginExtension::class.java) == null) {
            throw GradleException(
                "$TOOLS 必须是纯 JVM 模块（计划 P10 地基规则 4），但它没有应用 JVM 插件。",
            )
        }
        val offenders = LinkedHashSet<String>()
        classpathConfigurations(tools).forEach { configuration ->
            val result = resolvedClasspath(tools, configuration)
            result.allComponents
                .mapNotNull { component ->
                    val id = component.id as? ProjectComponentIdentifier ?: return@mapNotNull null
                    val dependencyProject = root.findProject(id.projectPath) ?: return@mapNotNull null
                    ANDROID_PLUGIN_IDS.firstOrNull { dependencyProject.pluginManager.hasPlugin(it) }
                        ?.let { plugin ->
                            configuration.name + ": " + dependencyPath(result, id) + "（Android 模块 " + plugin + "）"
                        }
                }
                .forEach { offenders.add(it) }
        }
        if (offenders.isEmpty()) return
        throw GradleException(
            buildString {
                appendLine("$TOOLS 不得依赖 Android 模块（计划 P10 地基规则 4）。")
                offenders.forEach { appendLine("  " + it) }
            },
        )
    }

    /**
     * Resolves a classpath and refuses to continue when part of it is unresolved. Without this an
     * unresolvable edge disappears from [ResolutionResult.getAllComponents] and the gate would report
     * a clean build for a graph it never saw.
     */
    private fun resolvedClasspath(project: Project, configuration: Configuration): ResolutionResult {
        val result = configuration.incoming.resolutionResult
        val unresolved = result.allDependencies.filterIsInstance<UnresolvedDependencyResult>()
        if (unresolved.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine(
                        project.path + " 的 " + configuration.name + " 无法完整解析，门禁无法证明依赖边界：",
                    )
                    unresolved.forEach { dependency ->
                        appendLine("  " + describe(dependency.attempted) + "：" + (dependency.failure.message ?: "未解析"))
                    }
                },
            )
        }
        return result
    }

    /**
     * Compile and runtime classpaths, matched case-insensitively so the plain JVM names
     * (compileClasspath, runtimeClasspath) and the Android variant names (debugCompileClasspath,
     * releaseRuntimeClasspath, ...) are both covered.
     *
     * Android test classpaths are excluded because AGP keeps their resolution under the test task's
     * exclusive lock, which Gradle 9 refuses to hand to another task; annotation processors, lint and
     * compiler-plugin classpaths are not application classpaths at all. JVM test classpaths are
     * lower-case and stay checked.
     */
    private fun classpathConfigurations(project: Project): List<Configuration> =
        project.configurations
            .filter { it.isCanBeResolved && !it.isCanBeConsumed }
            .filter {
                val name = it.name.lowercase()
                name.endsWith("compileclasspath") || name.endsWith("runtimeclasspath")
            }
            .filterNot { it.name.contains("Test") }
            .sortedBy { it.name }

    /** Prints the chain from the checked project to the offending component, for the failure message. */
    private fun dependencyPath(result: ResolutionResult, target: ComponentIdentifier): String {
        val visited = HashSet<ComponentIdentifier>()

        fun walk(component: ResolvedComponentResult): List<String>? {
            if (component.id == target) return listOf(describe(component.id))
            if (!visited.add(component.id)) return null
            component.dependencies.forEach { dependency ->
                if (dependency is ResolvedDependencyResult) {
                    val rest = walk(dependency.selected)
                    if (rest != null) return listOf(describe(component.id)) + rest
                }
            }
            return null
        }

        return walk(result.root)?.joinToString(" -> ") ?: describe(target)
    }

    private fun describe(id: ComponentIdentifier): String =
        when (id) {
            is ProjectComponentIdentifier -> id.projectPath
            is ModuleComponentIdentifier -> id.group + ":" + id.module + ":" + id.version
            else -> id.displayName
        }

    private fun describe(selector: ComponentSelector): String =
        when (selector) {
            is ProjectComponentSelector -> selector.projectPath
            is ModuleComponentSelector -> selector.group + ":" + selector.module + ":" + selector.version
            else -> selector.displayName
        }

    private companion object {
        const val IME = ":ime"
        const val TOOLS = ":core:tools"
        const val SCRIPT = ":script"
        val ANDROID_PLUGIN_IDS =
            listOf(
                "com.android.base",
                "com.android.application",
                "com.android.library",
                "com.android.test",
                "com.android.dynamic-feature",
            )
    }
}

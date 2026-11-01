import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

internal fun Project.catalogLibrary(alias: String): Provider<MinimalExternalModuleDependency> =
    extensions.getByType<VersionCatalogsExtension>().named("libs").findLibrary(alias)
        .orElseThrow { IllegalStateException("Missing version catalog library: $alias") }

internal fun Project.versions(): VersionCatalogsExtension =
    extensions.getByType<VersionCatalogsExtension>()

internal fun Project.catalogVersion(alias: String): String =
    versions().named("libs").findVersion(alias)
        .orElseThrow { IllegalStateException("Missing version catalog version: $alias") }
        .requiredVersion

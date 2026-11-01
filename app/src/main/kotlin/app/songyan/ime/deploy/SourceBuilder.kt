package app.songyan.ime.deploy

import app.songyan.ime.packages.DictionaryImportBuilder
import app.songyan.ime.packages.DictionarySourceFile
import app.songyan.ime.packages.SchemePackageImportBuilder
import app.songyan.ime.rime.AssetTree
import app.songyan.ime.rime.BuiltinSourceInstaller
import app.songyan.ime.rime.readAllFiles
import app.songyan.ime.storage.BuiltinSchemes
import app.songyan.ime.storage.SourceIds
import app.songyan.ime.storage.SourceKind
import app.songyan.ime.storage.SourceManifest
import app.songyan.ime.storage.SourceScheme
import java.io.File

/** A complete source tree written to a staging directory, plus what it is. */
class BuiltSource(
    val directory: File,
    val manifest: SourceManifest,
)

/**
 * Turns import input (dictionary files, a scheme package ZIP) into complete Rime source trees.
 * Runs in the :deploy process only; throws the core builders' errors unchanged so the caller can
 * report the precise reason.
 */
class SourceBuilder(
    /** The bundled `rime/` asset tree. */
    private val assets: AssetTree,
) {
    private val installer = BuiltinSourceInstaller(assets)

    /** The built-in templates an imported dictionary is layered over, keyed by file name. */
    private fun templates(): Map<String, ByteArray> {
        val files = assets.readAllFiles(TEMPLATE_ROOT).mapKeys { it.key.removePrefix("$TEMPLATE_ROOT/") }
        if (files.isEmpty()) throw DeployException("the built-in template assets are missing")
        return files
    }

    fun buildDictionary(
        sources: List<DictionarySourceFile>,
        target: File,
        cancelled: () -> Boolean = { false },
    ): BuiltSource {
        val prepared = DictionaryImportBuilder.prepare(sources, templates(), target, cancelled = cancelled)
        installer.supplementFrontendFiles(target)
        return BuiltSource(
            directory = target,
            manifest =
                SourceManifest(
                    sourceId = SourceIds.DICTIONARY,
                    kind = SourceKind.DICTIONARY,
                    displayName = prepared.displayName,
                    schemes = BuiltinSchemes.IDS.map { SourceScheme(it, it) },
                    revision = prepared.revision,
                    entryCount = prepared.entryCount,
                ),
        )
    }

    /** Rebuilds the stored dictionary source from the originals it keeps, with the current templates. */
    fun rebuildDictionary(
        storedSource: File,
        target: File,
    ): BuiltSource? {
        val originals = File(storedSource, DictionaryImportBuilder.ORIGINALS_DIRECTORY)
        if (!originals.isDirectory) return null
        val sources =
            originals
                .walkTopDown()
                .filter { it.isFile }
                .map { DictionarySourceFile(it.relativeTo(originals).invariantSeparatorsPath, it) }
                .sortedBy { it.path }
                .toList()
        if (sources.isEmpty()) return null
        return buildDictionary(sources, target)
    }

    /** Checks dictionary files and writes the dictionary stacked onto a scheme package into [target]. */
    fun buildStack(
        sources: List<DictionarySourceFile>,
        target: File,
        cancelled: () -> Boolean = { false },
    ): File {
        DictionaryImportBuilder.prepareStack(sources, target, cancelled = cancelled)
        return target
    }

    /**
     * [sourceId] installs the package under an existing id instead of its content hash: an update
     * of that package, whose learning data is keyed by the id. [origin] is the link it came from.
     */
    fun buildScheme(
        archive: File,
        target: File,
        cancelled: () -> Boolean = { false },
        sourceId: String? = null,
        origin: String? = null,
    ): BuiltSource {
        val prepared =
            SchemePackageImportBuilder.prepare(
                archive = archive,
                target = target,
                supplement = installer::supplementFrontendFiles,
                cancelled = cancelled,
            )
        return BuiltSource(
            directory = target,
            manifest =
                SourceManifest(
                    sourceId = sourceId ?: prepared.packageId,
                    kind = SourceKind.SCHEME,
                    displayName = prepared.displayName,
                    schemes = prepared.schemes.map { SourceScheme(it.schemaId, it.name) },
                    revision = prepared.revision,
                    origin = origin,
                ),
        )
    }

    private companion object {
        const val TEMPLATE_ROOT = "builtin"
    }
}

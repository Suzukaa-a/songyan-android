package app.songyan.ime.deploy

import app.songyan.ime.content.ImportedDictionaryMetadataReader
import app.songyan.ime.packages.CompactSchemaAdapter
import app.songyan.ime.packages.DictionaryImportBuilder
import app.songyan.ime.packages.DictionaryStacking
import app.songyan.ime.packages.EngineFeatureSettings
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.SourceKind
import app.songyan.ime.storage.SourceManifest
import app.songyan.ime.userconfig.UserConfigFiles
import app.songyan.ime.userconfig.UserScripts
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Files the app adds to one deployment without editing the stored source tree.
 *
 * They are written into the deployment's empty work directory, which librime resolves before the
 * source tree (user data directory over shared data directory, for configs and dictionary sources
 * alike). The stored tree stays exactly what the user imported, so a package update or re-import
 * gets the overlay computed again from its new files, and removing a feature is a redeploy without
 * it. The overlay carries the user's own configuration files, a dictionary stacked onto a scheme package, the engine feature
 * patches ([EngineFeatures]) and the generated compact-layout companions of a package's input
 * methods ([KeyboardCompanions]).
 */
internal class DeployOverlay(
    private val storage: AppStorage,
) {
    /**
     * Identity of [manifest]'s overlay with [features]. It is part of the published revision, so a
     * stored generation is only reused when it was built with the same overlay.
     */
    fun digest(
        manifest: SourceManifest,
        features: EngineFeatureSettings,
        config: File? = null,
    ): String {
        val parts = ArrayList<String>()
        parts += "features:" + hex(MessageDigest.getInstance("SHA-256").digest(features.encode().toByteArray()))
        parts += "companions:" + CompactSchemaAdapter.DISPLAY_GENERATOR_VERSION
        config?.let { UserConfigFiles.digest(it, excluded(manifest, it)) }?.let { parts += "config:$it" }
        stackOf(manifest)?.let { stack ->
            val revision = ImportedDictionaryMetadataReader.read(stack)?.revision ?: sha256(File(stack, STACKED_FILE))
            parts += "stack:$revision"
        }
        return hex(
            MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\n").toByteArray()),
        ).take(DIGEST_LENGTH)
    }

    /**
     * Writes [manifest]'s overlay into [workDir]: the user's own configuration files from [config]
     * first, as they would sit in a desktop Rime user folder, then what the app adds. Returns the
     * copied configuration files. Throws [DictionaryStacking.StackingError] when a stacked
     * dictionary cannot be applied to this tree; nothing is deployed then.
     */
    fun write(
        sourceDir: File,
        manifest: SourceManifest,
        workDir: File,
        config: File? = null,
    ): List<File> {
        val configured = config?.let { UserConfigFiles.copyInto(it, workDir, excluded(manifest, it)) }.orEmpty()
        stackOf(manifest)?.let { writeStack(sourceDir, manifest, it, workDir) }
        return configured
    }

    /**
     * The files of the applied configuration that hold a script the user has not confirmed, against
     * the files of this very source (a copy identical to another source's own is not identical to
     * this one's). They are left out, so the source's own files are used: a restored backup or a
     * file that changed behind the user's back never runs a script nobody agreed to.
     */
    fun excluded(
        manifest: SourceManifest,
        config: File,
    ): Set<String> =
        UserScripts(storage)
            .unconfirmed(config, storage.sourceDir(manifest.sourceId).takeIf { it.isDirectory })
            .map { it.path }
            .toSet()

    /** The stacked dictionary of a scheme package, or null. */
    fun stackOf(manifest: SourceManifest): File? {
        if (manifest.kind != SourceKind.SCHEME) return null
        val dir = storage.stackedDictionaryDir(manifest.sourceId)
        return dir.takeIf { File(it, STACKED_FILE).isFile }
    }

    private fun writeStack(
        sourceDir: File,
        manifest: SourceManifest,
        stack: File,
        workDir: File,
    ) {
        if (File(sourceDir, STACKED_FILE).exists()) {
            throw DictionaryStacking.StackingError(DictionaryStacking.Refusal.NAME_TAKEN, STACKED_FILE)
        }
        val schemas =
            manifest.schemaIds
                .associateWith { File(sourceDir, "$it.schema.yaml") }
                .filterValues { it.isFile && it.length() <= MAX_SCHEMA_BYTES }
                .mapValues { it.value.readBytes() }
        val dictionaries =
            DictionaryStacking
                .mainDictionaries(schemas)
                .values
                .distinct()
                .filter { File(sourceDir, DictionaryStacking.dictionaryFile(it)).isFile }
        if (dictionaries.isEmpty()) {
            throw DictionaryStacking.StackingError(DictionaryStacking.Refusal.NO_DICTIONARY, manifest.sourceId)
        }
        for (name in dictionaries) {
            val file = DictionaryStacking.dictionaryFile(name)
            val target = File(workDir, file)
            // The user's own copy of the dictionary, when there is one, is what librime reads.
            val own = File(workDir, ".own-$file").takeIf { target.isFile && target.renameTo(it) }
            try {
                DictionaryStacking.writeStacked(own ?: File(sourceDir, file), target)
            } finally {
                own?.delete()
            }
        }
        link(File(stack, STACKED_FILE), File(workDir, STACKED_FILE))
    }

    /** A hard link costs nothing for a large dictionary; a copy is the fallback. */
    private fun link(
        existing: File,
        target: File,
    ) {
        target.parentFile?.mkdirs()
        try {
            Files.createLink(target.toPath(), existing.toPath())
        } catch (_: IOException) {
            existing.copyTo(target, overwrite = true)
        } catch (_: UnsupportedOperationException) {
            existing.copyTo(target, overwrite = true)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }

    companion object {
        /** The stacked dictionary's file in its directory and in the work directory. */
        val STACKED_FILE: String = DictionaryStacking.dictionaryFile(DictionaryImportBuilder.DICTIONARY_NAME)
        private const val DIGEST_LENGTH = 16
        private const val MAX_SCHEMA_BYTES = 4L * 1024 * 1024
        private const val BUFFER_BYTES = 64 * 1024
    }
}

package app.songyan.ime.storage

import app.songyan.ime.packages.EngineFeatureSettings
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * [EngineFeatureSettings] on disk, shared by the settings screen (main process, writes) and the
 * deploy process (reads), with the files the settings name.
 *
 * Both sides take [lock] around anything that touches the feature files: the writer while it adds
 * a file and switches the settings to it (then deletes what nothing names any more), the deployment
 * while it reads the settings and links the named files into its work directory. So a deployment
 * never sees settings that name a file that is not there yet, or a file deleted under it.
 */
class EngineFeatureStore(
    private val storage: AppStorage,
) {
    /** The current settings; defaults when none were saved or the file is unreadable. */
    fun read(): EngineFeatureSettings {
        val file = storage.featureSettingsFile
        val text =
            try {
                if (file.isFile) file.readText(Charsets.UTF_8) else null
            } catch (_: IOException) {
                null
            }
        return EngineFeatureSettings.decode(text)
    }

    /** Writes [settings] atomically. Call inside [lock]. */
    fun write(settings: EngineFeatureSettings) {
        storage.featuresDir.mkdirs()
        val temp = File(storage.featuresDir, ".settings-" + System.nanoTime() + ".tmp")
        try {
            FileOutputStream(temp).use { out ->
                out.write(settings.encode().toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            Files.move(
                temp.toPath(),
                storage.featureSettingsFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temp.delete()
        }
    }

    /** The stored prediction database [settings] names, or null. */
    fun predictionDatabase(settings: EngineFeatureSettings): File? {
        val stored = settings.predictionDatabase ?: return null
        return storage.predictionDatabaseFile(stored.name).takeIf { it.isFile && it.length() == stored.size }
    }

    /** The stored grammar model named [name] in [settings], or null. */
    fun grammarModel(
        settings: EngineFeatureSettings,
        name: String,
    ): File? {
        val stored = settings.grammarModels.firstOrNull { it.name == name } ?: return null
        return storage.grammarModelFile(stored.name).takeIf { it.isFile && it.length() == stored.size }
    }

    /** Deletes stored feature files [settings] does not name (and abandoned temporary files). Call inside [lock]. */
    fun deleteUnnamed(settings: EngineFeatureSettings) {
        val database = settings.predictionDatabase?.name
        for (file in storage.featuresDir.listFiles().orEmpty()) {
            if (!file.isFile || file == storage.featureSettingsFile || file.name == LOCK_FILE) continue
            if (file.name != database) file.delete()
        }
        val models = settings.grammarModels.map { it.name }.toSet()
        for (file in storage.grammarModelsDir.listFiles().orEmpty()) {
            if (file.isFile && file.name !in models) file.delete()
        }
    }

    /** Runs [block] holding the cross-process feature lock. */
    fun <T> lock(block: () -> T): T {
        storage.featuresDir.mkdirs()
        FileChannel
            .open(
                File(storage.featuresDir, LOCK_FILE).toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            ).use { channel ->
                channel.lock().use { return block() }
            }
    }

    companion object {
        private const val LOCK_FILE = "features.lock"
    }
}

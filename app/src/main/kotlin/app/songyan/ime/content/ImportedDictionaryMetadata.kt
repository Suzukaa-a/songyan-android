package app.songyan.ime.content

import java.io.File
import java.io.IOException

/** What the generated user-dictionary.json says about the imported dictionary. */
internal data class ImportedDictionaryMetadata(
    val displayName: String,
    val entryCount: Int,
    val revision: String? = null,
)

/**
 * Reads the metadata file the core import builder writes into a staged source tree.
 *
 * The file is app-generated, small and has a fixed shape, so a tiny field reader keeps this path
 * free of a JSON dependency and unit-testable on the JVM (the android.jar JSON stubs are not).
 */
internal object ImportedDictionaryMetadataReader {
    private const val METADATA_FILE = "user-dictionary.json"
    private val REVISION = Regex("^[0-9a-f]{64}$")

    fun read(sourceDir: File): ImportedDictionaryMetadata? {
        val file = File(sourceDir, METADATA_FILE)
        if (!file.isFile) return null
        val text =
            try {
                file.readText()
            } catch (_: IOException) {
                return null
            }
        val displayName = stringField(text, "displayName")?.takeIf { it.isNotBlank() } ?: return null
        val entryCount = intField(text, "entryCount")?.takeIf { it >= 0 } ?: return null
        val revision = stringField(text, "revision")?.takeIf { REVISION.matches(it) }
        return ImportedDictionaryMetadata(displayName, entryCount, revision)
    }

    private fun stringField(
        json: String,
        key: String,
    ): String? = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"([^\"\\\\]*)\"").find(json)?.groupValues?.get(1)

    private fun intField(
        json: String,
        key: String,
    ): Int? =
        Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(-?[0-9]+)")
            .find(json)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
}

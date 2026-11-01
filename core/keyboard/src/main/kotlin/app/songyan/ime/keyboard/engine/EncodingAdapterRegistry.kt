package app.songyan.ime.keyboard.engine

import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The compact-layout companions one deployed build provides for its source schemes, written by the
 * deployment next to the compiled schemas and read by the keyboard with that same build.
 *
 * The companions are compiled but not listed in `schema_list`, so this file is the only place the
 * keyboard learns which engine schema runs a source scheme on 14, 18 or 9 keys.
 */
object EncodingAdapterRegistry {
    /** File name inside a build directory. */
    const val FILE_NAME = "keyboard-adapters.json"

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(KeyboardResourceSet.EncodingAdapter.serializer())
    private val SAFE_ID = Regex("^[A-Za-z0-9_.-]{1,128}$")
    private const val MAX_BYTES = 256L * 1024

    fun encode(adapters: List<KeyboardResourceSet.EncodingAdapter>): String = json.encodeToString(serializer, adapters)

    /** The entries of [text], or null when it is not a registry. */
    fun decode(text: String): List<KeyboardResourceSet.EncodingAdapter>? =
        try {
            json.decodeFromString(serializer, text)
        } catch (_: IllegalArgumentException) {
            null
        }

    /**
     * The usable entries of [compiledDir]'s registry for [sources]: each names one of those source
     * schemes and a compact layout, and runs either the source itself or a schema compiled into
     * this build. Anything else is dropped, so a layout without a proven companion stays unoffered.
     * Reads the disk; call off the main thread.
     */
    fun read(
        compiledDir: File,
        sources: Set<String>,
    ): List<KeyboardResourceSet.EncodingAdapter> {
        val file = File(compiledDir, FILE_NAME)
        if (!file.isFile || file.length() > MAX_BYTES) return emptyList()
        val entries = decode(file.readText(Charsets.UTF_8)) ?: return emptyList()
        val seen = HashSet<Pair<String, KeyboardLayoutKind>>()
        return entries.filter { entry ->
            entry.layout != KeyboardLayoutKind.QWERTY26 &&
                entry.sourceSchemaID in sources &&
                SAFE_ID.matches(entry.schemaID) &&
                (
                    entry.schemaID == entry.sourceSchemaID ||
                        (entry.schemaID !in sources && File(compiledDir, entry.schemaID + ".schema.yaml").isFile)
                ) &&
                seen.add(entry.sourceSchemaID to entry.layout)
        }
    }
}

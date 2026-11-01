package app.songyan.ime.packages

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Stacks the user's imported dictionary onto an imported scheme package.
 *
 * librime merges the tables a dictionary lists in `import_tables` into it, one level deep, and
 * reads every merged row with the main dictionary's columns. So the package's own main dictionary
 * (the primary translator's `dictionary`) gets [DictionaryImportBuilder.DICTIONARY_NAME] appended
 * to its `import_tables`; its name, prism and user dictionary stay the same, so the input method
 * keeps its learning. The package files are never edited: the patched copy of the dictionary is
 * deployed from the work directory, which librime resolves before the package directory, so a
 * package update or re-import simply gets the patch computed again from its new files.
 */
object DictionaryStacking {
    /** Why a package cannot carry a stacked dictionary. */
    enum class Refusal {
        /** No input method of the package names its dictionary where this can find it. */
        NO_DICTIONARY,

        /** The dictionary's header is not plain enough to patch safely. */
        UNSUPPORTED_HEADER,

        /** The dictionary's columns are not word / code / weight. */
        UNSUPPORTED_COLUMNS,

        /** The package already uses the imported dictionary's name. */
        NAME_TAKEN,
    }

    class StackingError(
        val refusal: Refusal,
        detail: String,
    ) : Exception(refusal.name + ": " + detail)

    /**
     * The main dictionary of each schema in [schemas] (schema id to `*.schema.yaml` bytes): the
     * primary script or table translator's `dictionary`. Schemas whose dictionary cannot be read
     * statically (no primary producer, a dictionary set only through `__include` / `__patch`) are
     * left out; they deploy unchanged.
     */
    fun mainDictionaries(schemas: Map<String, ByteArray>): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for ((schemaId, bytes) in schemas) {
            val root = decodeUtf8(bytes)?.let { runCatching { YamsYaml.loadMap(it) }.getOrNull() } ?: continue
            val translators = root["engine"].asStringMap()?.get("translators").asStringList() ?: continue
            val producer = CompactSchemaAdapter.primaryProducer(translators) ?: continue
            val dictionary = root[producer.namespace].asStringMap()?.get("dictionary") as? String ?: continue
            if (!DICTIONARY_NAME_PATTERN.matches(dictionary) || dictionary.contains("..")) continue
            result[schemaId] = dictionary
        }
        return result
    }

    /** Relative path of a dictionary's source file. */
    fun dictionaryFile(name: String): String = name + DICTIONARY_SUFFIX

    /**
     * Copies [input] (one `*.dict.yaml`) to [output] with [DictionaryImportBuilder.DICTIONARY_NAME]
     * added to its `import_tables`. Only the header is rewritten; rows are copied byte for byte.
     * Throws [StackingError] before writing anything when the header cannot be patched safely.
     */
    fun writeStacked(
        input: InputStream,
        output: OutputStream,
    ) {
        val stream = BufferedInputStream(input)
        val header = readHeader(stream)
        output.write(patchHeader(header).toByteArray(StandardCharsets.UTF_8))
        stream.copyTo(output)
    }

    /** [writeStacked] between files. */
    fun writeStacked(
        input: File,
        output: File,
    ) {
        output.parentFile?.mkdirs()
        input.inputStream().use { source -> output.outputStream().use { writeStacked(source, it) } }
    }

    /**
     * The header (everything up to and including the `...` line) with the stacked dictionary
     * added to `import_tables`. The edit is textual so comments and formatting survive, and it is
     * verified by reading both headers back: every other setting must be unchanged.
     */
    fun patchHeader(header: String): String {
        val lines = splitLinesKeepingEmpty(header).toMutableList()
        if (lines.lastOrNull() == "") lines.removeAt(lines.size - 1)
        val end = lines.indexOfLast { it == HEADER_END }
        if (end < 0) throw StackingError(Refusal.UNSUPPORTED_HEADER, "no closing ...")
        val before = settings(lines.subList(0, end))
        val existing =
            when (val tables = before["import_tables"]) {
                null -> emptyList()
                is List<*> -> tables.map { it as? String ?: throw unsupported("import_tables entry") }
                else -> throw unsupported("import_tables is not a list")
            }
        if (STACKED in existing || before["name"] == STACKED) {
            throw StackingError(Refusal.NAME_TAKEN, STACKED)
        }
        val columns = before["columns"]
        if (columns != null) {
            val names = (columns as? List<*>)?.map { it as? String }
            if (names == null ||
                names.size < 2 ||
                names[0] != "text" ||
                names[1] != "code" ||
                (names.size > 2 && names[2] != "weight")
            ) {
                throw StackingError(Refusal.UNSUPPORTED_COLUMNS, columns.toString())
            }
        }
        val key = lines.indexOfFirst { it.startsWith(IMPORT_TABLES_KEY) }
        if (key in 0 until end) {
            val rest = lines[key].substring(IMPORT_TABLES_KEY.length).substringBefore(" #").trim()
            if (rest.isEmpty()) {
                // Block list: append after its last item, at the item's indentation.
                var last = key
                var indent = "  "
                for (index in key + 1 until end) {
                    val line = lines[index]
                    val trimmed = line.trimStart()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                    if (!line.startsWith(" ") && !line.startsWith("-")) break
                    if (!trimmed.startsWith("- ")) throw unsupported("import_tables item")
                    last = index
                    indent = line.substring(0, line.length - trimmed.length)
                }
                lines.add(last + 1, "$indent- $STACKED")
            } else if (rest.startsWith("[") && rest.endsWith("]")) {
                val inner = rest.substring(1, rest.length - 1).trim()
                lines[key] = IMPORT_TABLES_KEY + " [" + (if (inner.isEmpty()) STACKED else "$inner, $STACKED") + "]"
            } else {
                throw unsupported("import_tables form")
            }
        } else {
            lines.add(end, "$IMPORT_TABLES_KEY\n  - $STACKED")
        }
        val patched = lines.joinToString("\n") + "\n"
        val patchedLines = splitLinesKeepingEmpty(patched).dropLast(1)
        val after = settings(patchedLines.subList(0, patchedLines.indexOfLast { it == HEADER_END }))
        val expected = LinkedHashMap(before).apply { this["import_tables"] = existing + STACKED }
        if (after != expected) throw unsupported("patched header reads differently")
        return patched
    }

    /** Header lines up to and including `...`; bounded so a file without a header is refused quickly. */
    private fun readHeader(stream: BufferedInputStream): String {
        val bytes = ByteArrayOutputStream()
        var lineStart = 0
        var lineCount = 0
        while (true) {
            val next = stream.read()
            if (next < 0) throw unsupported("no closing ...")
            bytes.write(next)
            if (bytes.size() > MAX_HEADER_BYTES) throw unsupported("header too long")
            if (next == '\n'.code) {
                val all = bytes.toByteArray()
                val line = String(all, lineStart, all.size - lineStart - 1, StandardCharsets.UTF_8).trimEnd('\r')
                if (line == HEADER_END) return String(all, StandardCharsets.UTF_8).replace("\r\n", "\n")
                lineStart = all.size
                if (++lineCount > MAX_HEADER_LINES) throw unsupported("header too long")
            }
        }
    }

    private fun settings(lines: List<String>): Map<String, Any?> {
        val body = lines.dropWhile { it.trim() != "---" && (it.isBlank() || it.startsWith("#")) }
        val text = body.filter { it.trim() != "---" }.joinToString("\n") + "\n"
        return runCatching { YamsYaml.loadMap(text) }.getOrNull() ?: throw unsupported("header is not a map")
    }

    private fun unsupported(detail: String) = StackingError(Refusal.UNSUPPORTED_HEADER, detail)

    private val STACKED = DictionaryImportBuilder.DICTIONARY_NAME
    private const val IMPORT_TABLES_KEY = "import_tables:"
    private const val HEADER_END = "..."
    private const val DICTIONARY_SUFFIX = ".dict.yaml"
    private const val MAX_HEADER_LINES = 4096
    private const val MAX_HEADER_BYTES = 1024 * 1024
    private val DICTIONARY_NAME_PATTERN =
        Regex("^[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}(/[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}){0,3}$")
}

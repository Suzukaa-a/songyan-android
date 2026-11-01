package app.songyan.ime.packages

import app.songyan.ime.keyboard.layout.BuiltinTemplateSchemas
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer

/**
 * One rejected dictionary import.
 *
 * [reason] is the stable contract the app layer maps to user-facing copy; [detail] is
 * developer-facing context (file name, line, offending token) and is never shown verbatim.
 * [paths] names the files a [Reason.MISSING_DEPENDENCIES] still needs.
 */
class DictionaryImportError(
    val reason: Reason,
    val detail: String? = null,
    val paths: List<String> = emptyList(),
) : Exception(if (detail == null) reason.name else reason.name + ": " + detail) {
    /** Why an import was rejected. Values are stable API for the app module. */
    enum class Reason {
        /** The selected file name does not end with `.dict.yaml`. */
        UNSUPPORTED_FILE_TYPE,

        /** The selected file name is absolute, too long or contains path escapes. */
        UNSAFE_FILE_NAME,

        /** The bytes are not valid UTF-8, or contain a NUL. */
        INVALID_ENCODING,

        /** The input exceeds the byte cap. */
        FILE_TOO_LARGE,

        /** More files were selected than one import takes. */
        TOO_MANY_FILES,

        /** Two selected files have the same path. */
        DUPLICATE_FILE,

        /** No `...` line closes the header within the first lines. */
        MISSING_HEADER_END,

        /** A header line uses YAML the strict reader does not support. */
        UNSUPPORTED_YAML,

        /** The header does not declare a usable dictionary name. */
        MISSING_NAME,

        /** The dictionary depends on a preset vocabulary. */
        UNSUPPORTED_PRESET_VOCABULARY,

        /** The dictionary is not sorted by weight; entry order would change its meaning. */
        UNSUPPORTED_SORT_ORDER,

        /** The main dictionary pulls in dictionaries (`import_tables`) that were not selected. */
        MISSING_DEPENDENCIES,

        /** The selected dictionaries import each other in a cycle. */
        DEPENDENCY_CYCLE,

        /** A selected file is neither the main dictionary nor one it imports. */
        UNREFERENCED_FILE,

        /** The declared columns are not the word/pinyin/weight triple this importer understands. */
        UNSUPPORTED_COLUMNS,

        /** An entry row has the wrong number of columns. */
        INVALID_ROW,

        /** An entry word is empty or contains a character outside the accepted ideographs. */
        NON_HAN_WORD,

        /** The number of pinyin syllables does not match the number of word characters. */
        SYLLABLE_COUNT_MISMATCH,

        /** A pinyin reading is not a plain full-pinyin syllable. */
        UNKNOWN_SYLLABLE,

        /** A weight is malformed or too long. */
        INVALID_WEIGHT,

        /** The dictionary declares no entries. */
        EMPTY_DICTIONARY,

        /** The dictionary has more entries than the cap. */
        TOO_MANY_ENTRIES,

        /** The built-in templates are not the shape this importer can patch. */
        INCOMPATIBLE_TEMPLATE,
    }
}

/**
 * One selected dictionary file: [path] is the relative path Rime would know it by (what other
 * dictionaries name in `import_tables`), [file] holds its bytes on disk.
 */
data class DictionarySourceFile(
    val path: String,
    val file: File,
)

/** What the header of one dictionary declares. */
data class DictionarySourceInspection(
    val path: String,
    val name: String,
    /** Relative `.dict.yaml` paths this dictionary imports, in declaration order. */
    val dependencies: List<String>,
    val byteCount: Long,
)

/**
 * A checked dictionary import, written as one complete Rime source directory at [root].
 *
 * [revision] is the SHA-256 of the dictionary bytes for a single file, and of the ordered
 * `path<TAB>sha256` list for several, so re-importing identical files yields an identical source
 * tree and generation.
 */
data class PreparedDictionaryImport(
    val root: File,
    val entryCount: Int,
    val dictionaryName: String,
    val displayName: String,
    val sourceId: String,
    val revision: String,
    val schemaId: String,
    val sha256: String,
    /** The selected files in merge order: every dictionary after the ones it imports. */
    val sources: List<DictionarySourceInspection>,
)

/** Import caps; tests lower them to exercise the boundaries without allocating huge inputs. */
class DictionaryImportLimits(
    val byteCap: Long = DictionaryImportBuilder.MAX_BYTE_COUNT,
    val entryCap: Int = DictionaryImportBuilder.MAX_ENTRY_COUNT,
    val fileCap: Int = DictionaryImportBuilder.MAX_FILE_COUNT,
)

/**
 * Builds a self-contained Rime source from user dictionaries plus the app's built-in templates.
 *
 * Ported from iOS `DictionaryImport.swift` / `PinyinDictionarySource`. The dictionary reader is
 * intentionally a strict YAML subset: anything it does not understand is rejected, so an accepted
 * dictionary keeps its meaning after the pinyin rewrite. Imported bytes never become configuration
 * or code. Several files are merged in `import_tables` order (dependencies first); rows are
 * streamed from disk to disk, so a large dictionary is never held in memory, and the original files
 * are kept beside the generated source under `import-sources/` with their notices.
 */
object DictionaryImportBuilder {
    /** Resource-set id of the single user dictionary slot. */
    const val SOURCE_ID: String = "user-pinyin"

    /** Scheme the imported dictionary is paired with (same as the iOS `BuiltinTemplateSchemas.quanpin`). */
    const val SCHEMA_ID: String = BuiltinTemplateSchemas.QUANPIN

    /** Rime dictionary name the imported entries are written under. */
    const val DICTIONARY_NAME: String = "songyan_imported"

    /** Metadata file the app reads back to describe the imported dictionary. */
    const val METADATA_FILE: String = "user-dictionary.json"

    /** Origin record of the selected files. */
    const val ORIGIN_FILE: String = "import.json"

    /** Directory the selected files are preserved in, byte for byte. */
    const val ORIGINALS_DIRECTORY: String = "import-sources"

    /** Notice staged next to the imported data. */
    const val LICENSE_FILE: String = "LICENSE"

    /** Largest accepted total size of the selected files, as on iOS. */
    const val MAX_BYTE_COUNT: Long = 64L * 1024 * 1024

    /** Most files one import may select, as on iOS. */
    const val MAX_FILE_COUNT: Int = 128

    /** Largest accepted entry count. */
    const val MAX_ENTRY_COUNT: Int = 2_000_000

    /**
     * How many header lines may precede the closing `...`. Real dictionaries (rime-ice) carry
     * multi-thousand-line comment headers, so the bound is generous but still finite.
     */
    const val MAX_HEADER_LINES: Int = 4096

    /** Longest accepted UTF-8 length of a dictionary file name. */
    const val MAX_FILE_NAME_BYTES: Int = 240

    /** The relative file a dictionary name in `import_tables` refers to. */
    fun dependencyPath(name: String): String = name + DICTIONARY_SUFFIX

    /** Reads only the header of [source]; used to discover what else an import needs. */
    fun inspect(source: DictionarySourceFile): DictionarySourceInspection {
        validateDictionaryFileName(source.path)
        return openReader(source).use { reader -> readHeader(reader, source).inspection }
    }

    /**
     * Checks [sources] and writes the complete Rime source that layers the merged dictionary over
     * the built-in starter dictionary into [target] (created; must be empty or absent).
     *
     * [builtin] is the app's read-only built-in template directory (file name to bytes): every
     * Rime config and dictionary file is copied through verbatim, and every `*.schema.yaml` has its
     * dictionary reference repointed at [DICTIONARY_NAME]. The built-in starter dictionary stays in
     * the source because the generated dictionary imports it: an imported dictionary augments the
     * built-in words instead of replacing them. The package manager's `manifest.yaml` is skipped:
     * it describes the built-in package, and a user dictionary source is not one.
     *
     * Exactly one selected file may be unreferenced by the others: that is the main dictionary.
     * Throws [DictionaryImportError]; the output is deterministic for identical inputs.
     */
    fun prepare(
        sources: List<DictionarySourceFile>,
        builtin: Map<String, ByteArray>,
        target: File,
        limits: DictionaryImportLimits = DictionaryImportLimits(),
        cancelled: () -> Boolean = { false },
    ): PreparedDictionaryImport = write(sources, templateFiles(builtin), target, limits, cancelled, layered = true)

    /**
     * Checks [sources] and writes only the merged dictionary [DICTIONARY_NAME] (without importing
     * the built-in starter dictionary), its originals, notice and metadata into [target]: the part
     * that is stacked onto an imported scheme package's own dictionary (see
     * [DictionaryStacking]). Same checks and errors as [prepare].
     */
    fun prepareStack(
        sources: List<DictionarySourceFile>,
        target: File,
        limits: DictionaryImportLimits = DictionaryImportLimits(),
        cancelled: () -> Boolean = { false },
    ): PreparedDictionaryImport = write(sources, emptyMap(), target, limits, cancelled, layered = false)

    private fun write(
        sources: List<DictionarySourceFile>,
        templates: Map<String, ByteArray>,
        target: File,
        limits: DictionaryImportLimits,
        cancelled: () -> Boolean,
        layered: Boolean,
    ): PreparedDictionaryImport {
        if (sources.isEmpty()) throw DictionaryImportError(DictionaryImportError.Reason.EMPTY_DICTIONARY, "no file")
        if (sources.size > limits.fileCap) {
            throw DictionaryImportError(DictionaryImportError.Reason.TOO_MANY_FILES, sources.size.toString())
        }
        if (sources.map { it.path.lowercase() }.toSet().size != sources.size) {
            throw DictionaryImportError(DictionaryImportError.Reason.DUPLICATE_FILE)
        }
        var total = 0L
        for (source in sources) {
            validateDictionaryFileName(source.path)
            total += source.file.length()
            if (total > limits.byteCap) {
                throw DictionaryImportError(
                    DictionaryImportError.Reason.FILE_TOO_LARGE,
                    source.path + ": " + total + " bytes",
                )
            }
        }
        val headers = sources.associate { source -> source.path to openReader(source).use { readHeader(it, source) } }
        val ordered = mergeOrder(sources, headers)
        target.mkdirs()
        for ((path, data) in templates) writeFile(File(target, path), data)
        var entryCount = 0
        val signatures = ArrayList<String>()
        val shas = HashMap<String, String>()
        val dictionaryFile = File(target, DICTIONARY_NAME + DICTIONARY_SUFFIX)
        val rowsFile = File(target, ".rows.tmp")
        BufferedWriter(OutputStreamWriter(rowsFile.outputStream(), StandardCharsets.UTF_8)).use { rows ->
            for (path in ordered) {
                val source = sources.first { it.path == path }
                val digest = MessageDigest.getInstance("SHA-256")
                openReader(source, digest).use { reader ->
                    val header = readHeader(reader, source)
                    entryCount += readRows(reader, source.path, header, rows, limits.entryCap - entryCount, cancelled)
                }
                val sha = hex(digest.digest())
                shas[path] = sha
                signatures += path + "\t" + sha
                val original = File(target, "$ORIGINALS_DIRECTORY/$path")
                original.parentFile?.mkdirs()
                source.file.copyTo(original, overwrite = true)
            }
        }
        if (entryCount == 0) {
            rowsFile.delete()
            throw DictionaryImportError(DictionaryImportError.Reason.EMPTY_DICTIONARY, ordered.last())
        }
        val revision =
            if (ordered.size == 1) {
                shas.getValue(ordered.single())
            } else {
                sha256Hex(signatures.joinToString("\n").toByteArray(StandardCharsets.UTF_8))
            }
        dictionaryFile.outputStream().use { output ->
            output.write(dictionaryHeader(revision, layered).toByteArray(StandardCharsets.UTF_8))
            rowsFile.inputStream().use { it.copyTo(output) }
        }
        rowsFile.delete()
        val root = headers.getValue(ordered.last())
        writeFile(
            File(target, METADATA_FILE),
            metadataJson(root.inspection.name, entryCount, revision).toByteArray(StandardCharsets.UTF_8),
        )
        writeFile(File(target, ORIGIN_FILE), originJson(ordered, shas).toByteArray(StandardCharsets.UTF_8))
        writeFile(File(target, LICENSE_FILE), LICENSE_NOTICE.toByteArray(StandardCharsets.UTF_8))
        return PreparedDictionaryImport(
            root = target,
            entryCount = entryCount,
            dictionaryName = DICTIONARY_NAME,
            displayName = root.inspection.name,
            sourceId = SOURCE_ID,
            revision = revision,
            schemaId = SCHEMA_ID,
            sha256 = revision,
            sources = ordered.map { headers.getValue(it).inspection },
        )
    }

    private class Header(
        val inspection: DictionarySourceInspection,
        val explicitColumns: Boolean,
        val textIndex: Int,
        val codeIndex: Int,
        val weightIndex: Int,
        val columnCount: Int,
        /** 1-based number of the `...` line; rows are numbered after it. */
        val endLine: Int,
    )

    /** Dependencies first (post-order), the single unreferenced main dictionary last. */
    private fun mergeOrder(
        sources: List<DictionarySourceFile>,
        headers: Map<String, Header>,
    ): List<String> {
        val paths = sources.map { it.path }
        val resolved = HashMap<String, List<String>>()
        val missing = sortedSetOf<String>()
        for (path in paths) {
            resolved[path] =
                headers.getValue(path).inspection.dependencies.mapNotNull { dependency ->
                    resolveDependency(dependency, paths).also { if (it == null) missing += dependency }
                }
        }
        if (missing.isNotEmpty()) {
            throw DictionaryImportError(
                DictionaryImportError.Reason.MISSING_DEPENDENCIES,
                missing.joinToString(", "),
                missing.toList(),
            )
        }
        val referenced = resolved.values.flatten().toSet()
        val roots = paths.filter { it !in referenced }
        if (roots.isEmpty()) {
            throw DictionaryImportError(
                DictionaryImportError.Reason.DEPENDENCY_CYCLE,
                paths.joinToString(),
            )
        }
        if (roots.size > 1) {
            throw DictionaryImportError(DictionaryImportError.Reason.UNREFERENCED_FILE, roots.joinToString())
        }
        val ordered = ArrayList<String>()
        val visiting = HashSet<String>()
        val visited = HashSet<String>()

        fun visit(path: String) {
            if (path in visiting) throw DictionaryImportError(DictionaryImportError.Reason.DEPENDENCY_CYCLE, path)
            if (!visited.add(path)) return
            visiting += path
            resolved.getValue(path).forEach(::visit)
            visiting -= path
            ordered += path
        }
        visit(roots.single())
        if (ordered.size != paths.size) {
            throw DictionaryImportError(
                DictionaryImportError.Reason.UNREFERENCED_FILE,
                (paths - ordered.toSet()).joinToString(),
            )
        }
        return ordered
    }

    /**
     * A dependency resolves to the selected file at exactly that path, or else to the only selected
     * file with the same file name: a system picker hands over names, not repository directories.
     */
    fun resolveDependency(
        dependency: String,
        paths: List<String>,
    ): String? {
        if (dependency in paths) return dependency
        val name = dependency.substringAfterLast('/')
        return paths.filter { it.substringAfterLast('/') == name }.singleOrNull()
    }

    private fun openReader(
        source: DictionarySourceFile,
        digest: MessageDigest? = null,
    ): BufferedReader {
        val decoder =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        val input = FileInputStream(source.file)
        val stream = if (digest == null) input else java.security.DigestInputStream(input, digest)
        return BufferedReader(InputStreamReader(stream, decoder), READ_BUFFER_CHARS)
    }

    /** Reads one line, turning a decoding failure into the importer's encoding error. */
    private fun BufferedReader.nextLine(path: String): String? {
        val line =
            try {
                readLine()
            } catch (_: CharacterCodingException) {
                throw DictionaryImportError(DictionaryImportError.Reason.INVALID_ENCODING, path)
            } ?: return null
        if (line.contains('\u0000')) throw DictionaryImportError(DictionaryImportError.Reason.INVALID_ENCODING, path)
        return line.replace("\uFEFF", "")
    }

    /**
     * Reads the strict YAML subset a Rime dictionary header uses. Mirrors the iOS parser including
     * its tolerances (optional weight column, quoted header scalars, comments) and its rejections.
     */
    private fun readHeader(
        reader: BufferedReader,
        source: DictionarySourceFile,
    ): Header {
        val path = source.path
        val fields = LinkedHashMap<String, String>()
        val lists = LinkedHashMap<String, MutableList<String>>()
        var currentList: String? = null
        // The whole header is found before any of it is interpreted: a file without a header end
        // is reported as such, not as whatever its first row looks like.
        val headerLines = ArrayList<String>()
        while (true) {
            val line = reader.nextLine(path)
            if (line == null || headerLines.size >= MAX_HEADER_LINES) {
                throw DictionaryImportError(DictionaryImportError.Reason.MISSING_HEADER_END, path)
            }
            if (line.trim() == HEADER_END) break
            headerLines += line
        }
        val lineIndex = headerLines.size + 1
        for (line in headerLines) {
            val comment = line.indexOf(" #")
            val clean = (if (comment < 0) line else line.substring(0, comment)).trim()
            if (clean.isEmpty() || clean.startsWith("#") || clean == "---") continue
            if (clean.startsWith("- ") && currentList != null) {
                lists.getValue(currentList).add(readScalar(clean.substring(2), path))
                continue
            }
            val colon = clean.indexOf(':')
            if (line.startsWith(" ") || colon < 0) {
                throw DictionaryImportError(DictionaryImportError.Reason.UNSUPPORTED_YAML, path)
            }
            val key = clean.substring(0, colon)
            if (key !in HEADER_KEYS || fields.containsKey(key)) {
                throw DictionaryImportError(DictionaryImportError.Reason.UNSUPPORTED_YAML, "$path: $key")
            }
            val value = clean.substring(colon + 1).trim()
            currentList = null
            if (key == "import_tables" || key == "columns") {
                fields[key] = value
                val items = mutableListOf<String>()
                lists[key] = items
                if (value.isEmpty()) {
                    currentList = key
                } else if (value.startsWith("[") && value.endsWith("]")) {
                    // Empty entries are dropped, as Foundation's split does by default.
                    for (item in value.substring(1, value.length - 1).split(',')) {
                        if (item.trim().isEmpty()) continue
                        items.add(readScalar(item.trim(), path))
                    }
                } else {
                    throw DictionaryImportError(DictionaryImportError.Reason.UNSUPPORTED_YAML, "$path: $key")
                }
            } else {
                fields[key] = readScalar(value, path)
            }
        }
        val name = fields["name"]
        if (name.isNullOrEmpty()) throw DictionaryImportError(DictionaryImportError.Reason.MISSING_NAME, path)
        validateDictionaryFileName(name + DICTIONARY_SUFFIX)
        val presetVocabulary = fields["use_preset_vocabulary"]
        if (presetVocabulary != null && presetVocabulary != "false") {
            throw DictionaryImportError(DictionaryImportError.Reason.UNSUPPORTED_PRESET_VOCABULARY, path)
        }
        val sort = fields["sort"]
        if (sort != null && sort != "by_weight") {
            throw DictionaryImportError(DictionaryImportError.Reason.UNSUPPORTED_SORT_ORDER, "$path: $sort")
        }
        val dependencies =
            lists["import_tables"].orEmpty().map { dependency ->
                dependencyPath(dependency).also { validateDictionaryFileName(it) }
            }
        val columns = lists["columns"] ?: DEFAULT_COLUMNS
        val columnsValid =
            columns.size == columns.toSet().size &&
                "text" in columns &&
                "code" in columns &&
                columns.all { it in COLUMN_NAMES }
        if (!columnsValid) {
            throw DictionaryImportError(
                DictionaryImportError.Reason.UNSUPPORTED_COLUMNS,
                path + ": " + columns.joinToString(", "),
            )
        }
        return Header(
            inspection = DictionarySourceInspection(path, name, dependencies, source.file.length()),
            explicitColumns = fields["columns"] != null,
            textIndex = columns.indexOf("text"),
            codeIndex = columns.indexOf("code"),
            weightIndex = columns.indexOf("weight"),
            columnCount = columns.size,
            endLine = lineIndex,
        )
    }

    /** Streams the rows after the header into [output], rewriting every reading; returns the count. */
    private fun readRows(
        reader: BufferedReader,
        path: String,
        header: Header,
        output: BufferedWriter,
        remaining: Int,
        cancelled: () -> Boolean,
    ): Int {
        var count = 0
        var lineNumber = header.endLine
        while (true) {
            val line = reader.nextLine(path) ?: break
            lineNumber++
            if (lineNumber % CANCELLATION_STRIDE == 0 && cancelled()) throw InterruptedException("import cancelled")
            if (line.isEmpty() || line.startsWith("#")) continue
            val cells = splitKeepingEmpty(line, "\t")
            // Default Rime columns allow the weight to be omitted; a declared columns list is exact.
            if (cells.size != header.columnCount && !(!header.explicitColumns && cells.size == 2)) {
                throw DictionaryImportError(DictionaryImportError.Reason.INVALID_ROW, rowDetail(path, lineNumber))
            }
            val word = cells[header.textIndex]
            if (!isHanWord(word)) {
                throw DictionaryImportError(DictionaryImportError.Reason.NON_HAN_WORD, rowDetail(path, lineNumber))
            }
            val syllables = ArrayList<String>()
            for (raw in cells[header.codeIndex].split(' ')) {
                if (raw.isEmpty()) continue
                syllables.add(normalizeSyllable(raw, path, lineNumber))
            }
            if (syllables.isEmpty() || syllables.size != word.codePointCount(0, word.length)) {
                throw DictionaryImportError(
                    DictionaryImportError.Reason.SYLLABLE_COUNT_MISMATCH,
                    rowDetail(path, lineNumber),
                )
            }
            val weight =
                if (header.weightIndex >= 0 &&
                    header.weightIndex < cells.size
                ) {
                    cells[header.weightIndex]
                } else {
                    "1"
                }
            if (weight.length > MAX_WEIGHT_LENGTH || !WEIGHT_PATTERN.matches(weight)) {
                throw DictionaryImportError(DictionaryImportError.Reason.INVALID_WEIGHT, rowDetail(path, lineNumber))
            }
            count++
            if (count > remaining) throw DictionaryImportError(DictionaryImportError.Reason.TOO_MANY_ENTRIES, path)
            output.write(word)
            output.write('\t'.code)
            output.write(syllables.joinToString(" "))
            output.write('\t'.code)
            output.write(weight)
            output.write('\n'.code)
        }
        return count
    }

    /** The built-in templates with every scheme repointed at the imported dictionary. */
    private fun templateFiles(builtin: Map<String, ByteArray>): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        var schemaCount = 0
        for ((path, data) in builtin.entries.sortedBy { it.key }) {
            validateRelativePath(path, reason = DictionaryImportError.Reason.INCOMPATIBLE_TEMPLATE)
            if (path == PACKAGE_MANIFEST_FILE) continue
            files[path] =
                if (path.endsWith(SCHEMA_SUFFIX)) {
                    schemaCount++
                    patchTemplateSchema(path, data)
                } else {
                    data.copyOf()
                }
        }
        if (schemaCount == 0 || !files.containsKey(DEFAULT_CONFIG_FILE) || !files.containsKey(BASIC_DICTIONARY_FILE)) {
            throw DictionaryImportError(
                DictionaryImportError.Reason.INCOMPATIBLE_TEMPLATE,
                "built-in templates need " +
                    DEFAULT_CONFIG_FILE + ", " + BASIC_DICTIONARY_FILE + " and one scheme at least",
            )
        }
        return files
    }

    /** Repoints one built-in scheme at the imported dictionary; anything else is a template bug. */
    private fun patchTemplateSchema(
        path: String,
        data: ByteArray,
    ): ByteArray {
        val text = decodeUtf8(data)
        if (text == null ||
            countOccurrences(text, SCHEMA_DICTIONARY_REF) != 1 ||
            !text.contains(SCHEMA_USER_DICT_REF)
        ) {
            throw DictionaryImportError(
                DictionaryImportError.Reason.INCOMPATIBLE_TEMPLATE,
                path + " must reference " + SCHEMA_DICTIONARY_REF.trim() + " exactly once",
            )
        }
        return text
            .replace(SCHEMA_DICTIONARY_REF, SCHEMA_DICTIONARY_PATCHED)
            .toByteArray(StandardCharsets.UTF_8)
    }

    private fun dictionaryHeader(
        revision: String,
        layered: Boolean,
    ): String =
        buildString {
            if (layered) {
                append("# User-imported pinyin dictionary layered over the built-in starter dictionary;\n")
            } else {
                append("# User-imported pinyin dictionary stacked onto a scheme package's dictionary;\n")
            }
            append("# the original files and their notices are in ").append(ORIGINALS_DIRECTORY).append("/.\n")
            append("---\n")
            append("name: ").append(DICTIONARY_NAME).append('\n')
            append("version: \"").append(revision).append("\"\n")
            append("sort: by_weight\n")
            append("use_preset_vocabulary: false\n")
            if (layered) {
                append("import_tables:\n")
                append("  - songyan_basic\n")
            }
            append("...\n")
        }

    private fun metadataJson(
        displayName: String,
        entryCount: Int,
        sha256: String,
    ): String =
        jsonObject(
            listOf(
                "dictionaryFile" to quoteJson(DICTIONARY_NAME + DICTIONARY_SUFFIX),
                "dictionaryName" to quoteJson(DICTIONARY_NAME),
                "displayName" to quoteJson(displayName),
                "entryCount" to entryCount.toString(),
                "revision" to quoteJson(sha256),
                "schemaId" to quoteJson(SCHEMA_ID),
                "sha256" to quoteJson(sha256),
                "sourceId" to quoteJson(SOURCE_ID),
            ),
        )

    private fun originJson(
        ordered: List<String>,
        shas: Map<String, String>,
    ): String =
        "[\n" +
            ordered.joinToString(",\n") { path ->
                "  {\"path\": " + quoteJson(path) + ", \"sha256\": " + quoteJson(shas.getValue(path)) + "}"
            } + "\n]\n"

    private fun jsonObject(fields: List<Pair<String, String>>): String =
        buildString {
            append("{\n")
            for ((position, field) in fields.withIndex()) {
                append("  \"").append(field.first).append("\": ").append(field.second)
                if (position != fields.lastIndex) append(',')
                append('\n')
            }
            append("}\n")
        }

    private fun quoteJson(value: String): String =
        buildString {
            append('"')
            for (character in value) {
                when (character) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    else -> append(character)
                }
            }
            append('"')
        }

    private fun writeFile(
        file: File,
        data: ByteArray,
    ) {
        file.parentFile?.mkdirs()
        file.writeBytes(data)
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }

    private fun rowDetail(
        path: String,
        lineNumber: Int,
    ): String = "$path:$lineNumber"

    private fun validateDictionaryFileName(path: String) {
        if (!path.endsWith(DICTIONARY_SUFFIX)) {
            throw DictionaryImportError(DictionaryImportError.Reason.UNSUPPORTED_FILE_TYPE, path)
        }
        validateRelativePath(path, reason = DictionaryImportError.Reason.UNSAFE_FILE_NAME)
    }

    /** One relative path with plain ASCII components; no absolute roots, no dot segments, no escapes. */
    private fun validateRelativePath(
        path: String,
        reason: DictionaryImportError.Reason,
    ) {
        val byteLength = path.toByteArray(StandardCharsets.UTF_8).size
        if (path.isEmpty() || path.startsWith("/") || byteLength > MAX_FILE_NAME_BYTES) {
            throw DictionaryImportError(reason, path)
        }
        for (component in splitKeepingEmpty(path, "/")) {
            if (component.isEmpty() ||
                component == "." ||
                component == ".." ||
                !SAFE_NAME_COMPONENT.matches(component)
            ) {
                throw DictionaryImportError(reason, path)
            }
        }
    }

    /** YAML scalars stay plain tokens; aliases, anchors, escapes and block scalars are rejected. */
    private fun readScalar(
        raw: String,
        path: String,
    ): String {
        var value = raw
        if (value.length >= 2) {
            val first = value.first()
            val last = value.last()
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                value = value.substring(1, value.length - 1)
            }
        }
        if (value.isEmpty() || value.contains('\\') || !HEADER_SCALAR_PATTERN.matches(value)) {
            throw DictionaryImportError(DictionaryImportError.Reason.UNSUPPORTED_YAML, path + ": " + raw)
        }
        return value
    }

    /**
     * Rewrites one reading the way the iOS importer does: lowercase, decompose tone marks, map
     * `u:` and the umlaut vowels to `v`, drop a trailing tone digit, expand a lone `r` to `er`, and
     * turn `j/q/x/y + v` back into `u`. Anything outside the full-pinyin syllable set is rejected.
     */
    private fun normalizeSyllable(
        raw: String,
        path: String,
        lineNumber: Int,
    ): String {
        var syllable = Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFC)
        syllable = syllable.replace("u:", "v")
        for (umlaut in UMLAUT_VOWELS) syllable = syllable.replace(umlaut, 'v')
        syllable = DIACRITIC_MARKS.replace(Normalizer.normalize(syllable, Normalizer.Form.NFD), "")
        if (syllable.isNotEmpty() && syllable.last() in TONE_DIGITS) syllable = syllable.dropLast(1)
        if (syllable == "r") syllable = "er"
        for (initial in V_RESTORING_INITIALS) {
            if (syllable.startsWith(initial + "v")) {
                syllable = initial + "u" + syllable.substring(2)
            }
        }
        if (syllable !in SYLLABLES) {
            throw DictionaryImportError(DictionaryImportError.Reason.UNKNOWN_SYLLABLE, rowDetail(path, lineNumber))
        }
        return syllable
    }

    private fun isHanWord(word: String): Boolean {
        if (word.isEmpty()) return false
        var offset = 0
        while (offset < word.length) {
            val codePoint = word.codePointAt(offset)
            if (!isAcceptedHan(codePoint)) return false
            offset += Character.charCount(codePoint)
        }
        return true
    }

    private fun isAcceptedHan(codePoint: Int): Boolean =
        HAN_RANGES.any { codePoint in it } || codePoint in COMPATIBILITY_HAN

    private fun countOccurrences(
        text: String,
        needle: String,
    ): Int {
        var count = 0
        var start = 0
        while (true) {
            val index = text.indexOf(needle, start)
            if (index < 0) return count
            count++
            start = index + needle.length
        }
    }

    private const val DICTIONARY_SUFFIX = ".dict.yaml"
    private const val READ_BUFFER_CHARS = 64 * 1024
    private const val CANCELLATION_STRIDE = 4096
    private const val SCHEMA_SUFFIX = ".schema.yaml"
    private const val DEFAULT_CONFIG_FILE = "default.yaml"
    private const val PACKAGE_MANIFEST_FILE = "manifest.yaml"
    private const val BASIC_DICTIONARY_FILE = "songyan_basic.dict.yaml"
    private const val HEADER_END = "..."
    private const val MAX_WEIGHT_LENGTH = 16
    private const val SCHEMA_DICTIONARY_REF = "  dictionary: songyan_basic"
    private const val SCHEMA_DICTIONARY_PATCHED = "  dictionary: songyan_imported"
    private const val SCHEMA_USER_DICT_REF = "user_dict: songyan_shared"
    private const val TONE_DIGITS = "12345"
    private const val UMLAUT_VOWELS = "\u00fc\u01d6\u01d8\u01da\u01dc"
    private val V_RESTORING_INITIALS = listOf("j", "q", "x", "y")
    private val HEADER_KEYS = setOf("name", "version", "sort", "use_preset_vocabulary", "import_tables", "columns")
    private val DEFAULT_COLUMNS = listOf("text", "code", "weight")
    private val COLUMN_NAMES = setOf("text", "code", "weight")
    private val SAFE_NAME_COMPONENT = Regex("[A-Za-z0-9_.-]+")
    private val HEADER_SCALAR_PATTERN = Regex("[A-Za-z0-9_./%-]+")
    private val WEIGHT_PATTERN = Regex("[0-9]+(?:\\.[0-9]+)?%?")
    private val DIACRITIC_MARKS = Regex("\\p{M}+")

    private val LICENSE_NOTICE =
        "User-provided dictionary data.\n" +
            "\n" +
            "The source file is not bundled. Its words and weights are reproduced with toneless\n" +
            "canonical full pinyin. No license is inferred or granted by importing; the original\n" +
            "file and its notices stay with the user.\n"

    /** Unicode 17.0 PropList.txt Unified_Ideograph: assigned ideographs, not whole blocks. */
    private val HAN_RANGES =
        listOf(
            0x3400..0x4DBF,
            0x4E00..0x9FFF,
            0xFA0E..0xFA0F,
            0xFA11..0xFA11,
            0xFA13..0xFA14,
            0xFA1F..0xFA1F,
            0xFA21..0xFA21,
            0xFA23..0xFA24,
            0xFA27..0xFA29,
            0x20000..0x2A6DF,
            0x2A700..0x2B81D,
            0x2B820..0x2CEAD,
            0x2CEB0..0x2EBE0,
            0x2EBF0..0x2EE5D,
            0x30000..0x3134A,
            0x31350..0x33479,
        )

    /**
     * The 542 assigned supplementary compatibility ideographs the iOS importer accepts. They are
     * not members of Unified_Ideograph; the unassigned tail of the block is left out.
     */
    private val COMPATIBILITY_HAN = 0x2F800..0x2FA1D

    /** Full-pinyin syllables librime and the built-in schemes can spell. */
    private val SYLLABLES: Set<String> =
        listOf(
            "a ai an ang ao ba bai ban bang bao bei ben beng bi bian biao bie bin bing bo bu ca cai can cang cao",
            "ce cen ceng cha chai chan chang chao che chen cheng chi chong chou chu chua chuai chuan chuang chui",
            "chun chuo ci cong cou cu cuan cui cun cuo da dai dan dang dao de dei den deng di dia dian diao die",
            "ding diu dong dou du duan dui dun duo e ei en eng er fa fan fang fei fen feng fiao fo fou fu ga gai",
            "gan gang gao ge gei gen geng gong gou gu gua guai guan guang gui gun guo ha hai han hang hao he hei",
            "hen heng hong hou hu hua huai huan huang hui hun huo ji jia jian jiang jiao jie jin jing jiong jiu",
            "ju juan jue jun ka kai kan kang kao ke kei ken keng kong kou ku kua kuai kuan kuang kui kun kuo la",
            "lai lan lang lao le lei leng li lia lian liang liao lie lin ling liu lo long lou lu luan lun luo lv",
            "lve ma mai man mang mao me mei men meng mi mian miao mie min ming miu mo mou mu na nai nan nang nao",
            "ne nei nen neng ni nia nian niang niao nie nin ning niu nong nou nu nuan nun nuo nv nve o ou pa pai",
            "pan pang pao pei pen peng pi pian piao pie pin ping po pou pu qi qia qian qiang qiao qie qin qing",
            "qiong qiu qu quan que qun ran rang rao re ren reng ri rong rou ru rua ruan rui run ruo sa sai san",
            "sang sao se sen seng sha shai shan shang shao she shei shen sheng shi shou shu shua shuai shuan",
            "shuang shui shun shuo si song sou su suan sui sun suo ta tai tan tang tao te tei teng ti tian tiao",
            "tie ting tong tou tu tuan tui tun tuo wa wai wan wang wei wen weng wo wu xi xia xian xiang xiao xie",
            "xin xing xiong xiu xu xuan xue xun ya yan yang yao ye yi yin ying yo yong you yu yuan yue yun za zai",
            "zan zang zao ze zei zen zeng zha zhai zhan zhang zhao zhe zhei zhen zheng zhi zhong zhou zhu zhua",
            "zhuai zhuan zhuang zhui zhun zhuo zi zong zou zu zuan zui zun zuo",
        ).flatMap { it.split(' ') }.toSet()
}

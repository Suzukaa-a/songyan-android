package app.songyan.ime.packages

import org.snakeyaml.engine.v2.nodes.MappingNode
import org.snakeyaml.engine.v2.nodes.Node
import org.snakeyaml.engine.v2.nodes.ScalarNode
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/** Rules for the `.gram` paths a schema can reference (iOS `GrammarModelPath`). */
object GrammarModelPath {
    fun isValid(path: String): Boolean {
        if (!path.endsWith(".gram") || path.startsWith("/")) return false
        val filename = splitKeepingEmpty(path, "/").last()
        if (filename.codePointCount(0, filename.length) <= 5) return false
        return splitKeepingEmpty(path.dropLast(5), "/").all { part ->
            part.isNotEmpty() &&
                part != "." &&
                part != ".." &&
                !part.contains('\\') &&
                !part.contains(':') &&
                part.codePoints().noneMatch { isControlCharacter(it) }
        }
    }

    fun path(language: String): String? = (language + ".gram").takeIf { isValid(it) }

    /** Foundation `CharacterSet.controlCharacters`: general categories Cc and Cf. */
    internal fun isControlCharacter(codePoint: Int): Boolean =
        when (Character.getType(codePoint).toByte()) {
            Character.CONTROL, Character.FORMAT -> true
            else -> false
        }
}

/** Reads deployed configuration; never infers a binding from a model filename or source schema. */
data class GrammarBinding(
    val schemaID: String,
    val language: String?,
    val contextualSuggestions: Boolean,
    val support: Support,
) {
    enum class Support { SUPPORTED, UNSUPPORTED, UNKNOWN }

    val id: String get() = schemaID

    /**
     * Whether this input method actually references [modelPath] in its deployed configuration. The
     * relation is model to input-method set, read from the real `grammar.language`, never inferred
     * from a filename, source recipe, brand or schema ID.
     */
    fun binds(modelPath: String): Boolean = language == GrammarModelRecipe.modelName(modelPath)

    companion object {
        private val SCHEMA_ID = Regex("^[A-Za-z0-9_-]+$")

        private fun support(root: Map<String, Any?>?): Support {
            val translators =
                root
                    ?.get("engine")
                    .asStringMap()
                    ?.get("translators")
                    .asStringList()
            if (translators.isNullOrEmpty()) return Support.UNKNOWN
            var unknown = false
            for (translator in translators) {
                // `split(separator:maxSplits: 1)` drops empty pieces, so a bare "@x" has no name part.
                val pieces = translator.split("@", limit = 2).filter { it.isNotEmpty() }
                val name = pieces.firstOrNull() ?: ""
                if (name == "script_translator") return Support.SUPPORTED
                if (name == "table_translator") {
                    val namespace = if (pieces.size == 2) pieces[1] else "translator"
                    val options = root?.get(namespace).asStringMap() ?: emptyMap()
                    if (listOf("enable_sentence", "sentence_over_completion", "contextual_suggestions").any {
                            options[it] ==
                                true
                        }
                    ) {
                        return Support.SUPPORTED
                    }
                } else if (name !in setOf("punct_translator", "echo_translator", "reverse_lookup_translator")) {
                    unknown = true
                }
            }
            return if (unknown) Support.UNKNOWN else Support.UNSUPPORTED
        }

        /** The scalar text of `grammar/language`, whatever its YAML type (`true`, `123`, a date stay exact strings). */
        private fun languageText(text: String): String? {
            val root: Node = BoundedYaml.compose(text) ?: return null
            val grammar = (root as? MappingNode)?.let { value(it, "grammar") } as? MappingNode ?: return null
            return (value(grammar, "language") as? ScalarNode)?.value
        }

        private fun value(
            mapping: MappingNode,
            key: String,
        ): Node? = mapping.value.firstOrNull { (it.keyNode as? ScalarNode)?.value == key }?.valueNode

        /** Reads one compiled schema's bytes. Throws [RecipeInstallError.InvalidResource] on an unusable file. */
        fun read(
            schemaID: String,
            compiled: ByteArray,
        ): GrammarBinding {
            if (!SCHEMA_ID.containsMatchIn(
                    schemaID,
                )
            ) {
                throw RecipeInstallError.InvalidResource("invalid input method id")
            }
            if (compiled.size > RecipeResourceBudget.SOURCE_FILE) {
                throw RecipeInstallError.InvalidResource("compiled configuration exceeds the size limit")
            }
            val text =
                decodeUtf8(compiled) ?: throw RecipeInstallError.InvalidResource("compiled configuration is not UTF-8")
            val root = YamsYaml.load(text).asStringMap()
            return GrammarBinding(
                schemaID = schemaID,
                language = languageText(text),
                contextualSuggestions =
                    root?.get("translator").asStringMap()?.get("contextual_suggestions") as? Boolean ?: false,
                support = support(root),
            )
        }

        /** Reads `<buildDirectory>/<schemaID>.schema.yaml`. */
        fun read(
            schemaID: String,
            buildDirectory: Path,
        ): GrammarBinding {
            if (!SCHEMA_ID.containsMatchIn(
                    schemaID,
                )
            ) {
                throw RecipeInstallError.InvalidResource("invalid input method id")
            }
            val file = buildDirectory.resolve("$schemaID.schema.yaml")
            if (Files.size(file) > RecipeResourceBudget.SOURCE_FILE) {
                throw RecipeInstallError.InvalidResource("compiled configuration exceeds the size limit")
            }
            return read(schemaID, Files.readAllBytes(file))
        }
    }
}

/** Per-target provenance that survives later edits of the same model recipe. */
data class GrammarManagedOverride(
    val schemaID: String,
    val rx: String,
    val body: String,
) {
    val target: String get() = "$schemaID.custom.yaml"
    val key: String get() = "$target|$rx"
    private val block: String get() = "# Rx: $rx {\n$body# }\n"

    /** The custom file without this managed block; refuses a file whose block was edited or cannot be verified. */
    fun removing(data: ByteArray): ByteArray {
        val text = decodeUtf8(data)
        if (text == null ||
            text.contains('\r') ||
            !text.endsWith("\n") ||
            splitKeepingEmpty(text, "# Rx: $rx {\n").size != 2 ||
            splitKeepingEmpty(text, block).size != 2
        ) {
            throw RecipeInstallError.InvalidResource("the model settings were modified or cannot be verified")
        }
        val lines = splitLinesKeepingEmpty(text.replace(block, "")).toMutableList()
        val begin = lines.indexOf("__patch:")
        if (begin >= 0) {
            val end =
                ((begin + 1) until lines.size).firstOrNull {
                    lines[it].isNotEmpty() && !lines[it].startsWith(" ") && !lines[it].startsWith("#")
                } ?: lines.size
            val remaining =
                lines.subList(begin + 1, end).any {
                    val trimmed = it.trimFoundationWhitespace()
                    trimmed.isNotEmpty() && !trimmed.startsWith("#")
                }
            if (!remaining) lines.removeAt(begin)
        }
        // Keep a neutral file: omitting it would resurrect the copied user custom.
        val result = lines.joinToString("\n")
        val hasContent =
            lines.any {
                val trimmed = it.trimFoundationWhitespace()
                trimmed.isNotEmpty() && !trimmed.startsWith("#")
            }
        return (if (hasContent) result else "$result{}\n").toByteArray(Charsets.UTF_8)
    }
}

/** Grammar model recipes and paths (iOS `GrammarModelRecipe` and `GrammarModelManagement`, pure parts). */
object GrammarModelRecipe {
    private val SCHEMA_ID = Regex("^[A-Za-z0-9_-]+$")
    private const val DISPLAY_PREFIX = "# model-display: "

    /** The display name recorded in a model recipe's first line, if any. */
    fun displayName(
        modelPath: String,
        sources: Map<String, ByteArray>?,
    ): String? {
        if (!validModelPath(modelPath)) return null
        val name = managedName(modelPath)
        val source =
            sources?.get("songyan-models/$name.recipe.yaml") ?: sources?.get("$name.recipe.yaml") ?: return null
        val firstLine = splitLinesKeepingEmpty(decodeUtf8(source) ?: return null).first()
        if (!firstLine.startsWith(DISPLAY_PREFIX)) return null
        val decoded =
            try {
                Base64.getDecoder().decode(firstLine.substring(DISPLAY_PREFIX.length))
            } catch (_: IllegalArgumentException) {
                return null
            }
        val display = decodeUtf8(decoded) ?: return null
        if (display.isEmpty() || display.codePointCount(0, display.length) > 160) return null
        return display.takeIf { it.codePoints().noneMatch { c -> GrammarModelPath.isControlCharacter(c) } }
    }

    /**
     * Imported filenames are user data, not recipe or glob syntax. Compatible names are kept; every
     * other `.gram` name maps to a stable internal path without asking for a rename.
     */
    fun installPath(filename: String): String? {
        if (!filename.lowercase().endsWith(".gram") || filename.codePointCount(0, filename.length) <= 5) return null
        if (literalModelPath(filename) && !filename.contains('/')) return filename
        return "model-" + sha256Hex(filename.toByteArray(Charsets.UTF_8)).take(20) + ".gram"
    }

    fun validModelPath(path: String): Boolean = GrammarModelPath.isValid(path)

    private val LITERAL_EXCLUDED = "\"'$`*?[]{}#".map { it.code }.toSet()

    // Foundation `CharacterSet.whitespacesAndNewlines`: Z* categories, U+0009...U+000D and U+0085.
    private fun isWhitespaceOrNewline(c: Int): Boolean =
        c in 0x09..0x0D ||
            c == 0x85 ||
            when (Character.getType(c).toByte()) {
                Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true
                else -> false
            }

    private fun literalModelPath(path: String): Boolean =
        validModelPath(path) && path.codePoints().noneMatch { isWhitespaceOrNewline(it) || it in LITERAL_EXCLUDED }

    internal fun managedName(modelPath: String): String {
        val language = modelName(modelPath)
        if (SCHEMA_ID.containsMatchIn(language)) return "model-$language"
        return "model-" + sha256Hex(modelPath.toByteArray(Charsets.UTF_8)).take(20)
    }

    /**
     * A model's binding identity is its file name stem, independent of any input method. One model
     * is bound to zero, one or many input methods; every named schema shares this identity but gets
     * its own managed patch target, so replacing or clearing one schema's binding never rewrites
     * another schema's block.
     */
    fun modelName(modelPath: String): String = modelPath.dropLast(5)

    /**
     * One `.gram` file, zero/one/many input methods. The model is installed exactly once
     * (`install_files`), never duplicated per binding. [bindingSchemaIDs] receive this model;
     * [clearingSchemaIDs] are unbound. Each named schema gets its own `__patch` block under the same
     * Rx. A call with only [clearingSchemaIDs] leaves the model installed and clears exactly those
     * bindings; an empty change set installs or replaces the file without binding an input method.
     */
    fun make(
        modelPath: String,
        bindingSchemaIDs: List<String>,
        clearingSchemaIDs: List<String> = emptyList(),
        sourceURL: URI? = null,
        displayName: String? = null,
    ): DeclarativeRecipe {
        if (!literalModelPath(modelPath)) {
            throw RecipeInstallError.InvalidResource(
                "model path cannot be configured directly; keep the scheme's own configuration and add the model file",
            )
        }
        val byValue = Comparator<String> { a, b -> YamsYaml.compareStrings(a, b) }
        val binding = bindingSchemaIDs.toSet().sortedWith(byValue)
        val clearing = (clearingSchemaIDs.toSet() - binding.toSet()).sortedWith(byValue)
        for (schemaID in binding + clearing) {
            if (!SCHEMA_ID.containsMatchIn(
                    schemaID,
                )
            ) {
                throw RecipeInstallError.InvalidResource("invalid input method id: $schemaID")
            }
        }
        val languageValue = jsonString(modelName(modelPath))
        val declaredName = "songyan-models/" + managedName(modelPath)
        val lines = ArrayList<String>()
        if (!displayName.isNullOrEmpty() &&
            displayName.codePointCount(0, displayName.length) <= 160 &&
            displayName.codePoints().noneMatch { GrammarModelPath.isControlCharacter(it) }
        ) {
            lines.add(
                DISPLAY_PREFIX + Base64.getEncoder().encodeToString(displayName.toByteArray(StandardCharsets.UTF_8)),
            )
        }
        // Recipe metadata that ends up in the generated recipe file, kept identical to iOS.
        lines.addAll(listOf("recipe:", "  Rx: $declaredName", "  description: 语法模型绑定"))
        if (sourceURL != null) {
            lines.add("download_files:")
            lines.add("  - $modelPath::$sourceURL")
        }
        // The model file is installed exactly once, regardless of how many input methods bind it.
        lines.add("install_files:")
        lines.add("  - $modelPath")
        lines.add("patch_files:")
        for (schemaID in binding) {
            lines.add("  $schemaID.custom.yaml:")
            lines.add("    - patch/+:")
            lines.add("        grammar/language: $languageValue")
        }
        for (schemaID in clearing) {
            lines.add("  $schemaID.custom.yaml:")
            lines.add("    - patch/+:")
            lines.add("        grammar/language: \"\"")
        }
        return DeclarativeRecipe.parse(
            "$declaredName.recipe.yaml",
            (lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8),
        )
    }

    // Foundation's JSONEncoder with `.withoutEscapingSlashes` for a string value.
    private fun jsonString(value: String): String {
        val out = StringBuilder("\"")
        value.codePoints().forEach { c ->
            when (c) {
                '"'.code -> out.append("\\\"")
                '\\'.code -> out.append("\\\\")
                0x08 -> out.append("\\b")
                0x0C -> out.append("\\f")
                0x0A -> out.append("\\n")
                0x0D -> out.append("\\r")
                0x09 -> out.append("\\t")
                else -> if (c < 0x20) out.append("\\u%04x".format(c)) else out.appendCodePoint(c)
            }
        }
        return out.append('"').toString()
    }

    /** The managed overrides whose block is still present in [files]. [records] are the installation's provenance records. */
    fun overrides(
        records: List<GrammarManagedOverride>,
        files: Map<String, ByteArray>,
    ): List<GrammarManagedOverride> =
        records
            .filter { record ->
                files[record.target]?.let { decodeUtf8(it) }?.contains("# Rx: ${record.rx} {\n") == true
            }.sortedWith { a, b -> YamsYaml.compareStrings(a.key, b.key) }

    /** The overrides a restore of [schemaIDs] removes; refuses unknown or edited settings. */
    fun restorationOverrides(
        schemaIDs: List<String>,
        records: List<GrammarManagedOverride>,
        files: Map<String, ByteArray>,
        packageReference: String,
    ): List<GrammarManagedOverride> {
        val selected = schemaIDs.toSet()
        val matched = overrides(records, files).filter { it.schemaID in selected }
        if (selected != matched.map { it.schemaID }.toSet()) {
            throw RecipeInstallError.InvalidResource("the selected input methods have no manually set model to restore")
        }
        for (record in matched) {
            val data =
                files[record.target] ?: throw RecipeInstallError.InvalidResource("the model target file does not exist")
            record.removing(data)
        }
        for (schema in selected) {
            val text = files["$schema.custom.yaml"]?.let { decodeUtf8Lenient(it) } ?: ""
            val lines = splitLinesKeepingEmpty(text)
            for ((index, line) in lines.withIndex()) {
                if (!line.startsWith("# Rx: $packageReference:") || !line.endsWith(" {")) continue
                val rx = line.substring(6, line.length - 2)
                if (!rx.startsWith("$packageReference:model-") &&
                    !rx.startsWith("$packageReference:songyan-models/")
                ) {
                    continue
                }
                val end = ((index + 1) until lines.size).firstOrNull { lines[it] == "# }" } ?: lines.size
                if (lines.subList(index + 1, end).any { it.contains("grammar/language:") } &&
                    matched.none { it.schemaID == schema && it.rx == rx }
                ) {
                    throw RecipeInstallError.InvalidResource(
                        "an unrecognised earlier model setting was found; defaults were not restored",
                    )
                }
            }
        }
        return matched
    }

    fun disabling(schemaIDs: List<String>): DeclarativeRecipe {
        val ids = schemaIDs.toSet().sortedWith { a, b -> YamsYaml.compareStrings(a, b) }
        if (ids.isEmpty() || !ids.all { SCHEMA_ID.containsMatchIn(it) }) {
            throw RecipeInstallError.InvalidResource("invalid input methods for turning the grammar model off")
        }
        val lines =
            arrayListOf("recipe:", "  Rx: songyan-models/disable", "  description: 关闭所选输入方式的语法模型", "patch_files:")
        for (schema in ids) {
            lines.addAll(
                listOf("  $schema.custom.yaml:", "    - patch/+:", "        grammar/language: \"\""),
            )
        }
        return DeclarativeRecipe.parse(
            "songyan-models/disable.recipe.yaml",
            (lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8),
        )
    }

    fun restorationRecipe(schemaIDs: List<String>): DeclarativeRecipe {
        val ids = schemaIDs.toSet().sortedWith { a, b -> YamsYaml.compareStrings(a, b) }
        if (ids.isEmpty() || !ids.all { SCHEMA_ID.containsMatchIn(it) }) {
            throw RecipeInstallError.InvalidResource("invalid input methods for restoring defaults")
        }
        val text =
            "# restore-grammar: " + ids.joinToString(",") +
                "\nrecipe:\n  Rx: songyan-models/restore-defaults\n  description: 恢复输入方案默认语法模型\n"
        return DeclarativeRecipe.parse("songyan-models/restore-defaults.recipe.yaml", text.toByteArray(Charsets.UTF_8))
    }
}

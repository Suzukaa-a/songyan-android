package app.songyan.ime.packages

import app.songyan.ime.keyboard.engine.SchemaKeyProfile
import app.songyan.ime.keyboard.engine.SchemaKeyProfile.Kind
import java.io.File

/**
 * Reads a [SchemaKeyProfile] from a compiled schema: the speller's alphabet, initials and
 * delimiter, the prefixes and suffixes of its translators, and the literal heads of the recognizer
 * patterns that route input to one of them. The primary translator, as in [CompactSchemaAdapter],
 * decides the kind.
 */
object SchemaKeyProfiles {
    private const val DEFAULT_ALPHABET = "abcdefghijklmnopqrstuvwxyz"
    private const val DEFAULT_DELIMITER = " '"
    private const val MAX_STROKES = 5

    /** Never throws: a schema that cannot be read has an unknown profile. */
    fun compute(compiledSchema: String): SchemaKeyProfile {
        val root = YamsYaml.loadMap(compiledSchema) ?: return UNKNOWN
        val engine = root["engine"].asStringMap() ?: return UNKNOWN
        val processors = engine["processors"].asStringList().orEmpty()
        val translators = engine["translators"].asStringList().orEmpty()
        if ("speller" !in processors) return UNKNOWN
        val primary = CompactSchemaAdapter.primaryProducer(translators) ?: return UNKNOWN

        val speller = root["speller"].asStringMap().orEmpty()
        val alphabet = (speller["alphabet"] as? String) ?: DEFAULT_ALPHABET
        val codes = LinkedHashSet<String>()

        fun add(text: String?) {
            text?.codePoints()?.forEach { if (it != ' '.code) codes += String(Character.toChars(it)) }
        }
        add(alphabet)
        add(speller["initials"] as? String)
        add((speller["delimiter"] as? String) ?: DEFAULT_DELIMITER)

        val namespaces = namespacesOf(translators)
        for ((namespace, _) in namespaces) {
            val section = root[namespace].asStringMap() ?: continue
            add(section["prefix"] as? String)
            add(section["suffix"] as? String)
        }
        val tags = HashSet<String>()
        for ((namespace, defaultTag) in namespaces) {
            tags += (root[namespace].asStringMap()?.get("tag") as? String) ?: defaultTag
        }
        tags +=
            root["abc_segmentor"]
                .asStringMap()
                ?.get("extra_tags")
                .asStringList()
                .orEmpty()
        val patterns =
            root["recognizer"]
                .asStringMap()
                ?.get("patterns")
                .asStringMap()
                .orEmpty()
        for ((tag, pattern) in patterns) {
            if (tag in tags && pattern is String) add(literalHead(pattern))
        }

        if (primary.klass == CompactSchemaAdapter.ProducerClass.SCRIPT) {
            return SchemaKeyProfile(Kind.SYLLABIC, codes.toList())
        }
        val strokes = strokeCaptions(root[primary.namespace].asStringMap(), alphabet)
        if (strokes.isEmpty()) return SchemaKeyProfile(Kind.SHAPE_CODE, codes.toList())
        val strokeCodes = strokes.map { it.code }
        val rest = codes.filter { it !in strokeCodes && !it[0].isLetter() }
        return SchemaKeyProfile(Kind.STROKES, strokeCodes + rest, strokes)
    }

    /** The profiles of every `<id>.schema.yaml` in [buildDir] that [ids] names; unreadable ones are unknown. */
    fun computeAll(
        buildDir: File,
        ids: Collection<String>,
    ): Map<String, SchemaKeyProfile> =
        ids.associateWith { id ->
            val file = File(buildDir, "$id.schema.yaml")
            val text =
                if (file.isFile &&
                    file.length() <= RecipeResourceBudget.SOURCE_FILE
                ) {
                    runCatching { file.readText() }.getOrNull()
                } else {
                    null
                }
            if (text == null) UNKNOWN else compute(text)
        }

    /**
     * The profiles of [ids] in [buildDir]: the ones the deployment wrote, and for a build published
     * before profiles existed (or a schema the file lacks) the ones computed from its compiled
     * schema. Reads the disk; call off the main thread.
     */
    fun read(
        buildDir: File,
        ids: Collection<String>,
    ): Map<String, SchemaKeyProfile> {
        val file = File(buildDir, SchemaKeyProfile.FILE_NAME)
        val written =
            if (file.isFile && file.length() <= MAX_FILE_BYTES) {
                runCatching { SchemaKeyProfile.decode(file.readText()) }.getOrNull().orEmpty()
            } else {
                emptyMap()
            }
        val missing = ids.filter { it !in written }
        return written.filterKeys { it in ids } + computeAll(buildDir, missing)
    }

    /**
     * Writes the profiles of [ids] next to the compiled schemas of [buildDir]. With the package's
     * [sourceDir] the language of each primary dictionary is read into its profile as well.
     */
    fun write(
        buildDir: File,
        ids: Collection<String>,
        sourceDir: File? = null,
    ) {
        val profiles = computeAll(buildDir, ids)
        val withLanguage =
            if (sourceDir == null) {
                profiles
            } else {
                profiles.mapValues { (id, profile) -> profile.copy(language = languageOf(buildDir, id, sourceDir)) }
            }
        File(buildDir, SchemaKeyProfile.FILE_NAME).writeText(SchemaKeyProfile.encode(withLanguage))
    }

    /** The language the primary dictionary of [id] is written in; null when it cannot be told. */
    private fun languageOf(
        buildDir: File,
        id: String,
        sourceDir: File,
    ): String? {
        val file = File(buildDir, "$id.schema.yaml").takeIf { it.isFile } ?: return null
        val dictionary = runCatching { primaryDictionary(file.readText()) }.getOrNull() ?: return null
        return DictionaryLanguage.read(sourceDir, dictionary)
    }

    /** The dictionary the primary script or table translator of a compiled schema reads. */
    internal fun primaryDictionary(compiledSchema: String): String? {
        val root = YamsYaml.loadMap(compiledSchema) ?: return null
        val translators =
            root["engine"]
                .asStringMap()
                ?.get("translators")
                .asStringList()
                .orEmpty()
        val primary = CompactSchemaAdapter.primaryProducer(translators) ?: return null
        return root[primary.namespace].asStringMap()?.get("dictionary") as? String
    }

    private const val MAX_FILE_BYTES = 1L shl 20

    private val UNKNOWN = SchemaKeyProfile(Kind.UNKNOWN)

    /**
     * The sections that carry translator settings with the tag each one routes: the unnamed script,
     * table and reverse-lookup translators and every `class@name` one. Lua entries written
     * `@*module` have no section of their own.
     */
    private fun namespacesOf(translators: List<String>): List<Pair<String, String>> =
        translators
            .mapNotNull { entry ->
                val at = entry.indexOf('@')
                val klass = if (at < 0) entry else entry.substring(0, at)
                when {
                    at >= 0 -> entry.substring(at + 1).takeIf { !it.startsWith("*") }
                    klass == "script_translator" || klass == "table_translator" -> "translator"
                    klass == "reverse_lookup_translator" -> "reverse_lookup"
                    else -> null
                }?.let { it to (if (klass == "reverse_lookup_translator") "reverse_lookup" else "abc") }
            }.distinct()

    /** The part of [pattern] every match starts with, up to its first regular-expression operator. */
    private fun literalHead(pattern: String): String {
        val start = if (pattern.startsWith("^")) 1 else 0
        var end = start
        while (end < pattern.length && pattern[end] !in "[(.*+?{\\|$") end++
        return pattern.substring(start, end)
    }

    /**
     * The captions an `xlit` rule of the translator gives to one to five alphabet symbols: the
     * scheme's own stroke labels. Empty when it has no such rule.
     */
    private fun strokeCaptions(
        translator: Map<String, Any?>?,
        alphabet: String,
    ): List<SchemaKeyProfile.Stroke> {
        val rules = translator?.get("preedit_format").asStringList().orEmpty()
        for (rule in rules) {
            if (!rule.startsWith("xlit") || rule.length < 5) continue
            val parts = rule.substring(5).split(rule[4])
            if (parts.size < 2) continue
            val source = parts[0].codePointsList()
            val target = parts[1].codePointsList()
            if (source.size !in 1..MAX_STROKES || source.size != target.size) continue
            if (source.any { it !in alphabet.codePointsList() }) continue
            return source.zip(target) { code, label -> SchemaKeyProfile.Stroke(code, label) }
        }
        return emptyList()
    }

    private fun String.codePointsList(): List<String> = codePoints().toArray().map { String(Character.toChars(it)) }
}

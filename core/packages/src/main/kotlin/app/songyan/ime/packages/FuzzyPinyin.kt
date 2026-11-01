package app.songyan.ime.packages

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Fuzzy pinyin. The feature patches the schema's own `speller/algebra` through Rime's native
 * `fuzz/<pattern>/<replacement>/` calculus: librime derives an extra spelling, tags it as fuzzy and
 * only then applies a credibility penalty, so the original normal spelling is produced first and
 * survives. Nothing here rewrites a keystroke, and nothing here talks to the engine. Ported from the
 * iOS `FuzzyPinyin`.
 *
 * Our rules go BEFORE the schema's own, one list insertion per rule (`speller/algebra/@before 00`,
 * `@before 01`, ...). The schema's rules are never restated: the engine keeps its list byte for
 * byte (including `$1` back-references and rules from `__include`/`__patch`, which a recipe body
 * cannot carry). Placing the rules first means they act on the dictionary's full-pinyin spellings,
 * so they also work for schemas whose own rules later convert pinyin into 双拼 key codes. Removing
 * our managed block restores the original behaviour exactly.
 */
object FuzzyPinyin {
    /** Recipe identity (`Rx`) of the managed block. Not tied to any source. */
    const val RECIPE_NAME = "fuzzy-pinyin"

    /** Schema-level key we patch, into whose list the derivations are inserted. */
    const val CONFIG_LEAF = "algebra"

    internal const val ALGEBRA_KEY = "speller/" + CONFIG_LEAF

    /**
     * Rime's list insertion path. Keys are zero-padded so that the engine, which applies patch keys
     * in sorted order, inserts rule N at index N and keeps our order.
     */
    internal const val INSERT_KEY_PREFIX = ALGEBRA_KEY + "/@before "

    internal fun insertKey(index: Int): String = INSERT_KEY_PREFIX + (if (index < 10) "0" else "") + index

    /** One bounded, commonly confused pair. [id] is the stable preference key. */
    data class Pair(
        val id: String,
        val label: String,
        /**
         * Both directions of the pair. Lookahead stands in for `$`, which a recipe patch body
         * cannot carry: `^z(?!h)` keeps `zhong` from becoming `zhhong`, and `an(?!g)` reaches
         * `ban` without mangling `nang`. Rule text must not contain `$`, backslashes, backticks
         * or double quotes.
         */
        val rules: List<String>,
    ) {
        companion object {
            val Z_ZH = Pair("z-zh", "z／zh", listOf("fuzz/^zh/z/", "fuzz/^z(?!h)/zh/"))
            val C_CH = Pair("c-ch", "c／ch", listOf("fuzz/^ch/c/", "fuzz/^c(?!h)/ch/"))
            val S_SH = Pair("s-sh", "s／sh", listOf("fuzz/^sh/s/", "fuzz/^s(?!h)/sh/"))
            val AN_ANG = Pair("an-ang", "an／ang", listOf("fuzz/an(?!g)/ang/", "fuzz/ang/an/"))
            val EN_ENG = Pair("en-eng", "en／eng", listOf("fuzz/en(?!g)/eng/", "fuzz/eng/en/"))
            val IN_ING = Pair("in-ing", "in／ing", listOf("fuzz/in(?!g)/ing/", "fuzz/ing/in/"))
            val N_L = Pair("n-l", "n／l", listOf("fuzz/^n/l/", "fuzz/^l/n/"))
            val F_H = Pair("f-h", "f／h", listOf("fuzz/^f/h/", "fuzz/^h/f/"))

            /** Stable order for every consumer of the pair list. */
            val ALL: List<Pair> = listOf(Z_ZH, C_CH, S_SH, AN_ANG, EN_ENG, IN_ING, N_L, F_H)
        }
    }

    /** Every selectable pair, in a stable order. */
    val allPairs: List<Pair> = Pair.ALL

    /** The pair with this stored identifier. */
    fun pair(id: String): Pair? = allPairs.firstOrNull { it.id == id }

    /**
     * The derivations for these pair identifiers, in [allPairs] order so the generated recipe is
     * deterministic for any set; null when an identifier is unknown.
     */
    fun rules(pairIDs: Set<String>): List<String>? {
        val known = pairIDs.filter { id -> allPairs.any { it.id == id } }.toSet()
        if (known != pairIDs) return null
        return allPairs.filter { it.id in pairIDs }.flatMap { it.rules }
    }

    /** The pairs a rule list covers; null when the list is not exactly what we generate. */
    fun pairIDs(rules: List<String>): Set<String>? {
        if (rules.isEmpty()) return emptySet()
        var cursor = 0
        val found = LinkedHashSet<String>()
        for (pair in allPairs) {
            if (cursor >= rules.size) break
            val needed = pair.rules
            if (rules.size - cursor < needed.size) continue
            val end = cursor + needed.size
            if (rules.subList(cursor, end) != needed) continue
            cursor = end
            found.add(pair.id)
        }
        return if (cursor == rules.size && found.isNotEmpty()) found else null
    }

    /** One schema whose algebra the fuzzy layer can patch. */
    data class Target(
        val schemaID: String,
        /** Primary producer namespace. Diagnostics only: the patched key is schema-level. */
        val namespace: String,
    ) {
        val configKey: String get() = ALGEBRA_KEY
    }

    /** Why a compiled schema cannot take the fuzzy layer. Stable codes, no UI text. */
    enum class Reason {
        INVALID_SCHEMA_ID,
        GENERATED_COMPANION,
        UNREADABLE_CONFIG,
        INCOMPLETE_CONFIG,
        COMPACT_KEYBOARD_ONLY,
        NOT_PINYIN,
        NO_DICTIONARY,
        SPELLER_ALPHABET_UNSUPPORTED,
    }

    sealed interface Eligibility {
        val target: Target?

        data class Eligible(
            override val target: Target,
        ) : Eligibility

        data class Ineligible(
            val reason: Reason,
        ) : Eligibility {
            override val target: Target? get() = null
        }
    }

    /** Why a save was refused. Stable codes, no UI text. */
    enum class RefusalReason {
        SCHEMA_NOT_ENABLED,

        /** The compiled deployment is missing or unreadable (`build/default.yaml`), not "not enabled". */
        CONFIG_UNREADABLE,
        NOT_SUPPORTED,
        USER_MANAGED,
    }

    /** What one save of the settings page amounts to. */
    sealed interface SavePlan {
        /** The input method already inserts exactly the requested pairs. */
        data object NoChange : SavePlan

        /** The save cannot be applied; the caller shows the reason for [reason]. */
        data class Refused(
            val reason: RefusalReason,
        ) : SavePlan

        /** All managed targets (the recipe requires exactly these), the changed one, and pairs. */
        data class Planned(
            val targets: List<Target>,
            val changed: Target,
            val pairIDsBySchema: Map<String, Set<String>>,
        ) : SavePlan
    }

    internal const val DEFAULT_SPELLER_ALPHABET = "zyxwvutsrqponmlkjihgfedcba"
    private val FULL_ALPHABET: Set<Char> = ('a'..'z').toSet()
    private val NAMESPACE = Regex("^[A-Za-z0-9_]+$")

    /** Structural eligibility from a compiled schema; never throws (fail closed). */
    fun eligibility(
        compiled: ByteArray,
        schemaID: String,
    ): Eligibility {
        if (!TypoCorrection.validIdentifier(schemaID)) return Eligibility.Ineligible(Reason.INVALID_SCHEMA_ID)
        if (CompactSchemaAdapter.isGeneratedCompanionID(schemaID)) {
            return Eligibility.Ineligible(Reason.GENERATED_COMPANION)
        }
        if (compiled.size > RecipeResourceBudget.SOURCE_FILE) return Eligibility.Ineligible(Reason.UNREADABLE_CONFIG)
        val text = decodeUtf8(compiled) ?: return Eligibility.Ineligible(Reason.UNREADABLE_CONFIG)
        val root = loadMap(text) ?: return Eligibility.Ineligible(Reason.UNREADABLE_CONFIG)
        val schema = root["schema"].asStringMap()
        if (schema?.get("schema_id") as? String != schemaID) {
            return Eligibility.Ineligible(Reason.INCOMPLETE_CONFIG)
        }
        if (root.containsKey("keyboard_rewrite") || root.containsKey("keyboard_adapter")) {
            return Eligibility.Ineligible(Reason.COMPACT_KEYBOARD_ONLY)
        }
        val translators = root["engine"].asStringMap()?.get("translators").asStringList()
        val producer = translators?.let { CompactSchemaAdapter.primaryProducer(it) }
        if (producer == null) return Eligibility.Ineligible(Reason.NOT_PINYIN)
        if (producer.klass != CompactSchemaAdapter.ProducerClass.SCRIPT) {
            return Eligibility.Ineligible(Reason.NOT_PINYIN)
        }
        if (!NAMESPACE.containsMatchIn(producer.namespace)) return Eligibility.Ineligible(Reason.NO_DICTIONARY)
        val section = root[producer.namespace].asStringMap() ?: return Eligibility.Ineligible(Reason.NO_DICTIONARY)
        val dictionary = section["dictionary"] as? String
        if (dictionary.isNullOrEmpty()) return Eligibility.Ineligible(Reason.NO_DICTIONARY)
        val speller = root["speller"].asStringMap()
        // An unquoted numeric alphabet loads as a number, not a string; any present-but-non-string
        // value is taken literally, never as the default alphabet.
        val alphabet =
            when {
                speller == null || !speller.containsKey("alphabet") -> DEFAULT_SPELLER_ALPHABET
                speller["alphabet"] is String -> speller["alphabet"] as String
                else -> describeScalar(speller["alphabet"])
            }
        if (!alphabet.toSet().containsAll(FULL_ALPHABET)) {
            return Eligibility.Ineligible(Reason.SPELLER_ALPHABET_UNSUPPORTED)
        }
        return Eligibility.Eligible(Target(schemaID, producer.namespace))
    }

    /**
     * The compiled schema's `speller/algebra` list: null when unreadable, mismatched, or present but
     * not a list of scalars; empty when the schema declares none.
     */
    fun compiledAlgebra(
        compiled: ByteArray,
        schemaID: String,
    ): List<String>? {
        if (!TypoCorrection.validIdentifier(schemaID) || compiled.size > RecipeResourceBudget.SOURCE_FILE) {
            return null
        }
        val text = decodeUtf8(compiled) ?: return null
        val root = loadMap(text) ?: return null
        if ((root["schema"].asStringMap()?.get("schema_id") as? String) != schemaID) return null
        val speller = root["speller"].asStringMap() ?: return emptyList()
        if (!speller.containsKey(CONFIG_LEAF)) return emptyList()
        return speller[CONFIG_LEAF].asStringList()
    }

    /** The human-readable `schema/name` of a compiled schema, for messages; null when absent. */
    fun schemaName(compiled: ByteArray): String? {
        if (compiled.size > RecipeResourceBudget.SOURCE_FILE) return null
        val text = decodeUtf8(compiled) ?: return null
        val root = loadMap(text) ?: return null
        val name = root["schema"].asStringMap()?.get("name") as? String ?: return null
        return if (name.trimFoundationWhitespace().isEmpty()) null else name
    }

    /** The Rx identity the recipe plan assigns to this recipe for [packageReference]. */
    fun rx(packageReference: String): String = packageReference + ":" + RECIPE_NAME + ":"

    /**
     * The derivations our managed block currently inserts, or null when this schema is not managed
     * by this invocation (no block, unreadable block, or an edited one). An empty list means the
     * block is ours and adds nothing, i.e. the feature is off.
     */
    fun managedAlgebra(
        customYaml: ByteArray?,
        packageReference: String,
    ): List<String>? {
        val bytes = customYaml ?: return null
        val text = decodeUtf8(bytes) ?: return null
        val marker = "# Rx: " + rx(packageReference) + " {"
        var body: List<String>? = null
        var inManaged = false
        var lines = mutableListOf<String>()
        for (line in splitLinesKeepingEmpty(text)) {
            if (!inManaged) {
                if (line == marker) {
                    inManaged = true
                    lines = mutableListOf()
                }
                continue
            }
            if (line == RecipePlanSupport.MANAGED_BLOCK_END) {
                body = lines
                inManaged = false
                break
            }
            if (line.startsWith(RecipePlanSupport.MANAGED_MARKER_PREFIX)) {
                // Our block was not closed before another invocation's: never read that block as
                // our rules (the caller falls back to refusing the save).
                return null
            }
            lines.add(line)
        }
        val managedBody = body ?: return null
        val root = loadMap("__patch:\n" + managedBody.joinToString("\n")) ?: return null
        val list = root["__patch"] as? List<*> ?: return null
        if (list.size != 1) return null
        val map = list[0].asStringMap()?.get("patch/+").asStringMap() ?: return null
        // Exactly the keys we generate, in order; anything else is an edited block.
        val keys = map.keys.sorted()
        if (keys != keys.indices.map { insertKey(it) }) return null
        val rules = mutableListOf<String>()
        for (key in keys) {
            val rule = map[key] as? String ?: return null
            rules.add(rule)
        }
        return rules
    }

    /**
     * True when anything outside OUR managed block edits the algebra: the user's own patch, or
     * another invocation's block, which owns its rules just as the user does. Only our exact marker
     * is skipped, so a different recipe that patches the same key is never silently overwritten.
     */
    fun userOwnsSetting(
        customYaml: ByteArray?,
        packageReference: String,
    ): Boolean {
        val bytes = customYaml ?: return false
        val text = decodeUtf8(bytes) ?: return false
        val marker = "# Rx: " + rx(packageReference) + " {"
        var inOurs = false
        for (line in splitLinesKeepingEmpty(text)) {
            if (inOurs) {
                if (line == RecipePlanSupport.MANAGED_BLOCK_END) {
                    inOurs = false
                } else if (line.startsWith(RecipePlanSupport.MANAGED_MARKER_PREFIX)) {
                    // Our block lost its terminator: treat the file as user-managed so a damaged
                    // file is refused instead of being rewritten around the other invocation.
                    return true
                }
                continue
            }
            if (line == marker) {
                inOurs = true
                continue
            }
            if (touchesAlgebraKey(line.substringBefore("#"))) return true
        }
        // A block that never closes is never patched either.
        return inOurs
    }

    /**
     * Whether one line of content assigns the algebra **key**. Matching the key rather than the bare
     * word keeps an unrelated value (a schema named `algebra`, prose in a comment) from being read
     * as a setting.
     */
    internal fun touchesAlgebraKey(content: CharSequence): Boolean {
        var trimmed = content.toString().trimFoundationWhitespace()
        // A `__patch` list entry (`- speller/algebra:`) names the key just like a map line.
        while (trimmed.startsWith("- ")) trimmed = trimmed.drop(2).trimFoundationWhitespace()
        if (trimmed.isEmpty()) return false
        val keys = listOf(ALGEBRA_KEY, CONFIG_LEAF)
        // YAML allows the key quoted: `"speller/algebra":`, `'speller/algebra/+':`.
        val quote = trimmed.first()
        if (quote == '"' || quote == '\'') {
            val body = trimmed.drop(1)
            val close = body.indexOf(quote)
            if (close < 0) return false
            val key = body.substring(0, close)
            val rest = body.substring(close + 1).trimFoundationWhitespace()
            return rest.startsWith(":") && keys.any { key == it || key.startsWith(it + "/") }
        }
        // `speller/algebra:`, and the append/assign operators `speller/algebra/+:`.
        for (key in keys) {
            if (trimmed.startsWith(key)) {
                val rest = trimmed.drop(key.length)
                if (rest.startsWith(":") || rest.startsWith("/")) return true
            }
        }
        // Flow style: `speller: {algebra: [...]}`, with the inner key bare or quoted.
        return trimmed.contains("speller") && listOf("", "\"", "'").any { trimmed.contains(CONFIG_LEAF + it + ":") }
    }

    /**
     * Plans a save for the ONE input method the settings page displayed. Every other eligible input
     * method keeps its own managed pairs: they are re-stated in the same Rx (byte-identical when
     * unchanged) instead of being silently reset. [build] is the installation's compiled directory.
     */
    fun plan(
        schemaID: String,
        enabledPairIDs: Set<String>,
        build: Path,
        customFiles: Map<String, ByteArray>,
        packageReference: String,
    ): SavePlan {
        // A missing or unreadable compiled default is a damaged deployment, not a disabled scheme:
        // reporting "not enabled" would send the user to the wrong setting.
        val defaults =
            readFileOrNull(build.resolve("default.yaml"))
                ?: return SavePlan.Refused(RefusalReason.CONFIG_UNREADABLE)
        val enabled =
            when (val schemas = TypoCorrection.enabledSchemaResult(defaults)) {
                is TypoCorrection.EnabledSchemas.Unreadable ->
                    return SavePlan.Refused(RefusalReason.CONFIG_UNREADABLE)
                is TypoCorrection.EnabledSchemas.Listed -> schemas.ids
            }
        if (schemaID !in enabled) return SavePlan.Refused(RefusalReason.SCHEMA_NOT_ENABLED)
        val compiled =
            readFileOrNull(build.resolve(schemaID + ".schema.yaml"))
                ?: return SavePlan.Refused(RefusalReason.NOT_SUPPORTED)
        val changed =
            eligibility(compiled, schemaID).target
                ?: return SavePlan.Refused(RefusalReason.NOT_SUPPORTED)
        val changedCustom = customFiles[schemaID + ".custom.yaml"]
        if (userOwnsSetting(changedCustom, packageReference)) {
            return SavePlan.Refused(RefusalReason.USER_MANAGED)
        }
        val changedAppended = managedAlgebra(changedCustom, packageReference)
        if (changedAppended?.let { pairIDs(it) } == enabledPairIDs) return SavePlan.NoChange
        val targets = mutableListOf(changed)
        val pairsBySchema = LinkedHashMap<String, Set<String>>()
        pairsBySchema[schemaID] = enabledPairIDs
        for (id in enabled) {
            if (id == schemaID) continue
            val other = readFileOrNull(build.resolve(id + ".schema.yaml")) ?: continue
            val target = eligibility(other, id).target ?: continue
            val custom = customFiles[id + ".custom.yaml"]
            // A user-managed or foreign-managed input method never enters our Rx map.
            if (userOwnsSetting(custom, packageReference)) continue
            val appended = managedAlgebra(custom, packageReference) ?: continue
            pairsBySchema[id] = pairIDs(appended) ?: continue
            targets.add(target)
        }
        return SavePlan.Planned(targets, changed, pairsBySchema)
    }

    /** One recipe with the same pair set for every target. */
    fun make(
        enabledPairIDs: Set<String>,
        targets: List<Target>,
    ): DeclarativeRecipe {
        val bySchema = LinkedHashMap<String, Set<String>>()
        for (target in targets) bySchema[target.schemaID] = enabledPairIDs
        return make(bySchema, targets)
    }

    /**
     * Per-input-method pairs: one managed block per schema under the same Rx, inserting that
     * schema's derivations at the start of its own `speller/algebra`.
     *
     * [enabledPairIDsBySchema] must name exactly the target schemas, because a missing one would be
     * written as switched off. Callers changing one method pass the others' current pairs so their
     * blocks are rewritten byte-for-byte unchanged.
     */
    fun make(
        enabledPairIDsBySchema: Map<String, Set<String>>,
        targets: List<Target>,
    ): DeclarativeRecipe {
        val schemas = targets.map { it.schemaID }.toSet().sorted()
        if (schemas.isEmpty()) {
            throw RecipeInstallError.InvalidResource("no input method needs fuzzy pinyin adjusted")
        }
        if (enabledPairIDsBySchema.keys.toSet() != schemas.toSet()) {
            throw RecipeInstallError.InvalidResource(
                "fuzzy pinyin settings do not match the input methods; reopen the settings",
            )
        }
        val appendedBySchema = LinkedHashMap<String, List<String>>()
        for (schemaID in schemas) {
            appendedBySchema[schemaID] =
                rules(enabledPairIDsBySchema[schemaID] ?: emptySet())
                    ?: throw RecipeInstallError.InvalidResource("unrecognised fuzzy pinyin setting")
        }
        for (schemaID in schemas) {
            if (!TypoCorrection.validIdentifier(schemaID) || CompactSchemaAdapter.isGeneratedCompanionID(schemaID)) {
                throw RecipeInstallError.InvalidResource("invalid input method id: " + schemaID)
            }
        }
        val lines = mutableListOf<String>()
        lines.add("recipe:")
        lines.add("  Rx: " + RECIPE_NAME)
        // Recipe metadata that ends up in the generated recipe file, kept identical to iOS.
        lines.add("  description: 模糊音")
        lines.add("patch_files:")
        for (schemaID in schemas) {
            val appended = appendedBySchema[schemaID] ?: emptyList()
            lines.add("  " + schemaID + ".custom.yaml:")
            if (appended.isEmpty()) {
                lines.add("    - patch/+: {}")
                continue
            }
            lines.add("    - patch/+:")
            appended.forEachIndexed { index, rule ->
                lines.add("        " + quoted(insertKey(index)) + ": " + quoted(rule))
            }
        }
        return DeclarativeRecipe.parse(
            RECIPE_NAME + ".recipe.yaml",
            (lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8),
        )
    }

    /** JSON string literal: a valid YAML flow scalar for any rule. */
    internal fun quoted(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        for (character in value) {
            when {
                character == '"' -> out.append('\\').append('"')
                character == '\\' -> out.append('\\').append('\\')
                character.code < 0x20 -> {
                    out.append('\\').append('u')
                    out.append(character.code.toString(16).padStart(4, '0'))
                }
                else -> out.append(character)
            }
        }
        out.append('"')
        return out.toString()
    }

    private fun loadMap(text: String): Map<String, Any?>? =
        try {
            YamsYaml.load(text).asStringMap()
        } catch (_: Exception) {
            null
        }

    private fun readFileOrNull(path: Path): ByteArray? =
        try {
            if (Files.isRegularFile(path)) Files.readAllBytes(path) else null
        } catch (_: IOException) {
            null
        }
}

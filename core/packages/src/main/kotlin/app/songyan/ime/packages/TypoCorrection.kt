package app.songyan.ime.packages

/**
 * Typo correction: librime `script_translator` reads `<name_space>/enable_correction` (default
 * false); when on, it tolerates single-key substitutions by physical-key neighbours. Ported from
 * the iOS `TypoCorrection`.
 *
 * `eligibility` decides from the **compiled** schema whether the switch can be enabled: the sole
 * primary producer must be a `script_translator` with a dictionary, the speller alphabet must cover
 * all of a-z, and the schema must not be a generated 14/18/9-key companion. `make` writes one
 * managed block per schema under the same Rx identity, so a rerun replaces only that block.
 */
object TypoCorrection {
    /** Recipe identity (`Rx`) of the managed block. Not tied to any source. */
    const val RECIPE_NAME = "typo-correction"

    /** librime config leaf under the primary producer namespace. */
    const val CONFIG_LEAF = "enable_correction"

    /** librime's default speller alphabet when a schema omits `speller/alphabet`. */
    internal const val DEFAULT_SPELLER_ALPHABET = "zyxwvutsrqponmlkjihgfedcba"
    private val FULL_ALPHABET: Set<Char> = ('a'..'z').toSet()
    private val NAMESPACE = Regex("^[A-Za-z0-9_]+$")
    private val SCHEMA_ID = Regex("^[A-Za-z0-9_-]+$")

    /** One schema whose primary producer can take the correction switch. */
    data class Target(
        val schemaID: String,
        /** Primary producer namespace (`translator` for a default entry, else `@name`). */
        val namespace: String,
    ) {
        /** Full config key, e.g. `translator/enable_correction`. */
        val configKey: String get() = namespace + "/" + CONFIG_LEAF
    }

    /** Why a compiled schema cannot take the switch. Stable codes, no UI text. */
    enum class Reason {
        INVALID_SCHEMA_ID,
        GENERATED_COMPANION,
        CONFIG_UNREADABLE,
        IDENTITY_MISMATCH,
        COMPACT_ADAPTER,
        NO_PRIMARY_PRODUCER,
        NOT_SCRIPT_TRANSLATOR,
        NO_DICTIONARY,
        ALPHABET_NOT_FULL,
    }

    sealed interface Eligibility {
        data class Eligible(
            val target: Target,
        ) : Eligibility

        data class Ineligible(
            val reason: Reason,
        ) : Eligibility
    }

    /** Structural eligibility from a compiled schema; never throws (fail closed). */
    fun eligibility(
        compiled: ByteArray,
        schemaID: String,
    ): Eligibility {
        if (!validIdentifier(schemaID)) return Eligibility.Ineligible(Reason.INVALID_SCHEMA_ID)
        if (CompactSchemaAdapter.isGeneratedCompanionID(schemaID)) {
            return Eligibility.Ineligible(Reason.GENERATED_COMPANION)
        }
        if (compiled.size > RecipeResourceBudget.SOURCE_FILE) return Eligibility.Ineligible(Reason.CONFIG_UNREADABLE)
        val text = decodeUtf8(compiled) ?: return Eligibility.Ineligible(Reason.CONFIG_UNREADABLE)
        val root = loadMap(text) ?: return Eligibility.Ineligible(Reason.CONFIG_UNREADABLE)
        val schema = root["schema"].asStringMap()
        if (schema?.get("schema_id") as? String != schemaID) {
            return Eligibility.Ineligible(Reason.IDENTITY_MISMATCH)
        }
        // Compact companions (and in-place compact templates) carry capability blocks.
        if (root.containsKey("keyboard_rewrite") || root.containsKey("keyboard_adapter")) {
            return Eligibility.Ineligible(Reason.COMPACT_ADAPTER)
        }
        val translators =
            root["engine"].asStringMap()?.get("translators").asStringList()
                ?: return Eligibility.Ineligible(Reason.NO_PRIMARY_PRODUCER)
        val producer =
            CompactSchemaAdapter.primaryProducer(translators)
                ?: return Eligibility.Ineligible(Reason.NO_PRIMARY_PRODUCER)
        if (producer.klass != CompactSchemaAdapter.ProducerClass.SCRIPT) {
            return Eligibility.Ineligible(Reason.NOT_SCRIPT_TRANSLATOR)
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
        if (!alphabet.toSet().containsAll(FULL_ALPHABET)) return Eligibility.Ineligible(Reason.ALPHABET_NOT_FULL)
        return Eligibility.Eligible(Target(schemaID, producer.namespace))
    }

    /** The compiled value of the switch; null when absent (librime default off). */
    fun compiledValue(
        compiled: ByteArray,
        target: Target,
    ): Boolean? {
        val text = decodeUtf8(compiled) ?: return null
        val root = loadMap(text) ?: return null
        val section = root[target.namespace].asStringMap() ?: return null
        return section[CONFIG_LEAF] as? Boolean
    }

    /** What a compiled `build/default.yaml` says, or that the deployment itself is damaged. */
    sealed interface EnabledSchemas {
        /** Missing, not UTF-8 or not a `schema_list` document: the caller must not guess. */
        data object Unreadable : EnabledSchemas

        /** The enabled schema ids, in order. */
        data class Listed(
            val ids: List<String>,
        ) : EnabledSchemas
    }

    /**
     * Enabled schema IDs from a compiled `build/default.yaml` (`schema_list`), in order. A damaged
     * file is reported as [EnabledSchemas.Unreadable] so a refusal can name the real problem instead
     * of claiming the input method is not enabled.
     */
    fun enabledSchemaResult(compiledDefault: ByteArray): EnabledSchemas {
        val text = decodeUtf8(compiledDefault) ?: return EnabledSchemas.Unreadable
        val root = loadMap(text) ?: return EnabledSchemas.Unreadable
        val list = root["schema_list"] as? List<*> ?: return EnabledSchemas.Unreadable
        val seen = HashSet<String>()
        val ids = mutableListOf<String>()
        for (element in list) {
            val entry = element.asStringMap() ?: return EnabledSchemas.Unreadable
            val id = entry["schema"] as? String ?: return EnabledSchemas.Unreadable
            if (seen.add(id)) ids.add(id)
        }
        return EnabledSchemas.Listed(ids)
    }

    /** Convenience form: an unreadable deployment reads as "no enabled schema". */
    fun enabledSchemaIDs(compiledDefault: ByteArray): List<String> =
        when (val result = enabledSchemaResult(compiledDefault)) {
            is EnabledSchemas.Unreadable -> emptyList()
            is EnabledSchemas.Listed -> result.ids
        }

    /** The Rx identity the recipe plan assigns to this recipe for [packageReference]. */
    fun rx(packageReference: String): String = packageReference + ":" + RECIPE_NAME + ":"

    /**
     * True when the user's own custom content (outside this recipe's managed block) mentions the
     * switch. Such a schema is user-managed and is never patched.
     */
    fun userOwnsSetting(
        customYaml: ByteArray?,
        packageReference: String,
    ): Boolean {
        val bytes = customYaml ?: return false
        val text = decodeUtf8(bytes) ?: return false
        val marker = "# Rx: " + rx(packageReference) + " {"
        var inManaged = false
        for (line in splitLinesKeepingEmpty(text)) {
            if (line == marker) {
                inManaged = true
                continue
            }
            if (inManaged) {
                if (line == RecipePlanSupport.MANAGED_BLOCK_END) {
                    inManaged = false
                } else if (line.startsWith(RecipePlanSupport.MANAGED_MARKER_PREFIX)) {
                    // Our block lost its terminator: never overwrite around another invocation.
                    return true
                }
                continue
            }
            if (line.substringBefore("#").contains(CONFIG_LEAF)) return true
        }
        return inManaged
    }

    /** Targets whose compiled state differs from [desired] and that the user does not manage. */
    fun changes(
        desired: Boolean,
        targets: List<Target>,
        current: Map<String, Boolean>,
        userManaged: Set<String>,
    ): List<Target> =
        targets
            .filter { it.schemaID !in userManaged && (current[it.schemaID] ?: false) != desired }
            .sortedBy { it.schemaID }

    /**
     * One declarative recipe, one managed block per schema under the same Rx.
     * [enabling] writes `true`, [disabling] writes an explicit `false`.
     */
    fun make(
        enabling: List<Target>,
        disabling: List<Target> = emptyList(),
    ): DeclarativeRecipe {
        val on = enabling.toSet().sortedBy { it.schemaID }
        val onIDs = on.map { it.schemaID }.toSet()
        val off = disabling.toSet().filter { it.schemaID !in onIDs }.sortedBy { it.schemaID }
        if (on.isEmpty() && off.isEmpty()) {
            throw RecipeInstallError.InvalidResource("no input method needs the typo-correction switch adjusted")
        }
        for (target in on + off) {
            if (!validIdentifier(target.schemaID) ||
                CompactSchemaAdapter.isGeneratedCompanionID(target.schemaID) ||
                !NAMESPACE.containsMatchIn(target.namespace)
            ) {
                throw RecipeInstallError.InvalidResource("invalid input method id: " + target.schemaID)
            }
        }
        val lines = ArrayList<String>()
        lines.add("recipe:")
        lines.add("  Rx: " + RECIPE_NAME)
        // Recipe metadata that ends up in the generated recipe file, kept identical to iOS.
        lines.add("  description: 错键纠正")
        lines.add("patch_files:")
        for (target in on + off) {
            lines.add("  " + target.schemaID + ".custom.yaml:")
            lines.add("    - patch/+:")
            lines.add("        " + target.configKey + ": " + if (target in on) "true" else "false")
        }
        return DeclarativeRecipe.parse(
            RECIPE_NAME + ".recipe.yaml",
            (lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8),
        )
    }

    internal fun validIdentifier(id: String): Boolean = SCHEMA_ID.containsMatchIn(id)

    private fun loadMap(text: String): Map<String, Any?>? =
        try {
            YamsYaml.load(text).asStringMap()
        } catch (_: Exception) {
            null
        }
}

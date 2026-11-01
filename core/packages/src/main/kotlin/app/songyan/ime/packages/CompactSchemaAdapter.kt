package app.songyan.ime.packages

import app.songyan.ime.keyboard.engine.KeyboardResourceSet
import app.songyan.ime.keyboard.layout.CompactFold
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Generated encoding configuration for the 14-, 18- and 9-key layouts, ported from the iOS
 * `CompactSchemaAdapter` (Companion, Table, TokenAlias, DisplayCapability, AdapterEligibility).
 * The source's effective algebra is kept in order and its spelling space is folded; the output is
 * byte-identical to the iOS generator because both go through the Yams-compatible [YamsYaml].
 *
 * Primary-producer selection mirrors the native `single_producer` algorithm exactly: a producer is
 * a `script_translator` or `table_translator` entry, the default namespace is the entry written
 * without `@<name>`, auxiliary entries never count as primary, two defaults are mixed provenance,
 * and with no default a single named entry is used as a read-only fallback.
 *
 * Entry point: [generateCompanions] (or [CompanionPlan] for one source computed once). Inputs are
 * the source's compiled schema bytes and the engine's source projection
 * ([SourceProjection.decode]); the output maps `<companion id>.schema.yaml` to file bytes.
 */
object CompactSchemaAdapter {
    /** Generation / adapter behaviour version; any change to generated artifacts must bump it. */
    const val DISPLAY_GENERATOR_VERSION = 10

    /** The wire version of `keyboard_adapter` (iOS `SchemaDisplayCapability.supportedVersion`). */
    private const val DISPLAY_WIRE_VERSION = app.songyan.ime.keyboard.display.SchemaDisplayCapability.SUPPORTED_VERSION

    /** Alias lock (script sources): `lock_map` maps each token to its opaque alias. */
    const val TOKEN_ALIAS_LOCK_VALUE = "bounded_token_alias_rewrite_v2"

    /** Direct-code lock (table sources). No lock_map; the token is its own target. */
    const val DIRECT_TOKEN_LOCK_VALUE = "direct_token_rewrite_v3"

    /** Letter-class prefix lock (numeric script sources that explicitly opted in). */
    const val PREFIX_TOKEN_LOCK_VALUE = "prefix_token_rewrite_v4"
    const val REWRITE_WIRE_VERSION = 2
    const val REWRITE_WIRE_VERSION_DIRECT = 3
    const val REWRITE_WIRE_VERSION_PREFIX = 4

    /** The grammar-layer version a v4 companion declares. */
    const val LETTER_CLASS_PREFIX_LAYER_VALUE = "letter_class_v1"

    /** Turns every first letter into a live prism key; applied before the compact fold. */
    const val LETTER_CLASS_PREFIX_RULE = "abbrev/^([a-z]).+\$/\$1/"
    const val LETTER_CLASS_PREFIX_KEY_LIMIT = 26

    /** Alias prefix; its `0` is a character no compact layout can type. */
    const val TOKEN_ALIAS_SENTINEL = "90"

    /** Versioned hard cap on the alias domain; larger domains get no alias layer. */
    const val TOKEN_ALIAS_LIMIT = 4096
    private const val TOKEN_ALIAS_MIN_WIDTH = 1

    private const val FOLDABLE_LETTERS = "abcdefghijklmnopqrstuvwxyz"

    /** Pass-through punctuation the compact layouts use; never folded. */
    private const val FOLDABLE_PUNCTUATION = ";'/`"

    /**
     * The eight standard telephone-digit derives. They ADD the digit spelling while the canonical
     * key survives, which the `canonical` display mode needs; a destructive `xlit` would not.
     */
    private val TELEPHONE_DERIVES =
        listOf(
            "derive/[abc]/2/",
            "derive/[def]/3/",
            "derive/[hgi]/4/",
            "derive/[jkl]/5/",
            "derive/[omn]/6/",
            "derive/[pqrs]/7/",
            "derive/[tuv]/8/",
            "derive/[wxyz]/9/",
        )

    // ------------------------------------------------------------------------------------
    // Producer contract and identifiers
    // ------------------------------------------------------------------------------------

    /** The class of the single provable primary producer. */
    enum class ProducerClass(
        val prescription: String,
    ) {
        SCRIPT("script_translator"),
        TABLE("table_translator"),
    }

    data class PrimaryProducer(
        val klass: ProducerClass,
        /** The producer's namespace: `translator` for a default entry, else the `@name` suffix. */
        val namespace: String,
        /** True when the entry was declared WITHOUT `@<name>`. */
        val isDefaultNamespace: Boolean,
    )

    /** Resolves the primary producer; null means no provable primary producer. */
    fun primaryProducer(translators: List<String>): PrimaryProducer? {
        var defaultSlot: PrimaryProducer? = null
        var defaultCount = 0
        var anySlot: PrimaryProducer? = null
        var anyCount = 0
        for (prescription in translators) {
            val at = prescription.indexOf('@')
            val klass = if (at < 0) prescription else prescription.substring(0, at)
            val kind =
                when (klass) {
                    ProducerClass.SCRIPT.prescription -> ProducerClass.SCRIPT
                    ProducerClass.TABLE.prescription -> ProducerClass.TABLE
                    else -> continue
                }
            val isDefault = at < 0
            val namespace = if (isDefault) "translator" else prescription.substring(at + 1)
            val producer = PrimaryProducer(kind, namespace, isDefaultNamespace = isDefault)
            anySlot = producer
            anyCount++
            if (isDefault) {
                defaultSlot = producer
                defaultCount++
            }
        }
        if (defaultCount == 1) return defaultSlot
        if (defaultCount > 1) return null
        if (anyCount == 1) return anySlot
        return null
    }

    /**
     * The primary producer auto-generation can safely adapt: the compact companion renames the
     * schema id and the DEFAULT `translator` section, so only a unique default producer qualifies.
     */
    fun autoAdaptableProducer(compiled: ByteArray): PrimaryProducer? {
        val root = decodeUtf8(compiled)?.let { YamsYaml.loadMap(it) } ?: return null
        val translators = root["engine"].asStringMap()?.get("translators").asStringList() ?: return null
        val producer = primaryProducer(translators) ?: return null
        return if (producer.isDefaultNamespace) producer else null
    }

    /** Schema id of the generated companion for [sourceID] on [fold]. */
    fun identifier(
        sourceID: String,
        fold: CompactFold,
    ): String = "keyboard_" + SchemeSourceCheck.hash(sourceID.toByteArray(Charsets.UTF_8)).take(20) + "_" + fold.id

    /** True for IDs minted by [identifier] (generated compact companions). */
    fun isGeneratedCompanionID(id: String): Boolean = KeyboardResourceSet.EncodingAdapter.isGeneratedID(id)

    // ------------------------------------------------------------------------------------
    // Adaptability
    // ------------------------------------------------------------------------------------

    sealed interface Adaptability {
        data object Adaptable : Adaptability

        data class Unsupported(
            val reason: String,
        ) : Adaptability
    }

    /**
     * Read-only classification: generates no files and swallows no errors. Budget, encoding,
     * parse and identity failures throw; "not a sole default primary producer" is
     * [Adaptability.Unsupported].
     */
    fun adaptability(
        compiled: ByteArray,
        sourceID: String,
    ): Adaptability {
        if (compiled.size > RecipeResourceBudget.SOURCE_FILE) {
            throw RecipeInstallError.InvalidResource("compiled schema exceeds the size limit: " + sourceID)
        }
        val text =
            decodeUtf8(compiled)
                ?: throw RecipeInstallError.InvalidResource("compiled schema is not UTF-8: " + sourceID)
        val root =
            try {
                YamsYaml.load(text).asStringMap()
                    ?: throw RecipeInstallError.InvalidResource("compiled schema has the wrong shape: " + sourceID)
            } catch (error: RecipeInstallError) {
                throw error
            } catch (error: Exception) {
                throw RecipeInstallError.InvalidResource("compiled schema cannot be parsed: " + sourceID)
            }
        if (root["schema"].asStringMap()?.get("schema_id") != sourceID) {
            throw RecipeInstallError.InvalidResource("compiled schema identity mismatch: " + sourceID)
        }
        val translators =
            root["engine"].asStringMap()?.get("translators").asStringList()
                ?: return Adaptability.Unsupported("no engine translators")
        val producer =
            primaryProducer(translators)
                ?: return Adaptability.Unsupported("no single primary script_translator/table_translator")
        if (!producer.isDefaultNamespace) {
            return Adaptability.Unsupported("only a default-namespace primary producer is auto-adaptable")
        }
        val speller = root["speller"].asStringMap()
        val dictionary = root[producer.namespace].asStringMap()?.get("dictionary") as? String
        if (speller == null || dictionary.isNullOrEmpty()) {
            throw RecipeInstallError.InvalidResource("compiled schema lacks the primary translator or dictionary")
        }
        val alphabet = speller["alphabet"] as? String ?: FOLDABLE_LETTERS
        val folds =
            KeyboardLayoutKind.entries.mapNotNull { it.compactFold }.any {
                physicalFold(alphabet, it) != null
            }
        if (!folds) return Adaptability.Unsupported("speller alphabet has no lowercase latin key to fold")
        return Adaptability.Adaptable
    }

    // ------------------------------------------------------------------------------------
    // Projection and capability model
    // ------------------------------------------------------------------------------------

    /**
     * One spelling after source-algebra projection; the flags come from librime's final script
     * properties, never from rule names.
     */
    data class ProjectedSpelling(
        val text: String,
        val isNormal: Boolean,
        val isCorrection: Boolean,
    )

    /** One source syllabary token and all of its final spellings. */
    data class ProjectedSyllable(
        val token: String,
        val spellings: List<ProjectedSpelling>,
    )

    /** Product of the native `rb_syllable_display_map_json`: source identity, config facts and projection. */
    data class SourceProjection(
        val sourceSchema: String,
        val translatorNamespace: String,
        val dictionary: String,
        val alphabet: String,
        val algebra: List<String>,
        val separator: String,
        /** The source's explicit semantic declaration (`keyboard_adapter_source`). */
        val declaration: String = "",
        /** The single producer's class, or empty when the native primitive reported none. */
        val producerClass: String = "",
        /** The explicit opt-in for the letter-class prefix layer (`keyboard_rewrite_prefix`). */
        val prefixDeclaration: String = "",
        val syllables: List<ProjectedSyllable>,
    ) {
        val isTableSource: Boolean get() = producerClass == "table_translator"

        companion object {
            /** Parses the native JSON; null when a required field is missing or malformed. */
            fun decode(json: ByteArray): SourceProjection? {
                val root =
                    try {
                        Json.parseToJsonElement(String(json, Charsets.UTF_8)).jsonObject
                    } catch (_: Exception) {
                        return null
                    }

                fun string(key: String): String? = (root[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                val sourceSchema = string("source_schema")?.takeIf { it.isNotEmpty() } ?: return null
                val namespace = string("translator_namespace")?.takeIf { it.isNotEmpty() } ?: return null
                val dictionary = string("dictionary")?.takeIf { it.isNotEmpty() } ?: return null
                val rawSyllables = root["syllables"] as? JsonObject ?: return null
                val algebraArray = root["algebra"] as? JsonArray
                val algebra =
                    algebraArray
                        ?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                        ?.takeIf { items -> items.all { it != null } }
                        ?.map { it!! } ?: emptyList()
                val syllables = ArrayList<ProjectedSyllable>()
                for ((token, value) in rawSyllables) {
                    val entries = value as? JsonArray ?: return null
                    val spellings = ArrayList<ProjectedSpelling>()
                    for (entry in entries) {
                        val map = entry as? JsonObject ?: return null
                        val text = (map["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
                        spellings.add(ProjectedSpelling(text, flag(map["normal"]), flag(map["correction"])))
                    }
                    syllables.add(ProjectedSyllable(token, spellings))
                }
                return SourceProjection(
                    sourceSchema = sourceSchema,
                    translatorNamespace = namespace,
                    dictionary = dictionary,
                    alphabet = string("alphabet") ?: "",
                    algebra = algebra,
                    separator = string("separator") ?: " ",
                    declaration = string("declaration") ?: "",
                    producerClass = string("producer_class") ?: "",
                    prefixDeclaration = string("prefix_declaration") ?: "",
                    syllables = syllables,
                )
            }

            // JSONSerialization yields NSNumber for booleans, so a number counts by its truthiness.
            private fun flag(element: kotlinx.serialization.json.JsonElement?): Boolean {
                val primitive = element as? JsonPrimitive ?: return false
                primitive.booleanOrNull?.let { return it }
                return primitive.doubleOrNull?.let { it != 0.0 } ?: false
            }
        }
    }

    /**
     * The `keyboard_adapter` capability written at generation time. `source_code` carries no
     * `preedit_format`: the encoding the user asked for must not be polluted by expansion rules.
     */
    data class DisplayCapability(
        val version: Int,
        val mode: String,
        val sourceSchema: String,
        val translatorNamespace: String,
        val separator: String,
        val mappingPolicy: String,
        /** Companion schema id the block belongs to; a block copied elsewhere is not trusted. */
        val adapterSchema: String = "",
        /** Tokens with a unique normal code only. */
        val syllables: Map<String, String>,
        val lockMap: Map<String, String> = emptyMap(),
        /** Rejected tokens and reasons, for deploy-time diagnostics only. */
        val rejectedTokens: Map<String, String> = emptyMap(),
    ) {
        fun settingAdapterSchema(schemaID: String): DisplayCapability = copy(adapterSchema = schemaID)

        fun wireDictionary(): Map<String, Any?> {
            val wire =
                linkedMapOf<String, Any?>(
                    "version" to version,
                    "display_mode" to mode,
                    "source_schema" to sourceSchema,
                    "translator_namespace" to translatorNamespace,
                    "separator" to separator,
                    "mapping_policy" to mappingPolicy,
                    "adapter_schema" to adapterSchema,
                    "syllables" to syllables,
                    "rejected_tokens" to rejectedTokens,
                )
            if (lockMap.isNotEmpty()) wire["lock_map"] = lockMap
            return wire
        }
    }

    /**
     * The schema-agnostic input-rewrite declaration (`keyboard_rewrite`), independent of display.
     * Alias (v2) carries `lockMap`, direct (v3) a bounded `directTokens` sample, prefix (v4) a
     * bounded `prefixKeys` sample plus the layer version.
     */
    data class RewriteCapability(
        val version: Int,
        val lock: String,
        val sourceSchema: String,
        val translatorNamespace: String,
        val adapterSchema: String,
        val lockMap: Map<String, String>,
        val directTokens: List<String> = emptyList(),
        val prefixKeys: List<String> = emptyList(),
        val prefixLayer: String = "",
    ) {
        fun settingAdapterSchema(schemaID: String): RewriteCapability = copy(adapterSchema = schemaID)

        fun wireDictionary(): Map<String, Any?> {
            val wire =
                linkedMapOf<String, Any?>(
                    "version" to version,
                    "lock" to lock,
                    "adapter_schema" to adapterSchema,
                    "source_schema" to sourceSchema,
                    "translator_namespace" to translatorNamespace,
                )
            if (lockMap.isNotEmpty()) wire["lock_map"] = lockMap
            if (directTokens.isNotEmpty()) wire["direct_tokens"] = directTokens
            if (prefixKeys.isNotEmpty()) {
                wire["prefix_keys"] = prefixKeys
                wire["prefix_layer"] = prefixLayer
            }
            return wire
        }
    }

    enum class RejectionReason(
        val wire: String,
    ) {
        /** No final normal non-correction spelling (including erase / uncovered). */
        MISSING_NORMAL("missing_normal"),

        /** Several final normal spellings: ambiguous, never pick the first. */
        AMBIGUOUS_NORMAL("ambiguous_normal"),

        /** Source code is not pure ASCII letters (e.g. bopomofo). */
        NON_ALPHABETIC("non_alphabetic"),
    }

    data class DisplayCapabilityRejection(
        val token: String,
        val reason: RejectionReason,
        val detail: String,
    )

    data class DisplayCapabilityBuild(
        val capability: DisplayCapability,
        val rejections: List<DisplayCapabilityRejection>,
    )

    sealed class DisplayCapabilityError(
        message: String,
    ) : Exception(message) {
        data object EmptySourceSchema : DisplayCapabilityError("empty source schema")

        data object EmptyTokenDomain : DisplayCapabilityError("empty token domain")

        class DuplicateToken(
            val token: String,
        ) : DisplayCapabilityError("duplicate token: " + token)

        /** Every token was rejected: no provable display capability. */
        data object EmptyResolvedDomain : DisplayCapabilityError("empty resolved domain")

        data object EmptySeparator : DisplayCapabilityError("empty separator")

        class IncompleteDomain(
            val tokens: List<String>,
        ) : DisplayCapabilityError("incomplete domain")
    }

    // ------------------------------------------------------------------------------------
    // Physical fold (shared by adaptability and generation)
    // ------------------------------------------------------------------------------------

    /** A validated physical fold for one source alphabet. */
    data class PhysicalFold(
        /** `exactTokenRules`, then `derive/<source letter>/<compact key>/` per present letter. */
        val rules: List<String>,
        /** `derive/<letter>/<exact token>/` for each key letter another present letter folds onto. */
        val exactTokenRules: List<String>,
        /** The exact tokens in use, in rule order. */
        val exactTokens: String,
        /** `xlit/<tokens>/<letters>/` so an exact pick shows its letter in the preedit. */
        val exactTokenPreedit: String?,
        val sourceAlphabet: String,
        /** What the companion must accept so compact keys and direct source codes re-syllabify. */
        val typeableAlphabet: String,
        /** Declared source characters the layout never sends, sorted; neither folded nor accepted. */
        val ignoredCharacters: String,
    )

    /**
     * The single physical-alphabet contract consumed by both [adaptability] and generation. Null
     * only when the source declares no lowercase Latin letter. Every other declared character is
     * typeable (lowercase letters, pass-through punctuation, 2-9 on the 9-key fold, uppercase whose
     * lowercase key is declared too) or ignored: tone digits, `\`, an uppercase-only key or a
     * non-Latin character are never sent by a compact layout, so the companion neither folds nor
     * accepts them and spellings needing them stay unreachable there.
     */
    fun physicalFold(
        sourceAlphabet: String,
        compactFold: CompactFold,
    ): PhysicalFold? {
        val nineKey = compactFold.isPhoneDigits
        val source = sourceAlphabet.toSet()
        val uppercaseLetters = FOLDABLE_LETTERS.uppercase().toSet()
        val present = FOLDABLE_LETTERS.filter { it in sourceAlphabet }
        if (present.isEmpty()) return null
        val passThrough = HashSet<Char>(FOLDABLE_PUNCTUATION.toList())
        if (nineKey) passThrough.addAll("23456789".toList())
        val ignored =
            sourceAlphabet.filter { character ->
                when {
                    character in present -> false
                    character in passThrough -> false
                    character !in uppercaseLetters -> true
                    else -> character.lowercaseChar() !in source
                }
            }

        val foldMap = compactFold.map
        // A key letter needs an exact token only when another present letter shares its key.
        val sharedKeys = present.mapNotNull { letter -> foldMap[letter]?.takeIf { it != letter } }.toSet()
        val exactTokens =
            present.filter { it in sharedKeys }.mapNotNull { letter ->
                compactFold.exactTokens[letter]?.let { letter to it }
            }
        val exactTokenRules = exactTokens.map { "derive/${it.first}/${it.second}/" }
        val rules = ArrayList(exactTokenRules)
        for (letter in present) {
            val folded = foldMap[letter] ?: continue
            if (folded != letter) rules.add("derive/$letter/$folded/")
            val uppercase = letter.uppercaseChar()
            if (uppercase in source) rules.add("derive/$uppercase/$folded/")
        }
        val declaredUppercase = source.filter { it in uppercaseLetters && it !in ignored }
        val typeable =
            (
                compactFold.keysSent + present.toSet() + exactTokens.map { it.second } +
                    declaredUppercase + sourceAlphabet.filter { it in FOLDABLE_PUNCTUATION }.toSet()
            ).sorted().joinToString("")
        return PhysicalFold(
            rules = rules,
            exactTokenRules = exactTokenRules,
            exactTokens = exactTokens.map { it.second }.joinToString(""),
            exactTokenPreedit =
                if (exactTokens.isEmpty()) {
                    null
                } else {
                    "xlit/${exactTokens.map { it.second }.joinToString("")}/" +
                        "${exactTokens.map { it.first }.joinToString("")}/"
                },
            sourceAlphabet = sourceAlphabet,
            typeableAlphabet = typeable,
            ignoredCharacters = ignored.toSet().sorted().joinToString(""),
        )
    }

    /** Runs before the source's own preedit rules, which then see the letters a tap would send. */
    private fun addExactTokenPreedit(
        rule: String?,
        translator: MutableMap<String, Any?>,
    ) {
        if (rule == null) return
        translator["preedit_format"] = listOf(rule) + (translator["preedit_format"].asStringList() ?: emptyList())
    }

    // ------------------------------------------------------------------------------------
    // Script (pinyin / double pinyin) generation
    // ------------------------------------------------------------------------------------

    private class Parsed(
        val root: MutableMap<String, Any?>,
        val producer: PrimaryProducer,
    )

    private fun invalid(detail: String): Nothing = throw RecipeInstallError.InvalidResource(detail)

    private fun parseCompiled(
        compiled: ByteArray,
        sourceID: String,
        klass: ProducerClass,
        failure: String,
    ): Parsed {
        val text = if (compiled.size <= RecipeResourceBudget.SOURCE_FILE) decodeUtf8(compiled) else null
        val root = text?.let { YamsYaml.load(it).asStringMap() }
        val schema = root?.get("schema").asStringMap()
        val producer = autoAdaptableProducer(compiled)
        if (root == null ||
            schema == null ||
            schema["schema_id"] != sourceID ||
            producer == null ||
            producer.klass != klass
        ) {
            invalid(failure)
        }
        return Parsed(root.toMutableMap(), producer)
    }

    private fun generateScript(
        compiled: ByteArray,
        sourceID: String,
        compactFold: CompactFold,
        canonicalTelephoneDerive: Boolean,
        prependAlgebra: List<String>?,
        extraAlphabet: String,
        eraseSourceDigitSpellings: Boolean = false,
    ): ByteArray {
        val failure = "scheme is not an auto-adaptable pinyin or double-pinyin configuration"
        val parsed = parseCompiled(compiled, sourceID, ProducerClass.SCRIPT, failure)
        val root = parsed.root
        val producer = parsed.producer
        val schema = root["schema"].asStringMap()!!.toMutableMap()
        val speller = root["speller"].asStringMap()?.toMutableMap() ?: invalid(failure)
        val translator = root[producer.namespace].asStringMap()?.toMutableMap() ?: invalid(failure)
        val dictionary = translator["dictionary"] as? String
        if (dictionary.isNullOrEmpty()) invalid(failure)
        val alphabet = speller["alphabet"] as? String ?: FOLDABLE_LETTERS
        val foldMap = compactFold.map
        val nineKey = compactFold.isPhoneDigits
        val fold =
            physicalFold(alphabet, compactFold)
                ?: invalid("scheme alphabet cannot be adapted to the compact keyboard layout")
        val presentLetters = FOLDABLE_LETTERS.filter { it in alphabet }
        val mergeSource = presentLetters
        val mergeTarget = presentLetters.map { foldMap[it] ?: it }.joinToString("")
        val target = identifier(sourceID, compactFold)
        schema["schema_id"] = target
        // The generated name suffix is data written into the companion file, not UI text.
        schema["name"] = "${schema["name"] as? String ?: sourceID} · ${compactFold.id}键适配"
        root["schema"] = schema
        // The dictionary's default user_dict is the dictionary name, NOT the new schema id.
        if (translator["user_dict"] == null) translator["user_dict"] = dictionary
        translator["prism"] = target
        // Typo correction substitutes physical QWERTY neighbours; meaningless on merged or numeric keys.
        if (translator[TypoCorrection.CONFIG_LEAF] != null) translator[TypoCorrection.CONFIG_LEAF] = false
        if (!nineKey) addExactTokenPreedit(fold.exactTokenPreedit, translator)
        root[producer.namespace] = translator
        val algebra = ArrayList(speller["algebra"].asStringList() ?: emptyList())
        val preserveCanonical = nineKey && canonicalTelephoneDerive
        // The alias layer goes BEFORE the untouched source algebra so every source key stays identical.
        if (prependAlgebra != null) algebra.addAll(0, prependAlgebra)
        if (nineKey && eraseSourceDigitSpellings) {
            // Before the fold: once letters become digits, a source digit could no longer be told
            // apart from a telephone key.
            algebra.add(SOURCE_DIGIT_SPELLING_ERASE)
        }
        if (preserveCanonical) {
            algebra.addAll(TELEPHONE_DERIVES)
        } else if (!nineKey) {
            // Letter groups ADD the folded spelling per letter and keep the source letters, so an
            // exact letter picked by a sideways swipe still spells.
            algebra.addAll(fold.exactTokenRules)
            for (letter in mergeSource) {
                val folded = foldMap[letter]
                if (folded != null && folded != letter) algebra.add("derive/$letter/$folded/")
            }
        } else {
            algebra.add("xlit/$mergeSource/$mergeTarget/")
        }
        speller["algebra"] = algebra
        val typeable =
            (fold.typeableAlphabet.toSet() + extraAlphabet.toSet()).sorted().joinToString("")
        speller["alphabet"] = typeable
        speller["initials"] = typeable
        root["speller"] = speller
        // A bare numeric spelling must reach abc_segmentor, not a number recognizer.
        val digits = "23456789"
        val samples =
            if (nineKey) {
                digits.map { it.toString() } + listOf(digits, digits.take(2), digits.take(5))
            } else {
                fold.exactTokens.flatMap { listOf(it.toString(), "a$it") }
            }
        if (samples.isNotEmpty()) stripRecognizerPatterns(root, samples)
        // Numeric candidate shortcuts must not consume the adapter's composition keys.
        if (nineKey) {
            val binder = root["key_binder"].asStringMap()?.toMutableMap()
            val bindings = binder?.get("bindings") as? List<*>
            if (binder != null && bindings != null && bindings.all { it.asStringMap() != null }) {
                binder["bindings"] =
                    bindings.filter { entry ->
                        val accept = entry.asStringMap()!!["accept"] as? String ?: return@filter true
                        !(accept.length == 1 && accept[0] in digits)
                    }
                root["key_binder"] = binder
            }
        }
        root.remove("__build_info")
        return YamsYaml.dump(root).toByteArray(Charsets.UTF_8)
    }

    /** Removes every recognizer pattern that matches one of [samples]. */
    private fun stripRecognizerPatterns(
        root: MutableMap<String, Any?>,
        samples: List<String>,
    ) {
        val recognizer = root["recognizer"].asStringMap()?.toMutableMap() ?: return
        val raw = recognizer["patterns"].asStringMap() ?: return
        if (raw.values.any { it !is String }) return
        val patterns = LinkedHashMap<String, Any?>(raw)
        for ((name, pattern) in raw) {
            val regex =
                try {
                    Regex(pattern as String)
                } catch (_: Exception) {
                    invalid("recognizer rule cannot be checked: " + name)
                }
            if (samples.any { regex.containsMatchIn(it) }) patterns.remove(name)
        }
        recognizer["patterns"] = patterns
        root["recognizer"] = recognizer
    }

    // ------------------------------------------------------------------------------------
    // Table (shape-code) generation
    // ------------------------------------------------------------------------------------

    /**
     * A bounded, deterministic deploy-check sample of direct tokens: the first [limit] sorted
     * tokens uniquely identified by their own code within their fold class. Null when none.
     */
    fun directTokenSample(
        projection: SourceProjection,
        compactFold: CompactFold,
        limit: Int = 16,
    ): List<String>? {
        val foldMap = HashMap(compactFold.map)
        // Uppercase is admitted only as a paired source key by physicalFold; keep equivalence
        // identical to the generated derive layer.
        for ((letter, folded) in compactFold.map) foldMap[letter.uppercaseChar()] = folded

        fun fold(value: String): String = value.map { foldMap[it] ?: it }.joinToString("")

        fun isPartialFold(
            candidate: String,
            code: String,
        ): Boolean {
            if (candidate.length != code.length) return false
            for ((c, k) in candidate.zip(code)) {
                val folded = foldMap[k] ?: k
                if (c != k && c != folded) return false
            }
            return true
        }
        val byFold = LinkedHashMap<String, MutableList<String>>()
        for (syllable in projection.syllables) byFold.getOrPut(fold(syllable.token)) { ArrayList() }.add(syllable.token)
        val sample = ArrayList<String>()
        for (token in projection.syllables.map { it.token }.sortedWith { a, b -> YamsYaml.compareStrings(a, b) }) {
            val members = (byFold[fold(token)] ?: emptyList()).filter { isPartialFold(token, it) }
            if (members.size == 1) sample.add(token)
            if (sample.size >= maxOf(1, limit)) break
        }
        return sample.ifEmpty { null }
    }

    /**
     * A `table_translator` compact companion. Keeps the source's engine lists, auxiliary config
     * sections and segmentors verbatim; renames the schema and prism and rewrites only the primary
     * default `translator` section, then declares the direct rewrite capability.
     */
    private fun generateTableCompanion(
        compiled: ByteArray,
        sourceID: String,
        compactFold: CompactFold,
        projection: SourceProjection,
    ): ByteArray {
        val fold =
            physicalFold(projection.alphabet, compactFold)
                ?: invalid("scheme alphabet cannot be adapted to the compact keyboard layout")
        val failure = "scheme is not an auto-adaptable code table configuration"
        val parsed = parseCompiled(compiled, sourceID, ProducerClass.TABLE, failure)
        val root = parsed.root
        val producer = parsed.producer
        val schema = root["schema"].asStringMap()!!.toMutableMap()
        val translator = root[producer.namespace].asStringMap()?.toMutableMap() ?: invalid(failure)
        val dictionary = translator["dictionary"] as? String
        if (dictionary.isNullOrEmpty()) invalid(failure)
        val target = identifier(sourceID, compactFold)
        schema["schema_id"] = target
        schema["name"] = "${schema["name"] as? String ?: sourceID} · ${compactFold.id}键适配"
        root["schema"] = schema
        if (translator["user_dict"] == null) translator["user_dict"] = dictionary
        translator["prism"] = target
        if (translator[TypoCorrection.CONFIG_LEAF] != null) translator[TypoCorrection.CONFIG_LEAF] = false
        addExactTokenPreedit(fold.exactTokenPreedit, translator)
        root[producer.namespace] = translator

        val algebra = ArrayList(root["speller"].asStringMap()?.get("algebra").asStringList() ?: emptyList())
        if (compactFold.isPhoneDigits && erasesSourceDigitSpellings(projection)) {
            algebra.add(SOURCE_DIGIT_SPELLING_ERASE)
        }
        algebra.addAll(fold.rules)
        val speller = root["speller"].asStringMap()?.toMutableMap() ?: linkedMapOf()
        speller["algebra"] = algebra
        speller["alphabet"] = fold.typeableAlphabet
        speller["initials"] = fold.typeableAlphabet
        root["speller"] = speller

        // A bare compact-code spelling must reach abc_segmentor, not a recognizer.
        val keys = fold.typeableAlphabet
        stripRecognizerPatterns(root, keys.map { it.toString() } + listOf(keys.take(4)))
        root.remove("__build_info")

        val base = YamsYaml.dump(root).toByteArray(Charsets.UTF_8)
        // No uniquely identifiable code means no bounded direct lock: fail closed, no capability.
        val sample =
            directTokenSample(projection, compactFold)
                ?: return injectingCapability(base, target, displayCapability = null, rewriteCapability = null)
        val capability =
            RewriteCapability(
                version = REWRITE_WIRE_VERSION_DIRECT,
                lock = DIRECT_TOKEN_LOCK_VALUE,
                sourceSchema = projection.sourceSchema,
                translatorNamespace = projection.translatorNamespace,
                adapterSchema = target,
                lockMap = emptyMap(),
                directTokens = sample,
            )
        return injectingCapability(base, target, displayCapability = null, rewriteCapability = capability)
    }

    // ------------------------------------------------------------------------------------
    // Letter-class prefix layer (v4)
    // ------------------------------------------------------------------------------------

    fun declaresLetterClassPrefixSource(projection: SourceProjection): Boolean =
        projection.prefixDeclaration == LETTER_CLASS_PREFIX_LAYER_VALUE

    /**
     * The distinct lowercase-ASCII first letters of the source's own token inventory, sorted; null
     * (fail closed) when there are none or more than the versioned cap.
     */
    fun letterClassPrefixKeys(projection: SourceProjection): List<String>? {
        val keys = sortedSetOf<String>()
        for (syllable in projection.syllables) {
            val first = syllable.token.firstOrNull() ?: continue
            if (first in 'a'..'z') keys.add(first.toString())
        }
        if (keys.isEmpty() || keys.size > LETTER_CLASS_PREFIX_KEY_LIMIT) return null
        return keys.toList()
    }

    /** Whether the source's own prism already provides every class key (a token spelled exactly so). */
    fun hasLetterClassKeys(
        projection: SourceProjection,
        keys: List<String>,
    ): Boolean {
        val provided = HashSet<String>()
        for (syllable in projection.syllables) {
            for (spelling in syllable.spellings) {
                if (spelling.isNormal && !spelling.isCorrection) provided.add(spelling.text)
            }
        }
        return keys.all { it in provided }
    }

    /**
     * Authorization for a generated v4 companion: its own declaration must be what the caller is
     * about to write, and every sampled key must be accepted by its alphabet and initials.
     */
    fun prefixLockAuthorized(
        sourceProjection: SourceProjection?,
        companion: ByteArray,
        keys: List<String>,
        layerAlreadyPresent: Boolean,
    ): Boolean {
        val projection = sourceProjection ?: return false
        if (!declaresLetterClassPrefixSource(projection) ||
            keys.isEmpty() ||
            keys.size > LETTER_CLASS_PREFIX_KEY_LIMIT
        ) {
            return false
        }
        val root = decodeUtf8(companion)?.let { YamsYaml.loadMap(it) } ?: return false
        val speller = root["speller"].asStringMap() ?: return false
        val alphabet = speller["alphabet"] as? String ?: return false
        val initials = speller["initials"] as? String ?: alphabet
        val algebra = speller["algebra"].asStringList() ?: emptyList()
        val hasRule = algebra.contains(LETTER_CLASS_PREFIX_RULE)
        if (!hasRule && !layerAlreadyPresent) return false
        return keys.all { key -> key.all { it in alphabet && it in initials } }
    }

    private val NUMERIC_DIGITS = "23456789".toSet()

    /**
     * Removes, ahead of a 9-key fold, every spelling carrying a source digit. On the 9-key layout
     * the digits are the letter keys, so a source tone digit would read as a key press once the
     * letters fold (`hao9` would become the code of `hao` followed by w/x/y/z). Alias spellings
     * (`90...`) are kept for the rewrite; they contain `0`, which no compact key sends.
     */
    const val SOURCE_DIGIT_SPELLING_ERASE = "erase/^(?!$TOKEN_ALIAS_SENTINEL[0-9]*$).*[0-9].*$/"

    /**
     * Whether the 9-key companion must apply [SOURCE_DIGIT_SPELLING_ERASE]: the source spells with
     * digits but is not itself a telephone-digit code space, whose digits already mean the keys.
     */
    fun erasesSourceDigitSpellings(projection: SourceProjection): Boolean {
        if (isNumericCodeSource(projection)) return false
        return projection.syllables.any { syllable ->
            syllable.spellings.any { spelling -> spelling.text.any { it in '0'..'9' } }
        }
    }

    /**
     * Whether typing on the compact layout can reach at least one source spelling: one made only of
     * keys the layout sends (and digit-free when the 9-key companion erases digit spellings).
     */
    fun hasReachableSpelling(
        projection: SourceProjection,
        fold: PhysicalFold,
        compactFold: CompactFold,
    ): Boolean {
        val keys = fold.sourceAlphabet.toSet() - fold.ignoredCharacters.toSet()
        val digitsErased = compactFold.isPhoneDigits && erasesSourceDigitSpellings(projection)
        return projection.syllables.any { syllable ->
            syllable.spellings.any { spelling ->
                spelling.text.isNotEmpty() &&
                    spelling.text.all { it in keys && !(digitsErased && it in '0'..'9') }
            }
        }
    }

    /**
     * Whether every primary token has a final normal non-correction spelling that is a bare 2-9
     * code. Scans the complete projection, so it cannot depend on token or spelling order.
     */
    fun isNumericCodeSource(projection: SourceProjection): Boolean {
        if (projection.syllables.isEmpty()) return false
        for (syllable in projection.syllables) {
            val numeric =
                syllable.spellings.any { spelling ->
                    spelling.isNormal &&
                        !spelling.isCorrection &&
                        spelling.text.isNotEmpty() &&
                        spelling.text.all { it in NUMERIC_DIGITS }
                }
            if (!numeric) return false
        }
        return true
    }

    // ------------------------------------------------------------------------------------
    // Bounded token alias layer (v2)
    // ------------------------------------------------------------------------------------

    /** Fixed index width for a domain of [count] tokens: equal-length aliases are prefix-free. */
    fun tokenAliasWidth(count: Int): Int = maxOf(TOKEN_ALIAS_MIN_WIDTH, maxOf(0, count - 1).toString().length)

    // Unicode White_Space, which Swift's `Unicode.Scalar.Properties.isWhitespace` reports.
    private fun isUnicodeWhitespace(c: Int): Boolean =
        c in 0x09..0x0D ||
            c == 0x20 ||
            c == 0x85 ||
            c == 0xA0 ||
            c == 0x1680 ||
            c in 0x2000..0x200A ||
            c == 0x2028 ||
            c == 0x2029 ||
            c == 0x202F ||
            c == 0x205F ||
            c == 0x3000

    // The algebra parser splits on '/', and whitespace or controls would break the rule.
    private fun isRepresentableTokenScalar(c: Int): Boolean =
        !(c == '/'.code || c < 0x20 || c == 0x7F || isUnicodeWhitespace(c))

    private val ALIAS_PATTERN_METACHARACTERS = ".^$|()[]{}*+?\\".map { it.code }.toSet()

    /** `^<regex-escaped token>$`, or null when the token cannot be represented exactly. */
    fun aliasPattern(token: String): String? {
        if (token.isEmpty()) return null
        val out = StringBuilder("^")
        for (scalar in token.codePoints().toArray()) {
            if (!isRepresentableTokenScalar(scalar)) return null
            if (scalar in ALIAS_PATTERN_METACHARACTERS) out.append('\\')
            out.appendCodePoint(scalar)
        }
        out.append('$')
        return out.toString()
    }

    /** Inverse of [aliasPattern] for an escaped body; only trusted when re-escaping reproduces it. */
    fun aliasToken(pattern: String): String? {
        val scalars = pattern.codePoints().toArray()
        val token = StringBuilder()
        var index = 0
        while (index < scalars.size) {
            val scalar = scalars[index]
            if (scalar == '\\'.code) {
                index++
                if (index >= scalars.size) return null
                token.appendCodePoint(scalars[index])
            } else {
                token.appendCodePoint(scalar)
            }
            index++
        }
        val value = token.toString()
        return if (aliasPattern(value) == "^$pattern$") value else null
    }

    /** Sorted primary tokens; null when empty, over the limit, duplicated or not exactly representable. */
    fun tokenAliasDomain(projection: SourceProjection): List<String>? {
        val tokens = projection.syllables.map { it.token }
        if (tokens.isEmpty() || tokens.size > TOKEN_ALIAS_LIMIT) return null
        val sorted = tokens.sortedWith { a, b -> YamsYaml.compareStrings(a, b) }
        if (sorted.toSet().size != sorted.size) return null
        for (token in sorted) if (aliasPattern(token) == null) return null
        return sorted
    }

    /** Deterministic alias assignment, in token order; empty when the domain is not aliasable. */
    fun tokenAliasAssignments(projection: SourceProjection): List<Pair<String, String>> {
        val domain = tokenAliasDomain(projection) ?: return emptyList()
        val width = tokenAliasWidth(domain.size)
        return domain.mapIndexed {
            index,
            token,
            ->
            token to TOKEN_ALIAS_SENTINEL + index.toString().padStart(width, '0')
        }
    }

    /** The deterministic token to alias map; empty when the domain is not aliasable. */
    fun lockMap(projection: SourceProjection): Map<String, String> = tokenAliasAssignmentMap(projection) ?: emptyMap()

    fun tokenAliasAssignmentMap(projection: SourceProjection): Map<String, String>? {
        val domain = tokenAliasDomain(projection) ?: return null
        val width = tokenAliasWidth(domain.size)
        val map = LinkedHashMap<String, String>()
        for ((index, token) in domain.withIndex()) {
            map[token] = TOKEN_ALIAS_SENTINEL + index.toString().padStart(width, '0')
        }
        return map
    }

    /** `derive/^<escaped token>$/9<index>/`, prepended before the source algebra. */
    fun tokenAliasDerives(projection: SourceProjection): List<String> =
        tokenAliasAssignments(projection).mapNotNull { (token, alias) ->
            aliasPattern(token)?.let { "derive/$it/$alias/" }
        }

    /** The distinct characters the aliases need in `speller/alphabet` and `initials`. */
    fun tokenAliasCharacters(projection: SourceProjection): String =
        tokenAliasAssignments(projection)
            .flatMap { it.second.toList() }
            .toSet()
            .sorted()
            .joinToString("")

    /**
     * Extracts an already-baked alias layer from a companion's `speller/algebra`. Null (fail
     * closed) unless the map is non-empty, bounded, unique and every entry round-trips.
     */
    fun existingAliasMap(companion: ByteArray): Map<String, String>? {
        val root = decodeUtf8(companion)?.let { YamsYaml.loadMap(it) } ?: return null
        val algebra = root["speller"].asStringMap()?.get("algebra").asStringList() ?: return null
        val map = LinkedHashMap<String, String>()
        var width: Int? = null
        for (rule in algebra) {
            if (!rule.startsWith("derive/^") || !rule.endsWith("/")) continue
            val body = rule.substring("derive/^".length, rule.length - 1)
            // A literal `$` in a token is escaped, so the first `$/` ends the anchored pattern.
            val anchor = body.indexOf("$/")
            if (anchor < 0) continue
            val alias = body.substring(anchor + 2)
            val aliasScalars = alias.codePoints().toArray()
            if (!alias.startsWith(TOKEN_ALIAS_SENTINEL) || aliasScalars.size <= TOKEN_ALIAS_SENTINEL.length) continue
            if (!aliasScalars.drop(TOKEN_ALIAS_SENTINEL.length).all { isNumberScalar(it) }) continue
            val thisWidth = aliasScalars.size - TOKEN_ALIAS_SENTINEL.length
            if (width != null && width != thisWidth) return null
            width = thisWidth
            val token = aliasToken(body.substring(0, anchor)) ?: return null
            if (map.containsKey(token)) return null
            map[token] = alias
        }
        if (map.isEmpty() || map.size > TOKEN_ALIAS_LIMIT || map.values.toSet().size != map.size) return null
        return map
    }

    // Swift `Character.isNumber`: any Unicode numeric character.
    private fun isNumberScalar(c: Int): Boolean =
        when (Character.getType(c).toByte()) {
            Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
            else -> false
        }

    /**
     * Authorization for the versioned alias rewrite: the companion's baked alias map must EQUAL the
     * current deterministic assignment, prepend exactly `derive/^token$/alias/` for every entry,
     * and its alphabet and initials must accept every alias character.
     */
    fun tokenAliasLockAuthorized(
        sourceProjection: SourceProjection?,
        companion: ByteArray,
    ): Boolean {
        val projection = sourceProjection ?: return false
        val expected = tokenAliasAssignmentMap(projection)?.takeIf { it.isNotEmpty() } ?: return false
        val baked = existingAliasMap(companion) ?: return false
        if (baked != expected) return false
        val root = decodeUtf8(companion)?.let { YamsYaml.loadMap(it) } ?: return false
        val speller = root["speller"].asStringMap() ?: return false
        val alphabet = speller["alphabet"] as? String ?: return false
        val initials = speller["initials"] as? String ?: alphabet
        val algebra = speller["algebra"].asStringList() ?: emptyList()
        for ((token, alias) in expected) {
            val pattern = aliasPattern(token) ?: return false
            if (!algebra.contains("derive/$pattern/$alias/")) return false
            if (!alias.all { it in alphabet && it in initials }) return false
        }
        return true
    }

    /** Lowercase ASCII letters only, bounded, and a standard pinyin syllable. */
    fun isCanonicalPinyinToken(token: String): Boolean {
        if (token.isEmpty() || token.toByteArray(Charsets.UTF_8).size > 6 || !token.all { it in 'a'..'z' }) return false
        return isPinyinSyllable(token)
    }

    /**
     * The only non-pinyin token shape tolerated: a single ASCII uppercase letter, the evidenced
     * shape of the 26 abbreviation extras in real double-pinyin dictionaries.
     */
    fun isUppercaseAbbreviationToken(token: String): Boolean = token.length == 1 && token[0] in 'A'..'Z'

    private val PINYIN_INITIALS =
        listOf(
            "zh",
            "ch",
            "sh",
            "b",
            "p",
            "m",
            "f",
            "d",
            "t",
            "n",
            "l",
            "g",
            "k",
            "h",
            "j",
            "q",
            "x",
            "r",
            "z",
            "c",
            "s",
        )
    private val PINYIN_FINALS =
        setOf(
            "a",
            "o",
            "e",
            "ai",
            "ei",
            "ao",
            "ou",
            "an",
            "en",
            "ang",
            "eng",
            "ong",
            "er",
            "i",
            "ia",
            "ie",
            "iao",
            "iu",
            "ian",
            "in",
            "iang",
            "ing",
            "iong",
            "u",
            "ua",
            "uo",
            "uai",
            "ui",
            "uan",
            "un",
            "uang",
            "ueng",
            "v",
            "ve",
            "van",
            "vn",
            "ue",
        )
    private val JQX_FINALS =
        setOf(
            "i",
            "ia",
            "ie",
            "iao",
            "iu",
            "ian",
            "in",
            "iang",
            "ing",
            "iong",
            "u",
            "ue",
            "uan",
            "un",
            "v",
            "ve",
            "van",
            "vn",
        )
    private val PINYIN_STANDALONE =
        setOf(
            "a",
            "o",
            "e",
            "ai",
            "ei",
            "ao",
            "ou",
            "an",
            "en",
            "ang",
            "eng",
            "er",
            "yi",
            "ya",
            "ye",
            "yo",
            "yao",
            "you",
            "yan",
            "yin",
            "yang",
            "ying",
            "yong",
            "wu",
            "wa",
            "wo",
            "wai",
            "wei",
            "wan",
            "wen",
            "wang",
            "weng",
            "yu",
            "yue",
            "yuan",
            "yun",
            "m",
            "n",
            "ng",
            "hm",
            "hng",
        )

    /**
     * Structure check: a zero-initial syllable must be on the allowlist; otherwise take the first
     * matching initial whose remainder is a valid final (j/q/x have extra final restrictions that
     * rule out shape-code tokens like `xx`).
     */
    internal fun isPinyinSyllable(value: String): Boolean {
        if (value.isEmpty() || value.length > 6) return false
        if (!value.all { it in 'a'..'z' }) return false
        if (value in PINYIN_STANDALONE) return true
        for (initial in PINYIN_INITIALS) {
            if (!value.startsWith(initial)) continue
            val final = value.substring(initial.length)
            if (final.isEmpty()) continue
            if (initial == "j" || initial == "q" || initial == "x") return final in JQX_FINALS
            if (final in PINYIN_FINALS) return true
        }
        return false
    }

    // ------------------------------------------------------------------------------------
    // Display capability
    // ------------------------------------------------------------------------------------

    /**
     * Builds the display capability from a verified projection by forward projection: a token is
     * written only when its final normal non-correction string is unique; ambiguity, missing
     * entries, erase and non-letter symbols reject that entry with a recorded reason.
     *
     * @param displaySeparator display joiner; the source delimiter when null.
     * @throws DisplayCapabilityError for an empty domain, separator, duplicate token or (with
     *   [requireFullDomain]) any rejection; [DisplayCapabilityError.EmptyResolvedDomain] when every
     *   token was rejected.
     */
    fun makeDisplayCapability(
        projection: SourceProjection,
        wireVersion: Int = DISPLAY_WIRE_VERSION,
        displaySeparator: String? = null,
        requireFullDomain: Boolean = false,
    ): DisplayCapabilityBuild {
        if (projection.sourceSchema.isEmpty()) throw DisplayCapabilityError.EmptySourceSchema
        if (projection.syllables.isEmpty()) throw DisplayCapabilityError.EmptyTokenDomain
        val separator = displaySeparator ?: projection.separator
        if (separator.isEmpty()) throw DisplayCapabilityError.EmptySeparator

        val seen = HashSet<String>()
        val resolved = LinkedHashMap<String, String>()
        val rejections = ArrayList<DisplayCapabilityRejection>()
        for (syllable in projection.syllables.sortedWith { a, b -> YamsYaml.compareStrings(a.token, b.token) }) {
            if (syllable.token.isEmpty()) throw DisplayCapabilityError.EmptyTokenDomain
            if (!seen.add(syllable.token)) throw DisplayCapabilityError.DuplicateToken(syllable.token)
            val distinct = HashSet<String>()
            for (spelling in syllable.spellings) {
                if (spelling.isNormal &&
                    !spelling.isCorrection &&
                    spelling.text.isNotEmpty()
                ) {
                    distinct.add(spelling.text)
                }
            }
            if (distinct.isEmpty()) {
                rejections.add(
                    DisplayCapabilityRejection(
                        syllable.token,
                        RejectionReason.MISSING_NORMAL,
                        "no final normal non-correction spelling",
                    ),
                )
                continue
            }
            if (distinct.size > 1) {
                rejections.add(
                    DisplayCapabilityRejection(
                        syllable.token,
                        RejectionReason.AMBIGUOUS_NORMAL,
                        distinct.sortedWith { a, b -> YamsYaml.compareStrings(a, b) }.joinToString(","),
                    ),
                )
                continue
            }
            val only = distinct.first()
            if (!isAsciiLetterCode(only)) {
                rejections.add(DisplayCapabilityRejection(syllable.token, RejectionReason.NON_ALPHABETIC, only))
                continue
            }
            resolved[syllable.token] = only
        }
        if (requireFullDomain && rejections.isNotEmpty()) {
            throw DisplayCapabilityError.IncompleteDomain(
                rejections.map { it.token }.sortedWith { a, b -> YamsYaml.compareStrings(a, b) },
            )
        }
        if (resolved.isEmpty()) throw DisplayCapabilityError.EmptyResolvedDomain
        val capability =
            DisplayCapability(
                version = wireVersion,
                mode = "source_code",
                sourceSchema = projection.sourceSchema,
                translatorNamespace = projection.translatorNamespace,
                separator = separator,
                mappingPolicy = "unique_normal",
                syllables = resolved,
                rejectedTokens = rejections.associate { it.token to it.reason.wire },
            )
        return DisplayCapabilityBuild(
            capability,
            rejections.sortedWith { a, b -> YamsYaml.compareStrings(a.token, b.token) },
        )
    }

    // ASCII letters, or the `; ' ` punctuation the compact layouts use.
    private fun isAsciiLetterCode(value: String): Boolean =
        value.isNotEmpty() &&
            value.all { it in 'A'..'Z' || it in 'a'..'z' || it == ';' || it == '\'' || it == '`' }

    /** Canonical display for sources explicitly declared canonical pinyin (carries no preedit_format). */
    fun canonicalDisplayCapability(
        sourceSchema: String,
        separator: String,
    ): DisplayCapability =
        DisplayCapability(
            version = DISPLAY_WIRE_VERSION,
            mode = "canonical",
            sourceSchema = sourceSchema,
            translatorNamespace = "translator",
            separator = separator.ifEmpty { " " },
            mappingPolicy = "",
            syllables = emptyMap(),
        )

    fun declaresCanonicalPinyinSource(projection: SourceProjection): Boolean =
        projection.declaration == "canonical_pinyin"

    fun declaresCanonicalPinyinSource(compiled: ByteArray): Boolean {
        val root = decodeUtf8(compiled)?.let { YamsYaml.loadMap(it) } ?: return false
        return root["keyboard_adapter_source"] == "canonical_pinyin"
    }

    /** Every token of the primary table is a standard pinyin syllable. */
    fun isCanonicalPinyinDomain(tokens: List<String>): Boolean =
        tokens.isNotEmpty() && tokens.all { isPinyinSyllable(it) }

    /**
     * Canonical-pinyin domain proof that tolerates the evidenced uppercase `A`-`Z` extras: every
     * token must be a canonical syllable or such an extra, with at least one canonical token.
     */
    fun isCanonicalPinyinDomainAllowingBenignExtras(tokens: List<String>): Boolean {
        if (tokens.isEmpty()) return false
        var canonical = 0
        for (token in tokens) {
            if (isCanonicalPinyinToken(token)) {
                canonical++
            } else if (!isUppercaseAbbreviationToken(token)) {
                return false
            }
        }
        return canonical > 0
    }

    /**
     * Writes the capability blocks onto existing schema data. A null capability explicitly removes
     * the block inherited from the source clone, so a companion never carries a stale one.
     */
    fun injectingCapability(
        compiled: ByteArray,
        adapterSchema: String,
        displayCapability: DisplayCapability?,
        rewriteCapability: RewriteCapability? = null,
    ): ByteArray {
        val root =
            decodeUtf8(compiled)?.let { YamsYaml.loadMap(it) }?.toMutableMap()
                ?: invalid("cannot reread the schema to add capabilities")
        root.remove("keyboard_adapter")
        root.remove("keyboard_rewrite")
        if (displayCapability != null) {
            root["keyboard_adapter"] = displayCapability.settingAdapterSchema(adapterSchema).wireDictionary()
        }
        if (rewriteCapability != null) {
            root["keyboard_rewrite"] = rewriteCapability.settingAdapterSchema(adapterSchema).wireDictionary()
        }
        return YamsYaml.dump(root).toByteArray(Charsets.UTF_8)
    }

    /**
     * Generates one companion. Dispatches on the same primary-producer contract as [adaptability],
     * read from the compiled schema: a table source gets the direct-code companion, a script
     * source the alias (v2) or, for opted-in numeric sources, the letter-class prefix (v4) layer.
     * Rewrite capabilities are independent of [displayCapability].
     */
    fun generate(
        compiled: ByteArray,
        sourceID: String,
        compactFold: CompactFold,
        displayCapability: DisplayCapability?,
        sourceProjection: SourceProjection? = null,
    ): ByteArray {
        val producer =
            autoAdaptableProducer(compiled)
                ?: invalid("scheme has no default primary translator and cannot be adapted")
        val nineKey = compactFold.isPhoneDigits
        if (producer.klass == ProducerClass.TABLE) {
            val projection = sourceProjection ?: invalid("code table adapter needs the source projection")
            return generateTableCompanion(compiled, sourceID, compactFold, projection)
        }
        val proven =
            sourceProjection?.let {
                declaresCanonicalPinyinSource(it) &&
                    isCanonicalPinyinDomainAllowingBenignExtras(it.syllables.map { s -> s.token })
            } ?: false
        val preserveCanonical = nineKey && proven

        // v4: only a 9-key companion of a source that explicitly opted in and whose own spelling
        // space is numeric. It replaces the alias layer with a direct rewrite to the live key.
        val prefixKeys: List<String>? =
            if (nineKey &&
                sourceProjection != null &&
                declaresLetterClassPrefixSource(sourceProjection) &&
                isNumericCodeSource(sourceProjection)
            ) {
                letterClassPrefixKeys(sourceProjection)
            } else {
                null
            }
        val prefixLayerAlreadyPresent =
            sourceProjection != null && prefixKeys != null && hasLetterClassKeys(sourceProjection, prefixKeys)
        val prefixRewrite = prefixKeys != null
        val aliasRewrite = !prefixRewrite && sourceProjection != null && tokenAliasDomain(sourceProjection) != null
        val prependAlgebra: List<String>? =
            if (prefixRewrite) {
                // The abbreviation layer must precede the fold so the single letter folds into its digit class.
                if (prefixLayerAlreadyPresent) null else listOf(LETTER_CLASS_PREFIX_RULE)
            } else if (aliasRewrite) {
                tokenAliasDerives(sourceProjection)
            } else {
                null
            }
        val base =
            generateScript(
                compiled,
                sourceID,
                compactFold,
                canonicalTelephoneDerive = preserveCanonical,
                prependAlgebra = prependAlgebra,
                extraAlphabet = if (aliasRewrite) tokenAliasCharacters(sourceProjection) else "",
                eraseSourceDigitSpellings = sourceProjection?.let(::erasesSourceDigitSpellings) ?: false,
            )
        val target = identifier(sourceID, compactFold)
        // Authorized only when the companion's own baked layer is exactly what was intended.
        var rewrite: RewriteCapability? = null
        if (prefixKeys != null &&
            sourceProjection != null &&
            prefixLockAuthorized(sourceProjection, base, prefixKeys, prefixLayerAlreadyPresent)
        ) {
            rewrite =
                RewriteCapability(
                    version = REWRITE_WIRE_VERSION_PREFIX,
                    lock = PREFIX_TOKEN_LOCK_VALUE,
                    sourceSchema = sourceProjection.sourceSchema,
                    translatorNamespace = sourceProjection.translatorNamespace,
                    adapterSchema = target,
                    lockMap = emptyMap(),
                    prefixKeys = prefixKeys,
                    prefixLayer = LETTER_CLASS_PREFIX_LAYER_VALUE,
                )
        } else if (aliasRewrite && tokenAliasLockAuthorized(sourceProjection, base)) {
            val assignments = tokenAliasAssignmentMap(sourceProjection)
            if (assignments != null) {
                rewrite =
                    RewriteCapability(
                        version = REWRITE_WIRE_VERSION,
                        lock = TOKEN_ALIAS_LOCK_VALUE,
                        sourceSchema = sourceProjection.sourceSchema,
                        translatorNamespace = sourceProjection.translatorNamespace,
                        adapterSchema = target,
                        lockMap = assignments,
                    )
            }
        }
        return injectingCapability(base, target, displayCapability, rewrite)
    }

    // ------------------------------------------------------------------------------------
    // Companion plan (integration entry point)
    // ------------------------------------------------------------------------------------

    /**
     * Everything needed to generate the compact companions of one source, computed once. The
     * keyboard preparation and the bundled built-in companions both use it, so a built-in scheme
     * gets exactly what an installed one would.
     */
    class CompanionPlan(
        private val compiled: ByteArray,
        val sourceID: String,
        private val projection: SourceProjection,
        sourceDelimiter: String?,
    ) {
        /** Letter-group display: the source's unique normal codes; null keeps the native display. */
        private val letterGroupDisplay: DisplayCapability?

        /** Phone-digit display: canonical pinyin, granted only to explicitly declared sources. */
        private val phoneDisplay: DisplayCapability?

        init {
            // Table (shape-code) sources keep a native display: no pinyin-canonical display exists.
            val isTableSource = autoAdaptableProducer(compiled)?.klass == ProducerClass.TABLE
            letterGroupDisplay =
                if (isTableSource) {
                    null
                } else {
                    try {
                        makeDisplayCapability(projection, displaySeparator = "'").capability
                    } catch (_: DisplayCapabilityError.EmptyResolvedDomain) {
                        null
                    }
                }
            phoneDisplay =
                if (!isTableSource &&
                    declaresCanonicalPinyinSource(projection) &&
                    isCanonicalPinyinDomainAllowingBenignExtras(projection.syllables.map { it.token })
                ) {
                    canonicalDisplayCapability(
                        sourceID,
                        sourceDelimiter?.codePoints()?.findFirst()?.let { String(Character.toChars(it.asInt)) } ?: " ",
                    )
                } else {
                    null
                }
        }

        /**
         * The companion schema file for [fold], or null when typing on that layout cannot reach any
         * of this source's spellings (a numeric key space only fits the 9-key layout), so the
         * layout is skipped.
         */
        fun companion(fold: CompactFold): ByteArray? {
            val physical = physicalFold(projection.alphabet, fold) ?: return null
            if (!hasReachableSpelling(projection, physical, fold)) return null
            return generate(
                compiled,
                sourceID,
                fold,
                displayCapability = if (fold.isPhoneDigits) phoneDisplay else letterGroupDisplay,
                sourceProjection = projection,
            )
        }

        /** Every foldable layout's companion, keyed by `<companion id>.schema.yaml`. */
        fun companions(folds: List<CompactFold> = ALL_FOLDS): Map<String, ByteArray> {
            val result = LinkedHashMap<String, ByteArray>()
            for (fold in folds) {
                val body = companion(fold) ?: continue
                result[identifier(sourceID, fold) + ".schema.yaml"] = body
            }
            return result
        }
    }

    private val ALL_FOLDS: List<CompactFold> get() = KeyboardLayoutKind.entries.mapNotNull { it.compactFold }

    /**
     * Documented integration entry point: the 14-, 18- and 9-key companion files for one source.
     *
     * Call it where a source schema has been compiled by the engine and the source projection
     * (`rb_syllable_display_map_json`) is available, then write each returned file next to the
     * source in the generation's build/user directory and deploy. It is pure: it performs no I/O.
     *
     * @param compiled the source's compiled `<id>.schema.yaml` bytes.
     * @param projection the engine's projection of that source ([SourceProjection.decode]).
     * @param sourceDelimiter the source's `translator/delimiter` (or `speller/delimiter`), whose
     *   first character joins a canonical 9-key display.
     */
    fun generateCompanions(
        compiled: ByteArray,
        sourceID: String,
        projection: SourceProjection,
        sourceDelimiter: String?,
        folds: List<CompactFold> = ALL_FOLDS,
    ): Map<String, ByteArray> = CompanionPlan(compiled, sourceID, projection, sourceDelimiter).companions(folds)
}

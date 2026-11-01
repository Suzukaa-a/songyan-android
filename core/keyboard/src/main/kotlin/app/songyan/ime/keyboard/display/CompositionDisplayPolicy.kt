package app.songyan.ime.keyboard.display

import app.songyan.ime.keyboard.engine.CompositionDisplayMode
import app.songyan.ime.keyboard.engine.EngineSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The `keyboard_adapter` capability block a generator writes at the top of a schema. Runtime value:
 * it only authorizes a display transform, it never changes engine input or control flow.
 *
 * ```yaml
 * keyboard_adapter:
 *   version: 1
 *   display_mode: source_code
 *   source_schema: songyan_flypy
 *   translator_namespace: translator
 *   separator: "'"
 *   mapping_policy: unique_normal
 *   adapter_schema: keyboard_..._14
 *   syllables: { zao: zc, shang: uh, hao: hc }
 * ```
 */
data class SchemaDisplayCapability(
    val version: Int,
    val mode: CompositionDisplayMode,
    val sourceSchema: String,
    val translatorNamespace: String,
    val separator: String,
    val mappingPolicy: String,
    /** Id of the companion adapter schema that owns the block; must equal the running schema id. */
    val adapterSchema: String,
    val syllables: Map<String, String>,
) {
    companion object {
        /** The only supported version; unknown versions must not take part in re-expression. */
        const val SUPPORTED_VERSION = 1

        /** The only supported mapping policy: each token has exactly one normal spelling. */
        const val UNIQUE_NORMAL_POLICY = "unique_normal"

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Strict decode from the bridge's JSON: nil on any gate failure (callers must fall back to
         * native). `canonical`/`source_code`/`source_rendered` must carry `adapter_schema`;
         * `source_code` also needs `unique_normal` and a non-empty mapping.
         */
        fun decode(jsonText: String): SchemaDisplayCapability? {
            val wire = runCatching { json.parseToJsonElement(jsonText).jsonObject }.getOrNull() ?: return null
            return fromWire(wire)
        }

        fun fromWire(wire: JsonObject): SchemaDisplayCapability? {
            val version = (wire["version"] as? JsonPrimitive)?.intOrNull ?: return null
            if (version != SUPPORTED_VERSION) return null
            val rawMode = (wire["display_mode"] as? JsonPrimitive)?.contentOrNull ?: return null
            val mode = CompositionDisplayMode.entries.firstOrNull { it.wireName() == rawMode } ?: return null
            val sourceSchema = (wire["source_schema"] as? JsonPrimitive)?.contentOrNull
            if (sourceSchema.isNullOrEmpty()) return null
            val namespace = (wire["translator_namespace"] as? JsonPrimitive)?.contentOrNull
            if (namespace.isNullOrEmpty()) return null
            val separator = (wire["separator"] as? JsonPrimitive)?.contentOrNull
            if (separator.isNullOrEmpty()) return null
            val policy = (wire["mapping_policy"] as? JsonPrimitive)?.contentOrNull ?: ""
            val adapterSchema = (wire["adapter_schema"] as? JsonPrimitive)?.contentOrNull ?: ""
            val syllables = linkedMapOf<String, String>()
            (wire["syllables"] as? JsonObject)?.forEach { (token, value) ->
                val spelling = (value as? JsonPrimitive)?.contentOrNull ?: return null
                if (token.isEmpty() || spelling.isEmpty()) return null
                syllables[token] = spelling
            }
            if (mode != CompositionDisplayMode.NATIVE && adapterSchema.isEmpty()) return null
            if (mode == CompositionDisplayMode.SOURCE_CODE || mode == CompositionDisplayMode.SOURCE_RENDERED) {
                if (policy != UNIQUE_NORMAL_POLICY) return null
                if (syllables.isEmpty()) return null
            }
            return SchemaDisplayCapability(
                version = version,
                mode = mode,
                sourceSchema = sourceSchema,
                translatorNamespace = namespace,
                separator = separator,
                mappingPolicy = policy,
                adapterSchema = adapterSchema,
                syllables = syllables,
            )
        }
    }
}

internal fun CompositionDisplayMode.wireName(): String =
    when (this) {
        CompositionDisplayMode.NATIVE -> "native"
        CompositionDisplayMode.CANONICAL -> "canonical"
        CompositionDisplayMode.SOURCE_CODE -> "source_code"
        CompositionDisplayMode.SOURCE_RENDERED -> "source_rendered"
    }

/** Resolution result; anything other than [Display] means "keep the engine preedit". */
sealed class CompositionDisplayOutcome {
    data class Display(
        val value: String,
    ) : CompositionDisplayOutcome()

    data class Native(
        val reason: Reason,
    ) : CompositionDisplayOutcome()

    enum class Reason {
        /** No `keyboard_adapter` block in the current schema/session (default path). */
        NO_CAPABILITY,

        /** Explicitly declared `native`. */
        MODE_NATIVE,

        /** Reserved/unknown mode (`source_rendered` is unimplemented). */
        UNSUPPORTED_MODE,

        /** The engine gave no decodable syllables. */
        EMPTY_TOKENS,

        /** A token is missing from the full mapping; the whole segment falls back. */
        MISSING_SYLLABLE,

        /** Display separator missing or empty. */
        EMPTY_SEPARATOR,
    }
}

/**
 * Pure display policy: given the syllable tokens of a trusted candidate and the schema's declared
 * capability block, compute the display value. It never reads comments and never touches
 * `rawInput`/`preedit`/control flow.
 */
object CompositionDisplayPolicy {
    /**
     * A proven phone-keypad definition emits ASCII digits as engine transport input. Deliberately
     * inspects only `rawInput` (never candidate text or schema identity): spaces inserted by a
     * preedit formatter do not make `55 5` safe to show.
     */
    fun isNumericTransport(rawInput: String): Boolean = rawInput.isNotEmpty() && rawInput.all { it in '0'..'9' }

    fun resolve(
        canonicalSyllables: List<String>,
        capability: SchemaDisplayCapability?,
    ): CompositionDisplayOutcome {
        if (capability == null) return CompositionDisplayOutcome.Native(CompositionDisplayOutcome.Reason.NO_CAPABILITY)
        if (canonicalSyllables.isEmpty() || canonicalSyllables.any { it.isEmpty() }) {
            return CompositionDisplayOutcome.Native(CompositionDisplayOutcome.Reason.EMPTY_TOKENS)
        }
        if (capability.separator.isEmpty()) {
            return CompositionDisplayOutcome.Native(CompositionDisplayOutcome.Reason.EMPTY_SEPARATOR)
        }
        return when (capability.mode) {
            CompositionDisplayMode.NATIVE ->
                CompositionDisplayOutcome.Native(CompositionDisplayOutcome.Reason.MODE_NATIVE)

            // Reserved: never declared until source-semantics joining is verified.
            CompositionDisplayMode.SOURCE_RENDERED ->
                CompositionDisplayOutcome.Native(CompositionDisplayOutcome.Reason.UNSUPPORTED_MODE)

            CompositionDisplayMode.CANONICAL ->
                CompositionDisplayOutcome.Display(canonicalSyllables.joinToString(capability.separator))

            CompositionDisplayMode.SOURCE_CODE -> {
                val mapped =
                    canonicalSyllables.map { token ->
                        val spelling = capability.syllables[token]
                        // Never default to identity for a missing token; fall back as a whole.
                        if (spelling.isNullOrEmpty()) {
                            return CompositionDisplayOutcome.Native(
                                CompositionDisplayOutcome.Reason.MISSING_SYLLABLE,
                            )
                        }
                        spelling
                    }
                CompositionDisplayOutcome.Display(mapped.joinToString(capability.separator))
            }
        }
    }

    /**
     * Applies the production display resolution to a snapshot and returns the exact outcome. Only the
     * display fields are written; `preedit`/`rawInput`/selection are untouched.
     */
    fun applied(
        base: EngineSnapshot,
        canonicalSyllables: List<String>,
        capability: SchemaDisplayCapability?,
        hasCapability: Boolean,
    ): Pair<EngineSnapshot, CompositionDisplayOutcome> {
        var snapshot = base.copy(hasDisplayCapability = hasCapability)
        // An authorized canonical capability is the explicit 9-key numeric display contract: the
        // engine preedit is a transport code, so the consumer must never echo it.
        if (capability?.mode == CompositionDisplayMode.CANONICAL) {
            snapshot = snapshot.copy(hidesNumericTransportCode = true)
        }
        if (capability == null) {
            return snapshot.copy(highlightedDisplay = "", displayMode = CompositionDisplayMode.NATIVE) to
                CompositionDisplayOutcome.Native(CompositionDisplayOutcome.Reason.NO_CAPABILITY)
        }
        return when (val outcome = resolve(canonicalSyllables, capability)) {
            is CompositionDisplayOutcome.Display ->
                snapshot.copy(
                    highlightedDisplay = outcome.value,
                    displayMode = capability.mode,
                ) to outcome

            is CompositionDisplayOutcome.Native ->
                snapshot.copy(
                    highlightedDisplay = "",
                    displayMode = CompositionDisplayMode.NATIVE,
                ) to outcome
        }
    }
}

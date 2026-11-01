package app.songyan.ime.rime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** How the highlighted segment should be displayed, from the schema's capability. */
enum class RimeDisplayMode(
    val wire: String,
) {
    NATIVE("native"),
    CANONICAL("canonical"),
    SOURCE_CODE("source_code"),
    SOURCE_RENDERED("source_rendered"),
    ;

    companion object {
        fun fromWire(value: String?): RimeDisplayMode? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Lock mode of the composition ambiguity report (`rb_copy_ambiguity_spellings_json`).
 *
 * [NONE] means the input rewrite is not authorized; the listed spellings are
 * display-only.
 */
enum class RimeLockMode(
    val wire: String,
) {
    NONE("none"),
    TOKEN_ALIAS("bounded_token_alias"),
    DIRECT_TOKEN("direct_token"),
    PREFIX_TOKEN("prefix_token"),
    ;

    companion object {
        /** Unknown or missing wire values are refused, never degraded to [NONE]. */
        fun fromWire(value: String?): RimeLockMode? = entries.firstOrNull { it.wire == value }
    }
}

/** Result of a capability-gated ambiguity rewrite (`rb_ambig_result`). */
enum class RimeAmbiguityApplyResult(
    val code: Int,
) {
    /** Applied and re-validated; nothing selected, nothing committed. */
    APPLIED(0),

    /** The expected input or tail segment no longer matches. Nothing written. */
    STALE(1),

    /** The active schema carries no recognized rewrite capability. Nothing written. */
    UNSUPPORTED(2),

    /** Bad arguments, or the spelling is not proven by a fresh prism enumeration. */
    INVALID(3),

    /** The write was attempted but post-validation failed; the input was restored. */
    FAILED(4),

    /** Post-validation failed and the rollback could not be re-read. */
    FAILED_ROLLBACK_UNVERIFIED(5),
    ;

    val isSuccess: Boolean get() = this == APPLIED

    companion object {
        fun fromCode(code: Int): RimeAmbiguityApplyResult = entries.firstOrNull { it.code == code } ?: FAILED
    }
}

/**
 * Read-only ambiguity report for the tail `abc` segment of the current composition.
 *
 * The bridge returns null (not an empty object) when it cannot prove an answer, so a
 * missing report is never mistaken for "not ambiguous".
 */
data class RimeAmbiguitySpellings(
    /** The whole raw input this report was computed for; compare it to reject stale taps. */
    val revisionInput: String,
    /** Raw-input byte range of the segment the spellings belong to. */
    val segmentStart: Int,
    val segmentEnd: Int,
    /** Deduplicated, sorted source spellings reachable from the segment. */
    val spellings: List<String>,
    /** The subset the engine will actually accept; may be smaller than [spellings]. */
    val lockable: List<String>,
    /** False when the list was truncated, so it must not be shown as the full set. */
    val isComplete: Boolean,
    val lockMode: RimeLockMode,
    /** Bounded narrowing keys of the prefix mode; always empty for the other modes. */
    val prefixOptions: List<String> = emptyList(),
    /** Per-option semantic label: exact / initial / mixed; data for the UI only. */
    val optionSemantics: Map<String, String> = emptyMap(),
) {
    val isAmbiguous: Boolean get() = spellings.size >= 2

    companion object {
        const val DEFAULT_LIMIT = 32
        const val HARD_LIMIT = 64
        private val VALID_SEMANTICS = setOf("exact", "initial", "mixed")

        /**
         * Strict decode of the bridge JSON; null on a malformed payload or a violated
         * invariant, so the caller keeps the native state instead of showing a
         * partial or mis-sorted choice set.
         */
        fun fromJson(json: String): RimeAmbiguitySpellings? {
            val root = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
            val revisionInput = root.string("revision_input") ?: return null
            val segment = runCatching { root["segment"]?.jsonObject }.getOrNull() ?: return null
            val start = segment.int("start") ?: return null
            val end = segment.int("end") ?: return null
            val spellings = root.stringList("spellings") ?: return null
            val lockable = root.stringList("lockable") ?: return null
            val complete = root.boolean("complete") ?: return null
            val lockMode = RimeLockMode.fromWire(root.string("lock_mode")) ?: return null
            if (start < 0 || end < start) return null
            if (end > revisionInput.toByteArray(Charsets.UTF_8).size) return null
            if (spellings.size > HARD_LIMIT || lockable.size > HARD_LIMIT) return null
            if (!spellings.all(::isWireToken) || !lockable.all(::isWireToken)) return null
            if (spellings != spellings.sorted() || spellings.toSet().size != spellings.size) return null
            if (lockable != lockable.sorted() || lockable.toSet().size != lockable.size) return null
            val prefixOptions = root.strictOptionalList("prefix_options") ?: return null
            val semantics = root.strictOptionalMap("option_semantics") ?: return null
            if (prefixOptions.size > HARD_LIMIT || !prefixOptions.all(::isPrefixKey)) return null
            if (prefixOptions != prefixOptions.sorted()) return null
            if (prefixOptions.toSet().size != prefixOptions.size) return null

            val lockableSet = lockable.toSet()
            when (lockMode) {
                RimeLockMode.PREFIX_TOKEN -> {
                    if (!lockableSet.containsAll(prefixOptions)) return null
                    if (!(spellings.toSet() + prefixOptions).containsAll(lockableSet)) return null
                    if (semantics.keys != lockableSet) return null
                    if (!semantics.values.all { it in VALID_SEMANTICS }) return null
                }
                else -> {
                    if (prefixOptions.isNotEmpty() || semantics.isNotEmpty()) return null
                    if (!spellings.toSet().containsAll(lockableSet)) return null
                }
            }
            return RimeAmbiguitySpellings(
                revisionInput = revisionInput,
                segmentStart = start,
                segmentEnd = end,
                spellings = spellings,
                lockable = lockable,
                isComplete = complete,
                lockMode = lockMode,
                prefixOptions = prefixOptions,
                optionSemantics = semantics,
            )
        }

        /** A wire token is non-empty, bounded and free of control characters. */
        private fun isWireToken(token: String): Boolean {
            if (token.isEmpty() || token.toByteArray(Charsets.UTF_8).size > 64) return false
            return token.all { it.code >= 0x20 && it.code != 0x7F }
        }

        /** A prefix key is one printable ASCII character. */
        private fun isPrefixKey(key: String): Boolean = key.length == 1 && key[0].code in 0x20..0x7E

        private fun JsonObject.string(key: String): String? =
            runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

        private fun JsonObject.int(key: String): Int? = runCatching { this[key]?.jsonPrimitive?.intOrNull }.getOrNull()

        private fun JsonObject.boolean(key: String): Boolean? =
            runCatching { this[key]?.jsonPrimitive?.booleanOrNull }.getOrNull()

        private fun JsonObject.stringList(key: String): List<String>? =
            runCatching { this[key]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrNull()

        /** Absent is allowed (older wire); present-but-wrong-typed is a malformed payload. */
        private fun JsonObject.strictOptionalList(key: String): List<String>? {
            if (!containsKey(key)) return emptyList()
            val array = runCatching { this[key]?.jsonArray }.getOrNull() ?: return null
            return array
                .mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                .takeIf { it.size == array.size }
        }

        private fun JsonObject.strictOptionalMap(key: String): Map<String, String>? {
            if (!containsKey(key)) return emptyMap()
            val objectValue = runCatching { this[key]?.jsonObject }.getOrNull() ?: return null
            val result = LinkedHashMap<String, String>()
            for ((name, value) in objectValue) {
                result[name] = runCatching { value.jsonPrimitive.contentOrNull }.getOrNull() ?: return null
            }
            return result
        }

        private fun JsonArray.size(): Int = this.size
    }
}

/** Canonical syllable tokens of the highlighted candidate plus the display separator. */
data class RimeHighlightedSyllables(
    val separator: String,
    val syllables: List<String>,
) {
    companion object {
        /** Null when the bridge JSON is malformed or the token list is empty. */
        fun fromJson(json: String): RimeHighlightedSyllables? {
            val root = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
            val separator = runCatching { root["separator"]?.jsonPrimitive?.contentOrNull }.getOrNull() ?: " "
            val syllables =
                runCatching {
                    root["syllables"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                }.getOrNull() ?: return null
            if (syllables.isEmpty() || syllables.any { it.isEmpty() }) return null
            return RimeHighlightedSyllables(separator, syllables)
        }
    }
}

/** Absolute pronunciations of one candidate (`rb_copy_candidate_syllables_json`). */
data class RimeCandidateSyllables(
    val paths: List<List<String>>,
    val isComplete: Boolean,
) {
    companion object {
        fun fromJson(json: String): RimeCandidateSyllables? {
            val root = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
            val paths =
                runCatching {
                    root["paths"]?.jsonArray?.map { path ->
                        path.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
                    }
                }.getOrNull() ?: return null
            val complete = runCatching { root["complete"]?.jsonPrimitive?.booleanOrNull }.getOrNull() ?: false
            return RimeCandidateSyllables(paths, complete && paths.isNotEmpty())
        }
    }
}

/** Read-only page-slot select metadata for cross-page candidate browsing. */
data class RimePageSelectMetadata(
    val pageSize: Int,
    val selectKeys: List<String>?,
    val selectLabels: List<String>?,
) {
    fun selectKey(pageLocalIndex: Int): String? = selectKeys?.getOrNull(pageLocalIndex)

    fun selectLabel(pageLocalIndex: Int): String? = selectLabels?.getOrNull(pageLocalIndex)

    companion object {
        /**
         * Strict decode; a label list must cover the whole page exactly, mirroring
         * librime's emission rule, so a partial set is never used.
         */
        fun fromJson(json: String): RimePageSelectMetadata? {
            val root = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
            val pageSize = runCatching { root["page_size"]?.jsonPrimitive?.intOrNull }.getOrNull() ?: return null
            if (pageSize < 0) return null
            val keys = readList(root, "select_keys")
            val labels = readList(root, "select_labels")
            if (labels != null && labels.size != pageSize) return null
            return RimePageSelectMetadata(pageSize, keys, labels)
        }

        private fun readList(
            root: JsonObject,
            key: String,
        ): List<String>? {
            val element = root[key] ?: return null
            if (element is kotlinx.serialization.json.JsonNull) return null
            val array = runCatching { element.jsonArray }.getOrNull() ?: return null
            return array.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
        }
    }
}

/** Verification of the active schema's rewrite capability (installer deploy check). */
sealed interface RimeRewriteVerification {
    /** The bridge could not answer (no live session). */
    data object Unavailable : RimeRewriteVerification

    /** The active schema declares no rewrite capability at all. */
    data object Absent : RimeRewriteVerification

    /** Declared, fully bound, and every alias enumerates to exactly its token. */
    data object Verified : RimeRewriteVerification

    /** Declared but unusable; carries the failed tokens for diagnostics. */
    data class Failed(
        val checked: Int,
        val failures: List<String>,
    ) : RimeRewriteVerification

    companion object {
        fun fromJson(json: String): RimeRewriteVerification {
            val root =
                runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull()
                    ?: return Unavailable
            val present =
                runCatching { root["present"]?.jsonPrimitive?.booleanOrNull }.getOrNull()
                    ?: return Unavailable
            val verified =
                runCatching { root["verified"]?.jsonPrimitive?.booleanOrNull }.getOrNull()
                    ?: return Unavailable
            val checked = runCatching { root["checked"]?.jsonPrimitive?.intOrNull }.getOrNull() ?: 0
            if (!present) return Absent
            if (verified) return Verified
            val failures =
                runCatching {
                    root["failures"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                }.getOrNull().orEmpty()
            return Failed(checked, failures)
        }
    }
}

package app.songyan.ime.layout

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.UUID

/** Why a hint document or edit is refused; the settings screen words each one. */
enum class ShuangpinHintProblem {
    TOO_LARGE,
    MALFORMED,
    UNKNOWN_FIELD,
    MISSING_FIELD,
    WRONG_TYPE,
    UNSUPPORTED_VERSION,
    INVALID_ID,
    INVALID_NAME,
    TOO_MANY_HINTS,
    INVALID_LETTER,
    DUPLICATE_LETTER,
    HINT_TOO_LONG,
    HINT_HAS_LINE_BREAK,
    TOO_MANY_PRESETS,
    DUPLICATE_PRESET,
    MISSING_SELECTION,
}

/** [field] names the offending JSON key or letter so a hand-written file can be fixed. */
class ShuangpinHintException(
    val problem: ShuangpinHintProblem,
    val field: String? = null,
) : Exception("shuangpin hints: " + problem.name + (field?.let { " ($it)" } ?: ""))

/**
 * One named set of shuangpin key hints: a letter maps to a short annotation drawn on its key.
 *
 * Display data only; it never changes a key action, the engine input or a layout. The file format
 * is the iOS one ({"version", "id", "name", "hints": {"q": "iu"}}), so presets move between the two
 * apps unchanged.
 */
data class ShuangpinHintPreset(
    val id: String,
    val name: String,
    val hints: Map<Char, String>,
    val version: Int = CURRENT_VERSION,
) {
    /** Lowercase letters, blank annotations dropped; idempotent. */
    fun normalized(): ShuangpinHintPreset =
        copy(
            hints =
                buildMap {
                    for ((letter, text) in hints) {
                        val trimmed = text.trim()
                        if (trimmed.isNotEmpty()) put(letter.lowercaseChar(), trimmed)
                    }
                },
        )

    @Throws(ShuangpinHintException::class)
    fun validate() {
        check(version == CURRENT_VERSION, ShuangpinHintProblem.UNSUPPORTED_VERSION, "version")
        check(
            id.isNotEmpty() && id.utf8Size() <= MAXIMUM_ID_BYTES && id.all { it in ID_CHARACTERS },
            ShuangpinHintProblem.INVALID_ID,
            "id",
        )
        check(name.isNotBlank() && name.utf8Size() <= MAXIMUM_NAME_BYTES, ShuangpinHintProblem.INVALID_NAME, "name")
        check(hints.size <= MAXIMUM_HINTS, ShuangpinHintProblem.TOO_MANY_HINTS, "hints")
        for ((letter, text) in hints) {
            check(letter in LetterGroupLayout.ALPHABET, ShuangpinHintProblem.INVALID_LETTER, letter.toString())
            check(text.utf8Size() <= MAXIMUM_HINT_BYTES, ShuangpinHintProblem.HINT_TOO_LONG, letter.toString())
            check(
                text.none { it == '\n' || it == '\r' || it == '\u0000' },
                ShuangpinHintProblem.HINT_HAS_LINE_BREAK,
                letter.toString(),
            )
        }
    }

    /** A user-editable copy: a new identity, the same hints. */
    fun duplicate(newName: String): ShuangpinHintPreset =
        ShuangpinHintPreset(id = UUID.randomUUID().toString().lowercase(), name = newName, hints = hints)

    /** The one-preset document, as shared between devices. */
    @Throws(ShuangpinHintException::class)
    fun encoded(): String {
        val value = normalized()
        value.validate()
        return ShuangpinHintCodec.pretty.encodeToString(JsonElement.serializer(), value.toJson())
    }

    internal fun toJson(): JsonObject =
        buildJsonObject {
            put(
                "hints",
                JsonObject(
                    hints.toSortedMap().entries.associate { (k, v) ->
                        k.toString() to JsonPrimitive(v)
                    },
                ),
            )
            put("id", id)
            put("name", name)
            put("version", version)
        }

    companion object {
        const val CURRENT_VERSION = 1
        const val MAXIMUM_HINTS = 26

        /** A hint is a small annotation on a phone key; longer text could never be read there. */
        const val MAXIMUM_HINT_BYTES = 16
        const val MAXIMUM_ID_BYTES = 64
        const val MAXIMUM_NAME_BYTES = 128

        /** The standard letter rows, in the order the editor lists them. */
        val LETTER_ROWS: List<String> = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

        private val ID_CHARACTERS: Set<Char> =
            (('0'..'9') + ('A'..'Z') + ('a'..'z') + listOf('-', '_')).toSet()

        /** Strict: unknown fields, a wrong version and wrong JSON types are refused. */
        @Throws(ShuangpinHintException::class)
        fun decode(text: String): ShuangpinHintPreset = fromJson(ShuangpinHintCodec.root(text))

        internal fun fromJson(root: JsonObject): ShuangpinHintPreset {
            ShuangpinHintCodec.rejectUnknown(root, setOf("version", "id", "name", "hints"))
            val preset =
                ShuangpinHintPreset(
                    id = ShuangpinHintCodec.string(root, "id"),
                    name = ShuangpinHintCodec.string(root, "name"),
                    hints = decodeHints(root["hints"]),
                    version = ShuangpinHintCodec.int(root, "version") ?: CURRENT_VERSION,
                ).normalized()
            preset.validate()
            return preset
        }

        /** Two keys differing only by case are refused instead of merged. */
        private fun decodeHints(value: JsonElement?): Map<Char, String> {
            if (value == null) return emptyMap()
            val table = value as? JsonObject ?: throw ShuangpinHintException(ShuangpinHintProblem.WRONG_TYPE, "hints")
            if (table.size > MAXIMUM_HINTS) throw ShuangpinHintException(ShuangpinHintProblem.TOO_MANY_HINTS, "hints")
            val result = LinkedHashMap<Char, String>()
            for ((key, raw) in table) {
                val letter = key.singleOrNull()?.lowercaseChar()
                if (letter == null || letter !in LetterGroupLayout.ALPHABET) {
                    throw ShuangpinHintException(ShuangpinHintProblem.INVALID_LETTER, key)
                }
                if (letter in result) throw ShuangpinHintException(ShuangpinHintProblem.DUPLICATE_LETTER, key)
                val text = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content
                result[letter] = text ?: throw ShuangpinHintException(ShuangpinHintProblem.WRONG_TYPE, key)
            }
            return result
        }

        private fun check(
            condition: Boolean,
            problem: ShuangpinHintProblem,
            field: String,
        ) {
            if (!condition) throw ShuangpinHintException(problem, field)
        }
    }
}

/** The presets that ship with the app. */
object ShuangpinHintPresets {
    /** The default snapshot always contains this preset. */
    const val BUILTIN_ID = "xiaohe-26"

    /** The display table of the bundled Xiaohe scheme; it never restricts what the engine accepts. */
    val builtinXiaohe =
        ShuangpinHintPreset(
            id = BUILTIN_ID,
            name = "小鹤双拼提示",
            hints =
                mapOf(
                    'q' to "iu",
                    'w' to "ei",
                    'r' to "uan",
                    't' to "ue",
                    'y' to "un",
                    'u' to "sh",
                    'i' to "ch",
                    'o' to "uo",
                    'p' to "ie",
                    's' to "ong/iong",
                    'd' to "ai",
                    'f' to "en",
                    'g' to "eng",
                    'h' to "ang",
                    'j' to "an",
                    'k' to "ing/uai",
                    'l' to "iang/uang",
                    'z' to "ou",
                    'x' to "ia/ua",
                    'c' to "ao",
                    'v' to "zh/ui",
                    'b' to "in",
                    'n' to "iao",
                    'm' to "ian",
                ),
        )
}

/**
 * The switch, the selection and the presets in one value, so the keyboard can never read a
 * selection whose preset is missing. Off by default: the user opts in.
 */
data class ShuangpinHintSnapshot(
    val enabled: Boolean = false,
    val selectedPresetId: String = ShuangpinHintPresets.BUILTIN_ID,
    val presets: List<ShuangpinHintPreset> = listOf(ShuangpinHintPresets.builtinXiaohe),
    val version: Int = CURRENT_VERSION,
) {
    /** The preset the keyboard draws, or null when hints are off: the renderer's only gate. */
    val activePreset: ShuangpinHintPreset? get() = if (enabled) preset(selectedPresetId) else null

    fun preset(id: String): ShuangpinHintPreset? = presets.firstOrNull { it.id == id }

    val userPresets: List<ShuangpinHintPreset> get() = presets.filter { it.id != ShuangpinHintPresets.BUILTIN_ID }

    /**
     * Strict: an inconsistent snapshot is refused, never repaired, since re-pointing a broken
     * selection at the built-in preset would show hints the user never chose.
     */
    @Throws(ShuangpinHintException::class)
    fun validate() {
        if (version !=
            CURRENT_VERSION
        ) {
            throw ShuangpinHintException(ShuangpinHintProblem.UNSUPPORTED_VERSION, "version")
        }
        if (presets.size >
            MAXIMUM_PRESETS
        ) {
            throw ShuangpinHintException(ShuangpinHintProblem.TOO_MANY_PRESETS, "presets")
        }
        val ids = presets.map { it.id }
        if (ids.toSet().size != ids.size) {
            throw ShuangpinHintException(
                ShuangpinHintProblem.DUPLICATE_PRESET,
                ids
                    .groupBy { it }
                    .entries
                    .first {
                        it.value.size >
                            1
                    }.key,
            )
        }
        if (preset(selectedPresetId) == null) {
            throw ShuangpinHintException(ShuangpinHintProblem.MISSING_SELECTION, selectedPresetId)
        }
        presets.forEach { it.validate() }
    }

    /** The whole settings document (the iOS hints.json). */
    @Throws(ShuangpinHintException::class)
    fun encoded(): String {
        validate()
        val root =
            buildJsonObject {
                put("enabled", enabled)
                putJsonArray("presets") { presets.forEach { add(it.normalized().toJson()) } }
                put("selectedPresetID", selectedPresetId)
                put("version", version)
            }
        val text = ShuangpinHintCodec.pretty.encodeToString(JsonElement.serializer(), root)
        if (text.utf8Size() >
            ShuangpinHintCodec.MAXIMUM_BYTES
        ) {
            throw ShuangpinHintException(ShuangpinHintProblem.TOO_LARGE)
        }
        return text
    }

    companion object {
        const val CURRENT_VERSION = 1
        const val MAXIMUM_PRESETS = 64

        @Throws(ShuangpinHintException::class)
        fun decode(text: String): ShuangpinHintSnapshot = fromJson(ShuangpinHintCodec.root(text))

        private fun fromJson(root: JsonObject): ShuangpinHintSnapshot {
            ShuangpinHintCodec.rejectUnknown(root, setOf("version", "enabled", "selectedPresetID", "presets"))
            val presets =
                when (val raw = root["presets"]) {
                    null -> emptyList()
                    is JsonArray -> {
                        if (raw.size >
                            MAXIMUM_PRESETS
                        ) {
                            throw ShuangpinHintException(ShuangpinHintProblem.TOO_MANY_PRESETS, "presets")
                        }
                        raw.map { element ->
                            val item =
                                element as? JsonObject
                                    ?: throw ShuangpinHintException(ShuangpinHintProblem.WRONG_TYPE, "presets")
                            ShuangpinHintPreset.fromJson(item)
                        }
                    }
                    else -> throw ShuangpinHintException(ShuangpinHintProblem.WRONG_TYPE, "presets")
                }
            val snapshot =
                ShuangpinHintSnapshot(
                    enabled = ShuangpinHintCodec.bool(root, "enabled") ?: false,
                    selectedPresetId = ShuangpinHintCodec.string(root, "selectedPresetID"),
                    presets = presets,
                    version = ShuangpinHintCodec.int(root, "version") ?: CURRENT_VERSION,
                )
            snapshot.validate()
            return snapshot
        }

        /**
         * Reads a single preset or a whole settings file. From a settings file: the preset in use,
         * else the first user preset, so importing a shared file yields something worth keeping.
         */
        @Throws(ShuangpinHintException::class)
        fun decodePresetFile(text: String): ShuangpinHintPreset {
            val root = ShuangpinHintCodec.root(text)
            if ("presets" !in root) return ShuangpinHintPreset.fromJson(root)
            val snapshot = fromJson(root)
            return snapshot.activePreset ?: snapshot.userPresets.firstOrNull()
                ?: snapshot.preset(snapshot.selectedPresetId)!!
        }
    }
}

/**
 * The hint for each letter on [key], in label order (one for a 26-key letter, several for a merged
 * key); empty when the key is not a letter key. A key's own corner label wins on a layout the user
 * authored or imported, so hints never cover what its author wrote.
 */
object ShuangpinHintOverlay {
    fun hints(
        key: LayoutKey,
        labels: Map<Char, String>,
        preservingSecondaryLabel: Boolean,
    ): List<String?> {
        val group = LetterGroupLayout.groupOf(key) ?: return emptyList()
        if (preservingSecondaryLabel && !key.secondaryLabel.isNullOrEmpty()) return group.map { null }
        return group.map { letter -> labels[letter]?.takeIf { it.isNotBlank() } }
    }
}

internal object ShuangpinHintCodec {
    const val MAXIMUM_BYTES = 256 * 1024

    val pretty: Json =
        Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }

    fun root(text: String): JsonObject {
        if (text.utf8Size() > MAXIMUM_BYTES) throw ShuangpinHintException(ShuangpinHintProblem.TOO_LARGE)
        val element =
            try {
                Json.parseToJsonElement(text)
            } catch (_: IllegalArgumentException) {
                throw ShuangpinHintException(ShuangpinHintProblem.MALFORMED)
            }
        return element as? JsonObject ?: throw ShuangpinHintException(ShuangpinHintProblem.MALFORMED)
    }

    /** A typo in a hand-written file must not be silently dropped. */
    fun rejectUnknown(
        root: JsonObject,
        allowed: Set<String>,
    ) {
        root.keys.sorted().firstOrNull { it !in allowed }?.let {
            throw ShuangpinHintException(ShuangpinHintProblem.UNKNOWN_FIELD, it)
        }
    }

    fun string(
        root: JsonObject,
        key: String,
    ): String {
        val value = root[key] ?: throw ShuangpinHintException(ShuangpinHintProblem.MISSING_FIELD, key)
        return (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw ShuangpinHintException(ShuangpinHintProblem.WRONG_TYPE, key)
    }

    /** JSON integers only: true, "1" and 1.5 are refused, not coerced. */
    fun int(
        root: JsonObject,
        key: String,
    ): Int? {
        val value = root[key] ?: return null
        val primitive = value as? JsonPrimitive
        val number = primitive?.takeIf { !it.isString && it.booleanOrNull == null }?.doubleOrNull
        if (number == null ||
            number != Math.rint(number) ||
            number !in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()
        ) {
            throw ShuangpinHintException(ShuangpinHintProblem.WRONG_TYPE, key)
        }
        return number.toInt()
    }

    /** JSON booleans only: a number is refused here, not coerced. */
    fun bool(
        root: JsonObject,
        key: String,
    ): Boolean? {
        val value = root[key] ?: return null
        return (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
            ?: throw ShuangpinHintException(ShuangpinHintProblem.WRONG_TYPE, key)
    }
}

private fun String.utf8Size(): Int = encodeToByteArray().size

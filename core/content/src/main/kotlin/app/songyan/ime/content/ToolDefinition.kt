package app.songyan.ime.content

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** Limits shared by the model and the validator, so both platforms accept the same files. */
object ToolDefinitionLimits {
    /** Steps one tool may declare. */
    const val MAXIMUM_STEPS: Int = 16

    /** Identifier budget, in bytes (the identifier is ASCII-only, so bytes and characters agree). */
    const val MAXIMUM_IDENTIFIER_UTF8_BYTES: Int = 64

    /** Name budget per language, counted in characters (grapheme clusters). */
    const val MAXIMUM_NAME_CHARACTERS: Int = 64

    /** Pattern budget for replace and template steps, counted in UTF-16 code units. */
    const val MAXIMUM_PATTERN_UTF16_CODE_UNITS: Int = 256

    /** Replacement budget for replace steps, counted in UTF-16 code units. */
    const val MAXIMUM_REPLACEMENT_UTF16_CODE_UNITS: Int = 256

    /** Entries one map step may hold. */
    const val MAXIMUM_MAP_ENTRIES: Int = 512

    /** Value budget of one map entry, counted in characters (grapheme clusters). */
    const val MAXIMUM_MAP_VALUE_CHARACTERS: Int = 16

    /** Byte budget of one tool file; also the external read limit. */
    const val MAXIMUM_FILE_BYTES: Int = 64 * 1024
}

/**
 * One OpenCC conversion that ships with the app.
 *
 * A tool may only run a conversion the app carries, so this set is closed and mirrors the
 * bundled data under the engine assets.
 */
enum class OpenCCToolConfig(
    val wireName: String,
) {
    HK2S("hk2s"),
    HK2T("hk2t"),
    JP2T("jp2t"),
    S2HK("s2hk"),
    S2T("s2t"),
    S2TW("s2tw"),
    S2TWP("s2twp"),
    T2HK("t2hk"),
    T2JP("t2jp"),
    T2S("t2s"),
    T2TW("t2tw"),
    TW2S("tw2s"),
    TW2SP("tw2sp"),
    TW2T("tw2t"),
    ;

    companion object {
        /** The config named [wireName], or null when the app does not ship it. */
        fun fromWireName(wireName: String): OpenCCToolConfig? = entries.firstOrNull { it.wireName == wireName }
    }
}

/**
 * One transformation step, run in declaration order.
 *
 * The JSON spelling of each type and its parameters is part of the shared format:
 * {"type":"opencc","config":"s2twp"}, {"type":"replace","pattern":"...","replacement":"..."},
 * {"type":"map","table":{...}}, {"type":"case","mode":"upper"}, {"type":"width","mode":"toHalf"}
 * and {"type":"template","pattern":"<{text}>"}.
 */
sealed class ToolStep {
    /** CAPITALIZE uppercases the first character of every whitespace-separated word. */
    enum class CaseMode(
        val wireName: String,
    ) {
        UPPER("upper"),
        LOWER("lower"),
        CAPITALIZE("capitalize"),
    }

    /** TO_HALF maps full-width forms to ASCII, TO_FULL maps ASCII to full-width forms. */
    enum class WidthMode(
        val wireName: String,
    ) {
        TO_HALF("toHalf"),
        TO_FULL("toFull"),
    }

    /** Runs one shipped OpenCC conversion. */
    data class OpenCC(
        val config: OpenCCToolConfig,
    ) : ToolStep()

    /** Replaces every match of [pattern] with [replacement]. */
    data class Replace(
        val pattern: String,
        val replacement: String,
    ) : ToolStep()

    /** Maps single characters through [table] (the JSON map step). */
    data class Map(
        val table: kotlin.collections.Map<String, String>,
    ) : ToolStep()

    /** Changes letter case. */
    data class CaseTransform(
        val mode: CaseMode,
    ) : ToolStep()

    /** Switches between full-width and half-width forms. */
    data class Width(
        val mode: WidthMode,
    ) : ToolStep()

    /** Wraps the text in [pattern], which must contain the placeholder once. */
    data class Template(
        val pattern: String,
    ) : ToolStep()
}

/**
 * A songyantool.json tool definition (format version 1).
 *
 * Unknown fields are ignored; an unknown step type, an unknown format version, an invalid
 * regular expression and an unshipped OpenCC config reject the whole file, because silently
 * dropping a step would change what the user asked the tool to do.
 *
 * Errors are typed [ToolDefinitionError] reasons so the UI can map them onto resources.
 */
data class ToolDefinition(
    val formatVersion: Int = FORMAT_VERSION,
    val id: String,
    val name: kotlin.collections.Map<String, String>,
    val steps: List<ToolStep>,
) {
    /**
     * Resolves the display name for the interface languages in [preferredLanguageTags].
     *
     * Each preferred tag is matched exactly (case-insensitively), then Simplified Chinese, then
     * the first available tag in sorted order, so the same file always shows the same name.
     */
    fun displayName(preferredLanguageTags: List<String>): String {
        for (tag in preferredLanguageTags) {
            val match = name.entries.firstOrNull { it.key.equals(tag, ignoreCase = true) }
            if (match != null) return match.value
        }
        val simplified = name.entries.firstOrNull { it.key.equals("zh-Hans", ignoreCase = true) }
        if (simplified != null) return simplified.value
        val firstTag = name.keys.sorted().firstOrNull() ?: return ""
        return name.getValue(firstTag)
    }

    /** Checks a definition built in memory, before it is saved or exported. */
    fun validate() {
        if (formatVersion != FORMAT_VERSION) {
            throw ToolDefinitionError(ToolDefinitionError.Reason.UnsupportedFormatVersion(formatVersion.toString()))
        }
        validateIdentifier()
        validateName()
        if (steps.isEmpty()) throw ToolDefinitionError(ToolDefinitionError.Reason.NoSteps)
        if (steps.size > ToolDefinitionLimits.MAXIMUM_STEPS) {
            throw ToolDefinitionError(ToolDefinitionError.Reason.TooManySteps(steps.size))
        }
        steps.forEachIndexed { index, step -> validateStep(step, index + 1) }
    }

    /** Writes the definition back as version 1 JSON; validation runs first so a bad model is never exported. */
    fun encoded(): ByteArray {
        validate()
        val element =
            buildJsonObject {
                put("formatVersion", formatVersion)
                put("id", id)
                put(
                    "name",
                    buildJsonObject {
                        for (tag in name.keys.sorted()) put(tag, name.getValue(tag))
                    },
                )
                put(
                    "steps",
                    buildJsonArray {
                        for (step in steps) add(step.toJson())
                    },
                )
            }
        val text = ContentJson.pretty.encodeToString(JsonElement.serializer(), element)
        return text.toByteArray(Charsets.UTF_8)
    }

    private fun validateIdentifier() {
        val valid =
            id.isNotEmpty() &&
                id.toByteArray(Charsets.UTF_8).size <= ToolDefinitionLimits.MAXIMUM_IDENTIFIER_UTF8_BYTES &&
                id.all { it.code < 128 && (it.isLetterOrDigit() || it == '-' || it == '_') }
        if (!valid) throw invalidField("$.id")
    }

    private fun validateName() {
        if (name.isEmpty()) throw invalidField("$.name")
        for (tag in name.keys.sorted()) {
            if (!NAME_LANGUAGE_TAGS.contains(tag)) throw invalidField("$.name." + tag)
            val trimmed = trimNameValue(name.getValue(tag))
            if (trimmed.isEmpty() || characterCount(trimmed) > ToolDefinitionLimits.MAXIMUM_NAME_CHARACTERS) {
                throw invalidField("$.name." + tag)
            }
        }
    }

    private fun validateStep(
        step: ToolStep,
        stepNumber: Int,
    ) {
        val path = "$.steps[" + (stepNumber - 1) + "]"
        when (step) {
            is ToolStep.OpenCC, is ToolStep.CaseTransform, is ToolStep.Width -> Unit
            is ToolStep.Replace -> {
                validateRegex(step.pattern, path + ".pattern", stepNumber)
                if (step.replacement.length > ToolDefinitionLimits.MAXIMUM_REPLACEMENT_UTF16_CODE_UNITS) {
                    throw invalidField(path + ".replacement")
                }
            }
            is ToolStep.Map -> validateTable(step.table, path + ".table")
            is ToolStep.Template -> {
                val placeholders = step.pattern.split(TEXT_PLACEHOLDER).size - 1
                if (step.pattern.isEmpty() ||
                    step.pattern.length > ToolDefinitionLimits.MAXIMUM_PATTERN_UTF16_CODE_UNITS ||
                    placeholders != 1
                ) {
                    throw invalidField(path + ".pattern")
                }
            }
        }
    }

    private fun validateTable(
        table: kotlin.collections.Map<String, String>,
        path: String,
    ) {
        if (table.isEmpty() || table.size > ToolDefinitionLimits.MAXIMUM_MAP_ENTRIES) {
            throw invalidField(path)
        }
        for (key in table.keys.sorted()) {
            val value = table.getValue(key)
            if (characterCount(key) != 1 || characterCount(value) > ToolDefinitionLimits.MAXIMUM_MAP_VALUE_CHARACTERS) {
                throw invalidField(path + "." + key)
            }
        }
    }

    private fun validateRegex(
        pattern: String,
        path: String,
        stepNumber: Int,
    ) {
        if (pattern.isEmpty() || pattern.length > ToolDefinitionLimits.MAXIMUM_PATTERN_UTF16_CODE_UNITS) {
            throw invalidField(path)
        }
        try {
            Pattern.compile(pattern)
        } catch (_: PatternSyntaxException) {
            throw ToolDefinitionError(ToolDefinitionError.Reason.InvalidRegex(stepNumber))
        }
    }

    companion object {
        /** The only format version this build reads and writes. */
        const val FORMAT_VERSION: Int = 1

        /** Name languages the format defines. Unknown tags are ignored, not rejected. */
        val NAME_LANGUAGE_TAGS: List<String> = listOf("zh-Hans", "zh-Hant", "ja", "en")

        /** The one placeholder a template step must contain exactly once. */
        const val TEXT_PLACEHOLDER: String = "{text}"

        /** One grapheme cluster, the character unit a name or map key is counted in. */
        private val GRAPHEME_CLUSTER: Pattern = Pattern.compile("\\X")

        /**
         * Decodes and validates a tool document.
         *
         * The byte budget is checked before parsing, invalid UTF-8 is refused instead of being
         * repaired to replacement characters, and unknown fields are ignored while unknown step
         * types reject the file.
         */
        fun decode(data: ByteArray): ToolDefinition {
            if (data.size > ToolDefinitionLimits.MAXIMUM_FILE_BYTES) {
                throw ToolDefinitionError(ToolDefinitionError.Reason.FileTooLarge(FILE_LIMIT_KILOBYTES))
            }
            val text = ContentJson.decodeUtf8Strict(data) ?: throw unreadable()
            val element =
                try {
                    ContentJson.parse(text.removePrefix("\uFEFF"))
                } catch (_: ContentException) {
                    throw unreadable()
                }
            val root = element as? JsonObject ?: throw unreadable()

            val versionElement = root["formatVersion"] ?: throw missingField("$.formatVersion")
            val version = integralIntOrNull(versionElement) ?: throw invalidField("$.formatVersion")
            if (version != FORMAT_VERSION) {
                throw ToolDefinitionError(ToolDefinitionError.Reason.UnsupportedFormatVersion(version.toString()))
            }
            val id = requireString(root, "id", "$.id")
            val name = decodeName(root)
            val steps = decodeSteps(root)
            return ToolDefinition(formatVersion = version, id = id, name = name, steps = steps).also { it.validate() }
        }

        /**
         * Reads a user-picked file through the same byte budget.
         *
         * Directories, pipes and missing files are rejected before anything is opened; the read
         * is bounded, so a file that grows while it is read still cannot exceed the budget.
         */
        fun decode(contentsOf: Path): ToolDefinition {
            val bytes =
                try {
                    BoundedFile.read(contentsOf, ToolDefinitionLimits.MAXIMUM_FILE_BYTES)
                } catch (error: ContentException) {
                    throw ToolDefinitionError(
                        when (error.code) {
                            ContentErrorCode.NOT_A_REGULAR_FILE -> ToolDefinitionError.Reason.NotAFile
                            ContentErrorCode.OVERSIZE ->
                                ToolDefinitionError.Reason.FileTooLarge(FILE_LIMIT_KILOBYTES)
                            else -> ToolDefinitionError.Reason.Unreadable
                        },
                    )
                }
            return decode(bytes)
        }

        private fun decodeName(root: JsonObject): kotlin.collections.Map<String, String> {
            val element = root["name"] ?: throw missingField("$.name")
            val source = element as? JsonObject ?: throw invalidField("$.name")
            val result = LinkedHashMap<String, String>()
            for ((tag, value) in source) {
                val primitive = value as? JsonPrimitive
                if (primitive == null || !primitive.isString) throw invalidField("$.name")
                if (NAME_LANGUAGE_TAGS.contains(tag)) result[tag] = trimNameValue(primitive.content)
            }
            return result
        }

        private fun decodeSteps(root: JsonObject): List<ToolStep> {
            val element = root["steps"] ?: throw missingField("$.steps")
            val array = element as? JsonArray ?: throw invalidField("$.steps")
            val steps = ArrayList<ToolStep>(minOf(array.size, ToolDefinitionLimits.MAXIMUM_STEPS))
            for ((index, entry) in array.withIndex()) {
                val path = "$.steps[" + index + "]"
                val container = entry as? JsonObject ?: throw invalidField(path)
                steps.add(decodeStep(container, index + 1, path))
                if (steps.size > ToolDefinitionLimits.MAXIMUM_STEPS) {
                    throw ToolDefinitionError(ToolDefinitionError.Reason.TooManySteps(steps.size))
                }
            }
            return steps
        }

        private fun decodeStep(
            container: JsonObject,
            stepNumber: Int,
            stepPath: String,
        ): ToolStep {
            val type = requireString(container, "type", stepPath + ".type")
            return when (type) {
                "opencc" -> {
                    val configName = requireString(container, "config", stepPath + ".config")
                    val config =
                        OpenCCToolConfig.fromWireName(configName)
                            ?: throw ToolDefinitionError(
                                ToolDefinitionError.Reason.UnavailableOpenCCConfig(stepNumber, configName),
                            )
                    ToolStep.OpenCC(config)
                }
                "replace" ->
                    ToolStep.Replace(
                        pattern = requireString(container, "pattern", stepPath + ".pattern"),
                        replacement = requireString(container, "replacement", stepPath + ".replacement"),
                    )
                "map" -> ToolStep.Map(decodeTable(container, stepPath + ".table"))
                "case" -> {
                    val modeName = requireString(container, "mode", stepPath + ".mode")
                    val mode =
                        ToolStep.CaseMode.entries.firstOrNull { it.wireName == modeName }
                            ?: throw invalidField(stepPath + ".mode")
                    ToolStep.CaseTransform(mode)
                }
                "width" -> {
                    val modeName = requireString(container, "mode", stepPath + ".mode")
                    val mode =
                        ToolStep.WidthMode.entries.firstOrNull { it.wireName == modeName }
                            ?: throw invalidField(stepPath + ".mode")
                    ToolStep.Width(mode)
                }
                "template" -> ToolStep.Template(requireString(container, "pattern", stepPath + ".pattern"))
                else -> throw ToolDefinitionError(ToolDefinitionError.Reason.UnsupportedStepType(stepNumber, type))
            }
        }

        private fun decodeTable(
            container: JsonObject,
            path: String,
        ): kotlin.collections.Map<String, String> {
            val element = container["table"] ?: throw missingField(path)
            val source = element as? JsonObject ?: throw invalidField(path)
            val table = LinkedHashMap<String, String>()
            for ((key, value) in source) {
                val primitive = value as? JsonPrimitive
                if (primitive == null || !primitive.isString) throw invalidField(path)
                table[key] = primitive.content
            }
            return table
        }

        private fun requireString(
            container: JsonObject,
            key: String,
            path: String,
        ): String {
            val element = container[key] ?: throw missingField(path)
            val primitive = element as? JsonPrimitive
            if (primitive == null || !primitive.isString) throw invalidField(path)
            return primitive.content
        }

        /**
         * The JSON number as an Int when it is integral, because the format version is numeric:
         * 1, 1.0 and 1e0 are all version 1, while "1" and 1.5 are not.
         */
        private fun integralIntOrNull(element: JsonElement): Int? {
            val primitive = element as? JsonPrimitive ?: return null
            if (primitive.isString) return null
            val value = primitive.content.toDoubleOrNull() ?: return null
            if (!value.isFinite() || value != kotlin.math.floor(value)) return null
            if (value < Int.MIN_VALUE.toDouble() || value > Int.MAX_VALUE.toDouble()) return null
            return value.toInt()
        }

        private fun characterCount(text: String): Int {
            if (text.isEmpty()) return 0
            val matcher = GRAPHEME_CLUSTER.matcher(text)
            var count = 0
            while (matcher.find()) count++
            return count
        }

        /**
         * Trims exactly what the iOS model trims around a name value: Unicode separators plus
         * tab, line breaks and the next-line separators. A plain Char.isWhitespace() would also
         * trim the ASCII record separators the iOS set leaves in.
         */
        private fun trimNameValue(value: String): String = value.trim(::isNameWhitespace)

        private fun isNameWhitespace(char: Char): Boolean =
            Character.isSpaceChar(char) ||
                char == '\t' ||
                char == '\n' ||
                char == '\u000B' ||
                char == '\u000C' ||
                char == '\r' ||
                char == '\u0085' ||
                char == '\u2028' ||
                char == '\u2029'

        private const val FILE_LIMIT_KILOBYTES: Int = ToolDefinitionLimits.MAXIMUM_FILE_BYTES / 1024

        private fun unreadable(): ToolDefinitionError = ToolDefinitionError(ToolDefinitionError.Reason.Unreadable)

        private fun missingField(path: String): ToolDefinitionError =
            ToolDefinitionError(ToolDefinitionError.Reason.MissingField(path))

        private fun invalidField(path: String): ToolDefinitionError =
            ToolDefinitionError(ToolDefinitionError.Reason.InvalidField(path))

        private fun ToolStep.toJson(): JsonObject =
            when (this) {
                is ToolStep.OpenCC ->
                    buildJsonObject {
                        put("type", "opencc")
                        put("config", config.wireName)
                    }
                is ToolStep.Replace ->
                    buildJsonObject {
                        put("type", "replace")
                        put("pattern", pattern)
                        put("replacement", replacement)
                    }
                is ToolStep.Map ->
                    buildJsonObject {
                        put("type", "map")
                        put(
                            "table",
                            buildJsonObject {
                                for (key in table.keys.sorted()) put(key, table.getValue(key))
                            },
                        )
                    }
                is ToolStep.CaseTransform ->
                    buildJsonObject {
                        put("type", "case")
                        put("mode", mode.wireName)
                    }
                is ToolStep.Width ->
                    buildJsonObject {
                        put("type", "width")
                        put("mode", mode.wireName)
                    }
                is ToolStep.Template ->
                    buildJsonObject {
                        put("type", "template")
                        put("pattern", pattern)
                    }
            }
    }
}

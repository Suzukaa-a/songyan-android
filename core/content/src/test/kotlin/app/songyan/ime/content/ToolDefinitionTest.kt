package app.songyan.ime.content

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Ported from QuickContentKitTests/ToolDefinitionTests: one case per rule of the shared
 * songyantool.json format version 1, so both platforms accept and reject the same files.
 */
class ToolDefinitionTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val widthStep = """{"type":"width","mode":"toHalf"}"""

    private val fullName = """{"zh-Hans":"Hans","zh-Hant":"Hant","ja":"JA","en":"EN"}"""

    private fun definitionJson(
        steps: String,
        formatVersion: String = "1",
        id: String = "\"sample\"",
        name: String = fullName,
        extra: String = "",
    ): String = """{"formatVersion":$formatVersion,"id":$id,"name":$name,"steps":[$steps]$extra}"""

    @Test
    fun utf8BomIsAcceptedLikeTheIosDecoder() {
        val bytes =
            byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) +
                definitionJson(widthStep).toByteArray(Charsets.UTF_8)
        assertEquals("sample", ToolDefinition.decode(bytes).id)
    }

    private fun decode(json: String): ToolDefinition = ToolDefinition.decode(json.toByteArray(Charsets.UTF_8))

    private fun assertRejected(
        json: String,
        expected: ToolDefinitionError.Reason,
    ) {
        val error = assertThrows(ToolDefinitionError::class.java) { decode(json) }
        assertEquals(json, expected, error.reason)
    }

    private fun assertBytesRejected(
        bytes: ByteArray,
        expected: ToolDefinitionError.Reason,
    ) {
        val error = assertThrows(ToolDefinitionError::class.java) { ToolDefinition.decode(bytes) }
        assertEquals(expected, error.reason)
    }

    private fun assertEncodingRejected(
        definition: ToolDefinition,
        expected: ToolDefinitionError.Reason,
    ) {
        val error = assertThrows(ToolDefinitionError::class.java) { definition.encoded() }
        assertEquals(expected, error.reason)
    }

    private fun assertFileRejected(
        path: Path,
        expected: ToolDefinitionError.Reason,
    ) {
        val error = assertThrows(ToolDefinitionError::class.java) { ToolDefinition.decode(contentsOf = path) }
        assertEquals(path.toString(), expected, error.reason)
    }

    private fun writeFile(
        name: String,
        bytes: ByteArray,
    ): Path {
        val path = temporary.root.toPath().resolve(name)
        Files.write(path, bytes)
        return path
    }

    // Format version

    @Test
    fun unsupportedFormatVersionIsRejected() {
        assertRejected(
            definitionJson(widthStep, formatVersion = "2"),
            ToolDefinitionError.Reason.UnsupportedFormatVersion("2"),
        )
        assertRejected(
            definitionJson(widthStep, formatVersion = "0"),
            ToolDefinitionError.Reason.UnsupportedFormatVersion("0"),
        )
        assertRejected(
            definitionJson(widthStep, formatVersion = "-1"),
            ToolDefinitionError.Reason.UnsupportedFormatVersion("-1"),
        )
        // A JSON number keeps its value: 2.0 is version 2, not an invalid field.
        assertRejected(
            definitionJson(widthStep, formatVersion = "2.0"),
            ToolDefinitionError.Reason.UnsupportedFormatVersion("2"),
        )
        assertRejected(
            definitionJson(widthStep, formatVersion = "\"1\""),
            ToolDefinitionError.Reason.InvalidField("$.formatVersion"),
        )
        assertRejected(
            definitionJson(widthStep, formatVersion = "1.5"),
            ToolDefinitionError.Reason.InvalidField("$.formatVersion"),
        )
        assertRejected(
            definitionJson(widthStep, formatVersion = "true"),
            ToolDefinitionError.Reason.InvalidField("$.formatVersion"),
        )
        assertRejected(
            definitionJson(widthStep, formatVersion = "null"),
            ToolDefinitionError.Reason.InvalidField("$.formatVersion"),
        )
    }

    @Test
    fun formatVersionAcceptsAnyJsonNumberEqualToOne() {
        for (literal in listOf("1", "1.0", "1e0", "1.00", "0.1e1")) {
            assertEquals(1, decode(definitionJson(widthStep, formatVersion = literal)).formatVersion)
        }
    }

    @Test
    fun missingFormatVersionIsRejected() {
        assertRejected(
            """{"id":"sample","name":$fullName,"steps":[$widthStep]}""",
            ToolDefinitionError.Reason.MissingField("$.formatVersion"),
        )
    }

    // Step set

    @Test
    fun unknownStepTypeIsRejected() {
        val steps = listOf(widthStep, """{"type":"translate","target":"ja"}""").joinToString(",")
        assertRejected(
            definitionJson(steps),
            ToolDefinitionError.Reason.UnsupportedStepType(step = 2, type = "translate"),
        )
    }

    @Test
    fun stepCountLimit() {
        val sixteen = List(ToolDefinitionLimits.MAXIMUM_STEPS) { widthStep }.joinToString(",")
        assertEquals(ToolDefinitionLimits.MAXIMUM_STEPS, decode(definitionJson(sixteen)).steps.size)
        val seventeen = List(ToolDefinitionLimits.MAXIMUM_STEPS + 1) { widthStep }.joinToString(",")
        assertRejected(definitionJson(seventeen), ToolDefinitionError.Reason.TooManySteps(17))
        // Decoding stops at the first step over the limit, so the count is always the limit plus one.
        val thirty = List(30) { widthStep }.joinToString(",")
        assertRejected(definitionJson(thirty), ToolDefinitionError.Reason.TooManySteps(17))
    }

    @Test
    fun zeroStepsAreRejected() {
        assertRejected(definitionJson(""), ToolDefinitionError.Reason.NoSteps)
    }

    // Step parameters

    @Test
    fun invalidRegexIsRejected() {
        assertRejected(
            definitionJson("""{"type":"replace","pattern":"(","replacement":"x"}"""),
            ToolDefinitionError.Reason.InvalidRegex(step = 1),
        )
        val steps = listOf(widthStep, """{"type":"replace","pattern":"[","replacement":"x"}""").joinToString(",")
        assertRejected(definitionJson(steps), ToolDefinitionError.Reason.InvalidRegex(step = 2))
        decode(definitionJson("""{"type":"replace","pattern":"([A-Za-z]+)","replacement":"[$1]"}"""))
    }

    @Test
    fun unavailableOpenCCConfigIsRejected() {
        assertRejected(
            definitionJson("""{"type":"opencc","config":"hw2s"}"""),
            ToolDefinitionError.Reason.UnavailableOpenCCConfig(step = 1, config = "hw2s"),
        )
        assertRejected(
            definitionJson("""{"type":"opencc","config":"S2T"}"""),
            ToolDefinitionError.Reason.UnavailableOpenCCConfig(step = 1, config = "S2T"),
        )
        assertRejected(
            definitionJson("""{"type":"opencc","config":"s2twp "}"""),
            ToolDefinitionError.Reason.UnavailableOpenCCConfig(step = 1, config = "s2twp "),
        )
    }

    @Test
    fun shippedOpenCCConfigsAreTheAvailableSet() {
        // Mirrors the bundled OpenCC data: a tool may only use a conversion the app carries.
        val expected =
            setOf(
                "hk2s",
                "hk2t",
                "jp2t",
                "s2hk",
                "s2t",
                "s2tw",
                "s2twp",
                "t2hk",
                "t2jp",
                "t2s",
                "t2tw",
                "tw2s",
                "tw2sp",
                "tw2t",
            )
        assertEquals(expected, OpenCCToolConfig.entries.map { it.wireName }.toSet())
        for (config in OpenCCToolConfig.entries) {
            decode(definitionJson("""{"type":"opencc","config":"${config.wireName}"}"""))
        }
        val opencc =
            Path.of(System.getProperty("user.dir")).resolve("../rime/src/main/assets/rime/opencc").normalize()
        assumeTrue("the bundled OpenCC data lives next to the module", Files.isDirectory(opencc))
        val shipped =
            Files.list(opencc).use { stream ->
                stream
                    .map { it.fileName.toString() }
                    .filter { it.endsWith(".json") }
                    .map { it.removeSuffix(".json") }
                    .toList()
                    .toSet()
            }
        assertEquals(expected, shipped)
    }

    @Test
    fun mapStepValidation() {
        val valid = """{"type":"map","table":{"A":"B","🇯🇵":"JP","C":""}}"""
        assertEquals(3, (decode(definitionJson(valid)).steps[0] as ToolStep.Map).table.size)
        assertRejected(
            definitionJson("""{"type":"map","table":{}}"""),
            ToolDefinitionError.Reason.InvalidField("$.steps[0].table"),
        )
        assertRejected(
            definitionJson("""{"type":"map","table":{"AB":"x"}}"""),
            ToolDefinitionError.Reason.InvalidField("$.steps[0].table.AB"),
        )
        assertRejected(
            definitionJson("""{"type":"map","table":{"A":"0123456789abcdefg"}}"""),
            ToolDefinitionError.Reason.InvalidField("$.steps[0].table.A"),
        )
        // Two flag emoji are two characters, so they cannot be one map key.
        assertRejected(
            definitionJson("""{"type":"map","table":{"🇯🇵🇯🇵":"x"}}"""),
            ToolDefinitionError.Reason.InvalidField("$.steps[0].table.🇯🇵🇯🇵"),
        )
        val entries =
            (0..ToolDefinitionLimits.MAXIMUM_MAP_ENTRIES)
                .map { "\"${(0x4E00 + it).toChar()}\":\"x\"" }
                .joinToString(",")
        assertRejected(
            definitionJson("""{"type":"map","table":{$entries}}"""),
            ToolDefinitionError.Reason.InvalidField("$.steps[0].table"),
        )
        val atLimit =
            (0 until ToolDefinitionLimits.MAXIMUM_MAP_ENTRIES)
                .map { "\"${(0x4E00 + it).toChar()}\":\"x\"" }
                .joinToString(",")
        val decoded = decode(definitionJson("""{"type":"map","table":{$atLimit}}"""))
        assertEquals(ToolDefinitionLimits.MAXIMUM_MAP_ENTRIES, (decoded.steps[0] as ToolStep.Map).table.size)
    }

    @Test
    fun mapValueLimitCountsGraphemes() {
        val valueLimit = ToolDefinitionLimits.MAXIMUM_MAP_VALUE_CHARACTERS
        decode(definitionJson("""{"type":"map","table":{"A":"${"🇯🇵".repeat(valueLimit)}"}}"""))
        assertRejected(
            definitionJson("""{"type":"map","table":{"A":"${"🇯🇵".repeat(valueLimit + 1)}"}}"""),
            ToolDefinitionError.Reason.InvalidField("$.steps[0].table.A"),
        )
    }

    @Test
    fun templateStepValidation() {
        decode(definitionJson("""{"type":"template","pattern":"<{text}>"}"""))
        assertRejected(
            definitionJson("""{"type":"template","pattern":"plain"}"""),
            ToolDefinitionError.Reason.InvalidField("$.steps[0].pattern"),
        )
        assertRejected(
            definitionJson("""{"type":"template","pattern":"{text}{text}"}"""),
            ToolDefinitionError.Reason.InvalidField("$.steps[0].pattern"),
        )
        assertRejected(
            definitionJson("""{"type":"template","pattern":""}"""),
            ToolDefinitionError.Reason.InvalidField("$.steps[0].pattern"),
        )
        val placeholder = ToolDefinition.TEXT_PLACEHOLDER
        val tooLong = "a".repeat(ToolDefinitionLimits.MAXIMUM_PATTERN_UTF16_CODE_UNITS) + placeholder
        assertRejected(
            definitionJson("""{"type":"template","pattern":"$tooLong"}"""),
            ToolDefinitionError.Reason.InvalidField("$.steps[0].pattern"),
        )
        val atLimit = "a".repeat(ToolDefinitionLimits.MAXIMUM_PATTERN_UTF16_CODE_UNITS - placeholder.length)
        decode(definitionJson("""{"type":"template","pattern":"$atLimit$placeholder"}"""))
    }

    @Test
    fun patternAndReplacementBounds() {
        val tooLong = "a".repeat(ToolDefinitionLimits.MAXIMUM_PATTERN_UTF16_CODE_UNITS + 1)
        assertRejected(
            definitionJson("""{"type":"replace","pattern":"$tooLong","replacement":"x"}"""),
            ToolDefinitionError.Reason.InvalidField("$.steps[0].pattern"),
        )
        assertRejected(
            definitionJson("""{"type":"replace","pattern":"a","replacement":"$tooLong"}"""),
            ToolDefinitionError.Reason.InvalidField("$.steps[0].replacement"),
        )
        val atLimit = "a".repeat(ToolDefinitionLimits.MAXIMUM_PATTERN_UTF16_CODE_UNITS)
        decode(definitionJson("""{"type":"replace","pattern":"$atLimit","replacement":""}"""))
        assertRejected(
            definitionJson("""{"type":"replace","pattern":"","replacement":"x"}"""),
            ToolDefinitionError.Reason.InvalidField("$.steps[0].pattern"),
        )
        // The budget counts UTF-16 code units, not characters: a surrogate pair uses two.
        val astralAtLimit = "😀".repeat(ToolDefinitionLimits.MAXIMUM_PATTERN_UTF16_CODE_UNITS / 2)
        decode(definitionJson("""{"type":"replace","pattern":"$astralAtLimit","replacement":""}"""))
        val astralOverLimit = "😀".repeat(ToolDefinitionLimits.MAXIMUM_PATTERN_UTF16_CODE_UNITS / 2 + 1)
        assertRejected(
            definitionJson("""{"type":"replace","pattern":"$astralOverLimit","replacement":""}"""),
            ToolDefinitionError.Reason.InvalidField("$.steps[0].pattern"),
        )
    }

    @Test
    fun caseAndWidthModesAreClosedSets() {
        for (mode in listOf("upper", "lower", "capitalize")) {
            decode(definitionJson("""{"type":"case","mode":"$mode"}"""))
        }
        for (mode in listOf("toHalf", "toFull")) {
            decode(definitionJson("""{"type":"width","mode":"$mode"}"""))
        }
        assertRejected(
            definitionJson("""{"type":"case","mode":"UPPER"}"""),
            ToolDefinitionError.Reason.InvalidField("$.steps[0].mode"),
        )
        assertRejected(
            definitionJson("""{"type":"width","mode":"half"}"""),
            ToolDefinitionError.Reason.InvalidField("$.steps[0].mode"),
        )
        assertRejected(
            definitionJson("""{"type":"width","mode":1}"""),
            ToolDefinitionError.Reason.InvalidField("$.steps[0].mode"),
        )
        assertRejected(
            definitionJson("""{"type":"case"}"""),
            ToolDefinitionError.Reason.MissingField("$.steps[0].mode"),
        )
        assertRejected(
            definitionJson("""{"type":"width"}"""),
            ToolDefinitionError.Reason.MissingField("$.steps[0].mode"),
        )
    }

    // Fields, unknown data and defaults

    @Test
    fun unknownFieldsAreIgnored() {
        val definition =
            decode(
                definitionJson(
                    steps = """{"type":"opencc","config":"s2t","comment":"ignored","flags":{"x":1}}""",
                    name = """{"zh-Hans":"Hans","zh-Hant":"Hant","ja":"JA","en":"EN","ko":"KO"}""",
                    extra = ""","schemaVersion":7,"future":{"a":true}""",
                ),
            )
        assertEquals(mapOf("zh-Hans" to "Hans", "zh-Hant" to "Hant", "ja" to "JA", "en" to "EN"), definition.name)
        assertEquals(listOf<ToolStep>(ToolStep.OpenCC(OpenCCToolConfig.S2T)), definition.steps)
    }

    @Test
    fun missingRequiredFieldsAreRejected() {
        assertRejected(
            """{"formatVersion":1,"name":$fullName,"steps":[$widthStep]}""",
            ToolDefinitionError.Reason.MissingField("$.id"),
        )
        assertRejected(
            """{"formatVersion":1,"id":"sample","steps":[$widthStep]}""",
            ToolDefinitionError.Reason.MissingField("$.name"),
        )
        assertRejected(
            """{"formatVersion":1,"id":"sample","name":$fullName}""",
            ToolDefinitionError.Reason.MissingField("$.steps"),
        )
        assertRejected(
            definitionJson("""{"mode":"toHalf"}"""),
            ToolDefinitionError.Reason.MissingField("$.steps[0].type"),
        )
        assertRejected(
            definitionJson("""{"type":"opencc"}"""),
            ToolDefinitionError.Reason.MissingField("$.steps[0].config"),
        )
        assertRejected(
            definitionJson("""{"type":"replace","replacement":"x"}"""),
            ToolDefinitionError.Reason.MissingField("$.steps[0].pattern"),
        )
        assertRejected(
            definitionJson("""{"type":"replace","pattern":"a"}"""),
            ToolDefinitionError.Reason.MissingField("$.steps[0].replacement"),
        )
        assertRejected(
            definitionJson("""{"type":"map"}"""),
            ToolDefinitionError.Reason.MissingField("$.steps[0].table"),
        )
        assertRejected(
            definitionJson("""{"type":"template"}"""),
            ToolDefinitionError.Reason.MissingField("$.steps[0].pattern"),
        )
    }

    @Test
    fun malformedFieldTypesAreRejected() {
        assertRejected(definitionJson(widthStep, name = "[]"), ToolDefinitionError.Reason.InvalidField("$.name"))
        assertRejected(
            definitionJson(widthStep, name = """{"en":5}"""),
            ToolDefinitionError.Reason.InvalidField("$.name"),
        )
        assertRejected(
            """{"formatVersion":1,"id":"sample","name":null,"steps":[$widthStep]}""",
            ToolDefinitionError.Reason.InvalidField("$.name"),
        )
        assertRejected(
            """{"formatVersion":1,"id":7,"name":$fullName,"steps":[$widthStep]}""",
            ToolDefinitionError.Reason.InvalidField("$.id"),
        )
        assertRejected(
            """{"formatVersion":1,"id":"sample","name":$fullName,"steps":null}""",
            ToolDefinitionError.Reason.InvalidField("$.steps"),
        )
        assertRejected(definitionJson("""{"type":7}"""), ToolDefinitionError.Reason.InvalidField("$.steps[0].type"))
        assertRejected(definitionJson("[]"), ToolDefinitionError.Reason.InvalidField("$.steps[0]"))
        assertRejected(
            definitionJson("""{"type":"map","table":[]}"""),
            ToolDefinitionError.Reason.InvalidField("$.steps[0].table"),
        )
    }

    @Test
    fun invalidIdentifierIsRejected() {
        val tooLong = "a".repeat(ToolDefinitionLimits.MAXIMUM_IDENTIFIER_UTF8_BYTES + 1)
        for (identifier in listOf("\"\"", "\"a b\"", "\"id/with/slash\"", "\"tööl\"", "\"$tooLong\"")) {
            assertRejected(definitionJson(widthStep, id = identifier), ToolDefinitionError.Reason.InvalidField("$.id"))
        }
        decode(definitionJson(widthStep, id = "\"tool_01-A\""))
        val atLimit = "\"" + "a".repeat(ToolDefinitionLimits.MAXIMUM_IDENTIFIER_UTF8_BYTES) + "\""
        decode(definitionJson(widthStep, id = atLimit))
    }

    @Test
    fun invalidNameTableIsRejected() {
        val tooLong = "x".repeat(ToolDefinitionLimits.MAXIMUM_NAME_CHARACTERS + 1)
        val cases =
            listOf(
                "{}" to "$.name",
                """{"en":"   "}""" to "$.name.en",
                """{"en":""}""" to "$.name.en",
                """{"ko":"KO"}""" to "$.name",
                """{"en":"$tooLong"}""" to "$.name.en",
            )
        for ((name, path) in cases) {
            assertRejected(definitionJson(widthStep, name = name), ToolDefinitionError.Reason.InvalidField(path))
        }
    }

    @Test
    fun nameValuesAreTrimmed() {
        val definition = decode(definitionJson(widthStep, name = """{"en":"  EN  ","ja":" 日本語 "}"""))
        assertEquals(mapOf("en" to "EN", "ja" to "日本語"), definition.name)
    }

    @Test
    fun nameLimitCountsCharactersNotCodeUnits() {
        val nameLimit = ToolDefinitionLimits.MAXIMUM_NAME_CHARACTERS
        decode(definitionJson(widthStep, name = """{"en":"${"x".repeat(nameLimit)}"}"""))
        assertRejected(
            definitionJson(widthStep, name = """{"en":"${"x".repeat(nameLimit + 1)}"}"""),
            ToolDefinitionError.Reason.InvalidField("$.name.en"),
        )
        decode(definitionJson(widthStep, name = """{"ja":"${"🇯🇵".repeat(nameLimit)}"}"""))
        assertRejected(
            definitionJson(widthStep, name = """{"ja":"${"🇯🇵".repeat(nameLimit + 1)}"}"""),
            ToolDefinitionError.Reason.InvalidField("$.name.ja"),
        )
        // A base letter plus a combining mark is one character and two code units.
        decode(definitionJson(widthStep, name = """{"ja":"${"e\u0301".repeat(nameLimit)}"}"""))
    }

    // Name fallback

    @Test
    fun nameFallbackFollowsPreferredTags() {
        val four = decode(definitionJson(widthStep))
        assertEquals("Hant", four.displayName(listOf("zh-Hant")))
        assertEquals("EN", four.displayName(listOf("EN")))
        assertEquals("JA", four.displayName(listOf("ja")))
        assertEquals("Hans", four.displayName(listOf("fr-FR")))
        assertEquals("Hans", four.displayName(emptyList()))
        // Only exact tags match: a regional tag does not fall back to its base language.
        assertEquals("Hans", four.displayName(listOf("ja-JP")))
        // Preferences are tried in order, so the first exact match wins.
        assertEquals("JA", four.displayName(listOf("en-US", "ja")))

        val noHans = decode(definitionJson(widthStep, name = """{"zh-Hant":"Hant","ja":"JA","en":"EN"}"""))
        assertEquals("EN", noHans.displayName(listOf("fr-FR")))
        assertEquals("Hant", noHans.displayName(listOf("zh-Hant")))
        assertEquals("JA", noHans.displayName(listOf("ja-JP", "ja")))

        val onlyJa = decode(definitionJson(widthStep, name = """{"ja":"JA"}"""))
        assertEquals("JA", onlyJa.displayName(emptyList()))

        val unnamed =
            ToolDefinition(
                id = "ok",
                name = emptyMap(),
                steps = listOf(ToolStep.Width(ToolStep.WidthMode.TO_HALF)),
            )
        assertEquals("", unnamed.displayName(listOf("en")))
    }

    // Round trip and encoding

    @Test
    fun validDefinitionRoundTrips() {
        val definition =
            decode(
                definitionJson(
                    steps =
                        listOf(
                            """{"type":"opencc","config":"s2twp"}""",
                            """{"type":"replace","pattern":"(\\d+)","replacement":"[$1]"}""",
                            """{"type":"map","table":{"A":"B"}}""",
                            """{"type":"case","mode":"capitalize"}""",
                            """{"type":"width","mode":"toFull"}""",
                            """{"type":"template","pattern":"<{text}>"}""",
                        ).joinToString(","),
                ),
            )
        assertEquals(6, definition.steps.size)
        val encoded = definition.encoded()
        assertEquals(definition, ToolDefinition.decode(encoded))
        val json = Json.parseToJsonElement(String(encoded, Charsets.UTF_8)).jsonObject
        val version = json["formatVersion"] as JsonPrimitive
        assertFalse("the version is a JSON number, not a string", version.isString)
        assertEquals(1, version.intOrNull ?: -1)
        assertEquals("sample", (json["id"] as JsonPrimitive).content)
        val steps = json["steps"]!!.jsonArray
        assertEquals(6, steps.size)
        assertEquals("opencc", (steps[0].jsonObject["type"] as JsonPrimitive).content)
        assertEquals("s2twp", (steps[0].jsonObject["config"] as JsonPrimitive).content)
        assertEquals("map", (steps[2].jsonObject["type"] as JsonPrimitive).content)
        val table = steps[2].jsonObject["table"] as JsonObject
        assertEquals("B", (table["A"] as JsonPrimitive).content)
        assertEquals("template", (steps[5].jsonObject["type"] as JsonPrimitive).content)
        // Deterministic: the same definition always exports the same bytes.
        assertArrayEquals(encoded, definition.encoded())
    }

    @Test
    fun encodingRejectsInvalidDefinition() {
        val step: ToolStep = ToolStep.Width(ToolStep.WidthMode.TO_HALF)
        val cases =
            listOf(
                ToolDefinition(id = "", name = mapOf("en" to "EN"), steps = listOf(step)) to
                    ToolDefinitionError.Reason.InvalidField("$.id"),
                ToolDefinition(id = "ok", name = emptyMap(), steps = listOf(step)) to
                    ToolDefinitionError.Reason.InvalidField("$.name"),
                ToolDefinition(id = "ok", name = mapOf("ko" to "KO"), steps = listOf(step)) to
                    ToolDefinitionError.Reason.InvalidField("$.name.ko"),
                ToolDefinition(id = "ok", name = mapOf("en" to "EN"), steps = emptyList()) to
                    ToolDefinitionError.Reason.NoSteps,
                ToolDefinition(
                    id = "ok",
                    name = mapOf("en" to "EN"),
                    steps = List(ToolDefinitionLimits.MAXIMUM_STEPS + 1) { step },
                ) to ToolDefinitionError.Reason.TooManySteps(ToolDefinitionLimits.MAXIMUM_STEPS + 1),
                ToolDefinition(
                    formatVersion = 2,
                    id = "ok",
                    name = mapOf("en" to "EN"),
                    steps = listOf(step),
                ) to ToolDefinitionError.Reason.UnsupportedFormatVersion("2"),
                ToolDefinition(
                    id = "ok",
                    name = mapOf("en" to "EN"),
                    steps = listOf(ToolStep.Replace(pattern = "(", replacement = "x")),
                ) to ToolDefinitionError.Reason.InvalidRegex(step = 1),
                ToolDefinition(
                    id = "ok",
                    name = mapOf("en" to "EN"),
                    steps = listOf(ToolStep.Map(mapOf("AB" to "x"))),
                ) to ToolDefinitionError.Reason.InvalidField("$.steps[0].table.AB"),
                ToolDefinition(
                    id = "ok",
                    name = mapOf("en" to "EN"),
                    steps = listOf(ToolStep.Template("plain")),
                ) to ToolDefinitionError.Reason.InvalidField("$.steps[0].pattern"),
            )
        for ((definition, reason) in cases) {
            assertEncodingRejected(definition, reason)
        }
        ToolDefinition(
            id = "ok",
            name = mapOf("en" to "EN"),
            steps = listOf(ToolStep.OpenCC(OpenCCToolConfig.S2T)),
        ).encoded()
    }

    @Test
    fun swiftEncodedFileIsReadable() {
        // Shaped like Swift's JSONEncoder output: sorted keys, " : " separators, two-space indent.
        val swiftEncoded =
            """
            {
              "formatVersion" : 1,
              "id" : "sample",
              "name" : {
                "en" : "EN",
                "ja" : "JA",
                "zh-Hans" : "Hans",
                "zh-Hant" : "Hant"
              },
              "steps" : [
                {
                  "config" : "s2twp",
                  "type" : "opencc"
                },
                {
                  "mode" : "toHalf",
                  "type" : "width"
                }
              ]
            }
            """.trimIndent()
        val definition = ToolDefinition.decode(swiftEncoded.toByteArray(Charsets.UTF_8))
        assertEquals("sample", definition.id)
        assertEquals(mapOf("en" to "EN", "ja" to "JA", "zh-Hans" to "Hans", "zh-Hant" to "Hant"), definition.name)
        assertEquals(
            listOf<ToolStep>(ToolStep.OpenCC(OpenCCToolConfig.S2TWP), ToolStep.Width(ToolStep.WidthMode.TO_HALF)),
            definition.steps,
        )
        assertEquals(definition, ToolDefinition.decode(definition.encoded()))
    }

    // File reading and malformed input

    @Test
    fun decodeFromFileAppliesByteBudgetAndRegularFileRule() {
        val file = writeFile("sample.songyantool.json", definitionJson(widthStep).toByteArray(Charsets.UTF_8))
        assertEquals("sample", ToolDefinition.decode(contentsOf = file).id)

        val folder = Files.createDirectory(temporary.root.toPath().resolve("folder"))
        assertFileRejected(folder, ToolDefinitionError.Reason.NotAFile)
        assertFileRejected(
            temporary.root.toPath().resolve("missing.songyantool.json"),
            ToolDefinitionError.Reason.NotAFile,
        )

        val oversized =
            writeFile(
                "oversized.songyantool.json",
                ByteArray(ToolDefinitionLimits.MAXIMUM_FILE_BYTES + 1) { 0x20 },
            )
        assertFileRejected(oversized, ToolDefinitionError.Reason.FileTooLarge(64))
        // A file exactly at the budget is read; its content decides the error.
        val atBudget =
            writeFile("limit.songyantool.json", ByteArray(ToolDefinitionLimits.MAXIMUM_FILE_BYTES) { 0x20 })
        assertFileRejected(atBudget, ToolDefinitionError.Reason.Unreadable)
    }

    @Test
    fun decodeFromDataAppliesByteBudget() {
        assertBytesRejected(
            ByteArray(ToolDefinitionLimits.MAXIMUM_FILE_BYTES + 1) { 0x20 },
            ToolDefinitionError.Reason.FileTooLarge(64),
        )
        assertBytesRejected(
            ByteArray(ToolDefinitionLimits.MAXIMUM_FILE_BYTES) { 0x20 },
            ToolDefinitionError.Reason.Unreadable,
        )
    }

    @Test
    fun malformedUtf8OrJsonIsUnreadable() {
        // Invalid UTF-8 is refused, never repaired to replacement characters.
        assertBytesRejected(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00), ToolDefinitionError.Reason.Unreadable)
        assertBytesRejected(byteArrayOf(0x7B, 0xC3.toByte(), 0x28, 0x7D), ToolDefinitionError.Reason.Unreadable)
        for (text in listOf("", "{", "[]", "null", "\"text\"", "{} trailing", "{\"formatVersion\":}")) {
            assertBytesRejected(text.toByteArray(Charsets.UTF_8), ToolDefinitionError.Reason.Unreadable)
        }
        assertBytesRejected(
            "{}".toByteArray(Charsets.UTF_8),
            ToolDefinitionError.Reason.MissingField("$.formatVersion"),
        )
    }
}

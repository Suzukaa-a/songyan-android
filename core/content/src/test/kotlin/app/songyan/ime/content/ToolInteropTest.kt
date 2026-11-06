package app.songyan.ime.content

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Tool files written by the iOS app's own encoder (`scripts/ios-interop.sh <ios-repo> export <dir>`,
 * committed under test resources/ios-interop) must decode into the same model here and read back as
 * the same document. The samples are one canonical tool with every step type and Unicode,
 * localized-name and regular-expression cases that both platforms accept.
 *
 * When SONGYAN_INTEROP_OUT is set, the same files are re-written by the Android encoder into that
 * directory so `scripts/ios-interop.sh <ios-repo> verify <dir>` can decode them with the iOS code
 * and compare the result with the iOS model.
 */
class ToolInteropTest {
    private val directory = File(ToolInteropTest::class.java.getResource("/ios-interop")!!.toURI())

    private fun files(): List<File> =
        directory.listFiles { file -> file.name.endsWith(FILE_EXTENSION) }!!.sortedBy { it.name }

    /** The model each committed iOS file must decode into, spelled out so a drift in either side fails. */
    private val expected: Map<String, ToolDefinition> =
        mapOf(
            "tool-canonical.songyantool.json" to
                ToolDefinition(
                    id = "canonical",
                    name =
                        mapOf(
                            "zh-Hans" to "排版整理",
                            "zh-Hant" to "排版整理",
                            "ja" to "組版を整える",
                            "en" to "Typeset tidy",
                        ),
                    steps =
                        listOf(
                            ToolStep.OpenCC(OpenCCToolConfig.S2TWP),
                            ToolStep.Replace("\\s+", " "),
                            ToolStep.Map(mapOf("，" to ", ", "。" to ". ", "；" to "; ")),
                            ToolStep.CaseTransform(ToolStep.CaseMode.CAPITALIZE),
                            ToolStep.Width(ToolStep.WidthMode.TO_HALF),
                            ToolStep.Template("「{text}」"),
                        ),
                ),
            "tool-localized-names.songyantool.json" to
                ToolDefinition(
                    id = "localized-names",
                    name =
                        mapOf(
                            "zh-Hans" to "本地化名称",
                            "zh-Hant" to "在地化名稱",
                            "ja" to "ローカライズ名",
                            "en" to "Localised name",
                        ),
                    steps =
                        listOf(
                            ToolStep.Width(ToolStep.WidthMode.TO_HALF),
                            ToolStep.CaseTransform(ToolStep.CaseMode.LOWER),
                        ),
                ),
            "tool-regex-representative.songyantool.json" to
                ToolDefinition(
                    id = "regex-representative",
                    name =
                        mapOf(
                            "zh-Hans" to "正则代表",
                            "zh-Hant" to "正規表示式",
                            "ja" to "正規表現",
                            "en" to "Regex samples",
                        ),
                    steps =
                        listOf(
                            ToolStep.Replace("^\\s+|\\s+$", ""),
                            ToolStep.Replace("(\\p{L})\\1", "$1"),
                            ToolStep.Replace("(?<=[（(])\\p{L}+(?=[）)])", "词"),
                            ToolStep.Replace("\\d{2,4}", "#"),
                            ToolStep.Replace("(?:ab|cd)+", "XY"),
                        ),
                ),
            "tool-unicode-text.songyantool.json" to
                ToolDefinition(
                    id = "unicode-text",
                    name =
                        mapOf(
                            "zh-Hans" to "文字整理（一）",
                            "zh-Hant" to "文字整理（一）",
                            "ja" to "文字の整理（一）",
                            "en" to "Text tidy (1)",
                        ),
                    steps =
                        listOf(
                            ToolStep.Replace("(\\p{L}+)\\s+(\\p{L}+)", "$2 $1"),
                            ToolStep.Map(
                                mapOf(
                                    "…" to "…",
                                    "℡" to "Tel",
                                    "①" to "1",
                                    "　" to " ",
                                    "🥰" to "😍",
                                ),
                            ),
                            ToolStep.Width(ToolStep.WidthMode.TO_FULL),
                            ToolStep.Template("「{text}」※"),
                            ToolStep.OpenCC(OpenCCToolConfig.T2S),
                        ),
                ),
        )

    private fun tree(text: String): JsonElement = Json.parseToJsonElement(text)

    /** Where two trees first differ, so a failure names the field instead of dumping both files. */
    private fun firstDifference(
        expected: JsonElement,
        actual: JsonElement,
        path: String = "$",
    ): String? {
        if (expected is JsonObject && actual is JsonObject) {
            for (key in expected.keys + actual.keys) {
                val left = expected[key]
                val right = actual[key]
                if (left == null || right == null) return "$path.$key expected=$left actual=$right"
                firstDifference(left, right, "$path.$key")?.let { return it }
            }
            return null
        }
        if (expected is JsonArray && actual is JsonArray) {
            if (expected.size != actual.size) {
                return "$path size expected=${expected.size} actual=${actual.size}"
            }
            for (index in expected.indices) {
                firstDifference(expected[index], actual[index], "$path[$index]")?.let { return it }
            }
            return null
        }
        return if (expected == actual) null else "$path expected=$expected actual=$actual"
    }

    private fun difference(
        name: String,
        expectedText: String,
        actualText: String,
    ): String? = firstDifference(tree(expectedText), tree(actualText))?.let { name + " " + it }

    @Test
    fun theFixturesAreThere() {
        assertEquals(expected.keys, files().map { it.name }.toSet())
    }

    @Test
    fun everyIosToolDecodesIntoTheExpectedModel() {
        val problems =
            files().mapNotNull { file ->
                val expectedDefinition = expected.getValue(file.name)
                val decoded =
                    try {
                        ToolDefinition.decode(file.readBytes())
                    } catch (error: ToolDefinitionError) {
                        return@mapNotNull file.name + " " + error.reason
                    }
                val encoded = String(decoded.encoded(), Charsets.UTF_8)
                val treeDifference = difference(file.name, file.readText(), encoded)
                when {
                    decoded != expectedDefinition -> file.name + " differs from the iOS model"
                    treeDifference != null -> treeDifference
                    ToolDefinition.decode(encoded.toByteArray(Charsets.UTF_8)) != decoded ->
                        file.name + " does not survive a decode/encode round trip"
                    else -> null
                }
            }
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun theCanonicalToolRunsEveryStepTypeOnce() {
        val decoded = ToolDefinition.decode(File(directory, "tool-canonical.songyantool.json").readBytes())
        assertEquals(6, decoded.steps.size)
        assertEquals(
            listOf(
                ToolStep.OpenCC::class.java,
                ToolStep.Replace::class.java,
                ToolStep.Map::class.java,
                ToolStep.CaseTransform::class.java,
                ToolStep.Width::class.java,
                ToolStep.Template::class.java,
            ),
            decoded.steps.map { it.javaClass },
        )
    }

    @Test
    fun theUnicodeFixtureKeepsItsNonAsciiText() {
        val unicode = ToolDefinition.decode(File(directory, "tool-unicode-text.songyantool.json").readBytes())
        val table = (unicode.steps[1] as ToolStep.Map).table
        assertEquals("😍", table["🥰"])
        assertEquals("Tel", table["℡"])
        assertEquals("「{text}」※", (unicode.steps[3] as ToolStep.Template).pattern)
        assertEquals("$2 $1", (unicode.steps[0] as ToolStep.Replace).replacement)
    }

    @Test
    fun theRegexFixtureKeepsItsPatterns() {
        val regex = ToolDefinition.decode(File(directory, "tool-regex-representative.songyantool.json").readBytes())
        assertEquals(
            listOf("^\\s+|\\s+$", "(\\p{L})\\1", "(?<=[（(])\\p{L}+(?=[）)])", "\\d{2,4}", "(?:ab|cd)+"),
            regex.steps.filterIsInstance<ToolStep.Replace>().map { it.pattern },
        )
    }

    @Test
    fun theLocalizedNameFixtureKeepsEveryLanguage() {
        val names = ToolDefinition.decode(File(directory, "tool-localized-names.songyantool.json").readBytes())
        assertEquals(ToolDefinition.NAME_LANGUAGE_TAGS.toSet(), names.name.keys)
        assertEquals("ローカライズ名", names.displayName(listOf("ja")))
        assertEquals("在地化名稱", names.displayName(listOf("zh-Hant")))
        assertEquals("Localised name", names.displayName(listOf("en-GB", "en")))
    }

    @Test
    fun writesTheAndroidEncodingsForTheIosDecoders() {
        val out = System.getenv("SONGYAN_INTEROP_OUT")?.let(::File) ?: return
        out.mkdirs()
        for (file in files()) {
            File(out, file.name).writeBytes(ToolDefinition.decode(file.readBytes()).encoded())
        }
    }

    private companion object {
        const val FILE_EXTENSION = ".songyantool.json"
    }
}

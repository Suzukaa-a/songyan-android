package app.songyan.ime.content

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The three examples users copy from. Names are asserted in every interface language because the
 * settings page shows them untranslated on all four, and the script example must never choose its
 * own conversion.
 */
class BuiltinToolDefinitionsTest {
    @Test
    fun theExamplesComeInTheDocumentedOrder() {
        val definitions = BuiltinToolDefinitions.all(OpenCCToolConfig.S2TWP)
        assertEquals(
            listOf(
                BuiltinToolDefinitions.SCRIPT_CONVERSION_ID,
                BuiltinToolDefinitions.WIDTH_ID,
                BuiltinToolDefinitions.LETTER_CASE_ID,
            ),
            definitions.map { it.id },
        )
    }

    @Test
    fun theScriptExampleUsesTheConversionTheCallerChose() {
        assertEquals(
            listOf(ToolStep.OpenCC(OpenCCToolConfig.S2TWP)),
            BuiltinToolDefinitions.scriptConversion(OpenCCToolConfig.S2TWP).steps,
        )
        assertEquals(
            listOf(ToolStep.OpenCC(OpenCCToolConfig.T2S)),
            BuiltinToolDefinitions.scriptConversion(OpenCCToolConfig.T2S).steps,
        )
    }

    @Test
    fun theWidthExampleTurnsFullWidthIntoHalfWidth() {
        assertEquals(
            listOf(ToolStep.Width(ToolStep.WidthMode.TO_HALF)),
            BuiltinToolDefinitions.width().steps,
        )
    }

    @Test
    fun theCaseExampleUppercases() {
        assertEquals(
            listOf(ToolStep.CaseTransform(ToolStep.CaseMode.UPPER)),
            BuiltinToolDefinitions.letterCase().steps,
        )
    }

    @Test
    fun everyExampleCarriesEveryInterfaceLanguage() {
        val expected =
            mapOf(
                BuiltinToolDefinitions.SCRIPT_CONVERSION_ID to
                    mapOf(
                        "zh-Hans" to "繁简转换",
                        "zh-Hant" to "繁簡轉換",
                        "ja" to "簡繁変換",
                        "en" to "Script conversion",
                    ),
                BuiltinToolDefinitions.WIDTH_ID to
                    mapOf(
                        "zh-Hans" to "全角半角",
                        "zh-Hant" to "全形半形",
                        "ja" to "全角半角",
                        "en" to "Full/half width",
                    ),
                BuiltinToolDefinitions.LETTER_CASE_ID to
                    mapOf(
                        "zh-Hans" to "英文大小写",
                        "zh-Hant" to "英文大小寫",
                        "ja" to "大文字・小文字",
                        "en" to "Letter case",
                    ),
            )
        val definitions = BuiltinToolDefinitions.all(OpenCCToolConfig.S2T)
        assertEquals(expected.keys, definitions.map { it.id }.toSet())
        for (definition in definitions) {
            assertEquals(expected.getValue(definition.id), definition.name)
            assertEquals(4, definition.name.size)
        }
    }

    @Test
    fun everyExampleRoundTripsThroughTheRealFormat() {
        for (definition in BuiltinToolDefinitions.all(OpenCCToolConfig.S2TW)) {
            assertEquals(definition, ToolDefinition.decode(definition.encoded()))
        }
    }
}

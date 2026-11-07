package app.songyan.ime.keyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Ported from KeyboardCoreTests.InputLanguageTests.
 *
 * The rules read only what a schema declares about itself: its id, speller alphabet and the
 * script conversion the user has in force. Unlisted schemas keep today's behavior.
 */
class InputLanguageTest {
    private fun assertProfile(
        expectedLanguage: InputLanguage,
        expectedFamily: InputSchemeFamily,
        schemaID: String,
        alphabet: String = LATIN_ALPHABET,
        conversion: String? = null,
    ) {
        assertEquals(
            InputLanguageProfile(expectedLanguage, expectedFamily),
            InputLanguageResolver.resolve(schemaID, alphabet, conversion),
        )
    }

    @Test
    fun kagiroiIsJapanese() {
        assertProfile(InputLanguage.JA, InputSchemeFamily.JAPANESE, "kagiroi")
    }

    @Test
    fun kagiroiTakesPriorityOverTraditionalConversion() {
        for (conversion in listOf("s2t", "s2twp", "s2hk")) {
            assertProfile(InputLanguage.JA, InputSchemeFamily.JAPANESE, "kagiroi", conversion = conversion)
        }
    }

    @Test
    fun bopomofoPrefixIsTraditionalZhuyin() {
        for (schemaID in listOf("bopomofo", "bopomofo_tw")) {
            assertProfile(InputLanguage.ZH_HANT, InputSchemeFamily.ZHUYIN, schemaID)
        }
    }

    @Test
    fun bopomofoAlphabetIsTraditionalZhuyin() {
        // The Dachen alphabet spreads the punctuation keys out, so they must never be matched as one run.
        assertFalse(BOPOMOFO_ALPHABET.contains("-;,./"))
        assertProfile(InputLanguage.ZH_HANT, InputSchemeFamily.ZHUYIN, "dachen", BOPOMOFO_ALPHABET)
    }

    @Test
    fun bopomofoAlphabetNeedsEveryPunctuationKey() {
        for (missing in listOf('-', ';', ',', '.', '/')) {
            val alphabet = BOPOMOFO_ALPHABET.replace(missing.toString(), "")
            assertFalse(alphabet.contains(missing))
            assertProfile(InputLanguage.ZH_HANS, InputSchemeFamily.GENERIC, "dachen", alphabet)
        }
    }

    @Test
    fun cangjiePrefixIsTraditionalRadicals() {
        for (schemaID in listOf("cangjie", "cangjie5")) {
            assertProfile(InputLanguage.ZH_HANT, InputSchemeFamily.CANGJIE_RADICALS, schemaID)
        }
    }

    @Test
    fun quickPrefixIsTraditionalRadicals() {
        for (schemaID in listOf("quick", "quick5")) {
            assertProfile(InputLanguage.ZH_HANT, InputSchemeFamily.CANGJIE_RADICALS, schemaID)
        }
    }

    @Test
    fun jyutpingPrefixIsTraditionalLatinPhonetic() {
        for (schemaID in listOf("jyut6ping3", "jyut6ping3_sc")) {
            assertProfile(InputLanguage.ZH_HANT, InputSchemeFamily.LATIN_PHONETIC, schemaID)
        }
    }

    @Test
    fun traditionalConvertersKeepFamily() {
        for (conversion in listOf("s2t", "s2tw", "s2twp", "s2hk", "t2tw", "t2hk")) {
            assertProfile(InputLanguage.ZH_HANT, InputSchemeFamily.GENERIC, "songyan_quanpin", conversion = conversion)
        }
        // A known family is decided before the conversion, so the conversion never rewrites it.
        assertProfile(InputLanguage.ZH_HANT, InputSchemeFamily.ZHUYIN, "bopomofo_tw", conversion = "s2twp")
        assertProfile(InputLanguage.ZH_HANT, InputSchemeFamily.CANGJIE_RADICALS, "cangjie5", conversion = "s2hk")
    }

    @Test
    fun aDeclaredSimplificationMakesAChineseFamilySimplifiedButKeepsItsFamily() {
        for (conversion in listOf("t2s", "tw2s", "tw2sp", "hk2s")) {
            assertProfile(InputLanguage.ZH_HANS, InputSchemeFamily.ZHUYIN, "bopomofo_tw", conversion = conversion)
            assertProfile(
                InputLanguage.ZH_HANS,
                InputSchemeFamily.CANGJIE_RADICALS,
                "cangjie5",
                conversion = conversion,
            )
            assertProfile(InputLanguage.ZH_HANS, InputSchemeFamily.CANGJIE_RADICALS, "quick5", conversion = conversion)
            assertProfile(
                InputLanguage.ZH_HANS,
                InputSchemeFamily.LATIN_PHONETIC,
                "jyut6ping3",
                conversion = conversion,
            )
            assertProfile(InputLanguage.JA, InputSchemeFamily.JAPANESE, "kagiroi", conversion = conversion)
        }
        // A conversion the rules do not know proves nothing: the family's own script stands.
        assertProfile(InputLanguage.ZH_HANT, InputSchemeFamily.CANGJIE_RADICALS, "cangjie5", conversion = "t2hkf")
    }

    @Test
    fun nonTraditionalConvertersKeepSimplified() {
        for (conversion in listOf("t2s", "tw2s", "tw2sp", "hk2s")) {
            assertProfile(InputLanguage.ZH_HANS, InputSchemeFamily.GENERIC, "songyan_quanpin", conversion = conversion)
        }
    }

    @Test
    fun unknownSchemaKeepsLegacyBehavior() {
        assertProfile(InputLanguage.ZH_HANS, InputSchemeFamily.GENERIC, "mystery_schema", "")
        assertProfile(InputLanguage.ZH_HANS, InputSchemeFamily.GENERIC, "mystery_schema")
        // A declared conversion the table does not know is treated like no conversion at all.
        assertProfile(InputLanguage.ZH_HANS, InputSchemeFamily.GENERIC, "mystery_schema", conversion = "s2x")
        // Prefixes match the Rime id literally, so a differently cased id stays unknown.
        assertProfile(InputLanguage.ZH_HANS, InputSchemeFamily.GENERIC, "Kagiroi")
    }

    @Test
    fun lunaPinyinKeepsSimplified() {
        assertProfile(InputLanguage.ZH_HANS, InputSchemeFamily.GENERIC, "luna_pinyin")
    }

    @Test
    fun rimeIceTraditionalSwitchIsTraditional() {
        assertProfile(InputLanguage.ZH_HANT, InputSchemeFamily.GENERIC, "rime_ice", conversion = "s2t")
        assertProfile(InputLanguage.ZH_HANS, InputSchemeFamily.GENERIC, "rime_ice")
    }

    private companion object {
        const val LATIN_ALPHABET = "zyxwvutsrqponmlkjihgfedcba"

        // Interleaves tone digits with the letters and spreads the punctuation keys out.
        const val BOPOMOFO_ALPHABET = "1qaz2wsx3edc4rfv5tgb6yhn7ujm8ik,9ol.0p;/-"
    }
}

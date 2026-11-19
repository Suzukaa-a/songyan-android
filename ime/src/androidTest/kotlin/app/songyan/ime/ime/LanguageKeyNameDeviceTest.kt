package app.songyan.ime.ime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.keyboard.engine.InputLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** The language switch key names the scheme's language, and falls back to 文/A for one it cannot name. */
@RunWith(AndroidJUnit4::class)
class LanguageKeyNameDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = KeyNames(context)

    @Test
    fun eachLanguageDrawsItsOwnSidesAndAnUnknownOneDrawsTheFallback() {
        // Chinese keeps the layout's own 中/英 face.
        assertNull(names.languageKeyDrawn(InputLanguage.ZH_HANS))
        assertNull(names.languageKeyDrawn(InputLanguage.ZH_HANT))
        assertEquals("あ/A", names.languageKeyDrawn(InputLanguage.JA))
        assertEquals("한/A", names.languageKeyDrawn(InputLanguage.KO))
        assertEquals("文/A", names.languageKeyDrawn(InputLanguage.UNSPECIFIED))
    }

    @Test
    fun aLanguageWithoutItsOwnWordingIsSpokenAsTheInputMethodsLanguage() {
        for (language in listOf(InputLanguage.KO, InputLanguage.UNSPECIFIED)) {
            assertEquals(R.string.ime_language_scheme_to_english, KeyNames.languageKeySpoken(language, false, false))
            assertEquals(R.string.ime_language_to_scheme, KeyNames.languageKeySpoken(language, false, true))
            assertEquals(
                R.string.ime_language_scheme_to_english_symbols,
                KeyNames.languageKeySpoken(language, true, false),
            )
            assertEquals(R.string.ime_language_to_scheme_symbols, KeyNames.languageKeySpoken(language, true, true))
        }
        assertEquals(R.string.ime_language_to_english, KeyNames.languageKeySpoken(InputLanguage.ZH_HANS, false, false))
    }
}

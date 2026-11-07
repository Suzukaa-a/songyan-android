package app.songyan.ime.ime

import app.songyan.ime.keyboard.engine.InputLanguage
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.layout.KeyboardPageKind
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutKeyTitleMode
import app.songyan.ime.layout.LayoutPageTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The generated 123 and symbol footers hold control keys, not page content: the host names them
 * from the input language, so they must carry the automatic title mode rather than a fixed literal.
 * The marks beside the space bar are the opposite: their label is the text they type.
 */
class KeyboardPagesFooterTest {
    private fun config(language: InputLanguage) =
        KeyboardPages.Config(
            layout = KeyboardLayoutKind.QWERTY26,
            englishDirectInput = false,
            symbolMarksAscii = false,
            languageSwitch = true,
            lettersReachable = true,
            everydayMarksPage = false,
            inputLanguage = language,
        )

    private fun footer(
        kind: KeyboardPageKind,
        language: InputLanguage = InputLanguage.ZH_HANS,
    ) = KeyboardPages
        .definition(kind, config(language))
        .rows
        .last()
        .keys

    @Test
    fun theGeneratedFooterControlsAreAutomatic() {
        for (language in InputLanguage.entries) {
            for (kind in listOf(KeyboardPageKind.NUMERIC, KeyboardPageKind.SYMBOLS)) {
                val keys = footer(kind, language)
                val back = keys.single { it.action.pageSwitchTarget == LayoutPageTarget.LETTERS }
                assertEquals("$kind back $language", LayoutKeyTitleMode.AUTOMATIC, back.titleMode)
                val space = keys.single { it.action.type == LayoutActionKind.SPACE }
                assertEquals("$kind space $language", LayoutKeyTitleMode.AUTOMATIC, space.titleMode)
                val enter = keys.single { it.action.type == LayoutActionKind.ENTER }
                assertEquals("$kind enter $language", LayoutKeyTitleMode.AUTOMATIC, enter.titleMode)
                val languageSwitch = keys.single { it.action.type == LayoutActionKind.LANGUAGE_SWITCH }
                assertEquals("$kind language $language", LayoutKeyTitleMode.AUTOMATIC, languageSwitch.titleMode)
            }
        }
        val more = footer(KeyboardPageKind.SYMBOLS).single { it.action.type == LayoutActionKind.MODE_SWITCH }
        assertEquals(LayoutKeyTitleMode.AUTOMATIC, more.titleMode)
    }

    @Test
    fun theMarksBesideTheSpaceBarKeepTheirLiterals() {
        val marks = footer(KeyboardPageKind.NUMERIC).filter { it.action.type == LayoutActionKind.LITERAL }
        assertTrue(marks.isNotEmpty())
        for (mark in marks) {
            assertNull(mark.label + " must keep its literal label", mark.titleMode)
        }
    }
}

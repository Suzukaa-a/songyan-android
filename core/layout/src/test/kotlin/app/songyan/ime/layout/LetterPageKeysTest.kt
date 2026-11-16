package app.songyan.ime.layout

import app.songyan.ime.keyboard.engine.InputLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LetterPageKeysTest {
    private val main = BuiltinLayouts.qwerty26

    private fun key(label: String) =
        main.rows
            .flatMap { it.keys }
            .first { it.label == label }

    @Test
    fun aBundledLetterKeyCarriesItsCornerMarkAsTheKeyboardDraws() {
        val a = LetterPageKeys.presented(key("a"), main, InputLanguage.ZH_HANS)
        assertEquals("a", a.label)
        assertNotNull(a.secondaryLabel)
        assertTrue(a.longPress.orEmpty().isNotEmpty())
    }

    @Test
    fun theTopRowShowsItsDigitInTheCorner() {
        assertEquals("1", LetterPageKeys.presented(key("q"), main, InputLanguage.ZH_HANS).secondaryLabel)
    }

    @Test
    fun anEditedLayoutKeepsItsOwnKeysAsWritten() {
        val edited =
            main.copy(
                rows =
                    listOf(
                        LayoutRow(
                            main.rows
                                .first()
                                .keys
                                .drop(1),
                        ),
                    ) + main.rows.drop(1),
            )
        val w = edited.rows.flatMap { it.keys }.first { it.label == "w" }
        assertEquals(w, LetterPageKeys.presented(w, edited, InputLanguage.ZH_HANS))
    }

    @Test
    fun theMergedKeyHintIsNeverDrawnAndFacesAreLowercase() {
        val shown =
            BuiltinLayouts.merged14.rows
                .flatMap { it.keys }
                .map { LetterPageKeys.mergedFace(it) }
        assertTrue(shown.none { it.secondaryLabel == BuiltinLayouts.MERGED_KEY_HINT })
        assertTrue(shown.filter { it.action.type == LayoutActionKind.CODE }.all { it.label == it.label.lowercase() })
    }

    @Test
    fun theShellFoldsShiftAndDeleteIntoTheShippedShapes() {
        val footer = LayoutBottomRow.preview(main)
        assertTrue(LetterPageKeys.shellEditsLastLetterRow(main, footer, kanaBoard = false))
        assertFalse(LetterPageKeys.shellEditsLastLetterRow(main, footer, kanaBoard = true))
    }
}

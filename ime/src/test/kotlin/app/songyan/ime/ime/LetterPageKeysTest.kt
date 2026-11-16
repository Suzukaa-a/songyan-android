package app.songyan.ime.ime

import app.songyan.ime.keyboard.engine.InputLanguage
import app.songyan.ime.layout.BuiltinLayouts
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutBottomRow
import app.songyan.ime.layout.LayoutDefinition
import app.songyan.ime.layout.LayoutKey
import app.songyan.ime.layout.LayoutRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LetterPageKeysTest {
    private val main = BuiltinLayouts.qwerty26

    /** The key as the Chinese letters page draws it. */
    private fun presented(
        key: LayoutKey,
        layout: LayoutDefinition,
    ) = LetterPageKeys.chineseLetterKey(
        LetterPageKeys.mergedFace(key),
        InputLanguage.ZH_HANS,
        LetterPageKeys.nativeLetterDefaults(layout),
    )

    private fun key(label: String) =
        main.rows
            .flatMap { it.keys }
            .first { it.label == label }

    @Test
    fun aBundledLetterKeyCarriesItsCornerMarkAsTheKeyboardDraws() {
        val a = presented(key("a"), main)
        assertEquals("a", a.label)
        assertNotNull(a.secondaryLabel)
        assertTrue(a.longPress.orEmpty().isNotEmpty())
    }

    @Test
    fun theTopRowShowsItsDigitInTheCorner() {
        assertEquals("1", presented(key("q"), main).secondaryLabel)
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
        assertEquals(w, presented(w, edited))
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

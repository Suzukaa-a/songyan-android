package app.songyan.ime.ime.panels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelTextTest {
    /** One fixed-width glyph per character, so the arithmetic is easy to assert. */
    private fun measure(
        text: String,
        sizePx: Float,
    ): Float = text.length * sizePx

    @Test
    fun aLabelThatAlreadyFitsKeepsItsNaturalSize() {
        val size = PanelText.fitSize(13f, 10f, 200f) { measure("数学", it) }
        assertEquals(13f, size, 0.001f)
    }

    @Test
    fun aLongLabelShrinksUntilItFits() {
        // Four characters at 13 px need 52 px; the rail offers 42 px, so 10.5 px still fits.
        val size = PanelText.fitSize(13f, 10f, 42f) { measure("图形符号", it) }
        assertEquals(10.5f, size, 0.001f)
    }

    @Test
    fun aLabelThatCannotShrinkEnoughStopsAtTheMinimum() {
        val size = PanelText.fitSize(13f, 10f, 1f) { measure("最近", it) }
        assertEquals(10f, size, 0.001f)
    }

    @Test
    fun zeroRoomFallsBackToTheMinimumSize() {
        assertEquals(10f, PanelText.fitSize(13f, 10f, 0f) { 999f }, 0.001f)
    }

    @Test
    fun shortTextIsNeverEllipsized() {
        assertEquals("符号", PanelText.ellipsize("符号", 100f) { measure(it, 13f) })
    }

    @Test
    fun longTextIsCutAtAnEllipsis() {
        val shown = PanelText.ellipsize("图形与标志", 52f) { measure(it, 13f) }
        assertTrue(shown.endsWith("\u2026"))
        assertTrue("kept text must fit", measure(shown, 13f) <= 52f)
        assertTrue("keeps some of the label", shown.length > 1)
    }

    @Test
    fun aVeryNarrowSlotKeepsOneCharacterPlusTheEllipsis() {
        val shown = PanelText.ellipsize("图形与标志", 20f) { measure(it, 13f) }
        assertEquals("图…", shown)
    }

    @Test
    fun emptyTextStaysEmpty() {
        assertEquals("", PanelText.ellipsize("", 20f) { measure(it, 13f) })
    }

    @Test
    fun aLabelThatFitsStaysOnOneLine() {
        assertEquals(listOf("Layout"), PanelText.twoLines("Layout", 100f) { measure(it, 10f) })
    }

    @Test
    fun wordsBreakWhereTheTwoLinesComeOutMostEven() {
        // "Emoji" | "candidates" is 50 | 100 px; no other break exists, and both fit 110 px.
        assertEquals(
            listOf("Emoji", "candidates"),
            PanelText.twoLines("Emoji candidates", 110f) { measure(it, 10f) },
        )
        // Of "Key" | "vibration test" and "Key vibration" | "test", the second is the more even.
        assertEquals(
            listOf("Key vibration", "test"),
            PanelText.twoLines("Key vibration test", 140f) { measure(it, 10f) },
        )
    }

    @Test
    fun cjkBreaksInTheMiddleButNeverBeforeALongVowelMark() {
        assertEquals(listOf("キーの", "プレビュー"), PanelText.twoLines("キーのプレビュー", 60f) { measure(it, 10f) })
        // An even split of 片手モード would put ー first on the second line.
        assertEquals(listOf("片手", "モード"), PanelText.twoLines("片手モード", 40f) { measure(it, 10f) })
    }

    @Test
    fun aLabelTooLongForTwoLinesEllipsizesTheSecond() {
        val lines = PanelText.twoLines("abcdefghij", 40f) { measure(it, 10f) }
        assertEquals("abcd", lines[0])
        assertTrue(lines[1].endsWith("\u2026"))
    }
}

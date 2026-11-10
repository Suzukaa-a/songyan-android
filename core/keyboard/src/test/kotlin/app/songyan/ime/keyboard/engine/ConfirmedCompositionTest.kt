package app.songyan.ime.keyboard.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmedCompositionTest {
    private fun snapshot(
        preedit: String,
        start: Int,
        end: Int,
        caret: Int = preedit.toByteArray(Charsets.UTF_8).size,
    ) = EngineSnapshot(
        preedit = preedit,
        rawInput = "su3cl3",
        isComposing = true,
        preeditSelStart = start,
        preeditSelEnd = end,
        preeditCaretByte = caret,
    )

    @Test
    fun wholeSelectionUsesNativeUtf8Offsets() {
        assertTrue(snapshot("你好", 6, 6).isFullyConfirmedComposition)
        assertTrue(snapshot("這", 3, 3).isFullyConfirmedComposition)
    }

    @Test
    fun guessedCompositionIsNotConfirmed() {
        assertFalse(snapshot("ㄋㄧˇ ㄏㄠˇ", 0, 17).isFullyConfirmedComposition)
    }

    @Test
    fun partialSelectionKeepsUnconfirmedTail() {
        assertFalse(snapshot("你ㄏㄠˇ", 3, 11).isFullyConfirmedComposition)
    }

    @Test
    fun firstToneSpaceDoesNotConfirm() {
        assertFalse(snapshot("ㄓㄜˉ", 0, 8).isFullyConfirmedComposition)
    }

    @Test
    fun emptyMenuAndCollapsedRangeAloneCannotConfirm() {
        assertFalse(snapshot("ㄓㄜˉ", 0, 0).isFullyConfirmedComposition)
        assertFalse(snapshot("你好", 2, 2).isFullyConfirmedComposition)
    }

    @Test
    fun idleOrEmptyInputCannotConfirm() {
        assertFalse(snapshot("你好", 6, 6).copy(isComposing = false).isFullyConfirmedComposition)
        assertFalse(snapshot("你好", 6, 6).copy(rawInput = "").isFullyConfirmedComposition)
        assertFalse(snapshot("", 0, 0).isFullyConfirmedComposition)
    }

    @Test
    fun cursorInsideAndInsertedSoftCursorFailClosed() {
        assertFalse(snapshot("你好", 6, 6, caret = 3).isFullyConfirmedComposition)
        assertFalse(snapshot("你‸好", 6, 6).isFullyConfirmedComposition)
    }
}

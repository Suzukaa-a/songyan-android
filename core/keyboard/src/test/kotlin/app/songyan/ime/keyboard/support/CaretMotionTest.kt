package app.songyan.ime.keyboard.support

import org.junit.Assert.assertEquals
import org.junit.Test

class CaretMotionTest {
    @Test
    fun leftAndRightStepOverWholeCharacters() {
        val text = "a👨‍👩‍👧b"
        val family = text.indexOf('b')
        assertEquals(1, CaretMotion.left(text, family))
        assertEquals(family, CaretMotion.right(text, 1))
        assertEquals(0, CaretMotion.left(text, 0))
        assertEquals(text.length, CaretMotion.right(text, text.length))
    }

    @Test
    fun lineEdgesFollowNewlines() {
        val text = "one\ntwo\nthree"
        assertEquals(4, CaretMotion.lineStart(text, 6))
        assertEquals(7, CaretMotion.lineEnd(text, 6))
        assertEquals(0, CaretMotion.lineStart(text, 2))
        assertEquals(text.length, CaretMotion.lineEnd(text, 9))
        assertEquals("the caret at a line's end is on that line", 3, CaretMotion.lineEnd(text, 3))
        assertEquals("the caret just after a newline starts the next line", 4, CaretMotion.lineStart(text, 4))
    }

    @Test
    fun upAndDownKeepTheColumnAndClampToShortLines() {
        val text = "abcdef\nab\nabcdef"
        assertEquals("down clamps to the short line's end", 9, CaretMotion.down(text, 4))
        assertEquals("up from the short line keeps its column", 2, CaretMotion.up(text, 9))
        assertEquals("down from the short line's end", 12, CaretMotion.down(text, 9))
        assertEquals("up clamps to the short line's end", 9, CaretMotion.up(text, 12))
    }

    @Test
    fun upOnTheFirstLineAndDownOnTheLastStayPut() {
        val text = "abc\ndef"
        assertEquals(2, CaretMotion.up(text, 2))
        assertEquals(6, CaretMotion.down(text, 6))
    }

    @Test
    fun verticalMovesNeverLandInsideASurrogatePair() {
        val text = "ab\n😀😀"
        // Column 1 of the second line is the middle of the first emoji's surrogate pair.
        assertEquals(3, CaretMotion.down(text, 1))
    }
}

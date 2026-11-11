package app.songyan.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class ChipWidthTest {
    private fun width(showComment: Boolean) = ChipWidth.of(40f, 30f, showComment, 8f, 10f)

    @Test
    fun hiddenCommentLeavesTheCellAsWideAsAPlainCandidate() {
        assertEquals(ChipWidth.of(40f, 0f, false, 8f, 10f), width(false), 0.001f)
        assertEquals(60f, width(false), 0.001f)
    }

    @Test
    fun shownCommentAddsItsWidthAndHalfTheGap() {
        assertEquals(94f, width(true), 0.001f)
    }
}

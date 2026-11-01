package app.songyan.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingRowTest {
    // The compact window's tokens (KeyboardDesignTokens.forCompactWindow): 40 dp bar, 5 dp band under the keys.
    private object Compact {
        const val BAR_DP = 40f
        const val GAP_DP = 5f
    }

    private fun landscapeRowDp(screenHeightDp: Int): Float {
        val pitch =
            KeyboardGeometry.rowPitchDp(
                KeyHeight.STANDARD,
                screenHeightDp,
                Compact.BAR_DP,
                Compact.GAP_DP,
            )
        return ReadingRowBudget.rowDp(screenHeightDp, Compact.BAR_DP, pitch, Compact.GAP_DP)
    }

    @Test
    fun aTallWindowGetsThe36DpRowWhateverThePreset() {
        for (height in listOf(640, 800, 1024, 521)) {
            assertEquals(36f, ReadingRowBudget.rowDp(height, 48f, 56f, 8f), 0f)
        }
        // An unreported window height is not a short window.
        assertEquals(36f, ReadingRowBudget.rowDp(0, 48f, 56f, 8f), 0f)
    }

    @Test
    fun aShortPhoneLandscapeDoesNotFitTheRowAndFallsBackToInline() {
        for (height in listOf(320, 360, 392, 411, 450)) {
            assertEquals("window $height dp", 0f, landscapeRowDp(height), 0f)
        }
    }

    @Test
    fun aShortWindowThatHasRoomGetsThe32DpRow() {
        for (height in listOf(480, 500, 520)) {
            assertEquals("window $height dp", 32f, landscapeRowDp(height), 0f)
        }
    }

    @Test
    fun theRowNeverTakesMoreThanTheShortWindowShareWhenItIsGranted() {
        for (height in 300..520) {
            if (landscapeRowDp(height) == 0f) continue
            val pitch =
                KeyboardGeometry.rowPitchDp(
                    KeyHeight.STANDARD,
                    height,
                    Compact.BAR_DP,
                    Compact.GAP_DP,
                )
            val total =
                Compact.BAR_DP + 32f + pitch * KeyboardDesignTokens.KEY_ROWS + Compact.GAP_DP
            assertTrue("window $height dp: $total", total <= KeyboardGeometry.shortKeyboardMaxDp(height))
        }
    }

    @Test
    fun theRowUnderTheReadingRowShrinksToItsHeightAndTheRestGoesBack() {
        assertEquals(36f, ReadingRowBudget.bandDp(48f, 36f), 0f)
        assertEquals(32f, ReadingRowBudget.bandDp(40f, 32f), 0f)
        // A theme with a shorter bar keeps it; without a reading row the bar keeps its own height.
        assertEquals(30f, ReadingRowBudget.bandDp(30f, 36f), 0f)
        assertEquals(48f, ReadingRowBudget.bandDp(48f, null), 0f)
    }

    @Test
    fun theRowDoesNotTakeAnythingFromTheKeyRows() {
        // The row is added on top: the pitch rule has no input for it, so the key rows stay as they were.
        val withoutRow = KeyboardGeometry.rowPitchDp(KeyHeight.STANDARD, 800, 48f, 8f)
        assertEquals(KeyboardGeometry.portraitAreaDp(KeyHeight.STANDARD, 800) / 4, withoutRow, 0f)
        assertEquals(36f, ReadingRowBudget.ROW_DP, 0f)
    }

    @Test
    fun theRowIsUsedOnlyWhenChosenAndWhenItFits() {
        assertEquals(ReadingPlacement.ROW, ReadingRowBudget.effective(ReadingPlacement.ROW, 36f))
        assertEquals(ReadingPlacement.ROW, ReadingRowBudget.effective(ReadingPlacement.ROW, 32f))
        assertEquals(ReadingPlacement.INLINE, ReadingRowBudget.effective(ReadingPlacement.ROW, 0f))
        assertEquals(ReadingPlacement.INLINE, ReadingRowBudget.effective(ReadingPlacement.INLINE, 36f))
    }

    @Test
    fun theStoredPlacementResolvesAndUnknownValuesGetTheDefaultRow() {
        assertEquals(ReadingPlacement.ROW, ReadingPlacement.resolve(null))
        assertEquals(ReadingPlacement.ROW, ReadingPlacement.resolve("nonsense"))
        assertEquals(ReadingPlacement.INLINE, ReadingPlacement.resolve("inline"))
        for (placement in ReadingPlacement.entries) {
            assertEquals(placement, ReadingPlacement.resolve(placement.wire))
        }
    }

    @Test
    fun everyReadingCellIsAtLeast48DpAndGrowsWithItsText() {
        val density = 2f
        val layout =
            ReadingCells.layout(
                textWidths = listOf(10f, 90f, 200f),
                start = ReadingCells.START_DP * density,
                padding = ReadingCells.PADDING_DP * density,
                minWidth = ReadingCells.MIN_WIDTH_DP * density,
            )
        assertEquals(96f, layout.widths[0], 0f)
        assertEquals(138f, layout.widths[1], 0f)
        assertEquals(248f, layout.widths[2], 0f)
        assertTrue(layout.widths.all { it >= 48f * density })
    }

    @Test
    fun readingCellsTileTheRowWithoutGapsSoNoPointBetweenTwoIsDead() {
        val layout = ReadingCells.layout(listOf(30f, 60f, 30f), 8f, 24f, 96f)
        for (i in 0 until layout.lefts.lastIndex) {
            assertEquals(layout.lefts[i] + layout.widths[i], layout.lefts[i + 1], 0f)
        }
        assertEquals(layout.lefts.last() + layout.widths.last(), layout.end, 0f)
    }

    @Test
    fun anEmptyReadingListTakesNoRoom() {
        val layout = ReadingCells.layout(emptyList(), 8f, 24f, 96f)
        assertEquals(8f, layout.end, 0f)
    }
}

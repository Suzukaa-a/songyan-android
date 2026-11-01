package app.songyan.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BarContentBoxTest {
    @Test
    fun theBoxIsCentredBelowTheRowsCentreByTheDrop() {
        val box = BarContentBox.of(48f, null)
        assertEquals(48f, box.rowDp, 0f)
        assertEquals(48f / 2 + BarContentBox.DROP_DP, box.centreDp, 0.001f)
        assertEquals(48f - 2 * BarContentBox.DROP_DP, box.heightDp, 0.001f)
        assertEquals(0f, box.bottomInsetDp, 0.001f)
    }

    @Test
    fun underAReadingRowTheBoxLiftsAndTheRowShrinksToTheReadingRow() {
        val box = BarContentBox.of(48f, 36f)
        assertEquals(36f, box.rowDp, 0f)
        assertEquals(36f / 2 + BarContentBox.DROP_UNDER_READINGS_DP, box.centreDp, 0.001f)
        assertEquals(0f, box.topDp, 0f)
        assertEquals(36f + 2 * BarContentBox.DROP_UNDER_READINGS_DP, box.heightDp, 0.001f)
    }

    @Test
    fun theBoxAlwaysFitsInsideTheRowForEveryBarHeight() {
        for (bar in listOf(0f, 4f, 36f, 44f, 48f, 56f, 64f)) {
            for (reading in listOf(null, 0f, 30f, 36f, 60f)) {
                val box = BarContentBox.of(bar, reading)
                assertTrue("$bar/$reading", box.topDp >= 0f && box.heightDp >= 0f && box.bottomInsetDp >= -0.001f)
                val drop = BarContentBox.dropDp(reading != null)
                if (box.heightDp > 0f) assertEquals(box.rowDp / 2 + drop, box.centreDp, 0.001f)
            }
        }
    }
}

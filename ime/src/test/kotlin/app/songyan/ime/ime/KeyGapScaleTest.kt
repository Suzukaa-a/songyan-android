package app.songyan.ime.ime

import app.songyan.ime.layout.LayoutSplitConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every phone draws a skin exactly; wider columns grow its gaps, labels and corners, by the same
 * rates as on iOS.
 */
class KeyGapScaleTest {
    @Test
    fun phonesKeepTheSkinGap() {
        for (width in listOf(354.0, 406.0, 430.0)) {
            assertEquals("width $width", 1.0, KeyGapScale.of(width, 5.0, null), 0.0)
        }
    }

    @Test
    fun widerColumnsGrowTheGapsLabelsAndCorners() {
        val width = 800.0
        val scale = KeyGapScale.of(width, 5.5, null)
        assertEquals((width + 5.5) / 10 / KeyGapScale.REFERENCE_PITCH_DP, scale, 1e-9)
        // The gap across outgrows the columns; the gap between rows, corners and labels, whose row
        // height does not grow, grow slower, corners and labels slowest.
        assertTrue(KeyGapScale.horizontal(scale) > scale)
        assertTrue(KeyGapScale.vertical(scale) in 1.0..scale)
        assertTrue(KeyGapScale.corner(scale) in 1.0..scale)
        assertTrue(KeyGapScale.label(scale) > 1.0)
        assertTrue(KeyGapScale.corner(scale) < KeyGapScale.vertical(scale))
        for (factor in listOf(
            KeyGapScale::horizontal,
            KeyGapScale::vertical,
            KeyGapScale::corner,
            KeyGapScale::label,
        )) {
            assertEquals(1.0, factor(1.0), 0.0)
        }
    }

    @Test
    fun aSplitBoardScalesByItsHalves() {
        val split = LayoutSplitConfiguration(activation = LayoutSplitConfiguration.Activation.ALWAYS)
        assertTrue(KeyGapScale.of(1000.0, 5.0, split) < KeyGapScale.of(1000.0, 5.0, null))
    }
}

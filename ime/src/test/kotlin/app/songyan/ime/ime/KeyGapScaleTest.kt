package app.songyan.ime.ime

import app.songyan.ime.layout.LayoutSplitConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every phone draws a skin's key gap exactly; wider columns grow it in proportion, so the gap keeps
 * the share of the key its author drew, the same as on iOS.
 */
class KeyGapScaleTest {
    @Test
    fun phonesKeepTheSkinGap() {
        for (width in listOf(354.0, 406.0, 430.0)) {
            assertEquals("width $width", 1.0, KeyGapScale.of(width, 5.0, null), 0.0)
        }
    }

    @Test
    fun widerColumnsGrowTheGapInProportion() {
        val width = 800.0
        val scale = KeyGapScale.of(width, 5.0, null)
        assertEquals((width + 5.0) / 10 / KeyGapScale.REFERENCE_PITCH_DP, scale, 1e-9)
        val pitch = (width + 5.0 * scale) / 10
        assertEquals(5.0 / KeyGapScale.REFERENCE_PITCH_DP, 5.0 * scale / pitch, 0.01)
    }

    @Test
    fun aSplitBoardScalesByItsHalves() {
        val split = LayoutSplitConfiguration(activation = LayoutSplitConfiguration.Activation.ALWAYS)
        assertTrue(KeyGapScale.of(1000.0, 5.0, split) < KeyGapScale.of(1000.0, 5.0, null))
    }
}

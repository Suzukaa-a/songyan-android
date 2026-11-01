package app.songyan.ime.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The quick-content panel budget never overflows and its bands stay usable. */
class QuickContentPanelLayoutTest {
    @Test
    fun regularDensityAtRoomHeight() {
        val layout = QuickContentPanelLayout.resolve(height = 300.0, railItemCount = 4)
        assertEquals(QuickContentPanelLayout.Density.REGULAR, layout.density)
        assertEquals(44.0, layout.titleBarHeight, 0.0001)
        assertEquals(0.5, layout.separatorHeight, 0.0001)
        assertEquals(255.5, layout.contentHeight, 0.0001)
        assertEquals(68.0, layout.railWidth, 0.0001)
        assertEquals(56.0, layout.symbolCellMinHeight, 0.0001)
        assertEquals(64.0, layout.symbolColumnMinWidth, 0.0001)
        assertEquals(72.0, layout.kaomojiColumnMinWidth, 0.0001)
        assertEquals(3, layout.kaomojiColumnCount)
        assertTrue(layout.showsRecents)
    }

    @Test
    fun compactDensityInLandscape() {
        val layout = QuickContentPanelLayout.resolve(height = 200.0, railItemCount = 4)
        assertEquals(QuickContentPanelLayout.Density.COMPACT, layout.density)
        assertEquals(40.0, layout.titleBarHeight, 0.0001)
        assertEquals(159.5, layout.contentHeight, 0.0001)
        assertEquals(48.0, layout.symbolCellMinHeight, 0.0001)
        assertEquals(58.0, layout.symbolColumnMinWidth, 0.0001)
        assertFalse(layout.showsRecents)
    }

    @Test
    fun minimalDensityOnVeryShortPanels() {
        val layout = QuickContentPanelLayout.resolve(height = 150.0, railItemCount = 4)
        assertEquals(QuickContentPanelLayout.Density.MINIMAL, layout.density)
        assertEquals(36.0, layout.titleBarHeight, 0.0001)
        assertEquals(113.5, layout.contentHeight, 0.0001)
        assertEquals(44.0, layout.symbolCellMinHeight, 0.0001)
        assertEquals(52.0, layout.symbolColumnMinWidth, 0.0001)
        assertFalse(layout.showsRecents)
    }

    @Test
    fun chromeNeverExceedsThePanel() {
        for (height in 0..400 step 7) {
            val layout = QuickContentPanelLayout.resolve(height = height.toDouble(), railItemCount = 3)
            assertTrue("height=$height", layout.titleBarHeight + layout.separatorHeight <= height + 0.0001)
            assertTrue("height=$height", layout.contentHeight >= 0.0)
        }
        val zero = QuickContentPanelLayout.resolve(height = 0.0, railItemCount = 3)
        assertEquals(0.0, zero.titleBarHeight, 0.0001)
        assertEquals(0.0, zero.contentHeight, 0.0001)
        val tiny = QuickContentPanelLayout.resolve(height = 10.0, railItemCount = 3)
        assertEquals(QuickContentPanelLayout.Density.MINIMAL, tiny.density)
        assertEquals(0.0, tiny.contentHeight, 0.0001)
        assertTrue(tiny.symbolCellMinHeight > 0.0)
    }

    @Test
    fun railSlotStaysATouchTarget() {
        // Too many rows for the height: the list scrolls, the slot never drops below the floor.
        val crowded = QuickContentPanelLayout.resolve(height = 250.0, railItemCount = 10)
        assertEquals(48.0, crowded.railSlotHeight, 0.0001)
        assertTrue(QuickContentPanelLayout.resolve(height = 150.0, railItemCount = 12).railSlotHeight >= 48.0)
        // A handful of rows: the slot grows but never above the ceiling.
        val roomy = QuickContentPanelLayout.resolve(height = 400.0, railItemCount = 3)
        assertEquals(56.0, roomy.railSlotHeight, 0.0001)
    }
}

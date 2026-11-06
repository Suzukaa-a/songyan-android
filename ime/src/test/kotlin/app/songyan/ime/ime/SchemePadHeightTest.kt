package app.songyan.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The vertical budget of the scheme pads. The zhuyin pad adds a fourth encoding row to the standard
 * page, so its five rows share the same four bands as every other page instead of growing the key
 * area past the 256 dp limit; the three presets still give three different heights on a portrait
 * window and on the short (landscape / split-screen) ones. The standard three-row pages keep their
 * exact old heights.
 */
class SchemePadHeightTest {
    private val bands = KeyboardDesignTokens.KEY_ROWS

    /** The area cap from the height decision; the standard page never grows past it. */
    private val maxAreaDp = 256f

    private fun pitch(
        preset: KeyHeight,
        screenHeightDp: Int,
    ) = KeyboardGeometry.rowPitchDp(preset, screenHeightDp, 48f, 8f)

    /** One row of a page with [pageRows] rows: the area's bands are shared, never exceeded. */
    private fun rowHeight(
        pitchDp: Float,
        pageRows: Int,
    ) = pitchDp * bands / maxOf(pageRows, bands)

    @Test
    fun theFiveRowPadSharesTheFourBandAreaWithoutGrowingIt() {
        for (screen in listOf(751, 800, 890, 1280)) {
            val areas = KeyHeight.entries.map { KeyboardGeometry.portraitAreaDp(it, screen) }
            for (preset in KeyHeight.entries) {
                val area = KeyboardGeometry.portraitAreaDp(preset, screen)
                val pitch = pitch(preset, screen)
                // Five rows of the pad fill exactly the four bands of the window, not a fifth one.
                assertEquals("$screen $preset", area, rowHeight(pitch, 5) * 5, 0.01f)
                assertEquals("$screen $preset", area, bands * pitch, 0.01f)
                assertTrue("$screen $preset $area", area <= maxAreaDp)
                // The pads stay finger-sized on a full-size portrait window.
                assertTrue("$screen $preset", rowHeight(pitch, 5) >= 44f)
            }
            // The presets must not collapse into one height just because the pad has more rows.
            assertEquals("$screen $areas", 3, areas.distinct().size)
            assertEquals("$screen $areas", areas.sorted(), areas)
        }
    }

    @Test
    fun theThreePresetsStayDistinctOnTheFiveRowPadOnShortWindows() {
        // Phone landscape and split-screen panes: the choice still counts, at a shorter pitch.
        for (screen in listOf(440, 480, 520)) {
            val heights = KeyHeight.entries.map { rowHeight(pitch(it, screen), 5) }
            assertEquals("$screen $heights", 3, heights.distinct().size)
            assertEquals("$screen $heights", heights.sorted(), heights)
            assertTrue("$screen $heights", heights[1] - heights[0] >= 1.2f - 0.001f)
        }
    }

    @Test
    fun aShortWindowStillKeepsEveryPadRowInsideTheKeyboardBudget() {
        for (screen in listOf(360, 411, 440, 480, 520)) {
            for (preset in KeyHeight.entries) {
                val pitch = pitch(preset, screen)
                val area = bands * pitch
                assertEquals("$screen $preset", area, rowHeight(pitch, 5) * 5, 0.01f)
                assertTrue(
                    "$screen $preset $pitch",
                    area <= KeyboardGeometry.shortKeyboardMaxDp(screen) + 0.01f || pitch == 32f,
                )
                assertTrue("$screen $preset", rowHeight(pitch, 5) > 0f)
            }
        }
    }

    @Test
    fun theStandardThreeRowPagesKeepTheirExactHeights() {
        assertEquals(listOf(56f, 60f, 64f), KeyHeight.entries.map { pitch(it, 890) })
        assertEquals(listOf(42f, 45f, 48f), KeyHeight.entries.map { pitch(it, 480) })
        assertEquals(
            listOf(224f, 240f, 256f),
            KeyHeight.entries.map { KeyboardGeometry.portraitAreaDp(it, 890) },
        )
        for (preset in KeyHeight.entries) {
            val full = pitch(preset, 890)
            // A page of four bands keeps one full band per row: the standard page is unchanged.
            assertEquals(full, rowHeight(full, 4), 0.001f)
            assertEquals(full * bands, rowHeight(full, 4) * 4, 0.001f)
        }
    }
}

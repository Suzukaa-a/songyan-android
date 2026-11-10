package app.songyan.ime.ime

import app.songyan.ime.layout.PhoneKeypadMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class Kana12LayoutGeometryTest {
    private val geometry = Kana12Geometry.resolve(widthDp = 360.0, heightDp = 224.0)

    private val bandHeight = 224.0 / Kana12Geometry.BAND_COUNT

    private fun rail(region: Kana12Region): List<Kana12Slot> = geometry.slots.filter { it.region == region }

    private fun grid(): List<Kana12Slot> = rail(Kana12Region.Grid)

    private fun assertClose(
        expected: Double,
        actual: Double,
    ) {
        assertTrue("expected $expected but was $actual", abs(expected - actual) < 1e-9)
    }

    @Test
    fun `the surface holds twelve grid keys and the two rails`() {
        assertEquals(19, geometry.slots.size)
        assertEquals(12, grid().size)
        assertEquals(Kana12Geometry.DEFAULT_LEFT_BANDS.size, rail(Kana12Region.LeftRail).size)
        assertEquals(Kana12Geometry.DEFAULT_RIGHT_BANDS.size, rail(Kana12Region.RightRail).size)
    }

    @Test
    fun `the grid is four rows of three aligned columns`() {
        val grid = grid()
        for (column in 0 until Kana12Geometry.GRID_COLUMNS) {
            val first = grid[column].bounds
            for (row in 0 until Kana12Geometry.BAND_COUNT) {
                val slot = grid[row * Kana12Geometry.GRID_COLUMNS + column]
                assertClose(first.left, slot.bounds.left)
                assertClose(first.width, slot.bounds.width)
                assertClose(row * bandHeight, slot.bounds.top)
                assertClose((row + 1) * bandHeight, slot.bounds.bottom)
                assertEquals(1, slot.bands)
            }
        }
    }

    @Test
    fun `the right rail stacks delete, space and a two-band return`() {
        val rail = rail(Kana12Region.RightRail)
        assertEquals(listOf(1, 1, 2), rail.map { it.bands })
        assertClose(0.0, rail[0].bounds.top)
        assertClose(bandHeight, rail[0].bounds.bottom)
        assertClose(bandHeight, rail[1].bounds.top)
        assertClose(2 * bandHeight, rail[1].bounds.bottom)
        assertClose(2 * bandHeight, rail[2].bounds.top)
        assertClose(224.0, rail[2].bounds.bottom)
    }

    @Test
    fun `the leading rail gives every key including the system switch its own band`() {
        val rail = rail(Kana12Region.LeftRail)
        assertEquals(Kana12Geometry.DEFAULT_LEFT_BANDS, rail.map { it.bands })
        for ((index, slot) in rail.withIndex()) {
            assertClose(index * bandHeight, slot.bounds.top)
            assertClose((index + 1) * bandHeight, slot.bounds.bottom)
        }
    }

    @Test
    fun `the kana keys keep the nine-key shell's solved width`() {
        val metrics = PhoneKeypadMetrics.resolve(360.0)
        val kana = grid().first().bounds
        val left = rail(Kana12Region.LeftRail).first().bounds
        val right = rail(Kana12Region.RightRail).first().bounds
        assertTrue(kana.width > left.width)
        assertClose(metrics.digitWidth, kana.width)
        assertClose(metrics.railWidth, left.width)
        assertEquals(left.width, right.width, 1e-9)
        assertClose(geometry.content.left, left.left)
        assertClose(geometry.content.right, right.right)
        assertClose(metrics.spacing, grid().first().bounds.left - left.right)
        assertClose(metrics.spacing, right.left - grid()[Kana12Geometry.GRID_COLUMNS - 1].bounds.right)
    }

    @Test
    fun `every placed key keeps its own centre and the margins belong to nobody`() {
        for (slot in geometry.slots) {
            assertEquals(slot, geometry.slotAt(slot.bounds.centerX, slot.bounds.centerY))
        }
        val first = grid().first()
        assertEquals(first, geometry.slotAt(first.bounds.right - 1e-6, first.bounds.centerY))
        assertNull(geometry.slotAt(-1.0, 10.0))
        assertNull(geometry.slotAt(361.0, 10.0))
        // The gutter between two grid columns is no key's own slot; touch zones close it.
        assertNull(geometry.slotAt(first.bounds.right + 1.0, first.bounds.centerY))
    }

    @Test
    fun `the drawn cap retreats the design-token gutter from its slot`() {
        val slot = geometry.slots.first()
        val tokens = KeyboardDesignTokens.light()
        val spacing = PhoneKeypadMetrics.resolve(360.0).spacing
        assertClose((tokens.keyGapHorizontalDp.toDouble() - spacing) / 2, geometry.capInsetHorizontal)
        assertClose(tokens.keyGapVerticalDp.toDouble() / 2, geometry.capInsetVertical)
        val cap = geometry.capBounds(slot)
        assertClose(slot.bounds.left + geometry.capInsetHorizontal, cap.left)
        assertClose(slot.bounds.top + geometry.capInsetVertical, cap.top)
        assertClose(slot.bounds.right - geometry.capInsetHorizontal, cap.right)
        assertClose(slot.bounds.bottom - geometry.capInsetVertical, cap.bottom)
    }

    @Test
    fun `the default gutters are the design tokens' own`() {
        val tokens = KeyboardDesignTokens.light()
        assertEquals(tokens.keyGapHorizontalDp.toDouble(), Kana12Geometry.DEFAULT_KEY_GAP_HORIZONTAL_DP, 1e-9)
        assertEquals(tokens.keyGapVerticalDp.toDouble(), Kana12Geometry.DEFAULT_KEY_GAP_VERTICAL_DP, 1e-9)
    }

    @Test
    fun `a caller's own rail split is honoured`() {
        val alternate = Kana12Geometry.resolve(360.0, 224.0, leftRailBands = listOf(2, 1, 1))
        val rail = alternate.slots.filter { it.region == Kana12Region.LeftRail }
        assertEquals(listOf(2, 1, 1), rail.map { it.bands })
        assertEquals(3, rail.size)
        assertClose(0.0, rail[0].bounds.top)
        assertClose(2 * bandHeight, rail[0].bounds.bottom)
        assertClose(2 * bandHeight, rail[1].bounds.top)
        assertClose(224.0, rail[2].bounds.bottom)
    }

    @Test
    fun `a rail split with no usable span falls back to one band per key`() {
        val fallback = Kana12Geometry.resolve(360.0, 224.0, rightRailBands = listOf(0, -1))
        val rail = fallback.slots.filter { it.region == Kana12Region.RightRail }
        assertEquals(2, rail.size)
        assertEquals(listOf(1, 1), rail.map { it.bands })
        assertClose(224.0, rail.last().bounds.bottom)
    }

    @Test
    fun `a narrow or empty surface never yields a broken rectangle`() {
        for (size in listOf(Kana12Geometry.resolve(160.0, 200.0), Kana12Geometry.resolve(0.0, 0.0))) {
            assertEquals(19, size.slots.size)
            for (slot in size.slots) {
                assertTrue(slot.bounds.width >= 0.0)
                assertTrue(slot.bounds.height >= 0.0)
            }
        }
    }
}

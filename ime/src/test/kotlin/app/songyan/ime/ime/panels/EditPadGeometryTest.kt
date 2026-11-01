package app.songyan.ime.ime.panels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditPadGeometryTest {
    private val sizes = listOf(360.0 to 180.0, 800.0 to 90.0, 412.0 to 220.0)

    @Test
    fun keysStayInsideTheBoundsAndNeverOverlap() {
        for ((w, h) in sizes) {
            val cells = EditPadGeometry.cells(w, h, leftDp = 10.0, topDp = 30.0)
            assertEquals(EditPadKey.entries.size, cells.size)
            cells.forEach {
                assertTrue(it.left >= 10.0 - EPS && it.right <= 10.0 + w + EPS)
                assertTrue(it.top >= 30.0 - EPS && it.bottom <= 30.0 + h + EPS)
                assertTrue(it.width > 0 && it.height > 0)
            }
            for (i in cells.indices) {
                for (j in i + 1 until cells.size) {
                    val a = cells[i]
                    val b = cells[j]
                    val apart =
                        a.right <= b.left + EPS ||
                            b.right <= a.left + EPS ||
                            a.bottom <= b.top + EPS ||
                            b.bottom <= a.top + EPS
                    assertTrue("${EditPadKey.entries[i]} overlaps ${EditPadKey.entries[j]}", apart)
                }
            }
        }
    }

    @Test
    fun theKeysTileThePadExactly() {
        val (w, h) = 360.0 to 180.0
        val area = EditPadGeometry.cells(w, h).sumOf { it.width * it.height }
        assertEquals(w * h, area, 1e-6)
    }

    @Test
    fun theArrowsSpanTheFullHeightOnEitherSideOfTheCentralBlock() {
        val cells = EditPadGeometry.cells(360.0, 180.0, topDp = 30.0)
        val left = cells[EditPadKey.LEFT.ordinal]
        val right = cells[EditPadKey.RIGHT.ordinal]
        val up = cells[EditPadKey.UP.ordinal]
        for (arrow in listOf(left, right)) {
            assertEquals(30.0, arrow.top, EPS)
            assertEquals(210.0, arrow.bottom, EPS)
        }
        assertTrue(left.right <= up.left + EPS)
        assertTrue(up.right <= right.left + EPS)
        val select = cells[EditPadKey.SELECT.ordinal]
        val down = cells[EditPadKey.DOWN.ordinal]
        assertTrue(up.bottom <= select.top + EPS && select.bottom <= down.top + EPS)
        val start = cells[EditPadKey.LINE_START.ordinal]
        val end = cells[EditPadKey.LINE_END.ordinal]
        assertTrue(down.bottom <= start.top + EPS)
        assertEquals(start.top, end.top, EPS)
        assertTrue(start.right <= end.left + EPS)
    }

    @Test
    fun theEditingColumnSitsBesideTheRightArrow() {
        val cells = EditPadGeometry.cells(360.0, 180.0)
        val right = cells[EditPadKey.RIGHT.ordinal]
        listOf(EditPadKey.SELECT_ALL, EditPadKey.COPY, EditPadKey.PASTE, EditPadKey.DELETE).forEach {
            assertTrue(cells[it.ordinal].left >= right.right - EPS)
        }
    }

    @Test
    fun onlyTheArrowsAndDeleteRepeatWhenHeld() {
        val repeating = EditPadKey.entries.filter { it.repeats }.toSet()
        assertEquals(
            setOf(EditPadKey.LEFT, EditPadKey.RIGHT, EditPadKey.UP, EditPadKey.DOWN, EditPadKey.DELETE),
            repeating,
        )
        assertFalse(EditPadKey.SELECT.repeats)
    }

    @Test
    fun theFirstRepeatWaitsAtLeastTheSystemHoldDelay() {
        assertEquals(350L, EditPadGeometry.repeatDelayMs(300, 350))
        assertEquals(800L, EditPadGeometry.repeatDelayMs(800, 350))
    }

    private companion object {
        const val EPS = 1e-9
    }
}

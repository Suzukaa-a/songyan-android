package app.songyan.ime.ime.panels

import app.songyan.ime.ime.EditAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditPadGeometryTest {
    /** Phone portrait, short landscape, tablet, one-handed (narrowed) widths: width to content height. */
    private val sizes = listOf(360.0 to 180.0, 800.0 to 90.0, 1280.0 to 300.0, 280.0 to 176.0, 412.0 to 220.0)

    private fun cells(
        w: Double = 360.0,
        h: Double = 180.0,
    ) = EditPadGeometry.cells(w, h)

    private fun box(
        key: EditPadKey,
        cells: List<PanelBox> = cells(),
    ) = cells[key.ordinal]

    @Test
    fun everyKeyOfThePadHasABoxAndEveryActionIsReachable() {
        assertEquals(EditPadKey.entries.size, cells().size)
        val actions = EditPadKey.entries.mapNotNull { it.action }.toSet()
        assertEquals(EditAction.entries.toSet(), actions)
        assertNull(EditPadKey.SELECT.action)
    }

    @Test
    fun keysStayInsideTheBoundsAndNeverOverlap() {
        for ((w, h) in sizes) {
            val cells = EditPadGeometry.cells(w, h, leftDp = 10.0, topDp = 30.0)
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
    fun onlyTheThreeCornersOfTheCrossAreLeftEmpty() {
        val (w, h) = 360.0 to 180.0
        val covered = cells(w, h).sumOf { it.width * it.height }
        val corner = w / EditPadKey.COLUMNS * (h / EditPadKey.ROWS)
        assertEquals(w * h - 3 * corner, covered, 1e-6)
        val occupied = EditPadKey.entries.map { it.column to it.row }.toSet()
        val empty =
            (0 until EditPadKey.COLUMNS).flatMap { c -> (0 until EditPadKey.ROWS).map { r -> c to r } } - occupied
        assertEquals(setOf(2 to 0, 0 to 2, 2 to 2), empty.toSet())
    }

    @Test
    fun theArrowsFormACrossAroundSelectWithUndoInTheTopLeftCorner() {
        val cells = cells()
        val select = box(EditPadKey.SELECT, cells)
        val up = box(EditPadKey.UP, cells)
        val down = box(EditPadKey.DOWN, cells)
        val left = box(EditPadKey.LEFT, cells)
        val right = box(EditPadKey.RIGHT, cells)
        assertEquals(select.centerX, up.centerX, EPS)
        assertEquals(select.centerX, down.centerX, EPS)
        assertEquals(select.centerY, left.centerY, EPS)
        assertEquals(select.centerY, right.centerY, EPS)
        assertTrue(up.bottom <= select.top + EPS && select.bottom <= down.top + EPS)
        assertTrue(left.right <= select.left + EPS && select.right <= right.left + EPS)
        val undo = box(EditPadKey.UNDO, cells)
        assertEquals(0.0, undo.left, EPS)
        assertEquals(0.0, undo.top, EPS)
    }

    @Test
    fun lineStartSelectAllAndLineEndShareTheRowUnderTheCross() {
        val cells = cells()
        val start = box(EditPadKey.LINE_START, cells)
        val all = box(EditPadKey.SELECT_ALL, cells)
        val end = box(EditPadKey.LINE_END, cells)
        val down = box(EditPadKey.DOWN, cells)
        assertEquals(start.top, all.top, EPS)
        assertEquals(all.top, end.top, EPS)
        assertTrue(down.bottom <= start.top + EPS)
        assertTrue(start.right <= all.left + EPS && all.right <= end.left + EPS)
        assertEquals(box(EditPadKey.LEFT, cells).left, start.left, EPS)
        assertEquals(box(EditPadKey.RIGHT, cells).left, end.left, EPS)
    }

    @Test
    fun theEditingColumnIsDeleteCopyCutPasteTopToBottomRightOfThePad() {
        val cells = cells()
        val column = listOf(EditPadKey.DELETE, EditPadKey.COPY, EditPadKey.CUT, EditPadKey.PASTE).map { box(it, cells) }
        column.zipWithNext { a, b -> assertTrue(a.bottom <= b.top + EPS) }
        val right = box(EditPadKey.RIGHT, cells)
        column.forEach {
            assertEquals(column[0].left, it.left, EPS)
            assertTrue(it.left >= right.right - EPS)
            assertEquals(360.0, it.right, EPS)
        }
        assertEquals(180.0, column.last().bottom, EPS)
    }

    @Test
    fun keysKeepTouchableSizesAtPhoneAndOneHandedWidths() {
        for ((w, h) in listOf(360.0 to 180.0, 280.0 to 176.0, 412.0 to 220.0)) {
            cells(w, h).forEachIndexed { i, it ->
                assertTrue("${EditPadKey.entries[i]} ${it.width}dp wide", it.width >= 64.0)
                assertTrue("${EditPadKey.entries[i]} ${it.height}dp tall", it.height >= 44.0)
            }
        }
    }

    @Test
    fun aShortKeyShowsItsNameAloneAndATallOneStacksIconAndName() {
        assertFalse(EditPadGeometry.stacksLabel(22.5))
        assertTrue(EditPadGeometry.stacksLabel(45.0))
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

    // ---- state ------------------------------------------------------------------------------

    @Test
    fun selectTogglesTheModeAndOnlyCaretKeysExtend() {
        val state = EditPadState()
        assertFalse(state.extends(EditPadKey.RIGHT))
        state.toggleSelect()
        assertTrue(state.selecting)
        listOf(
            EditPadKey.LEFT,
            EditPadKey.RIGHT,
            EditPadKey.UP,
            EditPadKey.DOWN,
            EditPadKey.LINE_START,
            EditPadKey.LINE_END,
        ).forEach { assertTrue("$it", state.extends(it)) }
        listOf(EditPadKey.DELETE, EditPadKey.COPY, EditPadKey.PASTE, EditPadKey.UNDO, EditPadKey.SELECT_ALL)
            .forEach { assertFalse("$it", state.extends(it)) }
        state.toggleSelect()
        assertFalse(state.selecting)
    }

    @Test
    fun selectAllTurnsTheModeOnAndCopyOrCutTurnItOff() {
        val state = EditPadState()
        state.afterKey(EditPadKey.SELECT_ALL, hadSelection = false)
        assertTrue(state.selecting)
        state.afterKey(EditPadKey.COPY, hadSelection = true)
        assertFalse(state.selecting)
        state.toggleSelect()
        state.afterKey(EditPadKey.CUT, hadSelection = true)
        assertFalse(state.selecting)
    }

    @Test
    fun deleteAndPasteEndTheModeOnlyWhenTheyReplacedASelection() {
        val state = EditPadState()
        state.toggleSelect()
        state.afterKey(EditPadKey.DELETE, hadSelection = false)
        assertTrue(state.selecting)
        state.afterKey(EditPadKey.DELETE, hadSelection = true)
        assertFalse(state.selecting)
        state.toggleSelect()
        state.afterKey(EditPadKey.PASTE, hadSelection = true)
        assertFalse(state.selecting)
    }

    @Test
    fun movingKeepsTheModeOn() {
        val state = EditPadState()
        state.toggleSelect()
        state.afterKey(EditPadKey.RIGHT, hadSelection = true)
        state.afterKey(EditPadKey.LINE_END, hadSelection = true)
        assertTrue(state.selecting)
    }

    @Test
    fun copyAndCutNeedASelectionAndPasteNeedsAClip() {
        val state = EditPadState()
        state.availability = EditAvailability(hasSelection = false, canPaste = false)
        listOf(EditPadKey.COPY, EditPadKey.CUT, EditPadKey.PASTE).forEach { assertFalse("$it", state.isEnabled(it)) }
        EditPadKey.entries
            .filter { it !in setOf(EditPadKey.COPY, EditPadKey.CUT, EditPadKey.PASTE) }
            .forEach { assertTrue("$it stays live", state.isEnabled(it)) }
        state.availability = EditAvailability(hasSelection = true, canPaste = true)
        EditPadKey.entries.forEach { assertTrue("$it", state.isEnabled(it)) }
    }

    @Test
    fun resetTurnsSelectOff() {
        val state = EditPadState()
        state.toggleSelect()
        state.reset()
        assertFalse(state.selecting)
    }

    private companion object {
        const val EPS = 1e-9
    }
}

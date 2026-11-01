package app.songyan.ime.ime.panels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelGeometryTest {
    @Test
    fun columnsFitTheMinimumWidthAndNeverDropToZero() {
        assertEquals(4, PanelGeometry.columnCount(contentWidthDp = 280.0, minColumnWidthDp = 64.0))
        assertEquals(1, PanelGeometry.columnCount(contentWidthDp = 30.0, minColumnWidthDp = 64.0))
        assertEquals(1, PanelGeometry.columnCount(contentWidthDp = 0.0, minColumnWidthDp = 64.0))
        assertEquals(1, PanelGeometry.columnCount(contentWidthDp = 200.0, minColumnWidthDp = 0.0))
    }

    @Test
    fun visibleRowsNeverDropToZero() {
        assertEquals(3, PanelGeometry.visibleRowCount(contentHeightDp = 180.0, cellHeightDp = 56.0))
        assertEquals(1, PanelGeometry.visibleRowCount(contentHeightDp = 10.0, cellHeightDp = 56.0))
        assertEquals(1, PanelGeometry.visibleRowCount(contentHeightDp = 0.0, cellHeightDp = 56.0))
    }

    @Test
    fun scrollRangeIsZeroWhenEverythingFits() {
        val range =
            PanelGeometry.contentScrollRange(
                itemCount = 8,
                columns = 4,
                cellHeightDp = 56.0,
                contentHeightDp = 120.0,
            )
        assertEquals(0.0, range, 0.001)
    }

    @Test
    fun scrollRangeCountsWholeRows() {
        // 10 symbols in 4 columns = 3 rows = 168 dp of content, 120 dp visible.
        val range =
            PanelGeometry.contentScrollRange(
                itemCount = 10,
                columns = 4,
                cellHeightDp = 56.0,
                contentHeightDp = 120.0,
            )
        assertEquals(48.0, range, 0.001)
    }

    @Test
    fun gridTilesLeftToRightThenDown() {
        val cells =
            PanelGeometry.gridCells(
                itemCount = 5,
                columns = 3,
                contentWidthDp = 300.0,
                topDp = 40.0,
                cellHeightDp = 50.0,
            )
        assertEquals(5, cells.size)
        assertEquals(0.0, cells[0].left, 0.001)
        assertEquals(100.0, cells[1].left, 0.001)
        assertEquals(200.0, cells[2].left, 0.001)
        assertEquals(0.0, cells[3].left, 0.001)
        assertEquals(40.0, cells[0].top, 0.001)
        assertEquals(40.0, cells[2].top, 0.001)
        assertEquals(90.0, cells[3].top, 0.001)
        assertEquals(100.0, cells[0].width, 0.001)
        assertEquals(50.0, cells[0].height, 0.001)
    }

    @Test
    fun aSectionStartsItsOwnRowBelowItsHeading() {
        val cells =
            PanelGeometry.gridCells(
                itemCount = 5,
                columns = 3,
                contentWidthDp = 300.0,
                topDp = 40.0,
                cellHeightDp = 50.0,
                sectionStart = 4,
                sectionTitleDp = 28.0,
            )
        // Cell 3 fills the second row; the section begins a third row, under the heading.
        assertEquals(90.0, cells[3].top, 0.001)
        assertEquals(0.0, cells[4].left, 0.001)
        assertEquals(168.0, cells[4].top, 0.001)
        assertEquals(3, PanelGeometry.gridRows(5, 3, sectionStart = 4))
        assertEquals(
            3 * 50.0 + 28.0 - 100.0,
            PanelGeometry.contentScrollRange(5, 3, 50.0, 100.0, sectionStart = 4, sectionTitleDp = 28.0),
            0.001,
        )
        // No section where it would start the grid or lie past it.
        assertEquals(
            40.0,
            PanelGeometry.gridCells(2, 3, 300.0, 40.0, 50.0, sectionStart = 0, sectionTitleDp = 28.0)[0].top,
            0.001,
        )
    }

    @Test
    fun cellsStartBesideTheRail() {
        val cells =
            PanelGeometry.gridCells(
                itemCount = 5,
                columns = 2,
                contentWidthDp = 200.0,
                topDp = 40.0,
                cellHeightDp = 50.0,
                leftDp = 60.0,
            )
        assertEquals(60.0, cells[0].left, 0.001)
        assertEquals(160.0, cells[0].right, 0.001)
        assertEquals(160.0, cells[1].left, 0.001)
        assertEquals(260.0, cells[1].right, 0.001)
        assertEquals(60.0, cells[2].left, 0.001)
    }

    @Test
    fun scrollMovesCellsUpwards() {
        val cells =
            PanelGeometry.gridCells(
                itemCount = 4,
                columns = 2,
                contentWidthDp = 200.0,
                topDp = 40.0,
                cellHeightDp = 50.0,
                scrollDp = 30.0,
            )
        assertEquals(10.0, cells[0].top, 0.001)
        assertEquals(60.0, cells[2].top, 0.001)
    }

    @Test
    fun railSlotsStackAndScroll() {
        val slots = PanelGeometry.railSlots(slotCount = 4, topDp = 44.0, slotHeightDp = 40.0, scrollDp = 20.0)
        assertEquals(4, slots.size)
        assertEquals(24.0, slots[0].top, 0.001)
        assertEquals(64.0, slots[1].top, 0.001)
        assertEquals(40.0, slots[0].height, 0.001)
    }

    @Test
    fun wholeRowsDivideTheViewportWithoutASlice() {
        // 275 dp of content at the symbol grid's 56 dp minimum: five rows of 55 dp, not four rows
        // plus a sliced fifth.
        val height = PanelGeometry.wholeRowHeight(contentHeightDp = 275.0, minCellHeightDp = 56.0)
        assertEquals(55.0, height, 0.001)
        assertEquals(0.0, 275.0 % height, 0.001)
    }

    @Test
    fun wholeRowsNeverDropBelowTheTouchTarget() {
        val height = PanelGeometry.wholeRowHeight(contentHeightDp = 275.0, minCellHeightDp = 48.0)
        assertTrue(height >= 48.0)
        assertEquals(0.0, 275.0 % height, 0.001)
    }

    @Test
    fun aShortPanelStillGetsOneWholeRow() {
        val height = PanelGeometry.wholeRowHeight(contentHeightDp = 40.0, minCellHeightDp = 56.0)
        assertEquals(40.0, height, 0.001)
    }

    @Test
    fun hitTestUsesTheBoxContainingThePoint() {
        val boxes =
            PanelGeometry.gridCells(
                itemCount = 4,
                columns = 2,
                contentWidthDp = 200.0,
                topDp = 0.0,
                cellHeightDp = 50.0,
            )
        assertEquals(0, PanelGeometry.hitIndex(boxes, 10.0, 10.0))
        assertEquals(1, PanelGeometry.hitIndex(boxes, 150.0, 10.0))
        assertEquals(2, PanelGeometry.hitIndex(boxes, 10.0, 60.0))
        assertEquals(3, PanelGeometry.hitIndex(boxes, 150.0, 60.0))
        assertEquals(-1, PanelGeometry.hitIndex(boxes, 10.0, 200.0))
        assertEquals(-1, PanelGeometry.hitIndex(boxes, -5.0, 10.0))
    }

    @Test
    fun boxContainsItsTopLeftButNotItsBottomRight() {
        val box = PanelBox(left = 10.0, top = 20.0, right = 50.0, bottom = 70.0)
        assertTrue(box.contains(10.0, 20.0))
        assertTrue(box.contains(49.9, 69.9))
        assertFalse(box.contains(50.0, 69.9))
        assertFalse(box.contains(49.9, 70.0))
        assertEquals(30.0, box.centerX, 0.001)
        assertEquals(45.0, box.centerY, 0.001)
    }

    @Test
    fun aPhoneKeepsTheAuthoredColumnsAndAWideSurfaceAddsColumnsAtTheSameCellWidth() {
        val rail = 68.0
        val narrow = PanelGeometry.symbolPageColumns(6, 360.0 - rail, rail, 44.0)
        assertEquals(6, narrow)
        val wide = PanelGeometry.symbolPageColumns(6, 900.0 - rail, rail, 44.0)
        assertTrue("wide=$wide", wide > narrow)
        val phoneCell = (360.0 - rail) / narrow
        val wideCell = (900.0 - rail) / wide
        assertTrue("wide cell $wideCell vs phone $phoneCell", wideCell in phoneCell..phoneCell * 1.2)
    }

    @Test
    fun wideCellsStayNearThePhoneWidthAcrossSurfaceWidths() {
        val rail = 68.0
        for (authored in listOf(3, 4, 6, 8)) {
            val phoneColumns = PanelGeometry.symbolPageColumns(authored, 360.0 - rail, rail, 44.0)
            val phoneCell = (360.0 - rail) / phoneColumns
            for (surface in listOf(600.0, 720.0, 840.0, 900.0, 1024.0)) {
                val columns = PanelGeometry.symbolPageColumns(authored, surface - rail, rail, 44.0)
                assertTrue(columns in phoneColumns..PanelGeometry.MAX_SYMBOL_COLUMNS)
                val cell = (surface - rail) / columns
                if (columns < PanelGeometry.MAX_SYMBOL_COLUMNS) {
                    assertTrue("authored=$authored surface=$surface cell=$cell", cell <= phoneCell * 1.35)
                }
            }
        }
    }

    @Test
    fun aNarrowerThanPhoneWindowBehavesAsBefore() {
        assertEquals(4, PanelGeometry.symbolPageColumns(8, 200.0, 68.0, 44.0))
        assertEquals(1, PanelGeometry.symbolPageColumns(5, 30.0, 68.0, 44.0))
    }

    @Test
    fun theRailScrollsTheOpenSlotIntoView() {
        assertEquals(0.0, PanelGeometry.railScrollRevealing(1, 48.0, 0.0, 150.0), 0.001)
        assertEquals(48.0, PanelGeometry.railScrollRevealing(4, 48.0, 0.0, 192.0), 0.001)
        assertEquals(144.0, PanelGeometry.railScrollRevealing(3, 48.0, 200.0, 100.0), 0.001)
        assertEquals(30.0, PanelGeometry.railScrollRevealing(1, 48.0, 30.0, 96.0), 0.001)
    }
}

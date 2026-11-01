package app.songyan.ime.ime.panels

import kotlin.math.roundToInt

/** A rectangle in density-independent pixels; the panel views convert to pixels once. */
data class PanelBox(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top
    val centerX: Double get() = (left + right) / 2
    val centerY: Double get() = (top + bottom) / 2

    fun contains(
        x: Double,
        y: Double,
    ): Boolean = x >= left && x < right && y >= top && y < bottom
}

/**
 * Layout math of the quick-content panels: the leading rail, the content grid and hit testing.
 *
 * Plain geometry with no Android types, so the band/cell rules stay unit-tested while the views
 * only draw and convert to pixels. Every box is in dp and already includes the scroll offset, so
 * a hit test never has to undo scrolling.
 */
object PanelGeometry {
    /** Equal grid columns that fit; always at least one, so a narrow split layout still shows a cell. */
    fun columnCount(
        contentWidthDp: Double,
        minColumnWidthDp: Double,
    ): Int {
        if (contentWidthDp <= 0.0 || minColumnWidthDp <= 0.0) return 1
        return maxOf(1, (contentWidthDp / minColumnWidthDp).toInt())
    }

    /**
     * Columns of the categorized symbol page. Up to [PHONE_REFERENCE_WIDTH_DP] the page keeps its
     * authored column count (squeezed no tighter than [minColumnWidthDp]); on a wider surface the cell
     * width stays at what that phone width gave and the extra room becomes more columns, up to
     * [MAX_SYMBOL_COLUMNS].
     */
    fun symbolPageColumns(
        authoredColumns: Int,
        contentWidthDp: Double,
        railWidthDp: Double,
        minColumnWidthDp: Double,
    ): Int {
        val phoneContent = minOf(contentWidthDp, maxOf(0.0, PHONE_REFERENCE_WIDTH_DP - railWidthDp))
        val phoneColumns = authoredColumns.coerceIn(1, columnCount(phoneContent, minColumnWidthDp))
        if (contentWidthDp <= phoneContent) return phoneColumns
        val cellWidth = phoneContent / phoneColumns
        return columnCount(contentWidthDp, cellWidth).coerceIn(phoneColumns, maxOf(phoneColumns, MAX_SYMBOL_COLUMNS))
    }

    /** Scroll offset that keeps rail slot [index] inside the viewport, moving only when it is not. */
    fun railScrollRevealing(
        index: Int,
        slotHeightDp: Double,
        scrollDp: Double,
        viewportDp: Double,
    ): Double {
        if (index < 0 || viewportDp <= 0.0) return scrollDp
        val top = index * slotHeightDp
        val bottom = top + slotHeightDp
        return when {
            top < scrollDp -> top
            bottom > scrollDp + viewportDp -> bottom - viewportDp
            else -> scrollDp
        }
    }

    /** Width of a typical phone, the size the symbol page's cells are designed for. */
    const val PHONE_REFERENCE_WIDTH_DP = 360.0

    /** Most symbols per row on a wide surface; a row longer than this is hard to scan, so past it the cells widen instead. */
    const val MAX_SYMBOL_COLUMNS = 16

    /** Rows the content area can show at [cellHeightDp]; at least one so the panel is never blank. */
    fun visibleRowCount(
        contentHeightDp: Double,
        cellHeightDp: Double,
    ): Int {
        if (contentHeightDp <= 0.0 || cellHeightDp <= 0.0) return 1
        return maxOf(1, (contentHeightDp / cellHeightDp).toInt())
    }

    /**
     * Row height that makes [contentHeightDp] hold whole rows only.
     *
     * A panel that shows a sliced row at its edge reads as clipped content, and fading the slice out
     * still cuts through readable glyphs. Sizing the rows to the viewport instead means every row is
     * fully visible; the remaining items stay one scroll away, so a scrollable grid still scrolls.
     */
    fun wholeRowHeight(
        contentHeightDp: Double,
        minCellHeightDp: Double,
    ): Double {
        val floorHeight = maxOf(1.0, minCellHeightDp)
        if (contentHeightDp <= 0.0) return floorHeight
        // Nearest row count to the design's own minimum keeps cells close to their intended size
        // instead of stretching a short grid; never so many rows that a row drops under the 48 dp
        // touch target every panel item must keep.
        val nearest = (contentHeightDp / floorHeight).roundToInt().coerceAtLeast(1)
        val maxRows = maxOf(1, (contentHeightDp / MIN_TOUCH_TARGET_DP).toInt())
        val rows = minOf(nearest, maxRows)
        return contentHeightDp / rows
    }

    /** Smallest touch target a panel row may have, in dp. */
    private const val MIN_TOUCH_TARGET_DP = 48.0

    /** How far the content may scroll; zero when everything fits. */
    fun contentScrollRange(
        itemCount: Int,
        columns: Int,
        cellHeightDp: Double,
        contentHeightDp: Double,
        sectionStart: Int = -1,
        sectionTitleDp: Double = 0.0,
    ): Double {
        if (itemCount <= 0 || columns <= 0) return 0.0
        val header = if (sectionStart in 1 until itemCount) sectionTitleDp else 0.0
        return maxOf(0.0, gridRows(itemCount, columns, sectionStart) * cellHeightDp + header - contentHeightDp)
    }

    /** Rows of a grid whose cells from [sectionStart] on begin a new row (no break when out of range). */
    fun gridRows(
        itemCount: Int,
        columns: Int,
        sectionStart: Int = -1,
    ): Int {
        if (itemCount <= 0) return 0
        val cols = maxOf(1, columns)
        if (sectionStart !in 1 until itemCount) return (itemCount + cols - 1) / cols
        return (sectionStart + cols - 1) / cols + (itemCount - sectionStart + cols - 1) / cols
    }

    /**
     * Cell boxes in reading order, left to right then top to bottom, offset by [scrollDp] vertically
     * and by [leftDp] horizontally (the leading rail's width). From [sectionStart] on the cells begin
     * a new row, below a [sectionTitleDp] gap for the section's heading.
     */
    fun gridCells(
        itemCount: Int,
        columns: Int,
        contentWidthDp: Double,
        topDp: Double,
        cellHeightDp: Double,
        scrollDp: Double = 0.0,
        leftDp: Double = 0.0,
        sectionStart: Int = -1,
        sectionTitleDp: Double = 0.0,
    ): List<PanelBox> {
        if (itemCount <= 0) return emptyList()
        val cols = maxOf(1, columns)
        val cellWidth = contentWidthDp / cols
        val top = topDp - scrollDp
        val section = sectionStart.takeIf { it in 1 until itemCount }
        val sectionRow = section?.let { gridRows(it, cols) } ?: 0
        return (0 until itemCount).map { index ->
            val inSection = section != null && index >= section
            val offset = if (inSection) index - section!! else index
            val column = offset % cols
            val row = offset / cols + if (inSection) sectionRow else 0
            val rowTop = top + row * cellHeightDp + if (inSection) sectionTitleDp else 0.0
            PanelBox(
                left = leftDp + column * cellWidth,
                top = rowTop,
                right = leftDp + (column + 1) * cellWidth,
                bottom = rowTop + cellHeightDp,
            )
        }
    }

    /** One box per rail slot, offset by [scrollDp]. */
    fun railSlots(
        slotCount: Int,
        topDp: Double,
        slotHeightDp: Double,
        scrollDp: Double = 0.0,
    ): List<PanelBox> {
        if (slotCount <= 0) return emptyList()
        val top = topDp - scrollDp
        return (0 until slotCount).map { index ->
            PanelBox(
                left = 0.0,
                top = top + index * slotHeightDp,
                right = 0.0,
                bottom = top + (index + 1) * slotHeightDp,
            )
        }
    }

    /** The first box containing the point, or -1. */
    fun hitIndex(
        boxes: List<PanelBox>,
        x: Double,
        y: Double,
    ): Int = boxes.indexOfFirst { it.contains(x, y) }
}

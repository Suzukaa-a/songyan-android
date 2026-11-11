package app.songyan.ime.ime

import android.graphics.Rect
import android.graphics.RectF
import app.songyan.ime.candidate.CandidateBarDefinition
import app.songyan.ime.ime.CandidateBarView.Control
import app.songyan.ime.ime.CandidateBarView.Entry

/**
 * The controls that currently exist: expanded, the fixed column; collapsed, the expand control, kept
 * whenever there are candidates so the strip's end never moves.
 */
internal fun CandidateBarView.controlList(): List<Control> =
    if (expanded) listOf(Control.BACK, Control.BACKSPACE, Control.RETYPE) else listOf(Control.EXPAND)

/**
 * Measures every entry not yet measured at the current text size: once when it joins the list,
 * again only after the text size changes. Layout and drawing read the stored widths.
 */
internal fun CandidateBarView.measureEntries() {
    val textSize = metrics.candidateTextSizeSp * textScale
    val commentSize = metrics.candidateCommentTextSizeSp * textScale
    if (textSize != measuredTextSize || commentSize != measuredCommentSize) {
        measureGeneration++
        measuredTextSize = textSize
        measuredCommentSize = commentSize
    }
    val generation = measureGeneration
    for (entry in entries) {
        if (entry.measuredFor == generation) continue
        textPaint.textSize = textSize
        entry.textWidth = textPaint.measureText(entry.text)
        if (entry.comment.isNotEmpty()) {
            textPaint.textSize = commentSize
            entry.commentWidth = textPaint.measureText(entry.comment)
        } else {
            entry.commentWidth = 0f
        }
        entry.measuredFor = generation
    }
}

/** The width an entry asks for: its text, the reading comment behind it, and the cell padding. */
internal fun CandidateBarView.chipWidth(entry: Entry): Float =
    ChipWidth.of(entry.textWidth, entry.commentWidth, showsComment(entry), chipGapPx, chipPaddingPx)

/** Whether [entry]'s comment is drawn and counted: always when expanded, by the user's choice in the row. */
internal fun CandidateBarView.showsComment(entry: Entry): Boolean =
    entry.comment.isNotEmpty() && (expanded || collapsedComments)

/** The cell width rule, apart from the view so it can be tested without one. */
internal object ChipWidth {
    fun of(
        textWidth: Float,
        commentWidth: Float,
        showComment: Boolean,
        gap: Float,
        padding: Float,
    ): Float = textWidth + (if (showComment) gap / 2 + commentWidth else 0f) + padding * 2
}

/**
 * The collapsed strip: every candidate read so far at its own width, left of the expand control; a
 * chip sliding under the control is clipped, never drawn over it. Inline readings, when there are
 * any, take a section of their own at the start ([InlineStrip]); the candidates start right after
 * its hairline and scroll on their own, so readings never push them out of view.
 */
internal fun CandidateBarView.layoutCollapsed() {
    val right = (width - controls.size * controlWidthPx).toInt().coerceAtLeast(0)
    val sections = layoutReadings(right.toFloat())
    val split = sections.readingWidth.toInt()
    readingViewport.set(0, 0, split, height)
    readingScroll = readingScroll.coerceIn(0f, sections.readingMaxScroll)
    readingMaxScroll = sections.readingMaxScroll
    viewport.set(split, 0, right.coerceAtLeast(split), height)
    val layout =
        CollapsedRowLayout.layout(
            intrinsic = entries.map(::chipWidth),
            available = viewport.width().toFloat(),
            // After the hairline, as much room as a reading's own padding leaves before it.
            start = if (split > 0) (chipPaddingPx - chipGapPx / 2).coerceAtLeast(0f) else stripInsetPx,
            end = stripInsetPx,
            gap = cellSpacingPx,
            minWidth = minChipWidthPx,
        )
    contentExtent = layout.contentWidth
    val origin = viewport.left.toFloat()
    cells =
        layout.lefts.indices.map { i ->
            RectF(origin + layout.lefts[i], 0f, origin + layout.lefts[i] + layout.widths[i], height.toFloat())
        }
    controlCells = controlRects()
}

/**
 * Lays the inline readings out at the start of a row [available] wide and returns their section.
 * A reading cell has the same padding, minimum width and start inset as a candidate cell, so
 * readings and candidates keep one rhythm, and covers the row's whole height.
 */
internal fun CandidateBarView.layoutReadings(available: Float): InlineStrip.Sections {
    if (readings.isEmpty()) {
        readingCells = emptyList()
        return InlineStrip.sections(emptyList(), 0f, available)
    }
    textPaint.textSize = metrics.candidateTextSizeSp * textScale
    val layout =
        ReadingCells.layout(
            readings.map { textPaint.measureText(it.label) },
            stripInsetPx,
            chipPaddingPx,
            minChipWidthPx,
        )
    readingCells =
        layout.lefts.indices.map { i ->
            RectF(layout.lefts[i], 0f, layout.lefts[i] + layout.widths[i], height.toFloat())
        }
    return InlineStrip.sections(layout.widths, stripInsetPx, available)
}

/** The controls in a row at the right end of the strip, each a full touch target. */
internal fun CandidateBarView.controlRects(): List<Rect> {
    var left = viewport.right
    val step = controlWidthPx.toInt()
    return controls.map { Rect(left, 0, left + step, height).also { left += step } }
}

/**
 * The expanded grid: candidates in rows of one touch-target height, left of a fixed column that holds
 * Back, Backspace and Retype. The appearance chooses flowing rows, a single column or a column grid.
 */
internal fun CandidateBarView.layoutExpanded() {
    val column = columnWidthPx
    readingCells = emptyList()
    readingViewport.setEmpty()
    viewport.set(0, 0, (width - column).toInt(), height)
    val gapX = style.columnGapDp * density
    val gapY = style.rowGapDp * density
    val layout =
        when (style.expandedMode) {
            CandidateBarDefinition.Expanded.Mode.ADAPTIVE ->
                ExpandedFlowLayout.layout(
                    intrinsic = entries.map(::chipWidth),
                    available = viewport.width().toFloat(),
                    gap = gapX,
                    rowHeight = barPx,
                    minWidth = minChipWidthPx,
                    rowGap = gapY,
                )
            CandidateBarDefinition.Expanded.Mode.LIST ->
                ExpandedFlowLayout.columns(entries.size, 1, viewport.width().toFloat(), gapX, barPx, gapY)
            CandidateBarDefinition.Expanded.Mode.GRID ->
                ExpandedFlowLayout.columns(
                    entries.size,
                    style.columns,
                    viewport.width().toFloat(),
                    gapX,
                    barPx,
                    gapY,
                )
        }
    contentExtent = layout.contentHeight
    cells = layout.cells.map { RectF(it.left, it.top, it.right, it.bottom) }
    val slot = height / controls.size.coerceAtLeast(1)
    controlCells =
        controls.indices.map { i ->
            Rect(viewport.right, i * slot, width, if (i == controls.lastIndex) height else (i + 1) * slot)
        }
}

internal fun CandidateBarView.viewportExtent(): Float =
    if (expanded) viewport.height().toFloat() else viewport.width().toFloat()

internal fun CandidateBarView.maxScroll(): Float = (contentExtent - viewportExtent()).coerceAtLeast(0f)

/** Where cell [index] starts along the scroll axis, in content coordinates. */
internal fun CandidateBarView.cellStart(index: Int): Float = cells[index].let { if (expanded) it.top else it.left }

/**
 * The first cell whose far edge reaches past [from] along the scroll axis. Cells are in list order
 * and their far edges never decrease along it (a strip runs left to right, the grid top to bottom),
 * so a binary search finds it and drawing touches only the cells on screen.
 */
internal fun CandidateBarView.firstCellReaching(from: Float): Int {
    var low = 0
    var high = cells.size
    while (low < high) {
        val mid = (low + high) ushr 1
        val box = cells[mid]
        if ((if (expanded) box.bottom else box.right) < from) low = mid + 1 else high = mid
    }
    return low
}

/** The first cell at least partly on screen, or -1 with none. */
internal fun CandidateBarView.firstVisibleCell(): Int {
    if (cells.isEmpty()) return -1
    val origin = if (expanded) 0f else viewport.left.toFloat()
    return firstCellReaching(scroll + origin).takeIf { it < cells.size } ?: -1
}

package app.songyan.ime.ime

import app.songyan.ime.keyboard.engine.Candidate
import kotlin.math.max
import kotlin.math.min

/**
 * Pure geometry of the collapsed candidate strip, in pixels.
 *
 * Cell widths come from the text that is actually drawn, measured with the paint that draws it, so
 * a candidate is never ellipsized because a width was estimated at another size. Every cell keeps
 * its own width (never below the touch minimum) and the cells run from the start of the row; they
 * are never stretched to fill it, so a short list leaves the rest of the row empty and a long one
 * scrolls.
 */
internal object CollapsedRowLayout {
    class Result(
        val lefts: List<Float>,
        val widths: List<Float>,
        /** Right edge of the last cell plus the end inset; what the strip can be scrolled to. */
        val contentWidth: Float,
    )

    fun layout(
        intrinsic: List<Float>,
        available: Float,
        start: Float,
        end: Float,
        gap: Float,
        minWidth: Float,
    ): Result {
        if (intrinsic.isEmpty()) return Result(emptyList(), emptyList(), start + end)
        // One candidate may be wider than the whole row; it is the only one that gets cut.
        val cap = max(minWidth, available - start - end)
        val lefts = ArrayList<Float>(intrinsic.size)
        val widths = ArrayList<Float>(intrinsic.size)
        var left = start
        for (natural in intrinsic) {
            val width = min(max(minWidth, natural), cap)
            lefts += left
            widths += width
            left += width + gap
        }
        return Result(lefts, widths, left - gap + end)
    }
}

/**
 * Pure geometry of the expanded candidate grid, in content pixels (the origin is the grid's own
 * top-left, before any scrolling).
 *
 * Candidates flow left to right at their own width and wrap when a row is full, so short ones are
 * not spread across fixed columns and a long one gets a row of its own, capped to the grid width.
 * Rows share one pitch, which is the candidate touch-target height. The first row takes the
 * collapsed strip's [start] inset, [end] inset and [firstRowGap], so it is the collapsed row with
 * the rest unrolled below it.
 */
internal object ExpandedFlowLayout {
    data class Cell(
        val index: Int,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    )

    class Result(
        val cells: List<Cell>,
        val contentHeight: Float,
    )

    fun layout(
        intrinsic: List<Float>,
        available: Float,
        gap: Float,
        rowHeight: Float,
        minWidth: Float,
        rowGap: Float = 0f,
        start: Float = 0f,
        end: Float = 0f,
        firstRowGap: Float = gap,
    ): Result {
        if (intrinsic.isEmpty() || available <= 0f || rowHeight <= 0f) return Result(emptyList(), 0f)
        val pitch = rowHeight + rowGap
        val cells = ArrayList<Cell>(intrinsic.size)
        // The same cap as a collapsed cell, so a candidate keeps its width when the bar expands.
        val cap = max(minWidth, available - start - end)
        val limit = available - end
        var row = 0
        var left = start
        for ((index, natural) in intrinsic.withIndex()) {
            val width = min(max(minWidth, natural), cap)
            if (left > start && left + width > limit) {
                row++
                left = start
            }
            cells += Cell(index, left, row * pitch, left + width, row * pitch + rowHeight)
            left += width + if (row == 0) firstRowGap else gap
        }
        return Result(cells, row * pitch + rowHeight)
    }

    /** [count] cells in [columns] equal columns (one column is the list mode); the last row may be short. */
    fun columns(
        count: Int,
        columns: Int,
        available: Float,
        gap: Float,
        rowHeight: Float,
        rowGap: Float = 0f,
    ): Result {
        if (count <= 0 || available <= 0f || rowHeight <= 0f) return Result(emptyList(), 0f)
        val perRow = columns.coerceAtLeast(1)
        val width = ((available - gap * (perRow - 1)) / perRow).coerceAtLeast(0f)
        val pitch = rowHeight + rowGap
        val cells =
            List(count) { index ->
                val row = index / perRow
                val left = (index % perRow) * (width + gap)
                Cell(index, left, row * pitch, left + width, row * pitch + rowHeight)
            }
        val rows = (count + perRow - 1) / perRow
        return Result(cells, (rows - 1) * pitch + rowHeight)
    }

    /** The cells that intersect the viewport [scroll, scroll + viewport), for drawing and hit tests. */
    fun visible(
        cells: List<Cell>,
        scroll: Float,
        viewport: Float,
    ): List<Cell> = cells.filter { it.bottom > scroll && it.top < scroll + viewport }
}

/**
 * What a candidate release commits, as a pure decision.
 *
 * The engine can replace the candidate list between touch down and touch up, so the cell index on
 * its own never identifies a word: a release commits the identity captured on touch down (its index
 * in the list's own numbering, absolute for a paged list, and its text), or the same text where the
 * engine's new page put it, and otherwise nothing at all.
 */
internal object CandidateRelease {
    data class Pressed(
        val cell: Int,
        val index: Int,
        val text: String,
        val revision: Int,
    )

    /** [fromList] is true for the pressed entry itself, false for the same text found on a new page. */
    data class Resolved(
        val index: Int,
        val text: String,
        val fromList: Boolean,
    )

    /**
     * [listedAt] gives the candidate a cell of the drawn list holds now, numbered like
     * [Pressed.index]; [page] is the engine's current page, page-local.
     */
    fun resolve(
        pressed: Pressed,
        upCell: Int,
        revision: Int,
        listedAt: (Int) -> Candidate?,
        page: List<Candidate>,
    ): Resolved? {
        if (pressed.revision == revision) {
            // Same list: only the pressed cell may commit, and only while it still shows that word.
            val current = listedAt(upCell) ?: return null
            if (upCell != pressed.cell || current.index != pressed.index || current.text != pressed.text) return null
            return Resolved(current.index, current.text, fromList = true)
        }
        // The list moved on under the finger: commit the pressed word if the new page still has it.
        return page.firstOrNull { it.text == pressed.text }?.let { Resolved(it.index, it.text, fromList = false) }
    }

    /**
     * Where the word the user tapped sits on the engine's current page, when the page the bar drew
     * has since been replaced: the same text with the same reading, never merely the same cell.
     */
    fun relocate(
        text: String,
        comment: String,
        candidates: List<Candidate>,
    ): Candidate? =
        candidates.firstOrNull { it.text == text && it.comment == comment }
            ?: candidates.singleOrNull { it.text == text }
}

/**
 * Pure geometry of the collapsed row when inline readings lead it, in pixels.
 *
 * The readings and the candidates are two sections that scroll on their own, split by a fixed
 * hairline, so however many readings a composition has, the first candidates stay in view. The
 * readings section is as wide as its readings when they fit; otherwise it takes a third of the
 * row, or two readings when those need more, but never more than half, so the candidates always
 * keep at least half of the row.
 */
internal object InlineStrip {
    /** One section of the row: where it sits in the view and how far its content runs. */
    class Sections(
        /** The readings section is [0, readingWidth) of the row; the hairline sits at its end. */
        val readingWidth: Float,
        /** Right edge of the last reading at no scroll: what the readings section scrolls to. */
        val readingContent: Float,
    ) {
        val readingMaxScroll: Float get() = max(0f, readingContent - readingWidth)
    }

    /** Share of the row the readings take before they scroll. */
    const val READING_SHARE = 1f / 3f

    /** The readings never take more of the row than this, whatever two of them need. */
    const val MAX_READING_SHARE = 0.5f

    /**
     * The sections for reading cells of [widths] laid out from [start] in a row [available] wide.
     * With no readings the readings section has no width.
     */
    fun sections(
        widths: List<Float>,
        start: Float,
        available: Float,
    ): Sections {
        if (widths.isEmpty() || available <= 0f) return Sections(0f, 0f)
        val content = start + widths.sum()
        val two = start + widths.take(2).sum()
        val cap = min(max(available * READING_SHARE, two), available * MAX_READING_SHARE)
        return Sections(min(content, cap), content)
    }

    /**
     * The cell under [x] of a section whose cells start at [lefts] (view coordinates at no scroll)
     * and that is scrolled by [scroll]; -1 between or past the cells.
     */
    fun hit(
        x: Float,
        scroll: Float,
        lefts: List<Float>,
        widths: List<Float>,
    ): Int {
        val content = x + scroll
        for (i in lefts.indices) {
            if (content >= lefts[i] && content < lefts[i] + widths[i]) return i
        }
        return -1
    }
}

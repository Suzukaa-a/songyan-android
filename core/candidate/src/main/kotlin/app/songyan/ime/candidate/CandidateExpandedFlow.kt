package app.songyan.ime.candidate

import kotlin.math.floor
import kotlin.math.max

/**
 * Adaptive flow layout of expanded candidates.
 *
 * The default expanded area lays candidates out left to right by their actual text width and wraps
 * when a row is full; short candidates are not spread across fixed column halves and long words get
 * their own row so they stay readable. Explicit `grid` (column count) and `list` configurations are
 * handled by the renderer, not here.
 */
object CandidateExpandedFlow {
    /** Minimum touch width of a cell (same as the collapsed strip). */
    const val MINIMUM_CELL_WIDTH: Double = 44.0

    /** Bottom safety inset so the last row's touch area does not hug the bottom bar. */
    const val BOTTOM_INSET: Double = 8.0

    /** Top inset so the first row's press fill does not touch the top divider. */
    const val TOP_INSET: Double = 2.0

    /** Row spacing used by the estimator; the scroller subtracts [BOTTOM_INSET] once, outside the viewport. */
    fun viewportHeight(totalHeight: Double): Double =
        if (!totalHeight.isFinite()) 0.0 else max(0.0, totalHeight - BOTTOM_INSET)

    /** Height available for whole rows inside the viewport. */
    fun rowAreaHeight(totalHeight: Double): Double = max(0.0, viewportHeight(totalHeight) - TOP_INSET)

    /**
     * Fits whole 44 dp rows without showing a fourth row with only its glyph tops. This changes the
     * row height, not the configured inter-row spacing or the candidate order.
     */
    fun fittedRowHeight(
        availableHeight: Double,
        spacing: Double,
    ): Double {
        if (!availableHeight.isFinite() || availableHeight < 44.0 || !spacing.isFinite()) return 44.0
        val gap = max(0.0, spacing)
        val count = max(1.0, floor((availableHeight + gap) / (44.0 + gap)))
        return max(44.0, (availableHeight - (count - 1) * gap) / count)
    }

    /**
     * Same, floored to device pixels so the summed row heights never exceed the viewport (a list
     * that fits on one screen must not become draggable from sub-pixel slack).
     */
    fun fittedRowHeight(
        rowAreaHeight: Double,
        spacing: Double,
        displayScale: Double,
    ): Double {
        val fitted = fittedRowHeight(availableHeight = max(44.0, rowAreaHeight), spacing = spacing)
        if (!displayScale.isFinite() || displayScale <= 0.0) return fitted
        return max(44.0, floor(fitted * displayScale + 1e-4) / displayScale)
    }

    /** One row: candidates and their cell widths correspond one-to-one, in the input order. */
    data class Row(
        val entries: List<BrowserCandidate>,
        val widths: List<Double>,
    )

    /** Preferred cell width: the larger of the text/badge and comment estimates plus side padding. */
    fun cellWidth(
        entry: BrowserCandidate,
        typography: CandidateTypography,
        cellPaddingX: Double,
    ): Double {
        val text = CandidateTextMetrics.estimatedWidth(entry.text, typography.candidateSize)
        val badge =
            entry.selectionBadge?.let {
                CandidateTextMetrics.estimatedWidth(it, typography.secondarySize) + 4.0
            } ?: 0.0
        val comment = CandidateTextMetrics.estimatedWidth(entry.comment, typography.secondarySize)
        val pad = if (cellPaddingX.isFinite()) max(0.0, cellPaddingX) else 0.0
        return max(MINIMUM_CELL_WIDTH, max(text + badge, comment) + pad * 2)
    }

    /** Whether the candidate takes its own row for readability: text or comment over the threshold. */
    fun wantsOwnRow(
        entry: BrowserCandidate,
        fullRowTextLength: Int,
    ): Boolean {
        val threshold = max(1, fullRowTextLength)
        return entry.text.length > threshold || entry.comment.length > threshold * 3
    }

    /**
     * Greedy line breaking: fill left to right and wrap when a candidate does not fit; long or
     * over-wide candidates take their own row capped to the available width. A non-finite or
     * non-positive [availableWidth] means nothing wraps.
     */
    fun layout(
        entries: List<BrowserCandidate>,
        expanded: CandidateBarDefinition.Expanded,
        typography: CandidateTypography,
        cellPaddingX: Double,
        availableWidth: Double,
    ): List<Row> {
        if (entries.isEmpty()) return emptyList()
        val spacing = if (expanded.columnSpacing.isFinite()) max(0.0, expanded.columnSpacing) else 0.0
        // A non-finite or non-positive width means "nothing wraps": the sentinel must not leak into
        // a cell width, because a candidate on its own row would get a non-renderable frame.
        val unconstrained = !availableWidth.isFinite() || availableWidth <= 0.0
        val available = if (unconstrained) Double.MAX_VALUE else availableWidth

        val rows = mutableListOf<Row>()
        var currentEntries = mutableListOf<BrowserCandidate>()
        var currentWidths = mutableListOf<Double>()
        var currentWidth = 0.0

        fun flush() {
            if (currentEntries.isEmpty()) return
            rows.add(Row(currentEntries.toList(), currentWidths.toList()))
            currentEntries = mutableListOf()
            currentWidths = mutableListOf()
            currentWidth = 0.0
        }

        for (entry in entries) {
            val estimated = cellWidth(entry = entry, typography = typography, cellPaddingX = cellPaddingX)
            val ownRow = wantsOwnRow(entry, expanded.fullRowTextLength) || estimated > available + 0.5
            if (ownRow) {
                flush()
                rows.add(Row(listOf(entry), listOf(if (unconstrained) estimated else available)))
                continue
            }
            val projected = if (currentEntries.isEmpty()) estimated else currentWidth + spacing + estimated
            if (currentEntries.isNotEmpty() && projected > available + 0.5) flush()
            currentEntries.add(entry)
            currentWidths.add(estimated)
            currentWidth = if (currentEntries.size == 1) estimated else currentWidth + spacing + estimated
        }
        flush()
        return rows
    }
}

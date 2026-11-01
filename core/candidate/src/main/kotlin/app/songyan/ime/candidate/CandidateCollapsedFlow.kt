package app.songyan.ime.candidate

import kotlin.math.max
import kotlin.math.min

/** Collapsed strip layout result: per-cell widths and whether horizontal scrolling is really needed. */
data class CandidateCollapsedLayout(
    val cellWidths: List<Double>,
    val scrolls: Boolean,
)

/**
 * Width policy for the collapsed candidate strip.
 *
 * A fixed-width cell cannot hold a three-character word at a 20 dp candidate size: it would be
 * truncated to one character plus an ellipsis. So:
 *  * few short candidates get even-width cells (never wider than [MAXIMUM_EVEN_CELL_WIDTH]) only
 *    when every candidate fits **by total width**;
 *  * otherwise cells follow the intrinsic width of the text actually drawn; a cell **may exceed the
 *    viewport** so a long candidate shows its full text in the same horizontal scroller;
 *  * [CandidateCollapsedLayout.scrolls] is true only when the total width really exceeds the viewport;
 *  * a cell has a finite guard rail (a multiple of the available width) so extreme text cannot
 *    produce pathological frames.
 */
object CandidateCollapsedFlow {
    /** Minimum touch width of a cell (same as expanded cells). */
    const val MINIMUM_CELL_WIDTH: Double = 44.0

    /** Upper bound of one cell in even-width fill, so a single candidate does not fill the screen. */
    const val MAXIMUM_EVEN_CELL_WIDTH: Double = 120.0

    /** Per-cell guard rail: a cell may exceed the viewport but not by more than this factor. */
    const val MAXIMUM_CELL_WIDTH_FACTOR: Double = 4.0

    /** Base layout box height of a collapsed cell. */
    const val MINIMUM_BLOCK_HEIGHT: Double = 36.0

    /** Slack so a font rounding does not truncate with an ellipsis when the estimate equals the width. */
    private const val TEXT_SLACK: Double = 4.0

    /** Floating-point tolerance used by the total-width comparison. */
    private const val FIT_EPSILON: Double = 0.001

    /**
     * Collapsed cell width estimated only from the candidate text and selection badge; comments stay
     * in the expanded area and in the accessibility value.
     */
    fun intrinsicCellWidth(
        text: String,
        comment: String,
        selectionBadge: String?,
        typography: CandidateTypography,
        cellPaddingX: Double,
    ): Double {
        val textWidth = CandidateTextMetrics.estimatedWidth(text, typography.candidateSize)
        val badgeWidth =
            selectionBadge?.let {
                CandidateTextMetrics.estimatedWidth(it, typography.secondarySize) + 4.0
            } ?: 0.0
        val pad = if (cellPaddingX.isFinite()) max(0.0, cellPaddingX) else 0.0
        return max(MINIMUM_CELL_WIDTH, textWidth + badgeWidth + pad * 2 + TEXT_SLACK)
    }

    /** Automatic layout box height: never above the budget the fixed header surface grants. */
    fun fittedBlockHeight(
        blockHeight: Double,
        budget: Double,
    ): Double {
        if (!blockHeight.isFinite() || !budget.isFinite() || blockHeight <= 0.0 || budget <= 0.0) {
            return blockHeight
        }
        return min(blockHeight, budget)
    }

    /** Effective lift: non-negative, finite, and never more than the slack above the centred position. */
    fun clampedLift(
        lift: Double,
        blockHeight: Double,
        budget: Double,
    ): Double {
        if (!lift.isFinite() || lift <= 0.0 || !blockHeight.isFinite() || !budget.isFinite()) return 0.0
        return min(lift, max(0.0, (budget - blockHeight) / 2))
    }

    /** Estimated height of a collapsed cell: text line + underline + optional comment line. */
    fun estimatedBlockHeight(
        typography: CandidateTypography,
        hasComment: Boolean,
    ): Double {
        val text = if (typography.candidateSize.isFinite()) max(0.0, typography.candidateSize) * 1.25 else 0.0
        val underline = 2.0
        val spacing = 1.0
        val comment =
            if (hasComment && typography.secondarySize.isFinite()) {
                max(0.0, typography.secondarySize) * 1.25
            } else {
                0.0
            }
        return text + underline + spacing + comment
    }

    /**
     * Per-cell finite guard rail: available width x factor, never below the minimum touch width.
     * An unconstrained width (the layout's sentinel) stays unbounded: clamping it to a finite value
     * would cap every cell at that value and truncate long candidates.
     */
    fun maximumCellWidth(availableWidth: Double): Double {
        if (!availableWidth.isFinite()) return Double.MAX_VALUE
        val base = max(0.0, availableWidth)
        // Multiplying the near-MAX_VALUE sentinel by the factor overflows to infinity; a huge finite
        // width must stay unbounded instead of falling back to the minimum touch width.
        if (base >= Double.MAX_VALUE / MAXIMUM_CELL_WIDTH_FACTOR) return Double.MAX_VALUE
        return max(MINIMUM_CELL_WIDTH, base * MAXIMUM_CELL_WIDTH_FACTOR)
    }

    /**
     * Visible viewport width while scrolling: shows only the cells that fit completely from the first
     * one, so the last cell is not cut in half at the edge.
     */
    fun wholeCellViewportWidth(
        cellWidths: List<Double>,
        spacing: Double,
        availableWidth: Double,
    ): Double {
        val available = if (availableWidth.isFinite()) max(0.0, availableWidth) else 0.0
        val gap = if (spacing.isFinite()) max(0.0, spacing) else 0.0
        var used = 0.0
        cellWidths.forEachIndexed { index, width ->
            val next = used + (if (index == 0) 0.0 else gap) + width
            if (next > available + FIT_EPSILON) return if (index == 0) available else used
            used = next
        }
        return available
    }

    /**
     * Collapsed strip layout: even-width fill (no scrolling) or intrinsic-width layout (scrolling when
     * wider). Even-width fill is decided on **total width**, so per-cell tolerance cannot accumulate
     * overflow.
     */
    fun layout(
        entries: List<BrowserCandidate>,
        collapsed: CandidateBarDefinition.Collapsed,
        typography: CandidateTypography,
        availableWidth: Double,
        fillsAvailableWidth: Boolean? = null,
    ): CandidateCollapsedLayout {
        if (entries.isEmpty()) return CandidateCollapsedLayout(emptyList(), scrolls = false)
        val spacing = if (collapsed.cellSpacing.isFinite()) max(0.0, collapsed.cellSpacing) else 0.0
        // A non-finite available width means "unconstrained"; zero or negative widths are a finite 0.
        val available =
            if (availableWidth.isFinite()) max(0.0, availableWidth) else Double.MAX_VALUE
        val intrinsic =
            entries.map {
                intrinsicCellWidth(
                    text = it.text,
                    comment = it.comment,
                    selectionBadge = it.selectionBadge,
                    typography = typography,
                    cellPaddingX = collapsed.cellPaddingX,
                )
            }
        val count = entries.size
        val widest = intrinsic.maxOrNull() ?: MINIMUM_CELL_WIDTH
        val evenTotal = widest * count + spacing * (count - 1)

        val fill = fillsAvailableWidth ?: collapsed.fillsAvailableWidth ?: true
        if (fill && evenTotal.isFinite() && evenTotal <= available + FIT_EPSILON) {
            val uniform = (available - spacing * (count - 1)) / count
            val even = max(widest, min(uniform, MAXIMUM_EVEN_CELL_WIDTH))
            return CandidateCollapsedLayout(List(count) { even }, scrolls = false)
        }

        val cap = maximumCellWidth(availableWidth = available)
        val widths = intrinsic.map { if (it.isFinite()) max(MINIMUM_CELL_WIDTH, min(it, cap)) else cap }
        val total = widths.sum() + spacing * (count - 1)
        return CandidateCollapsedLayout(widths, scrolls = total > available + FIT_EPSILON)
    }
}

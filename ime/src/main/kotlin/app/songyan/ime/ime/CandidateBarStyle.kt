package app.songyan.ime.ime

import app.songyan.ime.candidate.CandidateBarDefinition
import app.songyan.ime.candidate.CandidateBarDefinition.Expanded.Mode
import app.songyan.ime.ime.palette.KeyboardAppearanceRegistry

/**
 * The parts of an appearance's candidate area the keyboard honours.
 *
 * Built-in appearances carry the iOS default candidate area, whose numbers (a 40 pt row, 7 pt
 * padding, no spacing) are not Android's design tokens. A value equal to that default therefore means
 * "not customised" and keeps the token; only a value the user changed (or an imported file carries)
 * overrides it. The collapsed strip always lays candidates out at their own width with an expand
 * control at its end, so the definition's even-split flag, page-button position and expand switch
 * are kept in the file for iOS but not read here.
 */
data class CandidateBarStyle(
    /** Overrides the single-row height in dp, or null for the token. */
    val rowHeightDp: Float? = null,
    val chipPaddingDp: Float? = null,
    /** Overrides the space between two strip cells in dp, or null for the token. */
    val cellSpacingDp: Float? = null,
    val expandedMode: Mode = Mode.ADAPTIVE,
    /** Grid column count when [expandedMode] is [Mode.GRID]. */
    val columns: Int = DEFAULT_COLUMNS,
    val rowGapDp: Float = 0f,
    val columnGapDp: Float = 0f,
) {
    /** [tokens] with the single-row overrides applied. */
    fun applyTo(tokens: KeyboardDesignTokens): KeyboardDesignTokens {
        // The same instance when nothing is customised: callers rely on identity to skip copies.
        if (rowHeightDp == null && chipPaddingDp == null && cellSpacingDp == null) return tokens
        return tokens.copy(
            candidateBarHeightDp = rowHeightDp ?: tokens.candidateBarHeightDp,
            candidateChipPaddingDp = chipPaddingDp ?: tokens.candidateChipPaddingDp,
            candidateCellSpacingDp = cellSpacingDp ?: tokens.candidateCellSpacingDp,
        )
    }

    companion object {
        const val DEFAULT_COLUMNS = 2

        /** Android's row height for the iOS default, which is a 40 pt row: 8 dp more, a 48 dp touch target. */
        const val HEIGHT_OFFSET_DP = 8f

        /** Android's cell padding for the iOS default 7 pt: the 10 dp token, so a changed value keeps that step. */
        const val PADDING_OFFSET_DP = 3f

        /** The touch-target minimum a row never goes below. */
        const val MIN_ROW_DP = 48f

        /** The style of the installed appearance; the platform look until the app graph installs one. */
        fun current(): CandidateBarStyle = of(KeyboardAppearanceRegistry.current()?.document?.candidateBar)

        fun of(bar: CandidateBarDefinition?): CandidateBarStyle {
            if (bar == null) return CandidateBarStyle()
            val base = CandidateBarDefinition.systemDefault
            val collapsed = bar.collapsed
            val expanded = bar.expanded
            return CandidateBarStyle(
                rowHeightDp =
                    if (collapsed.height == base.collapsed.height) {
                        null
                    } else {
                        maxOf(
                            MIN_ROW_DP,
                            CandidateBarDefinition.Collapsed.clampedHeight(collapsed.height).toFloat() +
                                HEIGHT_OFFSET_DP,
                        )
                    },
                chipPaddingDp =
                    collapsed.cellPaddingX
                        .takeIf { it != base.collapsed.cellPaddingX }
                        ?.let { it.toFloat() + PADDING_OFFSET_DP },
                cellSpacingDp = collapsed.cellSpacing.takeIf { it != base.collapsed.cellSpacing }?.toFloat(),
                expandedMode = expanded.mode,
                columns = bar.expandedEffectiveColumns,
                rowGapDp = bar.expandedRowSpacing.toFloat(),
                columnGapDp = bar.expandedColumnSpacing.toFloat(),
            )
        }
    }
}

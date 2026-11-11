package app.songyan.ime.ime

import app.songyan.ime.candidate.CandidateBarDefinition
import app.songyan.ime.candidate.CandidateBarDefinition.Expanded.Mode
import app.songyan.ime.ime.palette.KeyboardAppearanceRegistry

/**
 * The parts of an appearance's candidate area the keyboard honours, in the file's own numbers:
 * 1 pt is 1 dp. The collapsed strip always lays candidates out at their own width with an expand
 * control at its end, so the definition's even-split flag, page-button position and expand switch
 * are kept in the file for iOS but not read here.
 */
data class CandidateBarStyle(
    /** The single-row height in dp, or null for the token. */
    val rowHeightDp: Float? = null,
    val chipPaddingDp: Float? = null,
    /** The space between two strip cells in dp, or null for the token. */
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
        private const val DEFAULT_CANDIDATE_SIZE = 17.0

        /** The style of the installed appearance; the platform look until the app graph installs one. */
        fun current(): CandidateBarStyle {
            val document = KeyboardAppearanceRegistry.current()?.document
            return of(document?.candidateBar, document?.skin?.typography?.candidateSize)
        }

        /**
         * The row height follows the iOS rule: the file's height clamped to 36..56 dp, topped up to
         * the candidate text's line height (1.25 times [candidateSize] plus 3 dp), never below 36 dp.
         */
        fun of(
            bar: CandidateBarDefinition?,
            candidateSize: Double? = null,
        ): CandidateBarStyle {
            if (bar == null) return CandidateBarStyle()
            val collapsed = bar.collapsed
            val expanded = bar.expanded
            return CandidateBarStyle(
                rowHeightDp =
                    CandidateBarDefinition.Collapsed
                        .effectiveHeight(collapsed.height, candidateSize ?: DEFAULT_CANDIDATE_SIZE)
                        .toFloat(),
                chipPaddingDp = collapsed.cellPaddingX.toFloat(),
                cellSpacingDp = collapsed.cellSpacing.toFloat(),
                expandedMode = expanded.mode,
                columns = bar.expandedEffectiveColumns,
                rowGapDp = bar.expandedRowSpacing.toFloat(),
                columnGapDp = bar.expandedColumnSpacing.toFloat(),
            )
        }
    }
}

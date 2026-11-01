package app.songyan.ime.candidate

/**
 * Eligibility and dimensions of the standalone reading row, declared by the physical layout's
 * capability.
 *
 * Readings and disambiguation must not overlap word candidates. Candidates and readings use only real
 * engine content. Height ownership: the panel reports eligibility and the host grants the row height;
 * the cold-start hint reuses only a previously accepted value for the same layout selection.
 */
object CandidateReadingRowPolicy {
    /** Reading row height: one full tappable horizontal cell, not just the glyphs. */
    const val HEIGHT: Double = 32.0

    /** Reading font size: one step below the candidate words so it stays readable and easy to hit. */
    const val FONT_SIZE: Double = 18.0

    /** Minimum width of each reading cell (the full row height is already the hit band). */
    const val MINIMUM_CELL_WIDTH: Double = 56.0

    /** Horizontal padding of a reading cell: hit boundaries between readings do not hug the glyphs. */
    const val CELL_PADDING_X: Double = 12.0

    /** The reading text sits this far below the row centre so it is not mistaken for a comment. */
    const val TEXT_DROP: Double = 4.0

    /** The shortest a reading row gets when the shell presents less height than the layout asked for. */
    const val MINIMUM_HEIGHT: Double = 24.0

    /** In compact height the row and its font each shrink one step to leave height for key rows. */
    const val COMPACT_HEIGHT: Double = 28.0
    const val COMPACT_FONT_SIZE: Double = 16.0

    /** The text's offset below the row's top: [TEXT_DROP] below centre, shrinking with a squeezed row. */
    fun textTopPadding(rowHeight: Double): Double = minOf(TEXT_DROP * 2, maxOf(0.0, rowHeight - MINIMUM_HEIGHT))

    /** How far word-candidate text sits below its row centre when a reading row is present. */
    const val CANDIDATE_DROP: Double =
        (KeyboardPanelGeometry.READING_CANDIDATE_SPACE - KeyboardPanelGeometry.CANDIDATE_BOTTOM_SPACE) / 2

    /** How far word-candidate text sits below its row centre when there is no reading row. */
    const val PLAIN_CANDIDATE_DROP: Double =
        (KeyboardPanelGeometry.CANDIDATE_TOP_SPACE - KeyboardPanelGeometry.CANDIDATE_BOTTOM_SPACE) / 2

    /** Host granted or not -> budget height in dp. */
    fun height(
        reserved: Boolean,
        compact: Boolean = false,
    ): Double =
        when {
            !reserved -> 0.0
            compact -> COMPACT_HEIGHT
            else -> HEIGHT
        }
}

/** Eligibility report from the panel to the host: a fact, not a height. */
data class CandidateReadingRowReport(
    /** Whether this layout reserves a word candidate area, independent of input state. */
    val candidateAreaRequired: Boolean = true,
    /** Whether the currently resolved physical layout needs a standalone reading row. */
    val eligible: Boolean,
    /** Resolved layout id, for diagnostics; eligibility itself comes from the content. */
    val definitionID: String? = null,
    /** Whether the published layout registry was available when the value was resolved. */
    val resolvedFromRegistry: Boolean = false,
    /** The grant describes the current ABC/123 page, not the layout itself. */
    val pageOverride: Boolean = false,
)

/**
 * Whether a report is accepted.
 *
 * While the layout registry is still loading, the panel falls back to the built-in layout of the
 * same kind. That is only a guess, because a DIY layout's physical structure can differ: an
 * unsettled source is not accepted, and a settled one accepts only reports resolved from the same
 * source.
 */
object CandidateReadingRowGrant {
    fun accepts(
        report: CandidateReadingRowReport,
        layoutSourceSettled: Boolean,
        registryAvailable: Boolean,
    ): Boolean = layoutSourceSettled && report.resolvedFromRegistry == registryAvailable
}

/**
 * Cold-start hint: the eligibility the host last **accepted**, recorded under the layout selection
 * at that time. It applies only while the recorded selection is unchanged; otherwise the first
 * frame starts ungranted and the host corrects it once the session settles.
 */
data class CandidateReadingRowHint(
    /** Layout selection at record time; an unknown selection is recorded as an empty string. */
    val selection: String,
    val eligible: Boolean,
    val candidateAreaRequired: Boolean = true,
) {
    /** Cold-start grant for the current selection: reused only when the selection matches. */
    fun resolve(currentSelection: String?): Boolean = eligible && selection == (currentSelection ?: "")

    /** An unknown or changed layout keeps the candidate area until its definition is resolved. */
    fun resolveCandidateArea(currentSelection: String?): Boolean =
        if (selection == (currentSelection ?: "")) candidateAreaRequired else true

    companion object {
        /** Selection context readable synchronously at cold start -> hint key. */
        fun selectionKey(
            layoutKind: String?,
            definitionID: String?,
        ): String = (layoutKind ?: "") + "|" + (definitionID ?: "")

        fun resolve(
            hint: CandidateReadingRowHint?,
            currentSelection: String?,
        ): Boolean = hint?.resolve(currentSelection) ?: false

        fun resolveCandidateArea(
            hint: CandidateReadingRowHint?,
            currentSelection: String?,
        ): Boolean = hint?.resolveCandidateArea(currentSelection) ?: true
    }
}

/** What the reading row draws in the current state: a pure decision shared by production and tests. */
sealed interface CandidateReadingRowContent {
    /** Transparent empty band: no placeholder title and no explanatory text. */
    data object Empty : CandidateReadingRowContent

    /** Lockable spellings reported by the engine, never invented by the front end. */
    data class Options(
        val options: List<String>,
    ) : CandidateReadingRowContent

    /** Locked: shows the spelling actually locked and the undo entry. */
    data class Locked(
        val chosen: String,
    ) : CandidateReadingRowContent

    /**
     * Multi-syllable composition: next-syllable options among the engine readings of loaded real
     * candidates. [selected] holds the already chosen prefix syllables; filtering is read-only.
     */
    data class Readings(
        val selected: List<String>,
        val options: List<String>,
    ) : CandidateReadingRowContent
}

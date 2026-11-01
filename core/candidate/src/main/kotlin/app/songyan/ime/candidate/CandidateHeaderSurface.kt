package app.songyan.ime.candidate

import app.songyan.ime.keyboard.layout.KeyboardLayoutKind

/** Layout families the candidate header must distinguish (9 / 14 / 18 / 26). */
enum class CandidateHeaderLayout {
    QWERTY26,
    PHONE14,
    PHONE18,
    PHONE9, ;

    /** The 26-key layout never offers a composition disambiguation entry. */
    val offersAmbiguityPicker: Boolean get() = this != QWERTY26

    /** Nine-key uses the phone keyboard shell (left reading rail / right editing rail). */
    val usesPhoneShell: Boolean get() = this == PHONE9

    companion object {
        fun of(layout: KeyboardLayoutKind): CandidateHeaderLayout =
            when (layout) {
                KeyboardLayoutKind.QWERTY26 -> QWERTY26
                KeyboardLayoutKind.MERGED14 -> PHONE14
                KeyboardLayoutKind.MERGED18 -> PHONE18
                KeyboardLayoutKind.PHONE9 -> PHONE9
            }
    }
}

/** The content kind the fixed header presents for one "layout x state". */
enum class CandidateHeaderContent {
    /** No candidates, no status: a bare surface. */
    IDLE,

    /** The engine still holds a composition but there are no candidates: hint to edit, never fabricate words. */
    COMPOSITION_WITHOUT_CANDIDATES,

    /** Real candidates in a horizontal strip (collapsed). */
    CANDIDATES,

    /** Expanded candidate browsing; the header keeps the same fixed budget. */
    CANDIDATES_EXPANDED,

    /** No candidates but a status hint fills the same budget. */
    STATUS,
}

/** The layers actually drawn inside the header; their z order is real, not declared. */
enum class CandidateHeaderLayer {
    BACKGROUND,
    CONTENT,
}

/** The single top-bar policy: geometry, z order and content kind. */
object CandidateHeaderSurfacePolicy {
    /** Normal candidates are never drawn with blur, material or translucency. */
    const val FORBIDS_BLUR_MATERIAL_OPACITY_FOR_NORMAL_CANDIDATES: Boolean = true

    /** Real z order: the background layer is always below the content layer. */
    fun zIndex(layer: CandidateHeaderLayer): Double =
        when (layer) {
            CandidateHeaderLayer.BACKGROUND -> 0.0
            CandidateHeaderLayer.CONTENT -> 1.0
        }

    /**
     * When the layout needs a candidate area the header always fills the configured budget: how much
     * content there is never changes the total keyboard height.
     */
    fun surfaceHeight(
        configured: Double,
        candidateSize: Double = 17.0,
    ): Double = CandidateBarDefinition.Collapsed.effectiveHeight(configured, candidateSize)

    /**
     * Layout x state -> content kind. [hasCandidates] gates candidate content; with no candidates but
     * still composing, a real status hint is shown so leftover raw code does not look fully idle.
     */
    fun content(
        layout: CandidateHeaderLayout,
        isEnglish: Boolean,
        isComposing: Boolean,
        hasCandidates: Boolean,
        hasStatus: Boolean,
        isExpanded: Boolean,
    ): CandidateHeaderContent =
        when {
            hasCandidates ->
                if (isExpanded) {
                    CandidateHeaderContent.CANDIDATES_EXPANDED
                } else {
                    CandidateHeaderContent.CANDIDATES
                }
            hasStatus -> CandidateHeaderContent.STATUS
            isEnglish -> CandidateHeaderContent.IDLE
            isComposing -> CandidateHeaderContent.COMPOSITION_WITHOUT_CANDIDATES
            else -> CandidateHeaderContent.IDLE
        }
}

/** Input facts of header visibility, all from real controller and host state. */
data class CandidateHeaderVisibilityInput(
    val isEnglish: Boolean,
    val hasCandidates: Boolean,
    val hasStatus: Boolean,
    val hasAmbiguityPicker: Boolean,
    /** In-keyboard preview of the whole composition when the host's marked text is unavailable. */
    val hasCompositionFallback: Boolean,
    /** Reading filter in progress (the header shows the filter row). */
    val isReadingFiltering: Boolean,
)

/**
 * Whether the header carries **real input content**.
 *
 * This is not a decision about whether the bar takes height: the band keeps the same fixed budget in
 * every state, so the key area never jumps. The policy only decides *what* is drawn inside it.
 */
object CandidateHeaderVisibility {
    fun showsHeader(input: CandidateHeaderVisibilityInput): Boolean =
        input.hasCandidates ||
            input.hasStatus ||
            input.hasAmbiguityPicker ||
            input.hasCompositionFallback ||
            input.isReadingFiltering
}

/** Visible long-press menu indicator drawn on the language key. */
object KeyboardToolsAffordance {
    /** A real drawn corner mark, not documentation. */
    const val INDICATOR_GLYPH: String = "•••"

    /** Lower bound of the hit budget (same as core keys). */
    const val MINIMUM_HIT_TARGET: Double = 44.0
}

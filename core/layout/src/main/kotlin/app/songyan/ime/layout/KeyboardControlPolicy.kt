package app.songyan.ime.layout

/**
 * Builtin presentation policy of the bottom control row: which slot holds which key and how wide
 * each key is. DIY rows/actions are deliberately outside this policy, and page changes never own or
 * mutate the engine's remembered Chinese/English mode.
 *
 * This type carries no user-visible text: titles and accessibility labels are data the host resolves
 * from its string resources (see [LanguageAction] and [Utility] for the semantics to label).
 */
data class KeyboardControlPolicy(
    val page: Page,
    val isEnglishMode: Boolean,
    val hasGlobe: Boolean,
    val isPhoneLayout: Boolean,
    val usesAsciiPunctuation: Boolean,
    /** Bundled T9 moves Return into its large trailing rail. Presentation only; the action stays enter. */
    val includesBottomEnter: Boolean = true,
    /** The #+= page is the categorized grid (with lock) rather than rows shaped like the 123 page. */
    val usesCategorySymbolPage: Boolean = true,
    /**
     * The 123 page this letter page opens carries its own switch to the symbol page (the row page's
     * third-row switch, or an authored page's symbol switch). Only then does the letter page's
     * utility position type punctuation instead of opening the symbol page.
     */
    val numericPageReachesSymbols: Boolean = true,
    /** The bundled phone shell's column widths, or null for the generic control-row budget. */
    val phoneColumns: PhoneColumns? = null,
) {
    enum class Page { LETTERS, NUMERIC, SYMBOLS }

    enum class Slot {
        PAGE,
        LANGUAGE,
        GLOBE,
        SPACE,
        UTILITY,
        ENTER,

        /**
         * Keys, never positions: the direct symbol key of a letter page whose 123 page cannot reach
         * the symbol page, or the lock toggle.
         */
        SYMBOL_PAGE,
        SYMBOL_LOCK,

        /**
         * A semantic key, never a physical slot: the row-shaped #+= page's left switch returns to
         * the letters keyboard, preserving the input language, instead of toggling the language.
         */
        RETURN_TO_LETTERS,
    }

    enum class LanguageAction {
        TOGGLE_LANGUAGE,

        /** Switch only the panel's symbol language and keep the current page. */
        TOGGLE_SYMBOLS,

        /** Return to the main letter keyboard without changing the Chinese/English input state. */
        RETURN_TO_LETTERS,
    }

    sealed class Utility {
        data class Punctuation(
            val text: String,
        ) : Utility()

        /** 9-key digit page: the 3x3 holds only 1-9, and 0 goes in the bottom-right slot. */
        data class Digit(
            val digit: String,
        ) : Utility()
    }

    /** The bundled phone shell's column widths. */
    data class PhoneColumns(
        val rail: Double,
        val digit: Double,
    )

    /** Column alignment, or realigning to a different shell without rebuilding the policy. */
    fun alignedTo(columns: PhoneColumns?): KeyboardControlPolicy = copy(phoneColumns = columns)

    /**
     * Slot identities and count stay unchanged across all pages and languages for one surface. Only
     * an actual system-keyboard availability change may add/remove globe, and only the bundled T9
     * (which moves Return into its trailing column) may drop enter. The utility position types the
     * comma on letter and row-shaped 123 / #+= pages (or opens symbols where 123 cannot), types 0 on
     * the phone digit pad and locks the categorized symbol page.
     */
    val slots: List<Slot>
        get() =
            if (isDigitPad) {
                listOf(Slot.PAGE, Slot.GLOBE, Slot.LANGUAGE, Slot.SPACE, Slot.SYMBOL_PAGE, Slot.ENTER)
                    .filter { it != Slot.GLOBE || hasGlobe }
            } else {
                listOf(Slot.PAGE, Slot.GLOBE, Slot.LANGUAGE, Slot.SPACE, Slot.UTILITY, Slot.ENTER)
                    .filter { (it != Slot.GLOBE || hasGlobe) && (it != Slot.ENTER || includesBottomEnter) }
            }

    /**
     * The phone digit pad's row is a grid row, not a control row: 返回 under the rail, then 中/英,
     * the space bar and 符号 under the three digit columns and Return under the trailing column,
     * which holds Delete, 0 and the comma above it. The globe, when shown, shares the first digit
     * column with 中/英, so 返回 keeps its whole cell.
     */
    val isDigitPad: Boolean get() = isBundledPhonePad && page == Page.NUMERIC

    /** The row-shaped 123 / #+= page: its third row opens with the 123/symbol switch. */
    val isRowPage: Boolean
        get() = page != Page.LETTERS && !isPhoneLayout && !(page == Page.SYMBOLS && usesCategorySymbolPage)

    private val lettersTypePunctuation: Boolean get() = !isPhoneLayout && numericPageReachesSymbols

    /**
     * Slots are positions (width and view identity stay put); on 123 / #+= the return-to-letters key
     * takes the page position, while the globe keeps the leading position it has on every page.
     *
     * The comma sits in the utility slot on the letter page and on the row-shaped 123 / #+= pages
     * alike (long press for the other sentence marks), so it never moves when the page flips. A phone
     * keypad's letter page, or one paired with a 123 page that has no symbol switch, opens the symbol
     * page from there instead so it stays reachable. The phone digit pad keeps 0 there.
     */
    fun keyAt(slot: Slot): Slot =
        when (page) {
            Page.LETTERS ->
                if (slot != Slot.UTILITY) {
                    slot
                } else if (lettersTypePunctuation) {
                    Slot.UTILITY
                } else {
                    Slot.SYMBOL_PAGE
                }
            Page.NUMERIC ->
                when (slot) {
                    Slot.PAGE -> Slot.RETURN_TO_LETTERS
                    Slot.UTILITY -> if (isRowPage || isBundledPhonePad) Slot.UTILITY else Slot.PAGE
                    else -> slot
                }
            Page.SYMBOLS -> {
                if (usesCategorySymbolPage) {
                    when (slot) {
                        Slot.PAGE -> Slot.LANGUAGE
                        Slot.LANGUAGE -> Slot.PAGE
                        Slot.UTILITY -> Slot.SYMBOL_LOCK
                        else -> slot
                    }
                } else {
                    if (slot == Slot.PAGE) Slot.RETURN_TO_LETTERS else slot
                }
            }
        }

    /** The phone-pad row: the bundled 9-key and every categorized symbol page move Return into a column. */
    val isBundledPhonePad: Boolean get() = isPhoneLayout && !includesBottomEnter

    val pageTarget: Page get() = if (page == Page.NUMERIC) Page.SYMBOLS else Page.NUMERIC

    /**
     * The page-following default: letters toggle the input language; 123 (the 9-key digit pad
     * included) and the row-shaped #+= page switch the symbol language in place; the categorized
     * symbol grid returns to the main letter keyboard.
     */
    val languageAction: LanguageAction get() = languageAction(null)

    /** What the language key does. Null follows the page; an authored behavior overrides that rule. */
    fun languageAction(behavior: LayoutLanguageSwitchBehavior?): LanguageAction =
        when (behavior) {
            LayoutLanguageSwitchBehavior.SYMBOLS -> LanguageAction.TOGGLE_SYMBOLS
            LayoutLanguageSwitchBehavior.LETTERS -> LanguageAction.RETURN_TO_LETTERS
            null ->
                when (page) {
                    Page.LETTERS -> LanguageAction.TOGGLE_LANGUAGE
                    Page.NUMERIC -> LanguageAction.TOGGLE_SYMBOLS
                    Page.SYMBOLS ->
                        if (usesCategorySymbolPage) {
                            LanguageAction.RETURN_TO_LETTERS
                        } else {
                            LanguageAction.TOGGLE_SYMBOLS
                        }
                }
        }

    /** The utility position's content; a letter page without a symbol switch resolves it through [keyAt]. */
    val utility: Utility
        get() {
            if (isPhoneLayout && page == Page.NUMERIC) return Utility.Digit("0")
            // Only the effective symbol branch decides the mark: an English input language with an
            // explicit Chinese symbol state must keep Chinese punctuation.
            return Utility.Punctuation(if (usesAsciiPunctuation) "," else "，")
        }

    /** The punctuation slot's long-press list, its first entry being the corner hint. */
    val utilityAlternates: List<String>
        get() {
            val text = (utility as? Utility.Punctuation)?.text ?: return emptyList()
            return if (text == ",") {
                listOf(".", "?", "!", "'", "\"")
            } else {
                listOf("。", "？", "！", "、", "…")
            }
        }

    /**
     * One budget for every builtin page. Fractions apply to content width after gaps: at 382 dp the
     * two switches keep the 44 dp target and the width they no longer take goes to space, the
     * most-hit key. Return never falls below 60 dp on a supported 320 dp keyboard; extremely small
     * previews scale as a whole.
     */
    fun metrics(width: Double): Metrics {
        val columns = phoneColumns
        if (isDigitPad) return Metrics.forDigitPad(width, columns?.rail, hasGlobe)
        if (columns != null && !hasGlobe && !includesBottomEnter) {
            return Metrics.forPhoneColumns(width, columns, includesUtility = true)
        }
        return Metrics.forSlots(
            width = width,
            hasGlobe = hasGlobe,
            includesEnter = includesBottomEnter,
            includesUtility = true,
        )
    }

    /** Per-slot widths in dp for one control row. */
    data class Metrics(
        val spacing: Double,
        val page: Double,
        val language: Double,
        val globe: Double,
        val space: Double,
        val utility: Double,
        val enter: Double,
        /** The symbol page key; as wide as the page switches except on the digit pad. */
        val symbolPage: Double = page,
    ) {
        operator fun get(slot: Slot): Double =
            when (slot) {
                Slot.SYMBOL_PAGE -> symbolPage
                Slot.PAGE, Slot.RETURN_TO_LETTERS -> page
                Slot.LANGUAGE -> language
                Slot.GLOBE -> globe
                Slot.SPACE -> space
                Slot.UTILITY, Slot.SYMBOL_LOCK -> utility
                Slot.ENTER -> enter
            }

        companion object {
            /** The generic budget: 44 dp switches, a wide space bar and a protected Return. */
            fun forSlots(
                width: Double,
                hasGlobe: Boolean,
                includesEnter: Boolean = true,
                includesUtility: Boolean = true,
            ): Metrics {
                val safeWidth = if (width.isFinite()) maxOf(0.0, width) else 0.0
                val count = (if (hasGlobe) 4 else 3) + (if (includesUtility) 1 else 0) + (if (includesEnter) 1 else 0)
                val minimumContent =
                    (if (hasGlobe) 188.0 else 144.0) +
                        (if (includesUtility) 44.0 else 0.0) +
                        (if (includesEnter) 60.0 else 0.0)
                val minimumTotal = minimumContent + (count - 1) * SPACING
                val scale = minOf(1.0, safeWidth / minimumTotal)
                val spacing = SPACING * scale
                val content = maxOf(0.0, safeWidth - (count - 1) * spacing)
                val extra = maxOf(0.0, content - minimumContent * scale)
                val pageColumn: Double
                val languageColumn: Double
                if (hasGlobe) {
                    // The globe already takes a slot; keeping both switches at the 44 dp target hands
                    // their old base plus growth to space instead of widening the switches.
                    pageColumn = 44 * scale
                    languageColumn = pageColumn
                } else {
                    // The base budget is 50 + 50 + [44] + 44 + 44 + 60.
                    pageColumn = 50 * scale + minOf(12.0, extra * 0.10)
                    languageColumn = pageColumn
                }
                val globe = if (hasGlobe) 44 * scale else 0.0
                val utility = if (includesUtility) 44 * scale else 0.0
                val enter = if (includesEnter) 60 * scale + minOf(20.0, extra * 0.16) else 0.0
                val space = maxOf(0.0, content - pageColumn - languageColumn - globe - utility - enter)
                return Metrics(spacing, pageColumn, languageColumn, globe, space, utility, enter)
            }

            /**
             * The digit pad row: one cell under the rail ([rail] wide, or a fifth of the row without
             * the shell's columns), three equal cells under the digit columns and one under the
             * trailing column, as wide as the rail. With the globe, 返回 keeps the first cell and the
             * globe and 中/英 split the second.
             */
            fun forDigitPad(
                width: Double,
                rail: Double?,
                hasGlobe: Boolean,
            ): Metrics {
                val safeWidth = if (width.isFinite()) maxOf(0.0, width) else 0.0
                val side = minOf(safeWidth / 2, maxOf(0.0, rail ?: ((safeWidth - 4 * SPACING) / 5)))
                val cell = maxOf(0.0, (safeWidth - 2 * side - 4 * SPACING) / 3)
                val shared = maxOf(0.0, (cell - SPACING) / 2)
                return Metrics(
                    spacing = SPACING,
                    page = side,
                    language = if (hasGlobe) shared else cell,
                    globe = if (hasGlobe) shared else 0.0,
                    space = cell,
                    utility = 0.0,
                    enter = side,
                    symbolPage = cell,
                )
            }

            /**
             * Phone row: the first key sits under the rail; the keys under the 3x3 grid do not follow
             * its columns. The space bar takes most of that span (side keys : space about 1 : 2.4 : 1),
             * since it is the most-hit key of the row, and the side switches stay wide enough to hit.
             * Falls back to the generic budget when the span cannot fit.
             */
            fun forPhoneColumns(
                width: Double,
                columns: PhoneColumns,
                includesUtility: Boolean,
            ): Metrics {
                val safeWidth = if (width.isFinite()) maxOf(0.0, width) else 0.0
                val gaps = (if (includesUtility) 3 else 2) * SPACING
                val span = safeWidth - columns.rail - gaps
                val sideShare = if (includesUtility) 1 / 4.4 else 1 / 3.4
                val side = span * sideShare
                val sideCount = if (includesUtility) 2 else 1
                if (columns.rail < 0 || side < 44 || span - side * sideCount < 44) {
                    return forSlots(
                        width = safeWidth,
                        hasGlobe = false,
                        includesEnter = false,
                        includesUtility = includesUtility,
                    )
                }
                return Metrics(
                    spacing = SPACING,
                    page = columns.rail,
                    language = side,
                    globe = 0.0,
                    space = span - side * sideCount,
                    utility = if (includesUtility) side else 0.0,
                    enter = 0.0,
                )
            }
        }
    }

    companion object {
        /** Gap between control-row keys, in dp. */
        const val SPACING: Double = 4.0
    }
}

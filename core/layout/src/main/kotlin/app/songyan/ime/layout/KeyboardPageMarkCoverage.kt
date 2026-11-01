package app.songyan.ime.layout

/**
 * The built-in row 123 page carries the everyday marks, and the built-in symbol pages leave them out
 * on purpose. When a pairing draws another 123 page (a digit pad, a custom page), some of those
 * marks are on no page the pairing opens; this finds them so the symbol page can take them over.
 * The extra page holds marks only: the pairing's own 123 page is where its digits are, and a second
 * digit page behind the symbol key would compete with it.
 *
 * Only a key's tap counts as reaching a mark. Long-press menus show at most their first entry on the
 * key face and the rest only after a press, so a mark that lives only there is effectively hidden;
 * repeating it on the symbol page costs a key, while missing it costs the mark.
 */
object KeyboardPageMarkCoverage {
    /** How the pairing's symbol page takes over the missing marks. */
    enum class SymbolPageForm {
        /** The built-in row page: it opens on an extra first page holding them, in its own grid. */
        BUILT_IN_ROWS,

        /** The built-in category tabs: 常用 lists them first. */
        BUILT_IN_CATEGORIES,

        /** A page the user made: never changed; the pairing screen names what is missing instead. */
        CUSTOM,
    }

    /** [page] is the pairing's symbol page, null when it has none (the built-in page draws it). */
    fun symbolPageForm(
        page: KeyboardPageDefinition?,
        mainLayoutIsPhoneKeypad: Boolean,
    ): SymbolPageForm {
        if (page == null ||
            page.kind != KeyboardPageKind.SYMBOLS ||
            page.symbolPresentation == KeyboardSymbolPresentation.AUTOMATIC
        ) {
            // The automatic page is the category browser on a phone keypad, rows elsewhere.
            return if (mainLayoutIsPhoneKeypad) SymbolPageForm.BUILT_IN_CATEGORIES else SymbolPageForm.BUILT_IN_ROWS
        }
        // A categorized page without its own categories draws the built-in tabs.
        if (page.symbolPresentation == KeyboardSymbolPresentation.CATEGORIZED && page.categories == null) {
            return SymbolPageForm.BUILT_IN_CATEGORIES
        }
        return SymbolPageForm.CUSTOM
    }

    /** The everyday marks in the 123 page's mark rows (the digit row left out). */
    fun everydayMarkRows(ascii: Boolean): List<List<String>> = BuiltinLayouts.numericPageRows(ascii).drop(1)

    /**
     * The everyday marks of the [ascii] branch that no page of the pairing types with a tap, in the
     * 123 page's order. [numeric] and [symbols] are the pages the pairing draws, null for the built-in
     * ones; [letters] is the main layout.
     *
     * The built-in category tabs are left out of the count: every mark is somewhere in them, and the
     * point is to bring the missing ones to the front of 常用.
     */
    fun missingMarks(
        letters: LayoutDefinition?,
        numeric: KeyboardPageDefinition?,
        symbols: KeyboardPageDefinition?,
        mainLayoutIsPhoneKeypad: Boolean,
        ascii: Boolean,
    ): List<String> {
        // The built-in 123 page types every everyday mark in both shapes: the row page holds them and
        // the phone digit pad's rail lists them all.
        if (numeric == null ||
            numeric.kind != KeyboardPageKind.NUMERIC ||
            numeric.numericPresentation == KeyboardNumericPresentation.AUTOMATIC
        ) {
            return emptyList()
        }
        val reached = HashSet<String>()

        fun reach(keys: List<LayoutKey>) {
            for (key in keys) key.action.literalValue(ascii)?.let(reached::add)
        }
        if (letters != null) {
            reach(letters.rows.flatMap { it.keys } + letters.auxiliaryAreas.orEmpty().flatMap { it.keys })
        }
        reach(numeric.rows.flatMap { it.keys } + numeric.chromeKeys)
        when (symbolPageForm(symbols, mainLayoutIsPhoneKeypad)) {
            SymbolPageForm.BUILT_IN_ROWS -> reached.addAll(BuiltinLayouts.symbolPageRows(ascii).flatten())
            SymbolPageForm.BUILT_IN_CATEGORIES -> Unit
            SymbolPageForm.CUSTOM ->
                if (symbols != null) {
                    // A recent tab's keys are a backup of fixed content; what it shows changes.
                    val categories =
                        symbols.categories.orEmpty().filter {
                            it.source !=
                                KeyboardPageCategory.Source.RECENT
                        }
                    reach(symbols.rows.flatMap { it.keys } + categories.flatMap { it.keys } + symbols.chromeKeys)
                }
        }
        return everydayMarkRows(ascii).flatten().filter { it !in reached }
    }

    /**
     * The marks of the row symbol page's extra first page, in the symbol page's own rows (ten, ten
     * and the seven between the page switch and Delete), so it has that page's key widths and no
     * row stands empty. The missing marks come first in the 123 page's order; the slots they leave
     * take the symbol page's own marks from its start, which are the ones the next page opens with,
     * so a mark appears at most once here and never a digit. Empty when nothing is missing: the
     * symbol page then has no extra page.
     */
    fun extraSymbolPageRows(
        missing: List<String>,
        ascii: Boolean,
    ): List<List<String>> {
        if (missing.isEmpty()) return emptyList()
        val shape = BuiltinLayouts.symbolPageRows(ascii)
        val marks = (missing + shape.flatten()).distinct().take(shape.sumOf { it.size })
        var next = 0
        return shape.map { row -> marks.subList(next, next + row.size).also { next += row.size } }
    }

    /** 常用 with the missing marks first; a mark already in it moves to the front instead of appearing twice. */
    fun commonSymbols(
        common: List<String>,
        leading: List<String>,
    ): List<String> = (leading + common).distinct()

    /**
     * The marks the pairing screen names for a user's own symbol page, both branches in turn; empty
     * when nothing is missing or the symbol page is a built-in one (which takes the marks over itself).
     */
    fun pairingMissingMarks(
        letters: LayoutDefinition?,
        numeric: KeyboardPageDefinition?,
        symbols: KeyboardPageDefinition?,
        mainLayoutIsPhoneKeypad: Boolean,
    ): List<String> {
        if (symbolPageForm(symbols, mainLayoutIsPhoneKeypad) != SymbolPageForm.CUSTOM) return emptyList()
        return listOf(false, true)
            .flatMap { ascii -> missingMarks(letters, numeric, symbols, mainLayoutIsPhoneKeypad, ascii) }
            .distinct()
    }
}

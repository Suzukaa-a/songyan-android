package app.songyan.ime.ime

import app.songyan.ime.keyboard.engine.InputLanguage
import app.songyan.ime.keyboard.layout.CompactFold
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.layout.BuiltinLayouts
import app.songyan.ime.layout.FooterKeys
import app.songyan.ime.layout.KeyboardComposition
import app.songyan.ime.layout.KeyboardNumericMarks
import app.songyan.ime.layout.KeyboardPageCategory
import app.songyan.ime.layout.KeyboardPageChrome
import app.songyan.ime.layout.KeyboardPageDefinition
import app.songyan.ime.layout.KeyboardPageKind
import app.songyan.ime.layout.KeyboardPageMarkCoverage
import app.songyan.ime.layout.KeyboardPagePresets
import app.songyan.ime.layout.KeyboardSymbolPresentation
import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutBottomRow
import app.songyan.ime.layout.LayoutDefinition
import app.songyan.ime.layout.LayoutFold
import app.songyan.ime.layout.LayoutInput
import app.songyan.ime.layout.LayoutKey
import app.songyan.ime.layout.LayoutKeyTitleMode
import app.songyan.ime.layout.LayoutPageTarget
import app.songyan.ime.layout.LayoutRegistry
import app.songyan.ime.layout.LayoutRow
import app.songyan.ime.layout.LayoutSplitConfiguration
import app.songyan.ime.layout.LayoutSplitRow
import app.songyan.ime.layout.LetterGroupLayout
import app.songyan.ime.layout.PhoneKeypadRailPolicy

/**
 * Builds the key area of every built-in page from the :core:layout page data.
 *
 * The data (rows, marks, footer keys) belongs to :core:layout so the keyboard, the page editor and
 * the presets cannot drift apart; this layer only decides which page is drawn and adapts the
 * footer to the running editor and language state.
 */
internal object KeyboardPages {
    private const val DIGIT_COLUMNS = 10

    /** What the key area needs to know about the running keyboard and editor. */
    data class Config(
        val layout: KeyboardLayoutKind,
        /** English direct input: the letters page becomes the ABC page. */
        val englishDirectInput: Boolean,
        /** Footer/123/symbol marks follow the panel's symbol branch. */
        val symbolMarksAscii: Boolean,
        /** False for editors that force ASCII: no 中/英 key. */
        val languageSwitch: Boolean,
        /** False for numeric editors, which have no letter keyboard to return to. */
        val lettersReachable: Boolean,
        /** What the field is for: the keys beside the space bar and the password digit row follow it. */
        val field: FieldKind = FieldKind.TEXT,
        /** The layout catalog this was resolved against; a new install builds the pages again. */
        val catalogRevision: Long = 0L,
        /** The pairing's pages and main layout; null follows the bundled ones. */
        val pairing: KeyboardComposition? = null,
        /** The user asked for the input-method switch key: the generated footers carry it and authored ones keep theirs. */
        val showsGlobe: Boolean = false,
        /**
         * The layouts a jump key may open now (see [KeyboardController.jumpableLayouts]); a jump to any
         * other is not drawn, the way a globe key is not drawn while the switch key is off.
         */
        val jumpLayouts: Set<KeyboardLayoutKind> = KeyboardLayoutKind.entries.toSet(),
        /**
         * The row symbol page opens on its extra page of the everyday marks the pairing reaches
         * nowhere else (see [everydayMarksPage]); its page switch turns to the usual rows.
         */
        val everydayMarksPage: Boolean = true,
        /** The schemas input language: the bundled long-press marks and the space-side marks follow it. */
        val inputLanguage: InputLanguage = InputLanguage.ZH_HANS,
    )

    /**
     * The built pages by (page, config). The controller asks after every key; an unchanged page must
     * come back as the same instance, which is what lets the key view skip re-solving it. The keyboard
     * is driven from the main thread only.
     */
    private val built =
        object : LinkedHashMap<Pair<KeyboardPageKind?, Config>, BuiltPage>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<Pair<KeyboardPageKind?, Config>, BuiltPage>,
            ) = size > MAX_CACHED_PAGES
        }

    /**
     * A built page: the rows (footer last) and, for an authored page that carries side rails, the
     * rails drawn beside them.
     */
    private class BuiltPage(
        val definition: LayoutDefinition,
        val rails: PageRails? = null,
    )

    /**
     * The side rails of an authored page. [contentRows] counts the rows before the footer, which is
     * the last row of the definition when [hasFooter]; the rails stand beside the content rows and
     * the trailing one may also run down beside the footer.
     */
    internal class PageRails(
        val chrome: KeyboardPageChrome,
        val contentRows: Int,
        val hasFooter: Boolean,
    )

    private const val MAX_CACHED_PAGES = 24

    /** Drops the built pages; the next ask builds the page again from the catalog. */
    fun trim() {
        built.clear()
    }

    /** The letters page (Chinese layouts and the ABC page share its footer). */
    fun definition(
        page: KeyboardPageKind?,
        config: Config,
    ): LayoutDefinition = builtPage(page, config).definition

    /**
     * The letters page the keyboard would draw for [main] in a plain text field, built from [main]
     * itself instead of the catalog: the layout editor previews a draft that is not saved yet.
     */
    fun previewLetters(main: LayoutDefinition): LayoutDefinition {
        val kind = main.input.builtinLayout?.let { KeyboardLayoutKind.fromWire(it.wire) } ?: KeyboardLayoutKind.QWERTY26
        val config =
            Config(
                layout = kind,
                englishDirectInput = false,
                symbolMarksAscii = false,
                languageSwitch = true,
                lettersReachable = true,
            )
        return letters(config, main).definition
    }

    /** The authored rails of the page drawn for ([page], [config]), or null for a page without any. */
    fun rails(
        page: KeyboardPageKind?,
        config: Config,
    ): PageRails? = builtPage(page, config).rails

    private fun builtPage(
        page: KeyboardPageKind?,
        config: Config,
    ): BuiltPage = built.getOrPut(page to config) { build(page, config) }

    private fun build(
        page: KeyboardPageKind?,
        config: Config,
    ): BuiltPage =
        when (page) {
            null -> letters(config)
            KeyboardPageKind.NUMERIC ->
                authoredPage(KeyboardPageKind.NUMERIC, config)
                    ?: BuiltPage(rowsPage(numericPreset(config), config, symbols = false))
            KeyboardPageKind.SYMBOLS ->
                authoredPage(KeyboardPageKind.SYMBOLS, config)
                    ?: symbolRowsPage(config)
            KeyboardPageKind.ENGLISH -> letters(config.copy(englishDirectInput = true))
        }

    /**
     * The rails of [chrome] the key area draws, or null when it has none. The system usually owns the
     * input method switch (navigation bar), so a globe key is drawn only when [showsGlobe].
     */
    private fun railsOf(
        chrome: KeyboardPageChrome?,
        contentRows: Int,
        hasFooter: Boolean,
        config: Config,
    ): PageRails? {
        if (chrome == null) return null
        val drawn =
            chrome.copy(
                leading = chrome.leading.filter { railKeyDrawn(it, config) },
                trailing = chrome.trailing.filter { railKeyDrawn(it, config) },
            )
        if (drawn.leading.isEmpty() && drawn.trailing.isEmpty()) return null
        return PageRails(drawn, contentRows, hasFooter)
    }

    private fun railKeyDrawn(
        key: LayoutKey,
        config: Config,
    ): Boolean = (config.showsGlobe || key.action.type != LayoutActionKind.GLOBE) && jumpDrawn(key, config)

    /**
     * Whether [key] is drawn as far as jumps go: a jump to a layout the running input method cannot
     * use, or to a target this version does not know, is left out rather than drawn as a key that
     * would do something else.
     */
    internal fun jumpDrawn(
        key: LayoutKey,
        config: Config,
    ): Boolean {
        if (key.action.type != LayoutActionKind.LAYOUT_PAGE_SWITCH) return true
        val jump = key.action.layoutPageJump ?: return false
        return KeyboardLayoutKind.fromWire(jump.layout.wire) in config.jumpLayouts
    }

    /** [rows] without the jump keys [jumpDrawn] leaves out; a row left empty goes with them. */
    private fun drawnRows(
        rows: List<LayoutRow>,
        config: Config,
    ): List<LayoutRow> {
        if (rows.none { row -> row.keys.any { it.action.type == LayoutActionKind.LAYOUT_PAGE_SWITCH } }) return rows
        return rows
            .map { row -> LayoutRow(row.keys.filter { jumpDrawn(it, config) }) }
            .filter { it.keys.isNotEmpty() }
    }

    /**
     * The categorized symbol page this keyboard opens for its symbol page, or null when the symbol page
     * is the row page. A categorized page of the pairing opens it with its own categories (null: the
     * built-in ones); a nine-key keyboard whose pairing leaves the choice automatic opens the built-in
     * page, because its 123 page is a digit pad with no rows for a second row page to share. A pairing
     * that authored a row page keeps it.
     */
    fun categorizedSymbols(
        layout: KeyboardLayoutKind,
        pairing: KeyboardComposition?,
    ): CategorizedSymbols? {
        val page =
            pairing
                ?.pageId(KeyboardPageKind.SYMBOLS)
                ?.let { registry.page(it) }
                ?.takeIf { it.kind == KeyboardPageKind.SYMBOLS }
        return when {
            page == null || page.symbolPresentation == KeyboardSymbolPresentation.AUTOMATIC ->
                if (layout == KeyboardLayoutKind.PHONE9) CategorizedSymbols(null) else null
            page.symbolPresentation == KeyboardSymbolPresentation.CATEGORIZED -> CategorizedSymbols(page.categories)
            else -> null
        }
    }

    /** [categories] null means the built-in categories (the page carries none of its own). */
    class CategorizedSymbols(
        val categories: List<KeyboardPageCategory>?,
    )

    private val registry: LayoutRegistry get() = KeyboardLayoutCatalog.snapshot.value.registry

    /** The main layout of the pairing (a user's copy or an edited bundled one), or the bundled preset. */
    private fun mainLayout(config: Config): LayoutDefinition = mainLayout(config.layout, config.pairing)

    private fun mainLayout(
        layout: KeyboardLayoutKind,
        pairing: KeyboardComposition?,
    ): LayoutDefinition =
        pairing?.let { registry.resolvedLayout(it.mainLayoutID) }
            ?: BuiltinLayouts.byId(layout.wire)
            ?: BuiltinLayouts.qwerty26

    /**
     * The everyday marks of the [ascii] branch that no page of this keyboard's pairing types with a
     * tap; the built-in symbol page takes them over.
     */
    fun missingMarks(
        layout: KeyboardLayoutKind,
        pairing: KeyboardComposition?,
        ascii: Boolean,
    ): List<String> {
        val pages = registry
        return KeyboardPageMarkCoverage.missingMarks(
            letters = mainLayout(layout, pairing),
            numeric = pairing?.pageId(KeyboardPageKind.NUMERIC)?.let { pages.page(it) },
            symbols = pairing?.pageId(KeyboardPageKind.SYMBOLS)?.let { pages.page(it) },
            mainLayoutIsPhoneKeypad = layout == KeyboardLayoutKind.PHONE9,
            ascii = ascii,
        )
    }

    private fun symbolRowsPage(config: Config): BuiltPage {
        val extra = everydayMarksPage(config.copy(everydayMarksPage = true))
        if (config.everydayMarksPage && extra != null) return extra
        val shown = rowsPage(symbolsPreset(config), config, symbols = true)
        if (extra == null) return BuiltPage(shown)
        // Both symbol pages share the same switch; the second must not escape to the paired pad.
        val rows =
            shown.rows.dropLast(1).map { row ->
                LayoutRow(
                    row.keys.map { key ->
                        if (key.action.pageSwitchTarget == LayoutPageTarget.NUMERIC) {
                            key.copy(action = LayoutAction.pageSwitch(LayoutPageTarget.SYMBOLS))
                        } else {
                            key
                        }
                    },
                )
            }
        return BuiltPage(shown.copy(rows = rows + shown.rows.last()))
    }

    /**
     * The built-in row symbol page's extra first page, or null when the pairing reaches every
     * everyday mark, draws another symbol page, or the switch already turned to the usual rows. It is
     * the symbol rows page itself with its mark keys taken over slot by slot, so the key widths, the
     * third-row switch and Delete and the footer are that page's own; a mark from the 123 page keeps
     * that page's key (its long press included). The switch turns to the usual symbol rows.
     */
    private fun everydayMarksPage(config: Config): BuiltPage? {
        if (!config.everydayMarksPage) return null
        val symbols = config.pairing?.pageId(KeyboardPageKind.SYMBOLS)?.let { registry.page(it) }
        val phone = config.layout == KeyboardLayoutKind.PHONE9
        if (KeyboardPageMarkCoverage.symbolPageForm(symbols, phone) !=
            KeyboardPageMarkCoverage.SymbolPageForm.BUILT_IN_ROWS
        ) {
            return null
        }
        val ascii = config.symbolMarksAscii
        val marks =
            KeyboardPageMarkCoverage
                .extraSymbolPageRows(missingMarks(config.layout, config.pairing, ascii), ascii)
                .flatten()
        if (marks.isEmpty()) return null
        val shown = rowsPage(symbolsPreset(config), config, symbols = true)
        val keys =
            (symbolsPreset(config).rows + numericPreset(config).rows)
                .flatMap { it.keys }
                .associateBy { it.action.literalValue(ascii) }
        var next = 0
        val markRows =
            shown.rows.dropLast(1).map { row ->
                LayoutRow(
                    row.keys.map { key ->
                        when {
                            key.action.pageSwitchTarget == LayoutPageTarget.NUMERIC ->
                                key.copy(action = LayoutAction.pageSwitch(LayoutPageTarget.SYMBOLS))
                            key.action.type != LayoutActionKind.LITERAL || next >= marks.size -> key
                            else -> {
                                val mark = marks[next++]
                                (keys[mark] ?: LayoutKey(mark, action = LayoutAction.literal(mark)))
                                    .copy(width = key.width)
                            }
                        }
                    },
                )
            }
        return BuiltPage(shown.copy(id = EVERYDAY_MARKS_PAGE_ID, rows = markRows + shown.rows.last()))
    }

    private const val EVERYDAY_MARKS_PAGE_ID = "page.symbols.everyday"

    /**
     * The shuangpin hints [hints] the Chinese letters page of [config] draws under its letters, or
     * none: only a layout whose letter keys cover a-z once carries them, so a nine-key pad or a
     * symbol layout never shows a hint on the wrong key.
     */
    fun syllableHints(
        config: Config,
        hints: Map<Char, String>,
    ): Map<Char, String> {
        if (hints.isEmpty() ||
            config.englishDirectInput ||
            config.layout == KeyboardLayoutKind.PHONE9
        ) {
            return emptyMap()
        }
        return if (LetterGroupLayout.groups(mainLayout(config)) != null) hints else emptyMap()
    }

    /**
     * The page the pairing chose for [kind] when it is drawn as data: a stored page that is not one
     * of the live-following presets. The row presets keep their own native pages, which follow the
     * editor and the language, so null means "use those". The keypad preset is drawn as data even
     * unchanged: no native page of a 26-, 14- or 18-key keyboard is a digit pad, so treating it as
     * native would silently draw the ten-column page instead of the pad the user paired.
     */
    private fun chosenPage(
        kind: KeyboardPageKind,
        config: Config,
    ): KeyboardPageDefinition? {
        val id = config.pairing?.pageId(kind) ?: return null
        val page = registry.page(id) ?: return null
        if (page.kind != kind || page.resolvesAtRuntime || page.usesSymbolBrowser) return null
        // The digit pad preset is authored data (its own rails and footer), drawn like a user's page.
        if (!page.isKeypad && KeyboardPagePresets.isUnmodifiedPreset(page)) return null
        return page
    }

    /**
     * Whether the 123 page of [config] is a page the user authored, which the nine-key shell must not
     * cover with its own digit pad. The unchanged keypad preset is that same pad, so the shell keeps
     * drawing it.
     */
    fun pairsAuthoredNumericPage(config: Config): Boolean {
        val page = chosenPage(KeyboardPageKind.NUMERIC, config) ?: return false
        return !KeyboardPagePresets.isUnmodifiedPreset(page)
    }

    /**
     * Whether the 123 page of [config] is the unchanged keypad preset, which every layout draws as
     * the nine-key shell's own digit pad, so the pad looks and sits the same whichever letters it is
     * paired with and cannot drift from the nine-key keyboard's 123 page.
     */
    fun pairsKeypadPreset(config: Config): Boolean {
        val page = chosenPage(KeyboardPageKind.NUMERIC, config) ?: return false
        return page.isKeypad && KeyboardPagePresets.isUnmodifiedPreset(page)
    }

    /** A numeric or symbol page the user authored: its rows, then its own footer, drawn as data. */
    private fun authoredPage(
        kind: KeyboardPageKind,
        config: Config,
    ): BuiltPage? {
        val chosen = chosenPage(kind, config) ?: return null
        val page = chosen.materialized()
        val rows = drawnRows(page.rows, config)
        val footer =
            page.chrome
                ?.bottom
                .orEmpty()
                .filter { jumpDrawn(it, config) }
        val definition =
            LayoutDefinition(
                id = page.id,
                name = page.name,
                input = page.layoutDefinition.input,
                rows = rows + if (footer.isEmpty()) emptyList() else listOf(LayoutRow(footer)),
                split = LayoutSplitConfiguration.resolving(page.split, mainLayout(config).split),
            )
        return BuiltPage(definition, railsOf(page.chrome, rows.size, footer.isNotEmpty(), config))
    }

    private fun numericPreset(config: Config): KeyboardPageDefinition =
        KeyboardPagePresets.numericRowsPage(config.symbolMarksAscii)

    private fun symbolsPreset(config: Config): KeyboardPageDefinition =
        KeyboardPagePresets.symbolRowsPage(config.symbolMarksAscii)

    /**
     * Whether the marks of a page are ASCII: the branch the user flipped with the page's 中/英 key,
     * else the branch the page opens in, else the input language (English input opens the English
     * marks, Chinese input the Chinese ones; an editor that forces ASCII always does).
     */
    fun marksAscii(
        flipped: Boolean?,
        opening: Boolean?,
        englishInput: Boolean,
        editorAscii: Boolean,
    ): Boolean = flipped ?: opening ?: (englishInput || editorAscii)

    /**
     * The branch the page of [kind] opens in when the user has not flipped it: the pairing's page
     * pins one (the editor's 初始符号), or null to follow the input language.
     */
    fun openingMarks(
        kind: KeyboardPageKind,
        pairing: KeyboardComposition?,
    ): Boolean? {
        val id = pairing?.pageId(kind) ?: return null
        val page = registry.page(id)?.takeIf { it.kind == kind } ?: return null
        return when (page.defaultSymbolMarks) {
            KeyboardNumericMarks.ASCII -> true
            KeyboardNumericMarks.CHINESE -> false
            null -> null
        }
    }

    /** The twelve-key kana board, the built-in one or a copy: it draws its own rails and footer. */
    private fun isKanaBoard(main: LayoutDefinition): Boolean = Kana12Surface.of(main) != null

    /**
     * The letters page: the chosen Chinese layout (or the English QWERTY page) with Shift and Delete
     * folded into the last letter row where the shape carries them, then the footer row (see
     * [lettersFooter]).
     */
    private fun letters(
        config: Config,
        main: LayoutDefinition = mainLayout(config),
    ): BuiltPage {
        val kanaBoard = !config.englishDirectInput && config.field == FieldKind.TEXT && isKanaBoard(main)
        val authoredEnglish =
            if (config.englishDirectInput) {
                chosenPage(
                    KeyboardPageKind.ENGLISH,
                    config,
                )?.materialized()
            } else {
                null
            }
        // The 9-key shell draws its own footer and editing rail; a nine-key layout it cannot draw
        // falls back to these rows and gets the generated footer like every other layout.
        val footer =
            when {
                kanaBoard -> emptyList()
                config.layout == KeyboardLayoutKind.PHONE9 &&
                    !config.englishDirectInput &&
                    PhoneKeypadLayout.draws(main) -> emptyList()
                authoredEnglish != null ->
                    authoredEnglish.chrome?.bottom?.filter { jumpDrawn(it, config) } ?: lettersFooter(config)
                !config.englishDirectInput && config.field == FieldKind.TEXT ->
                    LayoutBottomRow
                        .authored(
                            main,
                        )?.let { LayoutBottomRow.reachable(main, it) }
                        ?.filter { railKeyDrawn(it, config) }
                        ?: lettersFooter(config)
                else -> lettersFooter(config)
            }
        // The footer is known before the rows: a pad that edits through it keeps the shell's keys out.
        val rows =
            when {
                authoredEnglish != null ->
                    drawnRows(authoredEnglish.rows, config).map { row ->
                        LayoutRow(row.keys.map { englishKey(it) })
                    }
                config.englishDirectInput -> englishRows()
                else -> drawnRows(chineseRows(config, main, footer), config)
            }
        // A password field has no candidates to peek at, so its digits get a row of their own.
        val digitRows = if (config.field == FieldKind.PASSWORD) listOf(digitRow()) else emptyList()
        val cuts =
            if (authoredEnglish != null) {
                LayoutSplitConfiguration.resolving(authoredEnglish.split, main.split)
            } else {
                handCuts(main, rows, digitRows.size) ?: main.split
            }
        val split = cuts?.let { pageSplit(it, main, config, authoredEnglish != null, digitRows, footer) }
        val definition =
            LayoutDefinition(
                id = if (authoredEnglish != null) authoredEnglish.id else "page.letters." + main.id,
                name = "letters",
                input = LayoutInput(builtinLayout = builtin(config.layout)),
                rows = digitRows + rows + if (footer.isEmpty()) emptyList() else listOf(LayoutRow(footer)),
                split = split,
                auxiliaryAreas = if (kanaBoard) main.auxiliaryAreas else null,
            )
        val rails = railsOf(authoredEnglish?.chrome, digitRows.size + rows.size, footer.isNotEmpty(), config)
        return BuiltPage(definition, rails)
    }

    /**
     * The split of the letters page with the layout's explicit halves carried over: the same page
     * transformation (marks, Shift, Delete, the digit row, the footer) applied to each half. Only the
     * Chinese letters of the layout use them; an English or authored page follows the row cuts.
     */
    private fun pageSplit(
        split: LayoutSplitConfiguration,
        main: LayoutDefinition,
        config: Config,
        authoredEnglish: Boolean,
        digitRows: List<LayoutRow>,
        footer: List<LayoutKey>,
    ): LayoutSplitConfiguration {
        val halves = main.split?.halves
        if (halves == null || authoredEnglish || config.englishDirectInput) return split.copy(halves = null)
        val letters = chineseHalves(config.layout, main, halves, config, footer) ?: return split.copy(halves = null)
        val digits = digitRows.map { LayoutSplitRow(it.keys.take(DIGIT_COLUMNS / 2), it.keys.drop(DIGIT_COLUMNS / 2)) }
        val footerHalves =
            if (footer.isEmpty()) emptyList() else listOf(split.copy(rowBreaks = emptyList()).cut(0, footer))
        return split.copy(halves = digits + letters + footerHalves)
    }

    private fun chineseHalves(
        layout: KeyboardLayoutKind,
        base: LayoutDefinition,
        halves: List<LayoutSplitRow>,
        config: Config,
        footer: List<LayoutKey>,
    ): List<LayoutSplitRow>? {
        if (halves.size != base.rows.size) return null

        fun face(row: LayoutSplitRow) =
            LayoutSplitRow(
                row.left.map { LetterPageKeys.mergedFace(it) },
                row.right.map { LetterPageKeys.mergedFace(it) },
            )
        if (layout == KeyboardLayoutKind.PHONE9) {
            val control = PhoneKeypadRailPolicy.controlRowKeys(base) != null
            return (if (control) halves.dropLast(1) else halves).map(::face)
        }
        val nativeDefaults = LetterPageKeys.nativeLetterDefaults(base)
        val letters =
            halves
                .filterIndexed { index, _ -> index != LayoutBottomRow.index(base) }
                .map { row ->
                    val shown = face(row)
                    LayoutSplitRow(
                        shown.left.map { LetterPageKeys.chineseLetterKey(it, config.inputLanguage, nativeDefaults) },
                        shown.right.map { LetterPageKeys.chineseLetterKey(it, config.inputLanguage, nativeDefaults) },
                    )
                }.toMutableList()
        if (letters.isEmpty()) return letters
        // A pad taller than the shipped shapes edits through its own footer instead of the shell.
        if (!LetterPageKeys.shellEditsLastLetterRow(base, footer, isKanaBoard(base))) return letters
        val last = letters.last()
        val hasShift = last.keys.any { it.action.type == LayoutActionKind.SHIFT }
        val hasDelete = last.keys.any { it.action.type == LayoutActionKind.BACKSPACE }
        letters[letters.lastIndex] =
            LayoutSplitRow(
                (if (hasShift) emptyList() else listOf(LetterPageKeys.leadingControl(layout))) + last.left,
                last.right + if (hasDelete) emptyList() else listOf(LetterPageKeys.DELETE),
            )
        return letters
    }

    /**
     * Where the bundled 26- and 18-key layouts split when nothing saved says otherwise: each hand
     * keeps its letters from a physical keyboard, QWERT ASDFG ZXCVB on the left (on 18 keys three keys
     * a row, so BN and M share the right hand). 26 keys still splits only when the user
     * turns splitting on. The cuts count letter keys, so they move past the Shift the page adds in
     * front of the last letter row and below a password field's digit row.
     */
    private fun handCuts(
        main: LayoutDefinition,
        rows: List<LayoutRow>,
        digitRows: Int,
    ): LayoutSplitConfiguration? {
        val handSplit = BuiltinLayouts.handSplit(main) ?: return null
        val letters = handSplit.rowBreaks
        val letterRows = main.rows.filterIndexed { index, _ -> index != LayoutBottomRow.index(main) }
        val cuts =
            rows.mapIndexed { index, row ->
                val cut = letters.getOrNull(index) ?: return null
                val first =
                    letterRows
                        .getOrNull(index)
                        ?.keys
                        ?.firstOrNull()
                        ?.action
                cut + row.keys.indexOfFirst { it.action == first }.coerceAtLeast(0)
            }
        return handSplit.copy(rowBreaks = List(digitRows) { (DIGIT_COLUMNS + 1) / 2 } + cuts)
    }

    private fun digitRow(): LayoutRow =
        LayoutRow(
            "1234567890".map { digit ->
                LayoutKey(digit.toString(), action = LayoutAction.literal(digit.toString()))
            },
        )

    /** A row-shaped 123 or symbol page: its own mark rows plus the page footer. */
    private fun rowsPage(
        preset: KeyboardPageDefinition,
        config: Config,
        symbols: Boolean,
    ): LayoutDefinition {
        val page = preset.materialized()
        val footer = if (symbols) symbolsFooter(config) else numericFooter(config)
        return LayoutDefinition(
            id = page.id,
            name = page.name,
            input = page.layoutDefinition.input,
            rows = page.rows + LayoutRow(footer),
            split = LayoutSplitConfiguration.resolving(page.split, mainLayout(config).split),
        )
    }

    private fun chineseRows(
        config: Config,
        base: LayoutDefinition,
        footer: List<LayoutKey>,
    ): List<LayoutRow> {
        val layout = config.layout
        // A nine-key definition the phone shell cannot draw falls back to the row renderer.
        if (layout == KeyboardLayoutKind.PHONE9) {
            val declared = if (PhoneKeypadRailPolicy.controlRowKeys(base) != null) base.rows.dropLast(1) else base.rows
            return declared.map { row -> LayoutRow(row.keys.map { LetterPageKeys.mergedFace(it) }) }
        }
        val nativeDefaults = LetterPageKeys.nativeLetterDefaults(base)
        val rows =
            // Footer keys are added separately so removing Space never turns them into letter keys.
            base.rows
                .filterIndexed { index, _ -> index != LayoutBottomRow.index(base) }
                .map { row ->
                    LayoutRow(
                        row.keys.map {
                            LetterPageKeys.chineseLetterKey(
                                LetterPageKeys.mergedFace(it),
                                config.inputLanguage,
                                nativeDefaults,
                            )
                        },
                    )
                }.toMutableList()
        if (rows.isEmpty()) return rows
        // A pad taller than the shipped shapes edits through its own footer instead of the shell.
        if (!LetterPageKeys.shellEditsLastLetterRow(base, footer, isKanaBoard(base))) return rows
        // A layout that carries its own Shift or Delete keeps them; the bundled ones do not.
        val last = rows.last().keys
        val hasShift = last.any { it.action.type == LayoutActionKind.SHIFT }
        val hasDelete = last.any { it.action.type == LayoutActionKind.BACKSPACE }
        rows[rows.lastIndex] =
            LayoutRow(
                (if (hasShift) emptyList() else listOf(LetterPageKeys.leadingControl(layout))) + last +
                    if (hasDelete) emptyList() else listOf(LetterPageKeys.DELETE),
            )
        return rows
    }

    private fun englishRows(): List<LayoutRow> =
        englishPage.rows.map { row -> LayoutRow(row.keys.map { englishKey(it) }) }

    /** An English page letter is a key code, so Shift and the session own its case. */
    private fun englishKey(key: LayoutKey): LayoutKey {
        val action = key.action
        if (action.type != LayoutActionKind.LITERAL) return key
        val text = action.value ?: return key
        if (text.length != 1 || text[0].code > 127 || !text[0].isLetter()) return key
        return key.copy(action = LayoutAction.code(text.lowercase()))
    }

    /**
     * The letters footer: `123 | 中/英 | ，| 空格 | 。| 回车` in every field, so a field type never moves
     * a key. The marks follow the field: the language's comma and full stop in a text field (`, .`
     * in English), `: /` in a URL field, `_ .` in an email field. An editor without 中/英 (email,
     * password, a private field) puts a field mark in its place instead of stretching 123.
     */
    internal fun lettersFooter(config: Config): List<LayoutKey> {
        val side = spaceSideMarks(config, config.englishDirectInput)
        return FooterKeys.row(
            page = FooterKeys.PAGE,
            pageReachable = config.lettersReachable,
            beside = besideMark(config),
            languageSwitch = config.languageSwitch,
            right = side.second,
            globe = config.showsGlobe,
            spaceLeft = side.first,
        )
    }

    /**
     * The key that takes 中/英's place in an editor without it: `.` in a URL field, `@` everywhere
     * else (an email address, a user name or a password reaches for it most).
     */
    private fun besideMark(config: Config): LayoutKey =
        when (config.field) {
            FieldKind.URL -> FooterKeys.mark(".", listOf("-", "_")).copy(width = FooterKeys.Weight.LANGUAGE)
            else -> FooterKeys.atMark()
        }

    /** The comma key right of the space bar: the full stop first in its long press, so it is the corner hint. */
    internal fun commaKey(ascii: Boolean) =
        if (ascii) {
            FooterKeys.mark(",", listOf(".", "?", "!", "'", ":", ";")).copy(secondaryLabel = ".")
        } else {
            FooterKeys.mark("，", listOf("。", "？", "！", "、", "：", "；")).copy(secondaryLabel = "。")
        }

    /**
     * The marks either side of the space bar. A text field types the comma and full stop of [ascii]
     * or the script language, so 中/英 changes the marks and never moves a key; a URL field types
     * `:` and `/`, an email field `_` and `.`, whatever the language.
     */
    private fun spaceSideMarks(
        config: Config,
        ascii: Boolean,
    ): Pair<LayoutKey, LayoutKey> =
        when (config.field) {
            FieldKind.URL ->
                FooterKeys.mark(":", listOf(".", "-", "_", "@")) to
                    FooterKeys.mark("/", listOf(".", "?", "=", "&", "#"))
            FieldKind.EMAIL -> FooterKeys.mark("_", listOf("-", "+")) to FooterKeys.mark(".", listOf("-", "_"))
            FieldKind.PASSWORD -> FooterKeys.textMarks(config.inputLanguage, ascii = true)
            FieldKind.TEXT -> FooterKeys.textMarks(config.inputLanguage, ascii)
        }

    /** The 123 page footer: `返回 | 中/英 | ，| 空格 | 。| 回车`; the symbol switch opens the third row. */
    private fun numericFooter(config: Config): List<LayoutKey> {
        val side = spaceSideMarks(config, config.symbolMarksAscii)
        return FooterKeys.row(
            page =
                FooterKeys.key(
                    "返回",
                    LayoutAction.pageSwitch(LayoutPageTarget.LETTERS),
                    FooterKeys.Weight.PAGE,
                    titleMode = LayoutKeyTitleMode.AUTOMATIC,
                ),
            pageReachable = true,
            beside = besideMark(config),
            languageSwitch = config.languageSwitch,
            right = side.second,
            globe = config.showsGlobe,
            spaceLeft = side.first,
        )
    }

    /** Opens the symbol library panel; the footer's 更多 key carries it as a mode switch. */
    const val SYMBOL_PANEL_MODE = "symbolPanel"

    /**
     * The symbol page footer: `返回 | 中/英 | ，| 空格 | 更多 | 回车`. The page already types every mark, so
     * the full stop's place goes to 更多 and the row keeps the letters footer's shape; the 123 switch
     * is in the third row.
     */
    private fun symbolsFooter(config: Config): List<LayoutKey> {
        val side = spaceSideMarks(config, config.symbolMarksAscii)
        return FooterKeys.row(
            page =
                FooterKeys.key(
                    "返回",
                    LayoutAction.pageSwitch(LayoutPageTarget.LETTERS),
                    FooterKeys.Weight.PAGE,
                    titleMode = LayoutKeyTitleMode.AUTOMATIC,
                ),
            pageReachable = true,
            beside = besideMark(config),
            languageSwitch = config.languageSwitch,
            right =
                FooterKeys.key(
                    "更多",
                    LayoutAction(LayoutActionKind.MODE_SWITCH, SYMBOL_PANEL_MODE),
                    FooterKeys.Weight.MARK,
                    titleMode = LayoutKeyTitleMode.AUTOMATIC,
                ),
            globe = config.showsGlobe,
            spaceLeft = side.first,
        )
    }

    private fun builtin(layout: KeyboardLayoutKind): LayoutInput.Builtin =
        when (layout) {
            KeyboardLayoutKind.QWERTY26 -> LayoutInput.Builtin.QWERTY26
            KeyboardLayoutKind.MERGED14 -> LayoutInput.Builtin.MERGED14
            KeyboardLayoutKind.MERGED18 -> LayoutInput.Builtin.MERGED18
            KeyboardLayoutKind.PHONE9 -> LayoutInput.Builtin.PHONE9
        }

    /**
     * The English page's rows come from :core:layout, which materializes them with the legacy
     * Shift/Delete synthesis; the footer is this object's own.
     */
    private val englishPage: KeyboardPageDefinition by lazy { KeyboardPagePresets.englishQwerty.materialized() }

    /** Whether [key] is the separator this object puts in a merged pad's Shift slot. */
    fun isMergedSeparator(key: LayoutKey): Boolean = key == LetterPageKeys.MERGED_SEPARATOR
}

/**
 * Exact-letter sideways picks of a merged key: swiping left sends the group leader's exact token,
 * swiping right sends the partner letter. Both are gated on the running schema's alphabet, so a
 * scheme that cannot read the letter never gets the gesture.
 */
class MergedSwipeConfig(
    val fold: CompactFold,
    private val alphabet: String,
    /** True only when the schema's alphabet proves it reads the published exact tokens. */
    val useExactTokens: Boolean,
) {
    /** The two codes a sideways swipe sends, left then right; null when this key has no swipe. */
    fun codesFor(key: LayoutKey): List<String>? {
        val (group, sent) = swipeGroup(key) ?: return null
        // The code the key sends is the tap itself; the published exact token only replaces it when
        // the running schema declares the token in its alphabet, so a scheme that cannot read a
        // token never receives one. The other code is typed as itself.
        return group.map { code ->
            if (code == sent &&
                useExactTokens
            ) {
                fold.exactTokens[code]?.toString() ?: code.toString()
            } else {
                code.toString()
            }
        }
    }

    /** The letters the two swipe directions type, for the press preview. */
    fun lettersFor(key: LayoutKey): List<String>? = swipeGroup(key)?.first?.map { it.toString() }

    /**
     * The two codes a key covers, left to right, with the one it sends: as written on the key, else
     * the bundled grouping's in the order the key's title shows them. Null for a key without a swipe.
     */
    private fun swipeGroup(key: LayoutKey): Pair<List<Char>, Char>? {
        if (key.action.type != LayoutActionKind.CODE) return null
        val sent =
            key.action.value
                ?.takeIf { it.length == 1 }
                ?.get(0) ?: return null
        val written = LayoutFold.codesOf(key.codes)
        val group =
            written.ifEmpty {
                val title = key.label.lowercase()
                fold.map
                    .filterValues { it == sent }
                    .keys
                    .sortedBy { title.indexOf(it).let { at -> if (at < 0) Int.MAX_VALUE else at } }
            }
        if (group.size != 2 || sent !in group || group.any { fold.map[it] != sent || it !in alphabet }) return null
        return group to sent
    }
}

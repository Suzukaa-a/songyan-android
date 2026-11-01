package app.songyan.ime.layout

/**
 * The built-in page catalog. Presets ship in code: every upgrade merges them into the stored
 * catalog, so catalog totals never charge pages for them and a save may omit the unchanged ones.
 */
object KeyboardPagePresets {
    const val NUMERIC_AUTOMATIC_ID: String = "page.numeric.automatic"
    const val NUMERIC_KEYPAD_ID: String = "page.numeric.keypad"
    private const val NUMERIC_CHINESE_ID: String = "page.numeric.chinese"
    private const val NUMERIC_ENGLISH_ID: String = "page.numeric.english"
    private const val SYMBOLS_CHINESE_ID: String = "page.symbols.chinese"
    private const val SYMBOLS_ENGLISH_ID: String = "page.symbols.english"
    const val SYMBOLS_SYSTEM_ID: String = "page.symbols.system"
    private const val NUMERIC_AUTOMATIC_ENGLISH_ID: String = "page.numeric.automatic.english"
    private const val SYMBOLS_SYSTEM_ENGLISH_ID: String = "page.symbols.system.english"
    const val SYMBOLS_CATEGORIZED_ID: String = "page.symbols.categorized"
    const val ENGLISH_QWERTY_ID: String = "page.english.qwerty"

    /**
     * The default 123 page: its marks follow the running language and its shape follows the running
     * main layout, so pairing a keyboard never freezes the other punctuation width and never turns a
     * phone keypad's digit pad into a ten-column page.
     */
    val numericAutomatic: KeyboardPageDefinition =
        KeyboardPageDefinition(
            id = NUMERIC_AUTOMATIC_ID,
            name = "自动 123",
            kind = KeyboardPageKind.NUMERIC,
            rows = emptyList(),
            numericPresentation = KeyboardNumericPresentation.AUTOMATIC,
        )

    /**
     * An authored digit pad: the nine-key letter shell's grid with 1-9 in it and 0 below the 8 (see
     * [KeyboardPageDefinition.keypadChrome]) as editable data, usable with any main layout. Copying
     * the automatic phone digit pad lands here.
     */
    val numericKeypad: KeyboardPageDefinition = buildKeypadPreset()

    /**
     * The ten-column 123 rows of one symbol branch. Every key carries both branches in its action;
     * the label and the long-press list are the [ascii] branch's, because alternates are plain data.
     * The running keyboard draws these for the automatic page, and a copy starts from them.
     */
    fun numericRowsPage(ascii: Boolean): KeyboardPageDefinition = if (ascii) numericAsciiRows else numericChineseRows

    /** The row-shaped symbol page of one symbol branch; see [numericRowsPage]. */
    fun symbolRowsPage(ascii: Boolean): KeyboardPageDefinition = if (ascii) symbolsAsciiRows else symbolsChineseRows

    private val numericChineseRows: KeyboardPageDefinition by lazy { rowsPage(KeyboardPageKind.NUMERIC, false) }
    private val numericAsciiRows: KeyboardPageDefinition by lazy { rowsPage(KeyboardPageKind.NUMERIC, true) }
    private val symbolsChineseRows: KeyboardPageDefinition by lazy { rowsPage(KeyboardPageKind.SYMBOLS, false) }
    private val symbolsAsciiRows: KeyboardPageDefinition by lazy { rowsPage(KeyboardPageKind.SYMBOLS, true) }

    private fun rowsPage(
        kind: KeyboardPageKind,
        ascii: Boolean,
    ): KeyboardPageDefinition {
        val numeric = kind == KeyboardPageKind.NUMERIC
        val marks =
            if (numeric) BuiltinLayouts.numericPageBilingualRows() else BuiltinLayouts.symbolPageBilingualRows()
        return KeyboardPageDefinition(
            id = if (numeric) "page.numeric.rows" else "page.symbols.rows",
            name = if (numeric) "123" else "符号",
            kind = kind,
            rows = markRows(marks, kind, ascii),
        )
    }

    /** English QWERTY letters: each key carries one fixed letter, independent of the symbol branch. */
    val englishQwerty: KeyboardPageDefinition =
        KeyboardPageDefinition(
            id = ENGLISH_QWERTY_ID,
            name = "英文 QWERTY",
            kind = KeyboardPageKind.ENGLISH,
            rows =
                letterRows(
                    listOf(
                        "qwertyuiop".map { it.toString() },
                        "asdfghjkl".map { it.toString() },
                        "zxcvbnm".map { it.toString() },
                    ),
                ),
        )

    val symbolsSystem: KeyboardPageDefinition =
        KeyboardPageDefinition(
            id = SYMBOLS_SYSTEM_ID,
            name = "默认符号页",
            kind = KeyboardPageKind.SYMBOLS,
            rows = rowsPage(KeyboardPageKind.SYMBOLS, ascii = false).rows,
            symbolPresentation = KeyboardSymbolPresentation.AUTOMATIC,
        )

    val symbolsCategorized: KeyboardPageDefinition =
        KeyboardPageDefinition(
            id = SYMBOLS_CATEGORIZED_ID,
            name = "分类符号",
            kind = KeyboardPageKind.SYMBOLS,
            rows = emptyList(),
            symbolPresentation = KeyboardSymbolPresentation.CATEGORIZED,
        )

    /** Declaration order is the library order and the merge order of added presets. */
    val all: List<KeyboardPageDefinition> =
        listOf(
            symbolsSystem,
            symbolsCategorized,
            numericAutomatic,
            numericKeypad,
            englishQwerty,
        )

    val codeOwnedIds: Set<String> get() = all.map { it.id }.toSet()

    fun preset(id: String): KeyboardPageDefinition? = all.firstOrNull { it.id == id }

    /**
     * The Chinese and the English copy of the 123 page and of the symbol rows were separate library
     * pages although every key carries both branches. They are one page now: a retired id resolves to
     * the automatic page of its kind, which opens in the branch of the input language. An alias, not
     * a fresh id, because stored pairings, exported files and other devices still name the old ids.
     */
    private val retiredAliases: Map<String, String> =
        mapOf(
            NUMERIC_CHINESE_ID to NUMERIC_AUTOMATIC_ID,
            NUMERIC_ENGLISH_ID to NUMERIC_AUTOMATIC_ENGLISH_ID,
            SYMBOLS_CHINESE_ID to SYMBOLS_SYSTEM_ID,
            SYMBOLS_ENGLISH_ID to SYMBOLS_SYSTEM_ENGLISH_ID,
        )

    /**
     * The merged page with its initial marks pinned to English. A pairing that chose the English
     * page on purpose must keep typing English marks while the input language is Chinese; plain
     * automatic would follow the input language and silently switch it to Chinese marks.
     */
    private val englishAliasPages: Map<String, KeyboardPageDefinition> by lazy {
        listOf(
            numericAutomatic.copy(
                id = NUMERIC_AUTOMATIC_ENGLISH_ID,
                name = "自动 123（英文符号）",
                defaultSymbolMarks = KeyboardNumericMarks.ASCII,
            ),
            symbolsSystem.copy(
                id = SYMBOLS_SYSTEM_ENGLISH_ID,
                name = "默认符号页（英文符号）",
                defaultSymbolMarks = KeyboardNumericMarks.ASCII,
            ),
        ).associateBy { it.id }
    }

    /**
     * The retired presets exactly as they shipped, to tell an untouched stored copy from an edited
     * one. The keypad keeps its id; only its earlier arrangement is retired, so dropping a stored
     * copy of it lets the current preset merge back in under the same id.
     */
    private val retiredPresets: List<KeyboardPageDefinition> by lazy {
        fun old(
            id: String,
            name: String,
            kind: KeyboardPageKind,
            ascii: Boolean,
        ) = rowsPage(kind, ascii).copy(
            id = id,
            name = name,
            defaultSymbolMarks = if (ascii) KeyboardNumericMarks.ASCII else KeyboardNumericMarks.CHINESE,
        )
        listOf(
            numericKeypad.copy(chrome = KeyboardPageDefinition.previousKeypadChrome(KeyboardNumericMarks.CHINESE)),
            old(NUMERIC_CHINESE_ID, "中文 123", KeyboardPageKind.NUMERIC, ascii = false),
            old(NUMERIC_ENGLISH_ID, "英文 123", KeyboardPageKind.NUMERIC, ascii = true),
            old(SYMBOLS_CHINESE_ID, "中文符号", KeyboardPageKind.SYMBOLS, ascii = false),
            old(SYMBOLS_ENGLISH_ID, "英文符号", KeyboardPageKind.SYMBOLS, ascii = true),
        )
    }

    /**
     * A loaded catalog with the retired presets resolved: a stored copy the user never edited goes
     * (the automatic page replaces it), an edited one stays as an ordinary user page under its own
     * id, and a pairing that names a retired id whose page is gone points at the alias.
     */
    fun resolvingRetired(
        pages: List<KeyboardPageDefinition>,
        compositions: Map<String, KeyboardComposition>,
    ): Pair<List<KeyboardPageDefinition>, Map<String, KeyboardComposition>> {
        val kept = pages.filterNot { page -> retiredPresets.any { it == page } }
        val keptIds = kept.map { it.id }.toSet()

        fun resolved(id: String): String = if (id in keptIds) id else retiredAliases[id] ?: id
        val remapped =
            compositions.mapValues { (_, composition) ->
                composition.copy(
                    symbolPageID = resolved(composition.symbolPageID),
                    numericPageID = resolved(composition.numericPageID),
                    englishPageID = resolved(composition.englishPageID),
                )
            }
        // The English aliases are ordinary user pages, added only when a pairing points at one.
        val used = remapped.values.flatMap { listOf(it.symbolPageID, it.numericPageID, it.englishPageID) }.toSet()
        val added = englishAliasPages.values.filter { it.id in used && it.id !in keptIds }
        return (kept + added) to remapped
    }

    /** A stored page with a preset id may be a user-modified preset; only an unchanged one is code-owned. */
    fun isUnmodifiedPreset(page: KeyboardPageDefinition): Boolean = preset(page.id) == page

    /**
     * Adds the built-in pages a catalog saved before they existed is missing. A stored page with the
     * same id stays untouched (it may be the user's edited preset); missing ones append in [all]
     * order, so an upgraded catalog can still reference a page shipped later.
     */
    fun mergingInto(pages: List<KeyboardPageDefinition>): List<KeyboardPageDefinition> {
        val merged = pages.toMutableList()
        val known = pages.map { it.id }.toMutableSet()
        for (preset in all) {
            if (known.add(preset.id)) merged.add(preset)
        }
        return merged
    }

    private fun buildKeypadPreset(): KeyboardPageDefinition {
        val materialised =
            numericAutomatic.materialized(numericForm = KeyboardNumericForm.PHONE_DIGIT_PAD)
        return materialised.copy(id = NUMERIC_KEYPAD_ID, name = "数字盘 123")
    }

    /** English letter rows: fixed letters with their long-press alternates and corner hints. */
    private fun letterRows(values: List<List<String>>): List<LayoutRow> =
        values.map { row ->
            LayoutRow(
                row.map { value ->
                    val options =
                        AlternateKeyPolicy
                            .options(
                                AlternateKeyPolicy.Request(
                                    keyLabel = value,
                                    secondaryLiterals = AlternateKeyPolicy.defaultLetterLiterals(value),
                                    page = AlternateKeyPolicy.Page.LETTERS,
                                    language = AlternateKeyPolicy.Language.ENGLISH,
                                    usesAsciiPunctuation = true,
                                ),
                            ).map { option -> LayoutOption(option.label, LayoutAction.literal(option.value)) }
                    LayoutKey(
                        label = value,
                        secondaryLabel = options.firstOrNull()?.label,
                        action = LayoutAction.literal(value),
                        longPress = options.ifEmpty { null },
                    )
                },
            )
        }

    /**
     * 123 / row-shaped symbol marks pair the two existing key tables by slot. The stored action
     * keeps the Chinese branch in value and the ASCII branch in asciiValue; the label and the
     * long-press list follow the branch the page opens in, so a copy shows a real symbol switch
     * without translating anything for an ordinary author literal.
     */
    private fun markRows(
        marks: List<List<BuiltinLayouts.BilingualPageMark>>,
        kind: KeyboardPageKind,
        ascii: Boolean,
    ): List<LayoutRow> {
        val policyPage =
            if (kind == KeyboardPageKind.NUMERIC) {
                AlternateKeyPolicy.Page.NUMERIC
            } else {
                AlternateKeyPolicy.Page.SYMBOLS
            }
        val visible = marks.flatten().map { if (ascii) it.ascii else it.chinese }.toSet()
        return marks.map { row ->
            LayoutRow(
                row.map { mark ->
                    val initial = if (ascii) mark.ascii else mark.chinese
                    val options =
                        AlternateKeyPolicy
                            .options(
                                AlternateKeyPolicy.Request(
                                    keyLabel = initial,
                                    page = policyPage,
                                    language =
                                        if (ascii) {
                                            AlternateKeyPolicy.Language.ENGLISH
                                        } else {
                                            AlternateKeyPolicy.Language.CHINESE
                                        },
                                    usesAsciiPunctuation = ascii,
                                    visibleKeys = visible,
                                ),
                            ).map { option -> LayoutOption(option.label, LayoutAction.literal(option.value)) }
                    LayoutKey(
                        label = initial,
                        secondaryLabel = options.firstOrNull()?.label,
                        action =
                            LayoutAction.literal(
                                mark.chinese,
                                asciiValue = if (mark.ascii == mark.chinese) null else mark.ascii,
                            ),
                        longPress = options.ifEmpty { null },
                        titleMode = LayoutKeyTitleMode.AUTOMATIC,
                    )
                },
            )
        }
    }

    /** The literal text one alternate policy option inserts, whichever payload kind it carries. */
    private val AlternateKeyPolicy.Option.value: String
        get() =
            when (val payload = payload) {
                is AlternateKeyPolicy.Payload.Code -> payload.value
                is AlternateKeyPolicy.Payload.Literal -> payload.value
            }
}

package app.songyan.ime.layout

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Which page slot of a keyboard pairing a page fills. */
@Serializable
enum class KeyboardPageKind {
    @SerialName("numeric")
    NUMERIC,

    @SerialName("symbols")
    SYMBOLS,

    @SerialName("english")
    ENGLISH,
}

/** Built-in symbol browsers keep their existing categories and recent items. */
@Serializable
enum class KeyboardSymbolPresentation {
    @SerialName("automatic")
    AUTOMATIC,

    @SerialName("rows")
    ROWS,

    @SerialName("categorized")
    CATEGORIZED,
}

/**
 * How a 123 page draws. [KeyboardNumericPresentation.AUTOMATIC] follows the live keyboard: the
 * running language picks the marks and the running main layout picks the shape, so the page carries
 * no rows of its own. [KeyboardNumericPresentation.KEYPAD] is an authored digit pad usable with any
 * main layout.
 */
@Serializable
enum class KeyboardNumericPresentation {
    @SerialName("automatic")
    AUTOMATIC,

    @SerialName("keypad")
    KEYPAD,
}

/** Punctuation width of the automatic 123 page, taken from the live input state. */
@Serializable
enum class KeyboardNumericMarks {
    @SerialName("chinese")
    CHINESE,

    @SerialName("ascii")
    ASCII,
}

/** The built-in 123 shape an automatic page resolves to. */
enum class KeyboardNumericForm {
    /** The ten-column row page every non-keypad layout shows. */
    ROWS,

    /** The running phone keypad's digit pad: 1-9 in the 3x3 grid, 0 in the bottom row. */
    PHONE_DIGIT_PAD,
}

/** What the key area draws for the page a keyboard pairing selects. */
sealed class KeyboardPageResolution {
    /** The page carries its own rows or categories. */
    data class Authored(
        override val page: KeyboardPageDefinition,
    ) : KeyboardPageResolution()

    /** The 123 page follows the live keyboard; the built-in page area draws that form with those marks. */
    data class AutomaticNumeric(
        val form: KeyboardNumericForm,
        val marks: KeyboardNumericMarks,
    ) : KeyboardPageResolution()

    /** The symbol page follows the configured presentation; the built-in symbol page draws it. */
    data object AutomaticSymbolPage : KeyboardPageResolution()

    /** The pairing has no page for this kind, so the built-in page area keeps drawing it. */
    data object BuiltIn : KeyboardPageResolution()

    /** The page object to render, or null when the built-in page area owns this page. */
    open val page: KeyboardPageDefinition?
        get() = null
}

/**
 * The live facts an automatic page resolves against. Both come from the running keyboard, never from
 * the page: punctuation width and the main layout's keypad shape.
 */
data class KeyboardPageResolutionInput(
    val usesAsciiPunctuation: Boolean,
    val mainLayoutIsPhoneKeypad: Boolean,
)

/** Resolves the page a pairing selects into what the key area (or a preview) draws. */
object KeyboardPageResolver {
    /**
     * The single resolution rule. A null page means the pairing has no page for this kind; such a
     * pairing keeps the built-in page, exactly as it did before pairings existed.
     */
    fun resolve(
        page: KeyboardPageDefinition?,
        kind: KeyboardPageKind,
        input: KeyboardPageResolutionInput,
    ): KeyboardPageResolution {
        if (page == null || page.kind != kind) return builtIn(kind, input)
        if (page.numericPresentation == KeyboardNumericPresentation.AUTOMATIC) {
            return KeyboardPageResolution.AutomaticNumeric(numericForm(input), marks(input))
        }
        if (page.symbolPresentation == KeyboardSymbolPresentation.AUTOMATIC) {
            return KeyboardPageResolution.AutomaticSymbolPage
        }
        return KeyboardPageResolution.Authored(page)
    }

    /** The built-in 123 form for the running main layout. */
    fun numericForm(input: KeyboardPageResolutionInput): KeyboardNumericForm =
        if (input.mainLayoutIsPhoneKeypad) KeyboardNumericForm.PHONE_DIGIT_PAD else KeyboardNumericForm.ROWS

    /** The built-in 123 marks for the running input state. */
    fun marks(input: KeyboardPageResolutionInput): KeyboardNumericMarks =
        if (input.usesAsciiPunctuation) KeyboardNumericMarks.ASCII else KeyboardNumericMarks.CHINESE

    /**
     * Whether the 123 page a letter page opens has its own way to the symbol page. The built-in row
     * page (and an authored row page drawn with native controls) opens the third row with the
     * switch; a phone digit pad has none; other authored pages only if they carry a symbol switch.
     * [page] is the pairing's 123 page as the keyboard would draw it, null for the built-in one.
     */
    fun numericPageReachesSymbols(
        page: KeyboardPageDefinition?,
        mainLayoutIsPhoneKeypad: Boolean,
    ): Boolean {
        if (page == null ||
            page.kind != KeyboardPageKind.NUMERIC ||
            page.numericPresentation == KeyboardNumericPresentation.AUTOMATIC
        ) {
            return !mainLayoutIsPhoneKeypad
        }
        if (page.chrome == null) return true
        return page.allKeys.any { it.action.pageSwitchTarget == LayoutPageTarget.SYMBOLS }
    }

    private fun builtIn(
        kind: KeyboardPageKind,
        input: KeyboardPageResolutionInput,
    ): KeyboardPageResolution =
        when (kind) {
            KeyboardPageKind.NUMERIC ->
                KeyboardPageResolution.AutomaticNumeric(numericForm(input), marks(input))
            KeyboardPageKind.SYMBOLS -> KeyboardPageResolution.AutomaticSymbolPage
            KeyboardPageKind.ENGLISH -> KeyboardPageResolution.BuiltIn
        }
}

/**
 * A reusable page independent of the input method and main layout. Pure data: it never reads or
 * writes files; [LayoutStore] owns persistence.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class KeyboardPageDefinition(
    val id: String,
    val name: String,
    val kind: KeyboardPageKind,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val rows: List<LayoutRow> = emptyList(),
    val split: LayoutSplitConfiguration? = null,
    val symbolPresentation: KeyboardSymbolPresentation? = null,
    /** Set on the 123 page that follows the live keyboard. */
    val numericPresentation: KeyboardNumericPresentation? = null,
    val categories: List<KeyboardPageCategory>? = null,
    /** Authored function area. Null keeps the exact legacy synthesis; non-nil is drawn as data only. */
    val chrome: KeyboardPageChrome? = null,
    /**
     * The symbol branch this page opens in when the panel has no explicit override. Null follows
     * the live keyboard; authored pages keep null unless the copy chose a branch, so an old page is
     * never guessed into one.
     */
    val defaultSymbolMarks: KeyboardNumericMarks? = null,
) {
    val usesSymbolBrowser: Boolean
        get() =
            symbolPresentation == KeyboardSymbolPresentation.AUTOMATIC ||
                symbolPresentation == KeyboardSymbolPresentation.CATEGORIZED

    /** The runtime draws this page from the live keyboard instead of these rows. */
    val resolvesAtRuntime: Boolean
        get() =
            numericPresentation == KeyboardNumericPresentation.AUTOMATIC ||
                symbolPresentation == KeyboardSymbolPresentation.AUTOMATIC

    /** An authored digit pad page. */
    val isKeypad: Boolean get() = numericPresentation == KeyboardNumericPresentation.KEYPAD

    /** The same geometry, action and resource validation as main layouts. */
    val layoutDefinition: LayoutDefinition
        get() =
            LayoutDefinition(
                id = id,
                name = name,
                input = LayoutInput(builtinLayout = LayoutInput.Builtin.QWERTY26),
                rows = rows,
                split = split,
            )

    /** Field-preserving edits; editors must not rebuild a page and drop unrelated fields. */
    fun withName(name: String): KeyboardPageDefinition = copy(name = name)

    fun withRows(rows: List<LayoutRow>): KeyboardPageDefinition = copy(rows = rows)

    fun withChrome(chrome: KeyboardPageChrome?): KeyboardPageDefinition = copy(chrome = chrome)

    fun withSplit(split: LayoutSplitConfiguration?): KeyboardPageDefinition = copy(split = split)

    fun withCategories(categories: List<KeyboardPageCategory>?): KeyboardPageDefinition = copy(categories = categories)

    fun withDefaultSymbolMarks(marks: KeyboardNumericMarks?): KeyboardPageDefinition = copy(defaultSymbolMarks = marks)

    @Throws(LayoutException::class)
    fun validate() {
        if (split?.halves != null) throw LayoutException.Invalid(LayoutInvalidCode.SPLIT_HALVES_ONLY_ON_LAYOUT)
        val numeric = numericPresentation
        if (numeric != null && kind != KeyboardPageKind.NUMERIC) {
            throw LayoutException.Invalid(LayoutInvalidCode.PAGE_NUMERIC_PRESENTATION_ONLY_ON_NUMERIC_PAGE)
        }
        if (numeric == KeyboardNumericPresentation.AUTOMATIC) {
            if (rows.isNotEmpty() ||
                categories != null ||
                symbolPresentation != null ||
                chrome != null
            ) {
                throw LayoutException.Invalid(LayoutInvalidCode.PAGE_AUTOMATIC_NUMERIC_TAKES_NO_CONTENT)
            }
            validateIdentity()
            split?.validate(emptyList())
            LayoutResourceBudget.validate(this)
            return
        }
        if (kind != KeyboardPageKind.SYMBOLS && (symbolPresentation != null || categories != null)) {
            throw LayoutException.Invalid(LayoutInvalidCode.PAGE_CATEGORIES_ONLY_ON_SYMBOL_PAGE)
        }
        chrome?.validate()
        if (symbolPresentation == KeyboardSymbolPresentation.CATEGORIZED) {
            // The category grid is navigation, so the leading rail has no fixed keys to show.
            if (chrome != null && chrome.leading.isNotEmpty()) {
                throw LayoutException.Invalid(LayoutInvalidCode.PAGE_CATEGORY_PAGE_TAKES_NO_LEADING_RAIL)
            }
            LayoutResourceBudget.validate(this)
            // Existing catalogs only had the non-empty check, so the length stays unvalidated here
            // to keep an old catalog readable.
            if (id.isBlank() || name.isBlank()) {
                throw LayoutException.Invalid(LayoutInvalidCode.PAGE_ID_AND_NAME_REQUIRED)
            }
            val categories = categories
            if (categories != null) {
                if (categories.isEmpty()) {
                    throw LayoutException.Invalid(LayoutInvalidCode.PAGE_CATEGORIES_REQUIRED)
                }
                if (categories.map { it.id }.toSet().size != categories.size) {
                    throw LayoutException.Invalid(LayoutInvalidCode.PAGE_CATEGORY_IDS_DUPLICATED)
                }
                for (category in categories) category.validate()
            }
            split?.validate(splitRowKeyCounts())
            return
        }
        if (categories != null) {
            throw LayoutException.Invalid(LayoutInvalidCode.PAGE_CATEGORIES_NEED_CATEGORY_PRESENTATION)
        }
        val chrome = chrome
        if (chrome != null) {
            // Authored pages keep deliberate structural emptiness: an empty key area, footer or rail
            // is a user decision, not a state to repair.
            validateIdentity()
            split?.validate(splitRowKeyCounts())
            LayoutResourceBudget.validate(this)
            for (key in rows.flatMap { it.keys }) validateKey(key)
            return
        }
        if (numeric != null) {
            throw LayoutException.Invalid(LayoutInvalidCode.PAGE_KEYPAD_REQUIRES_ROWS)
        }
        layoutDefinition.validate()
        for (key in rows.flatMap { it.keys }) validateKey(key)
    }

    private fun validateIdentity() {
        if (id.isBlank() || name.isBlank()) {
            throw LayoutException.Invalid(LayoutInvalidCode.PAGE_ID_AND_NAME_REQUIRED)
        }
        if (id.encodeToByteArray().size > 256 || name.encodeToByteArray().size > 256) {
            throw LayoutException.Invalid(LayoutInvalidCode.PAGE_ID_AND_NAME_REQUIRED)
        }
    }

    /**
     * Split row counts including the footer row. The footer cut lives at rows.size, so a materialized
     * page can keep the legacy footer split without a bogus row.
     */
    private fun splitRowKeyCounts(): List<Int> {
        val counts = rows.map { it.keys.size }.toMutableList()
        val bottom = chrome?.bottom
        if (bottom != null && bottom.isNotEmpty()) counts.add(bottom.size)
        return counts
    }

    // MARK: - Materialized drafts and copies

    /**
     * The editable form of this page: legacy synthesis becomes real data. It is idempotent, so
     * opening the editor twice can never duplicate Shift/Delete/footer/rails or resurrect a key the
     * user deleted.
     *
     * The host resolves global categories before calling this; materialization itself is pure data
     * and never reads disk. Name and id are preserved.
     */
    fun materialized(
        numericForm: KeyboardNumericForm = KeyboardNumericForm.ROWS,
        numericMarks: KeyboardNumericMarks = KeyboardNumericMarks.CHINESE,
    ): KeyboardPageDefinition {
        if (chrome != null) return this
        var page = this
        when (numericPresentation) {
            KeyboardNumericPresentation.AUTOMATIC -> {
                if (numericForm == KeyboardNumericForm.PHONE_DIGIT_PAD) {
                    page =
                        page.copy(
                            rows = KEYPAD_DIGIT_ROWS,
                            numericPresentation = KeyboardNumericPresentation.KEYPAD,
                            chrome = keypadChrome(numericMarks),
                            defaultSymbolMarks = numericMarks,
                        )
                } else {
                    val preset = KeyboardPagePresets.numericRowsPage(numericMarks == KeyboardNumericMarks.ASCII)
                    val inserted =
                        rowsInsertingLegacyControls(preset.rows, KeyboardPageKind.NUMERIC, page.split)
                    page =
                        page.copy(
                            rows = inserted.rows,
                            split = inserted.split,
                            numericPresentation = null,
                            // The legacy row page got its third-row switch and Delete from the
                            // renderer; materialized data carries them explicitly.
                            chrome = rowsChrome(KeyboardPageKind.NUMERIC),
                            defaultSymbolMarks = numericMarks,
                        )
                }
            }
            KeyboardNumericPresentation.KEYPAD -> {
                page =
                    page.copy(
                        rows = if (page.rows.isEmpty()) KEYPAD_DIGIT_ROWS else page.rows,
                        chrome = keypadChrome(numericMarks),
                        defaultSymbolMarks = numericMarks,
                    )
            }
            null -> {
                when {
                    symbolPresentation == KeyboardSymbolPresentation.AUTOMATIC -> {
                        // An untouched automatic symbol page still carries the built-in canonical
                        // rows, so the copy can take the initial-language snapshot. Author-modified
                        // rows are never replaced by a whole preset.
                        val canonical =
                            page.rows.isEmpty() ||
                                page.rows == KeyboardPagePresets.symbolRowsPage(ascii = false).rows ||
                                page.rows == KeyboardPagePresets.symbolRowsPage(ascii = true).rows
                        val rows =
                            if (canonical) {
                                KeyboardPagePresets.symbolRowsPage(numericMarks == KeyboardNumericMarks.ASCII).rows
                            } else {
                                page.rows
                            }
                        val inserted =
                            rowsInsertingLegacyControls(rows, KeyboardPageKind.SYMBOLS, page.split)
                        page =
                            page.copy(
                                symbolPresentation = KeyboardSymbolPresentation.ROWS,
                                categories = null,
                                rows = inserted.rows,
                                split = inserted.split,
                                chrome = rowsChrome(KeyboardPageKind.SYMBOLS),
                                defaultSymbolMarks = numericMarks,
                            )
                    }
                    symbolPresentation == KeyboardSymbolPresentation.CATEGORIZED || page.categories != null -> {
                        // Legacy browser rows stay readable and editable; materializing must not
                        // delete stored content, even though the category grid is what renders.
                        page =
                            page.copy(
                                symbolPresentation = KeyboardSymbolPresentation.CATEGORIZED,
                                chrome = categoryChrome(),
                            )
                    }
                    else -> {
                        val inserted =
                            rowsInsertingLegacyControls(page.rows, page.kind, page.split)
                        page =
                            page.copy(
                                rows = inserted.rows,
                                split = inserted.split,
                                chrome = rowsChrome(page.kind),
                            )
                    }
                }
            }
        }
        return page
    }

    /**
     * A copy is editable authored content, so an automatic page materializes into the built-in rows
     * it stands for instead of keeping the automatic flag. The caller supplies the new identity so
     * this type stays platform-free; the settings UI hands it a generated id and a localized name.
     * The marks only choose which branch's labels the rows start with unless [numericMarks] is given.
     */
    fun duplicated(
        newId: String,
        name: String,
        numericMarks: KeyboardNumericMarks? = null,
        numericForm: KeyboardNumericForm = KeyboardNumericForm.ROWS,
    ): KeyboardPageDefinition {
        require(newId.isNotBlank()) { "duplicated page needs a new id" }
        val materialised =
            materialized(
                numericForm = numericForm,
                numericMarks =
                    numericMarks ?: KeyboardNumericMarks.CHINESE,
            )
        // A page that followed the keyboard keeps following it; only a caller's explicit choice pins
        // the branch the copy opens in, so a copy never freezes the Chinese marks by accident.
        val opening = if (resolvesAtRuntime) numericMarks else materialised.defaultSymbolMarks
        return materialised.copy(id = newId, name = name, defaultSymbolMarks = opening)
    }

    private data class InsertedRows(
        val rows: List<LayoutRow>,
        val split: LayoutSplitConfiguration?,
    )

    /**
     * Legacy rendering synthesized the leading control (Shift on ABC, the 123/symbol switch
     * elsewhere) and Delete around the last row. Materializing writes them as keys instead; a stored
     * split cut on that row moves by one because the leading key is inserted before the characters,
     * while an appended Delete never moves a cut.
     */
    private fun rowsInsertingLegacyControls(
        rows: List<LayoutRow>,
        kind: KeyboardPageKind,
        split: LayoutSplitConfiguration?,
    ): InsertedRows {
        if (rows.isEmpty()) return InsertedRows(rows, split)
        val declared = rows.flatMap { it.keys }
        val leadingType = if (kind == KeyboardPageKind.ENGLISH) LayoutActionKind.SHIFT else LayoutActionKind.PAGE_SWITCH
        val declaresLeading = declared.any { it.action.type == leadingType }
        val declaresDelete = declared.any { it.action.type == LayoutActionKind.BACKSPACE }
        var result = rows
        var leadingRow: Int? = null
        val lastIndex = result.size - 1
        if (!declaresLeading) {
            val keys = result[lastIndex].keys.toMutableList()
            keys.add(0, if (kind == KeyboardPageKind.ENGLISH) SHIFT_KEY else markPageSwitchKey(kind))
            result = result.toMutableList().also { it[lastIndex] = LayoutRow(keys) }
            leadingRow = lastIndex
        }
        if (!declaresDelete) {
            val keys = result[lastIndex].keys.toMutableList()
            keys.add(DELETE_KEY)
            result = result.toMutableList().also { it[lastIndex] = LayoutRow(keys) }
        }
        var updated = split
        if (leadingRow != null && split != null && split.rowBreaks.size > leadingRow) {
            val breaks = split.rowBreaks.toMutableList()
            breaks[leadingRow] = minOf(maxOf(1, result[leadingRow].keys.size - 1), breaks[leadingRow] + 1)
            updated = split.copy(rowBreaks = breaks)
        }
        return InsertedRows(result, updated)
    }

    companion object {
        private val SHIFT_KEY =
            LayoutKey(
                label = "⇧",
                width = 1.5,
                action = LayoutAction(LayoutActionKind.SHIFT),
                titleMode = LayoutKeyTitleMode.AUTOMATIC,
            )

        private val DELETE_KEY =
            LayoutKey(
                label = "⌫",
                width = 1.5,
                action = LayoutAction(LayoutActionKind.BACKSPACE),
                titleMode = LayoutKeyTitleMode.AUTOMATIC,
            )

        /** The 123 / symbol page's third-row switch in the Shift position, opening the other page. */
        private fun markPageSwitchKey(kind: KeyboardPageKind): LayoutKey {
            val target = if (kind == KeyboardPageKind.NUMERIC) LayoutPageTarget.SYMBOLS else LayoutPageTarget.NUMERIC
            return pageSwitchKey(target).copy(width = 1.5)
        }

        /**
         * The comma in the utility position of every row page; long press holds the other sentence
         * marks. Each mark keeps its Chinese/ASCII pair so the key follows the symbol branch.
         */
        private val COMMA_KEY: LayoutKey =
            LayoutKey(
                label = ",",
                action = LayoutAction.literal("，", asciiValue = ","),
                longPress =
                    listOf("。" to ".", "？" to "?", "！" to "!", "、" to "'", "…" to "\"").map { (chinese, ascii) ->
                        LayoutOption(ascii, LayoutAction.literal(chinese, asciiValue = ascii))
                    },
                titleMode = LayoutKeyTitleMode.AUTOMATIC,
            )

        private fun functionKey(
            label: String,
            action: LayoutAction,
            width: Double? = null,
        ): LayoutKey = LayoutKey(label, width = width, action = action, titleMode = LayoutKeyTitleMode.AUTOMATIC)

        private val LANGUAGE_SWITCH_KEY: LayoutKey = functionKey("中英", LayoutAction.languageSwitch())

        private val RETURN_TO_LETTERS_LANGUAGE_KEY: LayoutKey =
            functionKey("返回", LayoutAction.languageSwitch(LayoutLanguageSwitchBehavior.LETTERS))

        private val GLOBE_KEY: LayoutKey = functionKey("🌐", LayoutAction(LayoutActionKind.GLOBE))

        private val SPACE_KEY: LayoutKey =
            functionKey("空格", LayoutAction(LayoutActionKind.SPACE), width = 4.0)

        private val ENTER_KEY: LayoutKey = functionKey("⏎", LayoutAction(LayoutActionKind.ENTER))

        private val SYMBOL_LOCK_KEY: LayoutKey =
            functionKey("锁定", LayoutAction(LayoutActionKind.SYMBOL_LOCK))

        private fun pageSwitchKey(target: LayoutPageTarget): LayoutKey {
            // The letters switch is a return, never an ABC/English hint.
            val title =
                when (target) {
                    LayoutPageTarget.LETTERS -> "返回"
                    LayoutPageTarget.NUMERIC -> "123"
                    LayoutPageTarget.SYMBOLS -> "符号"
                }
            return functionKey(title, LayoutAction.pageSwitch(target))
        }

        /**
         * The digit pad's three leading rail keys are the real 123 rail's first three entries, with
         * their explicit Chinese/ASCII branches; the stored label follows the copy's initial branch.
         */
        private fun keypadLeadingKeys(marks: KeyboardNumericMarks): List<LayoutKey> {
            val chinese = BuiltinLayouts.phoneNumericRailValues(ascii = false).take(3)
            val ascii = BuiltinLayouts.phoneNumericRailValues(ascii = true).take(3)
            return chinese.zip(ascii).map { (chineseMark, asciiMark) ->
                LayoutKey(
                    label = if (marks == KeyboardNumericMarks.ASCII) asciiMark else chineseMark,
                    action =
                        LayoutAction.literal(
                            chineseMark,
                            asciiValue = if (chineseMark == asciiMark) null else asciiMark,
                        ),
                    titleMode = LayoutKeyTitleMode.AUTOMATIC,
                )
            }
        }

        private fun railKey(
            label: String,
            action: LayoutAction,
            span: Double,
        ): LayoutKey = LayoutKey(label, width = span, action = action, titleMode = LayoutKeyTitleMode.AUTOMATIC)

        /**
         * Default slots stay physically stable — globe first, the switches at the positions the
         * control row already budgets — so materializing a page never re-tunes the bottom row.
         */
        private fun rowsChrome(kind: KeyboardPageKind): KeyboardPageChrome =
            when (kind) {
                KeyboardPageKind.ENGLISH ->
                    // globe, 123, 中英, space, comma, Return
                    KeyboardPageChrome(
                        bottom =
                            listOf(
                                GLOBE_KEY,
                                pageSwitchKey(LayoutPageTarget.NUMERIC),
                                LANGUAGE_SWITCH_KEY,
                                SPACE_KEY,
                                COMMA_KEY,
                                ENTER_KEY,
                            ),
                    )
                KeyboardPageKind.NUMERIC, KeyboardPageKind.SYMBOLS ->
                    // globe, 返回, 中英, space, comma, Return; the 123/symbol switch opens the third row.
                    KeyboardPageChrome(
                        bottom =
                            listOf(
                                GLOBE_KEY,
                                pageSwitchKey(LayoutPageTarget.LETTERS),
                                LANGUAGE_SWITCH_KEY,
                                SPACE_KEY,
                                COMMA_KEY,
                                ENTER_KEY,
                            ),
                    )
            }

        /**
         * The categorized symbol page keeps its category navigation as metadata; the authored chrome
         * only carries the footer and the full-height editing rail.
         */
        private fun categoryChrome(): KeyboardPageChrome =
            KeyboardPageChrome(
                bottom =
                    listOf(
                        // The categorized default return keeps the native language/tools menu.
                        GLOBE_KEY,
                        RETURN_TO_LETTERS_LANGUAGE_KEY,
                        pageSwitchKey(LayoutPageTarget.NUMERIC),
                        SPACE_KEY,
                        SYMBOL_LOCK_KEY,
                    ),
                trailing =
                    listOf(
                        railKey("⌫", LayoutAction(LayoutActionKind.BACKSPACE), span = 1.0),
                        railKey("重输", LayoutAction(LayoutActionKind.CLEAR_INPUT), span = 1.0),
                        railKey("⏎", LayoutAction(LayoutActionKind.ENTER), span = 2.0),
                    ),
                trailingSpansBottom = true,
            )

        private val KEYPAD_DIGIT_ROWS: List<LayoutRow> =
            listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9)).map { row ->
                LayoutRow(
                    row.map { digit ->
                        val text = digit.toString()
                        LayoutKey(label = text, action = LayoutAction.literal(text))
                    },
                )
            }

        /**
         * The digit pad on the nine-key letter shell's grid: three marks beside the digit rows;
         * Delete, 0 and the comma down the right; and the bottom row 返回, 中英, space, 符号 and
         * Return under the rail, the three digit columns and the right column (the input-method
         * switch, when shown, first).
         */
        internal fun keypadChrome(marks: KeyboardNumericMarks): KeyboardPageChrome =
            KeyboardPageChrome(
                bottom =
                    listOf(
                        GLOBE_KEY,
                        pageSwitchKey(LayoutPageTarget.LETTERS),
                        LANGUAGE_SWITCH_KEY,
                        // One digit column wide, not the control row's wide space bar.
                        functionKey("空格", LayoutAction(LayoutActionKind.SPACE)),
                        // A letter layout paired with this pad has no other way to the symbol page.
                        pageSwitchKey(LayoutPageTarget.SYMBOLS),
                        ENTER_KEY,
                    ),
                leading = keypadLeadingKeys(marks),
                trailing =
                    listOf(
                        railKey("⌫", LayoutAction(LayoutActionKind.BACKSPACE), span = 1.0),
                        railKey("0", LayoutAction.literal("0"), span = 1.0),
                        railKey(
                            if (marks == KeyboardNumericMarks.ASCII) "," else "，",
                            LayoutAction.literal("，", asciiValue = ","),
                            span = 1.0,
                        ),
                    ),
            )

        /**
         * The digit pad as it shipped before it moved onto the letter shell's grid, so a stored copy
         * of the untouched preset can be told apart from a page the user edited.
         */
        internal fun previousKeypadChrome(marks: KeyboardNumericMarks): KeyboardPageChrome =
            KeyboardPageChrome(
                bottom =
                    listOf(
                        GLOBE_KEY,
                        pageSwitchKey(LayoutPageTarget.LETTERS),
                        LANGUAGE_SWITCH_KEY,
                        SPACE_KEY,
                        LayoutKey(label = "0", action = LayoutAction.literal("0")),
                    ),
                leading =
                    keypadLeadingKeys(marks) +
                        railKey(
                            pageSwitchKey(LayoutPageTarget.SYMBOLS).label,
                            LayoutAction.pageSwitch(LayoutPageTarget.SYMBOLS),
                            span = 1.0,
                        ),
                trailing =
                    listOf(
                        railKey("⌫", LayoutAction(LayoutActionKind.BACKSPACE), span = 1.0),
                        railKey("重输", LayoutAction(LayoutActionKind.CLEAR_INPUT), span = 1.0),
                        railKey("⏎", LayoutAction(LayoutActionKind.ENTER), span = 2.0),
                    ),
                trailingSpansBottom = true,
            )

        /**
         * Page keys may insert text or run a keyboard command, but never feed the engine: a page is
         * display content and its keys have no spelling meaning.
         */
        @Throws(LayoutException::class)
        internal fun validateKey(key: LayoutKey) {
            if (key.label.isEmpty() || !key.widthWeight.isFinite() || key.widthWeight <= 0.0) {
                throw LayoutException.Invalid(
                    LayoutInvalidCode.KEY_REQUIRES_LABEL_AND_POSITIVE_FINITE_WIDTH,
                    key.label,
                )
            }
            if (key.action.type == LayoutActionKind.CODE ||
                (key.longPress ?: emptyList()).any { it.action.type == LayoutActionKind.CODE }
            ) {
                throw LayoutException.Invalid(LayoutInvalidCode.PAGE_KEY_MUST_NOT_SEND_A_CODE, key.label)
            }
            key.action.validate()
            for (option in key.longPress ?: emptyList()) {
                if (option.label.isEmpty()) {
                    throw LayoutException.Invalid(LayoutInvalidCode.LONG_PRESS_REQUIRES_LABEL)
                }
                option.action.validate()
            }
        }
    }
}

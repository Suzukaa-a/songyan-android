package app.songyan.ime.layout

import app.songyan.ime.keyboard.engine.InputLayoutPolicy

/**
 * Independently authored layout data, also available as editable JSON in resources/layouts.
 *
 * The JSON examples and these declarations must stay in step: the examples are the copy source for
 * authors, so [BuiltinLayoutsTest] compares them field for field.
 */
object BuiltinLayouts {
    val qwerty26: LayoutDefinition
        get() =
            LayoutDefinition(
                id = "qwerty26",
                name = "26 键",
                input = LayoutInput(builtinLayout = LayoutInput.Builtin.QWERTY26),
                rows = alphabetRows() + listOf(controls),
            )

    val merged14: LayoutDefinition
        get() =
            LayoutDefinition(
                id = "merged14",
                name = "14 键",
                input = LayoutInput(builtinLayout = LayoutInput.Builtin.MERGED14),
                rows =
                    mergedRows(
                        listOf(
                            listOf("qw", "er", "ty", "ui", "op"),
                            listOf("as", "df", "gh", "jk", "l"),
                            listOf("zx", "cv", "bn", "m"),
                        ),
                    ) + listOf(controls),
                note = "两个字母合用一个键，轻点输入。输入方案支持时，向左或向右滑动可只输入左边或右边的字母。",
                // Wide surfaces split by default so both thumbs reach the merged pairs; a saved
                // split, including an explicit off, stays authoritative.
                split = LayoutSplitConfiguration(activation = LayoutSplitConfiguration.Activation.AUTOMATIC),
            )

    /**
     * Every key must be easy to hit, single letters included. Two-unit pairs would leave the singles
     * as the narrowest caps on the pad (about 38 pt on a 420 pt phone), so pairs give width back:
     * 1.7 units against 1.3, caps about 1.33 : 1. Pairs stay wider because one cap carries two
     * letters and the sideways swipe. All upper-row singles and pairs share one width each, so row 2
     * (9.0 units) sits centred under row 1 (10.3) half a single key in from each side.
     */
    val merged18: LayoutDefinition
        get() =
            LayoutDefinition(
                id = "merged18",
                name = "18 键",
                input = LayoutInput(builtinLayout = LayoutInput.Builtin.MERGED18),
                rows =
                    mergedRows(
                        listOf(
                            listOf("q", "we", "rt", "y", "u", "io", "p"),
                            listOf("a", "sd", "fg", "h", "jk", "l"),
                            listOf("z", "xc", "v", "bn", "m"),
                        ),
                        singleWidth = 1.3,
                        pairWidth = 1.7,
                    ) + listOf(controls),
                note = "部分键由两个字母合用，轻点输入。输入方案支持时，向左或向右滑动可只输入左边或右边的字母。",
                split = LayoutSplitConfiguration(activation = LayoutSplitConfiguration.Activation.AUTOMATIC),
            )

    /**
     * The Dachen zhuyin pad: four rows of bopomofo caps, each sending the ASCII code the
     * rime-bopomofo speller declares. Digits and punctuation are part of that alphabet, so those
     * caps send codes too; a literal cap would commit text instead of spelling a syllable.
     *
     * It is a physical layout of one scheme family rather than a shipped key shape, so it keeps the
     * 26-key identity transport: pairings and the family rules already speak that wire. The board has
     * no case, so no Shift; Delete closes the fourth row so it lines up with the first. The last row
     * is the 26-key stand-in, so the keyboard draws the same footer as every other board.
     */
    val zhuyin41: LayoutDefinition
        get() =
            LayoutDefinition(
                id = InputLayoutPolicy.ZHUYIN_LAYOUT,
                name = "注音 41 键",
                input = LayoutInput(builtinLayout = LayoutInput.Builtin.QWERTY26),
                rows =
                    listOf(
                        codeRow("ㄅㄉˇˋㄓˊ˙ㄚㄞㄢㄦ", "1234567890-"),
                        codeRow("ㄆㄊㄍㄐㄔㄗㄧㄛㄟㄣ", "qwertyuiop"),
                        codeRow("ㄇㄋㄎㄑㄕㄘㄨㄜㄠㄤ", "asdfghjkl;"),
                        LayoutRow(
                            codeRow("ㄈㄌㄏㄒㄖㄙㄩㄝㄡㄥ", "zxcvbnm,./").keys +
                                automaticKey("⌫", LayoutAction(LayoutActionKind.BACKSPACE)),
                        ),
                    ) + listOf(controls),
            )

    /**
     * The cangjie pad: the 26-key arrangement keeps its positions, the main cap shows the radical and
     * the corner badge shows the letter the engine actually reads. A radical cap is a face, never a
     * literal, so tapping spells a code instead of committing a character. The last row is the 26-key
     * stand-in, so the keyboard draws the same footer as every other 26-key board.
     */
    val cangjie26: LayoutDefinition
        get() =
            LayoutDefinition(
                id = InputLayoutPolicy.CANGJIE_LAYOUT,
                name = "仓颉 26 键",
                input = LayoutInput(builtinLayout = LayoutInput.Builtin.QWERTY26),
                rows =
                    listOf(
                        radicalRow("手田水口廿卜山戈人心", "qwertyuiop"),
                        radicalRow("日尸木火土竹十大中", "asdfghjkl"),
                        radicalRow("重難金女月弓一", "zxcvbnm"),
                    ) + listOf(controls),
            )

    /**
     * The Japanese romaji board: the QWERTY 26-key letters with ー right of L. The board keeps the
     * 26-key encoding kind and is identified by its id, so no new kind and no new engine case come
     * with it. The fourth row is the 26-key stand-in: while it is unedited the keyboard draws its
     * own footer, which names the space, Return and language keys for the input language and puts
     * 、 and 。 on either side of the space bar.
     */
    val romaji26: LayoutDefinition
        get() =
            LayoutDefinition(
                id = InputLayoutPolicy.ROMAJI_LAYOUT,
                name = "罗马字 26 键",
                input = LayoutInput(builtinLayout = LayoutInput.Builtin.QWERTY26),
                rows = romajiRows() + listOf(controls),
            )

    /**
     * The Japanese twelve-key (kana12) board: the 4 x 3 kana grid between a leading rail
     * (composition caret left, 記号, あ/A, the system keyboard switch) and a trailing rail (delete,
     * space, Return spanning the lower two rows). The grid carries the tap key of each row only:
     * flick and repeated-tap targets belong to the kana input model, so a cap names its row and the
     * handler spells that row's kana into the engine. Because the fourth row types kana it is
     * content, not a control row, so neither the 26-key Shift/Delete chrome nor the automatic footer
     * replaces it; the leading rail's globe is the only keyboard switch, which keeps the four rows
     * intact.
     */
    val kana12: LayoutDefinition
        get() =
            LayoutDefinition(
                id = InputLayoutPolicy.KANA_LAYOUT,
                name = "假名 12 键",
                // The encoding kind stays the 26-key one: the board is a layout of its own,
                // identified by its id, so no new shape and no new engine case come with it.
                input = LayoutInput(builtinLayout = LayoutInput.Builtin.QWERTY26),
                rows = kana12Rows(),
                auxiliaryAreas = listOf(kana12LeadingRail(), kana12TrailingRail()),
            )

    val phone9: LayoutDefinition
        get() =
            LayoutDefinition(
                id = "phone9",
                name = "9 键",
                input = LayoutInput(builtinLayout = LayoutInput.Builtin.PHONE9),
                rows =
                    listOf(
                        // Corner badge 1 + long-press 1 (like 2-9), so editors show the key that runs.
                        LayoutRow(
                            listOf(
                                LayoutKey(
                                    label = "，。？",
                                    secondaryLabel = T9_DIGIT_ONE,
                                    action = LayoutAction.literal("，"),
                                    longPress = listOf(literalOption(T9_DIGIT_ONE)),
                                ),
                                phone("ABC", "2"),
                                phone("DEF", "3"),
                            ),
                        ),
                        LayoutRow(listOf(phone("GHI", "4"), phone("JKL", "5"), phone("MNO", "6"))),
                        LayoutRow(listOf(phone("PQRS", "7"), phone("TUV", "8"), phone("WXYZ", "9"))),
                        LayoutRow(
                            listOf(
                                LayoutKey("布局", action = LayoutAction(LayoutActionKind.MODE_SWITCH, "nextLayout")),
                                LayoutKey("空格", width = 4.0, action = LayoutAction(LayoutActionKind.SPACE)),
                                LayoutKey("🌐", action = LayoutAction(LayoutActionKind.GLOBE)),
                            ),
                        ),
                    ),
                auxiliaryAreas =
                    listOf(
                        LayoutAuxiliaryArea(
                            position = LayoutAuxiliaryArea.Position.TRAILING,
                            role = LayoutAuxiliaryArea.Role.EDITING,
                            keys =
                                listOf(
                                    LayoutKey("⌫", action = LayoutAction(LayoutActionKind.BACKSPACE)),
                                    LayoutKey("分词", action = LayoutAction.code("'")),
                                    LayoutKey("⏎", action = LayoutAction(LayoutActionKind.ENTER)),
                                ),
                        ),
                        LayoutAuxiliaryArea(
                            position = LayoutAuxiliaryArea.Position.LEADING,
                            role = LayoutAuxiliaryArea.Role.DISAMBIGUATION,
                        ),
                    ),
                note = PHONE9_NOTE,
            )

    /** Explicit behavior wins; older definitions retain their inferred first-key behavior. */
    fun usesT9QuickFirstKey(definition: LayoutDefinition): Boolean {
        if (!PhoneKeypadStructure.matches(definition)) return false
        return when (definition.t9FirstKeyBehavior) {
            T9FirstKeyBehavior.QUICK_SYMBOLS -> true
            T9FirstKeyBehavior.CUSTOM -> false
            null -> matchesPhone9PrimarySemantics(definition)
        }
    }

    /**
     * Primary-gesture identity of the bundled 9-key: the known input metadata and the three main rows'
     * key counts, widths and primary actions. Labels, corner hints, title modes and long-press menus,
     * and everything outside the three rows (control row, rails), are face metadata and never break
     * it, so the first key keeps cycling the quick symbols after such edits.
     */
    fun matchesPhone9PrimarySemantics(definition: LayoutDefinition): Boolean =
        definition.input == phone9.input && mainRowsMatch(definition, phone9)

    /**
     * The narrow visual identity iOS uses for its native shell: the primary semantics, the rails and
     * the control row exactly as declared by the preset, and every main key's face unchanged (only
     * long-press menus may differ). Android draws the main keys from data and the bottom row from the
     * editor, so it only needs [matchesPhone9PrimarySemantics] and
     * [PhoneKeypadRailPolicy.usesBundledRails]; this stays as the iOS-compatible identity.
     */
    fun matchesPhone9VisualShell(definition: LayoutDefinition): Boolean {
        val preset = phone9
        if (!matchesPhone9PrimarySemantics(definition) ||
            definition.rows.size != preset.rows.size ||
            definition.auxiliaryAreas != preset.auxiliaryAreas ||
            definition.rows.last() != preset.rows.last()
        ) {
            return false
        }
        return definition.rows.take(PhoneKeypadMetrics.MAIN_ROW_COUNT).zip(preset.rows).all { (row, presetRow) ->
            row.keys.zip(presetRow.keys).all { (key, presetKey) ->
                key.label == presetKey.label &&
                    key.secondaryLabel == presetKey.secondaryLabel &&
                    key.titleMode == presetKey.titleMode
            }
        }
    }

    /** The main area is the first three rows by declaration: count, width and primary action must match. */
    private fun mainRowsMatch(
        definition: LayoutDefinition,
        preset: LayoutDefinition,
    ): Boolean {
        val count = PhoneKeypadMetrics.MAIN_ROW_COUNT
        if (definition.rows.size < count) return false
        return definition.rows.take(count).zip(preset.rows.take(count)).all { (row, presetRow) ->
            row.keys.size == presetRow.keys.size &&
                row.keys.zip(presetRow.keys).all { (key, presetKey) ->
                    key.width == presetKey.width && key.action == presetKey.action
                }
        }
    }

    /** Shown to users (resource inspector, layout manager), so it must match real behavior. */
    private const val PHONE9_NOTE =
        "2–9 键按所选的输入方案输入。输入中轻点首键可分词；未输入时，左栏前三个符号可直接输入，连续点按首键可轮换其余符号。长按首键输入 1，长按 2–9 输入对应数字。"

    /** Long-press digit shared by the first key and 2-9. */
    const val T9_DIGIT_ONE: String = "1"

    /** The ordered builtin layouts; the registry starts from these. */
    val all: List<LayoutDefinition>
        get() = listOf(qwerty26, merged14, merged18, phone9, zhuyin41, cangjie26, romaji26, kana12)

    fun byId(id: String): LayoutDefinition? = all.firstOrNull { it.id == id }

    /** The bundled preset of a physical shape. */
    fun preset(layout: LayoutInput.Builtin): LayoutDefinition =
        when (layout) {
            LayoutInput.Builtin.QWERTY26 -> qwerty26
            LayoutInput.Builtin.MERGED14 -> merged14
            LayoutInput.Builtin.MERGED18 -> merged18
            LayoutInput.Builtin.PHONE9 -> phone9
        }

    /**
     * Where the 26- and 18-key layouts split when nothing saved says otherwise: each hand keeps its
     * letters from a physical keyboard, QWERT ASDFG ZXCVB on the left (on 18 keys three keys a
     * row, so BN and M share the right hand). The cuts count letter keys; the keyboard draws Shift
     * outside the rows. A copy counts as the bundled layout (id, name, note and split are ignored),
     * so the cuts do not depend on what the layout is called. Null for any other layout, for one
     * whose keys or settings differ from the bundled one, and for a split with saved cuts.
     */
    fun handSplit(definition: LayoutDefinition): LayoutSplitConfiguration? {
        val layout = definition.input.builtinLayout
        val breaks =
            when (layout) {
                LayoutInput.Builtin.QWERTY26 -> STANDARD_LETTER_ROW_BREAKS
                LayoutInput.Builtin.MERGED18 -> MERGED18_ROW_BREAKS
                else -> return null
            }
        val bundled = preset(layout)
        val comparable =
            definition.copy(id = bundled.id, name = bundled.name, note = bundled.note, split = bundled.split)
        if (comparable != bundled || definition.split?.rowBreaks?.isNotEmpty() == true) return null
        val base = definition.split ?: LayoutSplitConfiguration(activation = LayoutSplitConfiguration.Activation.OFF)
        return base.copy(rowBreaks = breaks)
    }

    /**
     * The definition with the hand cuts it is drawn with written into its split, for a copy: the
     * copy keeps those cuts whatever it is later called or edited into.
     */
    fun persistingHandCuts(definition: LayoutDefinition): LayoutDefinition =
        handSplit(definition)?.let { definition.withSplit(it) } ?: definition

    private val STANDARD_LETTER_ROW_BREAKS = listOf(5, 5, 5)
    private val MERGED18_ROW_BREAKS = listOf(3, 3, 3)

    // MARK: - 9-key quick symbols

    /**
     * Ordered quick symbols: saved order first, missing items filled from the default sequence,
     * full-width mapped to half-width in ASCII mode, then de-duplicated after mapping (first
     * occurrence decides rank), with the original first-key address/URL marks appended as a
     * low-frequency tail.
     *
     * This is the single ordering source for both the leading rail (first three) and the first-key
     * cycle (4th item on), so titles, accessibility hints and insertion order cannot disagree.
     */
    fun t9QuickSymbolValues(
        saved: String,
        ascii: Boolean,
    ): List<String> {
        val userOrdered =
            (saved + T9QuickSymbolStore.DEFAULT_SYMBOLS).map { character ->
                val value = character.toString()
                if (!ascii) value else T9_ASCII_PUNCTUATION[value] ?: value
            }
        val seen = mutableSetOf<String>()
        return (userOrdered + t9PunctuationSymbols()).filter { seen.add(it) }
    }

    /** The first three items typed directly by the leading rail, top to bottom. */
    fun t9QuickRailValues(
        saved: String,
        ascii: Boolean,
    ): List<String> = t9QuickSymbolValues(saved, ascii).take(3)

    /** From the 4th item on: cycled by repeated taps on the first key of the main area. */
    fun t9QuickCycleValues(
        saved: String,
        ascii: Boolean,
    ): List<String> = t9QuickSymbolValues(saved, ascii).drop(3)

    /** Full-width to half-width punctuation, used only in ASCII mode. */
    private val T9_ASCII_PUNCTUATION: Map<String, String> =
        mapOf(
            "，" to ",",
            "、" to ",",
            "。" to ".",
            "？" to "?",
            "！" to "!",
            "…" to "...",
            "（" to "(",
            "）" to ")",
            "：" to ":",
            "；" to ";",
            "【" to "[",
            "】" to "]",
        )

    /**
     * Whether a builtin 9-key digit key (2-9) should take the layout declared alternates. A digit key
     * is label "ABC" + a digit code + a digit literal long-press; the generic policy returns empty
     * for it, so these keys must take the declared path.
     */
    fun t9DigitKeyUsesDeclaredAlternates(key: LayoutKey): Boolean {
        if (key.action.type != LayoutActionKind.CODE) return false
        val value = key.action.value ?: return false
        if (value.length != 1 || value[0] !in '2'..'9') return false
        // Without declared long-press alternates, fall back to the generic policy.
        return (key.longPress ?: emptyList()).isNotEmpty()
    }

    /** The idle first key cycles the address/URL marks of the system 9-key. */
    fun t9PunctuationSymbols(): List<String> = listOf("@", "/", ".", ":", "_", "-")

    /**
     * Cap and action of the first key, derived from the cycle order: the label is the first three
     * values and tap inserts the first. Returns null for an empty list, never a dead-looking key.
     */
    fun t9CycleKey(values: List<String>): LayoutKey? {
        val first = values.firstOrNull() ?: return null
        if (first.isEmpty()) return null
        return LayoutKey(
            label = values.take(3).joinToString(""),
            secondaryLabel = T9_DIGIT_ONE,
            action = LayoutAction.literal(first),
            longPress = listOf(literalOption(T9_DIGIT_ONE)),
        )
    }

    /** Last-resort first key built from the address-mark fallback. */
    fun t9PunctuationKey(): LayoutKey {
        val marks = t9PunctuationSymbols()
        return LayoutKey(label = marks.take(3).joinToString(""), action = LayoutAction.literal(marks[0]))
    }

    /**
     * First key while composing: tap separates syllables (sends apostrophe); long-press types 1 like
     * 2-9. It is a plain code action, so it commits the composition first, then inserts the digit.
     */
    fun t9SeparatorKey(): LayoutKey =
        LayoutKey(
            label = "分词",
            secondaryLabel = T9_DIGIT_ONE,
            action = LayoutAction.code("'"),
            longPress = listOf(literalOption(T9_DIGIT_ONE)),
        )

    /** Idle-only, ordered defaults; the live builtin lets users replace each slot. */
    fun t9LeadingShortcuts(ascii: Boolean): List<LayoutKey> =
        (if (ascii) "@/-()_" else "@/-()…").map { character ->
            val value = character.toString()
            LayoutKey(label = value, action = LayoutAction.literal(value))
        }

    // MARK: - 123 / symbol page rows

    /**
     * The real 123 punctuation rail order: period/question/exclamation first, then the last and
     * second numeric rows, de-duplicated by the branch actual strings.
     */
    fun phoneNumericRailValues(ascii: Boolean): List<String> {
        val priority = if (ascii) listOf(".", "?", "!") else listOf(".", "？", "！")
        val rows = numericPageRows(ascii)
        // The row pages type the comma from the bottom row; the digit pad has none, so the rail keeps
        // it right after the period.
        val last = (rows.lastOrNull() ?: emptyList()).toMutableList()
        last.add(minOf(1, last.size), if (ascii) "," else "，")
        val rest = last + (if (rows.size > 1) rows[1] else emptyList())
        val seen = mutableSetOf<String>()
        return (priority + rest).filter { seen.add(it) }
    }

    /**
     * The 123 page: digits, then the everyday marks, so what most text needs is one tap from the
     * letters. Nothing here repeats on the symbol page, which holds the rest one page deeper (the
     * comma is in the bottom row).
     */
    fun numericPageRows(ascii: Boolean): List<List<String>> =
        if (ascii) {
            listOf(
                "1234567890".map { it.toString() },
                listOf("-", "/", ":", ";", "(", ")", "$", "&", "@", "\""),
                listOf(".", "?", "!", "'", "%", "#", "+"),
            )
        } else {
            listOf(
                "1234567890".map { it.toString() },
                listOf("-", "/", "：", "；", "（", "）", "¥", "@", "“", "”"),
                listOf("。", "？", "！", "、", "…", ".", "%"),
            )
        }

    /**
     * The row-shaped symbol page: the 123 geometry with the less frequent marks, none of which is
     * also on the 123 page.
     */
    fun symbolPageRows(ascii: Boolean): List<List<String>> =
        if (ascii) {
            listOf(
                listOf("[", "]", "{", "}", "*", "=", "\\", "€", "£", "¥"),
                listOf("•", "°", "§", "×", "÷", "±", "≈", "≠", "©", "™"),
                listOf("<", ">", "~", "`", "^", "_", "|"),
            )
        } else {
            listOf(
                listOf("【", "】", "{", "}", "#", "^", "*", "+", "=", "&"),
                listOf("_", "——", "\\", "|", "～", "《", "》", "「", "」", "·"),
                listOf("$", "€", "£", "<", ">", "‘", "’"),
            )
        }

    /**
     * The Chinese/ASCII branch pair for one page slot. It is the same position in the two existing
     * key tables, not a punctuation translation: the Chinese comma/question and the ASCII at/amp
     * stay the two explicitly authored versions of that slot.
     */
    data class BilingualPageMark(
        val chinese: String,
        val ascii: String,
    )

    /** Numeric 123 slots paired by position across the two existing canonical tables. */
    fun numericPageBilingualRows(): List<List<BilingualPageMark>> =
        pairRows(numericPageRows(ascii = false), numericPageRows(ascii = true))

    /** Row-shaped symbol slots paired by position across the two existing canonical tables. */
    fun symbolPageBilingualRows(): List<List<BilingualPageMark>> =
        pairRows(symbolPageRows(ascii = false), symbolPageRows(ascii = true))

    private fun pairRows(
        chinese: List<List<String>>,
        ascii: List<List<String>>,
    ): List<List<BilingualPageMark>> {
        if (chinese.size != ascii.size) return emptyList()
        return chinese.zip(ascii).map { (chineseRow, asciiRow) ->
            chineseRow.zip(asciiRow).map { (chineseMark, asciiMark) ->
                BilingualPageMark(chineseMark, asciiMark)
            }
        }
    }

    // MARK: - Shared construction

    /** The bottom control row shared by every 26/14/18 declaration. */
    val controls: LayoutRow
        get() =
            LayoutRow(
                listOf(
                    LayoutKey("布局", action = LayoutAction(LayoutActionKind.MODE_SWITCH, "nextLayout")),
                    LayoutKey("🌐", action = LayoutAction(LayoutActionKind.GLOBE)),
                    LayoutKey("空格", width = 4.0, action = LayoutAction(LayoutActionKind.SPACE)),
                    LayoutKey("⌫", action = LayoutAction(LayoutActionKind.BACKSPACE)),
                    LayoutKey("⏎", action = LayoutAction(LayoutActionKind.ENTER)),
                ),
            )

    /** A control key whose title the host resolves from the running input language. */
    private fun automaticKey(
        label: String,
        action: LayoutAction,
        width: Double? = null,
    ): LayoutKey = LayoutKey(label = label, width = width, action = action, titleMode = LayoutKeyTitleMode.AUTOMATIC)

    /** Caps paired one-to-one with the codes they send; a code may be a digit or a mark, not a letter only. */
    private fun codeRow(
        caps: String,
        codes: String,
    ): LayoutRow {
        require(caps.length == codes.length) { "cap/code count mismatch: " + caps }
        return LayoutRow(
            codes.indices.map { index ->
                LayoutKey(caps[index].toString(), action = LayoutAction.code(codes[index].toString()))
            },
        )
    }

    /** A radical cap with the Latin letter in the corner, because the letter is what the engine reads. */
    private fun radicalRow(
        radicals: String,
        letters: String,
    ): LayoutRow {
        require(radicals.length == letters.length) { "radical/letter count mismatch: " + radicals }
        return LayoutRow(
            letters.indices.map { index ->
                LayoutKey(
                    label = radicals[index].toString(),
                    secondaryLabel = letters[index].uppercase(),
                    action = LayoutAction.code(letters[index].toString()),
                )
            },
        )
    }

    private fun literalOption(value: String): LayoutOption =
        LayoutOption(label = value, action = LayoutAction.literal(value))

    private fun phone(
        letters: String,
        digit: String,
    ): LayoutKey =
        LayoutKey(
            label = letters,
            secondaryLabel = digit,
            action = LayoutAction.code(digit),
            longPress = listOf(literalOption(digit)),
        )

    private fun mergedRows(
        groups: List<List<String>>,
        singleWidth: Double? = null,
        pairWidth: Double? = null,
    ): List<LayoutRow> =
        groups.map { row ->
            LayoutRow(row.map { mergedKey(it, if (it.length > 1) pairWidth else singleWidth) })
        }

    /**
     * Real long-press alternates for merged keys: the main key sends the cluster representative and
     * long-press types the rest of the cluster; single-letter keys (l/m) get common punctuation.
     */
    private fun mergedKey(
        group: String,
        width: Double?,
    ): LayoutKey {
        val partnerOptions = group.drop(1).map { literalOption(it.toString()) }
        val punctuationOptions =
            when (group) {
                "l" -> listOf(literalOption("，"))
                "m" -> listOf(literalOption("。"))
                else -> emptyList()
            }
        var options = partnerOptions + punctuationOptions
        if (group == "qw") options = options + listOf(literalOption("1"), literalOption("!"))
        return LayoutKey(
            label = group.uppercase(),
            secondaryLabel =
                when {
                    group == "qw" -> "1 !"
                    group.length > 1 -> "合并键"
                    else -> null
                },
            width = width,
            action = LayoutAction.code(group.take(1)),
            longPress = options.ifEmpty { null },
        )
    }

    private fun alphabetRows(): List<LayoutRow> {
        val digits = "qwertyuiop".map { it.toString() }.zip("1234567890".map { it.toString() }).toMap()
        return listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").map { letters ->
            LayoutRow(
                letters.map { letter ->
                    val value = letter.toString()
                    LayoutKey(
                        label = value,
                        secondaryLabel = digits[value],
                        action = LayoutAction.code(value),
                        longPress =
                            AlternateKeyPolicy.defaultLetterLiterals(value).map { literalOption(it) },
                    )
                },
            )
        }
    }

    /**
     * ー sends the scheme's minus code, the key the romaji spelling uses for a long vowel; a literal
     * ー would bypass the scheme's own punctuation handling.
     */
    private val longVowelKey: LayoutKey = LayoutKey(label = "ー", action = LayoutAction.code("-"))

    /**
     * The bundled letter rows with ー appended to the second row. Keeping the bundled letter menus
     * lets the input language rename their marks (a Japanese board offers 、 。 「 」 and ー).
     */
    private fun romajiRows(): List<LayoutRow> {
        val rows = alphabetRows().toMutableList()
        rows[1] = LayoutRow(rows[1].keys + longVowelKey)
        return rows
    }

    // MARK: - Japanese twelve-key board

    /**
     * あ か さ / た な は / ま や ら: the tap glyph of each row with the row id the kana handler turns
     * into romaji, in drawn order.
     */
    private val kana12Grid: List<List<Pair<String, String>>> =
        listOf(
            listOf("あ" to "a", "か" to "ka", "さ" to "sa"),
            listOf("た" to "ta", "な" to "na", "は" to "ha"),
            listOf("ま" to "ma", "や" to "ya", "ら" to "ra"),
        )

    /**
     * 小゛゜ わ 、。?!: the modifier edits the kana just typed, わ taps its row, and the last cap
     * types its tap mark (。 ？ ！ … are flick targets of the kana model).
     */
    private fun kana12BottomRow(): LayoutRow =
        LayoutRow(
            listOf(
                LayoutKey("小゛゜", action = LayoutAction.kanaModifier),
                LayoutKey("わ", action = LayoutAction.kana("wa")),
                LayoutKey("、。?!", action = LayoutAction.literal("、")),
            ),
        )

    private fun kana12Rows(): List<LayoutRow> =
        kana12Grid.map { row ->
            LayoutRow(row.map { (cap, rowID) -> LayoutKey(cap, action = LayoutAction.kana(rowID)) })
        } + listOf(kana12BottomRow())

    /**
     * Four keys, one per grid row. Every face is automatic, so the board reads 記号 and あ/A for
     * Japanese; only the named caret key keeps its stored face because no control draws it.
     */
    private fun kana12LeadingRail(): LayoutAuxiliaryArea =
        LayoutAuxiliaryArea(
            position = LayoutAuxiliaryArea.Position.LEADING,
            role = LayoutAuxiliaryArea.Role.EDITING,
            keys =
                listOf(
                    LayoutKey("←", action = LayoutAction.cursorLeft),
                    automaticKey("記号", LayoutAction.pageSwitch(LayoutPageTarget.SYMBOLS)),
                    automaticKey("あ/A", LayoutAction.languageSwitch()),
                    automaticKey("🌐", LayoutAction(LayoutActionKind.GLOBE)),
                ),
        )

    /**
     * Delete, space and Return; Return's span is two rows, the existing rail width field, so the
     * rail covers the same four rows as the grid without a height metadata of its own.
     */
    private fun kana12TrailingRail(): LayoutAuxiliaryArea =
        LayoutAuxiliaryArea(
            position = LayoutAuxiliaryArea.Position.TRAILING,
            role = LayoutAuxiliaryArea.Role.EDITING,
            keys =
                listOf(
                    automaticKey("⌫", LayoutAction(LayoutActionKind.BACKSPACE)),
                    automaticKey("空格", LayoutAction(LayoutActionKind.SPACE)),
                    automaticKey("⏎", LayoutAction(LayoutActionKind.ENTER), width = 2.0),
                ),
        )
}

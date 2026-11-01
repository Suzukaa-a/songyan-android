package app.songyan.ime.layout

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from LayoutKitTests/KeyboardPageChromeTests and the materialization half of the page tests. */
class KeyboardPageDefinitionTest {
    private fun weights(keys: List<LayoutKey>): List<Double> = keys.map { it.widthWeight }

    private fun literal(
        text: String,
        label: String = text,
    ): LayoutKey = LayoutKey(label, action = LayoutAction.literal(text))

    // MARK: - Materialisation defaults

    @Test
    fun materialisedAbcCarriesShiftDeleteAndNativeFooter() {
        val page = KeyboardPagePresets.englishQwerty.materialized()
        assertEquals(listOf(10, 9, 9), page.rows.map { it.keys.size })
        assertEquals(KeyboardPagePresets.englishQwerty.rows[0], page.rows[0])
        assertEquals(KeyboardPagePresets.englishQwerty.rows[1], page.rows[1])

        val last = page.rows[2].keys
        val shift = last.first()
        assertEquals(LayoutActionKind.SHIFT, shift.action.type)
        assertEquals(1.5, shift.width ?: 0.0, 0.0)
        assertEquals(LayoutKeyTitleMode.AUTOMATIC, shift.titleMode)
        assertNull(shift.secondaryLabel)
        val delete = last.last()
        assertEquals(LayoutActionKind.BACKSPACE, delete.action.type)
        assertEquals(1.5, delete.width ?: 0.0, 0.0)
        assertEquals(LayoutKeyTitleMode.AUTOMATIC, delete.titleMode)
        assertEquals("zxcvbnm".map { it.toString() }, last.drop(1).dropLast(1).map { it.label })

        val bottom = requireNotNull(page.chrome).bottom
        assertEquals(6, bottom.size)
        assertEquals(LayoutActionKind.GLOBE, bottom[0].action.type)
        assertEquals(LayoutPageTarget.NUMERIC, bottom[1].action.pageSwitchTarget)
        assertEquals(LayoutActionKind.LANGUAGE_SWITCH, bottom[2].action.type)
        assertEquals(LayoutActionKind.SPACE, bottom[3].action.type)
        assertEquals(4.0, bottom[3].width ?: 0.0, 0.0)
        // The comma sits in the symbol slot; its long press holds the other sentence marks.
        assertEquals(LayoutActionKind.LITERAL, bottom[4].action.type)
        assertEquals("，", bottom[4].action.value)
        assertEquals(",", bottom[4].action.asciiValue)
        assertEquals(",", bottom[4].label)
        assertEquals(listOf(".", "?", "!", "'", "\""), bottom[4].longPress?.map { it.label })
        assertEquals(listOf("。", "？", "！", "、", "…"), bottom[4].longPress?.map { it.action.value })
        assertEquals(LayoutActionKind.ENTER, bottom[5].action.type)
        assertTrue(bottom.all { it.titleMode == LayoutKeyTitleMode.AUTOMATIC })
        assertTrue(requireNotNull(page.chrome).leading.isEmpty())
        assertTrue(requireNotNull(page.chrome).trailing.isEmpty())
        assertEquals(page, page.materialized())
        page.validate()
    }

    /** The frozen contract fixes the default footer weights: space is 4, every other default key 1. */
    @Test
    fun defaultFunctionAreaKeepsContractWeights() {
        for (
        page in
        listOf(
            KeyboardPagePresets.englishQwerty,
            KeyboardPagePresets.numericRowsPage(false),
            KeyboardPagePresets.symbolRowsPage(false),
        )
        ) {
            val materialised = page.materialized()
            assertEquals(listOf(1.0, 1.0, 1.0, 4.0, 1.0, 1.0), weights(requireNotNull(materialised.chrome).bottom))
        }
        val categorized = KeyboardPagePresets.symbolsCategorized.materialized()
        assertEquals(listOf(1.0, 1.0, 1.0, 4.0, 1.0), weights(requireNotNull(categorized.chrome).bottom))
        assertEquals(listOf(1.0, 1.0, 2.0), weights(requireNotNull(categorized.chrome).trailing))
        val keypad = KeyboardPagePresets.numericAutomatic.materialized(KeyboardNumericForm.PHONE_DIGIT_PAD)
        assertEquals(listOf(1.0, 1.0, 1.0), weights(requireNotNull(keypad.chrome).leading))
        assertEquals(listOf(1.0, 1.0, 1.0, 1.0, 1.0, 1.0), weights(requireNotNull(keypad.chrome).bottom))
        assertEquals(listOf(1.0, 1.0, 1.0), weights(requireNotNull(keypad.chrome).trailing))
    }

    @Test
    fun materialisedNumericRowPageKeepsSwitchMarksAndDeleteOnTheThirdRow() {
        val chinese = KeyboardPagePresets.numericAutomatic.materialized()
        assertNull(chinese.numericPresentation)
        assertEquals(listOf(10, 10, 9), chinese.rows.map { it.keys.size })
        val switchKey = chinese.rows[2].keys.first()
        assertEquals(LayoutPageTarget.SYMBOLS, switchKey.action.pageSwitchTarget)
        assertEquals("符号", switchKey.label)
        assertEquals(1.5, switchKey.width ?: 0.0, 0.0)
        assertEquals(LayoutKeyTitleMode.AUTOMATIC, switchKey.titleMode)
        val delete = chinese.rows[2].keys.last()
        assertEquals(LayoutActionKind.BACKSPACE, delete.action.type)
        assertEquals("Delete is a side key as wide as on the ABC page", 1.5, delete.width ?: 0.0, 0.0)
        assertEquals(LayoutKeyTitleMode.AUTOMATIC, delete.titleMode)
        assertEquals(KeyboardNumericMarks.CHINESE, chinese.defaultSymbolMarks)
        // Frozen default footer: globe, 返回, 中英, space, comma, Return.
        val bottom = requireNotNull(chinese.chrome).bottom
        assertEquals(
            listOf(
                LayoutActionKind.GLOBE,
                LayoutActionKind.PAGE_SWITCH,
                LayoutActionKind.LANGUAGE_SWITCH,
                LayoutActionKind.SPACE,
                LayoutActionKind.LITERAL,
                LayoutActionKind.ENTER,
            ),
            bottom.map { it.action.type },
        )
        assertEquals("返回", bottom[1].label)
        assertEquals(LayoutPageTarget.LETTERS, bottom[1].action.pageSwitchTarget)
        assertEquals(LayoutKeyTitleMode.AUTOMATIC, bottom[1].titleMode)
        assertNull(bottom[2].action.languageSwitchBehavior)
        assertEquals("，", bottom[4].action.value)
        // The marks are an explicit bilingual pair: Chinese value, ASCII counterpart, initial label.
        val paired = chinese.rows[1].keys[2]
        assertEquals("：", paired.action.value)
        assertEquals(":", paired.action.asciiValue)
        assertEquals("：", paired.label)
        assertEquals(LayoutKeyTitleMode.AUTOMATIC, paired.titleMode)
        assertNull(
            chinese.rows[0]
                .keys[0]
                .action.asciiValue,
        )
        chinese.validate()

        val automaticAscii =
            KeyboardPagePresets.numericAutomatic.materialized(
                numericMarks = KeyboardNumericMarks.ASCII,
            )
        assertEquals(listOf(10, 10, 9), automaticAscii.rows.map { it.keys.size })
        assertEquals(KeyboardNumericMarks.ASCII, automaticAscii.defaultSymbolMarks)
        assertEquals(":", automaticAscii.rows[1].keys[2].label)
        assertEquals(
            "：",
            automaticAscii.rows[1]
                .keys[2]
                .action.value,
        )
        assertEquals(
            ":",
            automaticAscii.rows[1]
                .keys[2]
                .action.asciiValue,
        )
        assertEquals(
            LayoutPageTarget.SYMBOLS,
            automaticAscii.rows[2]
                .keys
                .first()
                .action.pageSwitchTarget,
        )
        automaticAscii.validate()

        val symbols = KeyboardPagePresets.symbolsSystem.materialized()
        val symbolSwitch = symbols.rows[2].keys.first()
        assertEquals(LayoutPageTarget.NUMERIC, symbolSwitch.action.pageSwitchTarget)
        assertEquals("123", symbolSwitch.label)
        assertEquals(1.5, symbolSwitch.width ?: 0.0, 0.0)
        assertEquals(LayoutKeyTitleMode.AUTOMATIC, symbolSwitch.titleMode)
        assertEquals(
            LayoutActionKind.BACKSPACE,
            symbols.rows[2]
                .keys
                .last()
                .action.type,
        )
        assertEquals(6, requireNotNull(symbols.chrome).bottom.size)
    }

    @Test
    fun materialisedAutomaticSymbolPageBecomesAuthoredRows() {
        val page = KeyboardPagePresets.symbolsSystem.materialized()
        assertEquals(KeyboardSymbolPresentation.ROWS, page.symbolPresentation)
        assertEquals(listOf(10, 10, 9), page.rows.map { it.keys.size })
        assertEquals(
            LayoutPageTarget.NUMERIC,
            page.rows[2]
                .keys
                .first()
                .action.pageSwitchTarget,
        )
        assertEquals(
            LayoutActionKind.BACKSPACE,
            page.rows[2]
                .keys
                .last()
                .action.type,
        )
        val bottom = requireNotNull(page.chrome).bottom
        assertEquals(6, bottom.size)
        assertEquals(LayoutPageTarget.LETTERS, bottom[1].action.pageSwitchTarget)
        page.validate()
    }

    /** Materializing a row page on native controls only adds the switch and Delete; author literals stay. */
    @Test
    fun materialisingNativeRowsAddsTheSwitchAndDeleteWithoutRewritingLiterals() {
        val fixed = LayoutKey("固定", action = LayoutAction.literal("固定"))
        val native =
            KeyboardPageDefinition(
                id = "author.native",
                name = "作者旧页",
                kind = KeyboardPageKind.NUMERIC,
                rows = listOf(LayoutRow(listOf(fixed, LayoutKey("2", action = LayoutAction.literal("2"))))),
            )
        val materialised = native.materialized()
        val keys = materialised.rows[0].keys
        assertEquals(native.rows[0].keys, keys.drop(1).take(2))
        assertEquals(4, keys.size)
        assertEquals(LayoutPageTarget.SYMBOLS, keys[0].action.pageSwitchTarget)
        assertNull(keys[1].action.asciiValue)
        materialised.validate()
    }

    @Test
    fun materialisedCategoryPageMovesNavigationToTrailingRail() {
        val page = KeyboardPagePresets.symbolsCategorized.materialized()
        assertTrue(page.rows.isEmpty())
        assertEquals(KeyboardSymbolPresentation.CATEGORIZED, page.symbolPresentation)
        val chrome = requireNotNull(page.chrome)
        assertEquals(5, chrome.bottom.size)
        assertEquals(
            listOf(
                LayoutActionKind.GLOBE,
                LayoutActionKind.LANGUAGE_SWITCH,
                LayoutActionKind.PAGE_SWITCH,
                LayoutActionKind.SPACE,
                LayoutActionKind.SYMBOL_LOCK,
            ),
            chrome.bottom.map { it.action.type },
        )
        assertEquals("返回", chrome.bottom[1].label)
        assertEquals(
            LayoutLanguageSwitchBehavior.LETTERS,
            chrome.bottom[1].action.languageSwitchBehavior,
        )
        assertEquals(LayoutPageTarget.NUMERIC, chrome.bottom[2].action.pageSwitchTarget)
        assertEquals(
            listOf(LayoutActionKind.BACKSPACE, LayoutActionKind.CLEAR_INPUT, LayoutActionKind.ENTER),
            chrome.trailing.map { it.action.type },
        )
        assertEquals(listOf(1.0, 1.0, 2.0), weights(chrome.trailing))
        assertTrue(chrome.trailingSpansBottom)
        assertTrue(chrome.leading.isEmpty())
        page.validate()

        // A legacy categorized page keeps its browser rows; materialising only adds chrome.
        val legacyBrowser =
            KeyboardPagePresets.symbolsCategorized.withRows(KeyboardPagePresets.symbolRowsPage(false).rows)
        val kept = legacyBrowser.materialized()
        assertEquals(legacyBrowser.rows, kept.rows)
        assertEquals(3, requireNotNull(kept.chrome).trailing.size)
        kept.validate()
    }

    @Test
    fun explicitKeypadIsAuthoredDataForAnyMainLayout() {
        val keypad = KeyboardPagePresets.numericAutomatic.materialized(KeyboardNumericForm.PHONE_DIGIT_PAD)
        assertEquals(KeyboardNumericPresentation.KEYPAD, keypad.numericPresentation)
        assertTrue(keypad.isKeypad)
        assertFalse(keypad.resolvesAtRuntime)
        assertEquals(3, keypad.rows.size)
        assertEquals(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9"),
            keypad.rows.flatMap { it.keys }.map { it.label },
        )
        assertTrue(keypad.rows.flatMap { it.keys }.all { it.action.type == LayoutActionKind.LITERAL })
        val chrome = requireNotNull(keypad.chrome)
        // The letter shell's grid: three marks beside the digit rows, Delete, 0 and the comma down
        // the right, and the globe, 返回, 中英, space, 符号 and Return along the bottom.
        assertEquals(3, chrome.leading.size)
        assertTrue(chrome.leading.all { it.action.type == LayoutActionKind.LITERAL })
        assertEquals(
            listOf<Any?>(
                LayoutActionKind.GLOBE,
                LayoutPageTarget.LETTERS,
                LayoutActionKind.LANGUAGE_SWITCH,
                LayoutActionKind.SPACE,
                LayoutPageTarget.SYMBOLS,
                LayoutActionKind.ENTER,
            ),
            chrome.bottom.map { key -> key.action.pageSwitchTarget ?: key.action.type },
        )
        assertEquals("符号", chrome.bottom[4].label)
        assertEquals(listOf("⌫", "0", "，"), chrome.trailing.map { it.label })
        assertEquals(listOf(null, "0", "，"), chrome.trailing.map { it.action.value })
        assertEquals(",", chrome.trailing[2].action.asciiValue)
        assertFalse(chrome.trailingSpansBottom)
        assertEquals(listOf(1.0, 1.0, 1.0), weights(chrome.trailing))
        assertNull(chrome.leadingWidth)
        assertNull(chrome.trailingWidth)
        keypad.validate()
        assertEquals(keypad, keypad.materialized())

        val preset = KeyboardPagePresets.numericKeypad
        assertEquals(KeyboardNumericPresentation.KEYPAD, preset.numericPresentation)
        assertEquals("数字盘 123", preset.name)
        preset.validate()
        assertTrue(KeyboardPagePresets.codeOwnedIds.contains(preset.id))
        assertEquals(1, KeyboardPagePresets.mergingInto(emptyList()).count { it.id == preset.id })
    }

    @Test
    fun keypadStructureIsAuthorEditable() {
        val edited =
            KeyboardPagePresets.numericKeypad
                .withRows(listOf(LayoutRow(listOf(literal("5")))))
                .withChrome(requireNotNull(KeyboardPagePresets.numericKeypad.chrome).copy(leading = emptyList()))
        edited.validate()
        assertEquals(KeyboardNumericPresentation.KEYPAD, edited.numericPresentation)
        assertEquals(1, edited.rows[0].keys.size)
    }

    // MARK: - Split cut and shift insertion

    @Test
    fun insertingTheLeadingKeyMovesTheStoredCutByOneOnly() {
        val legacy =
            KeyboardPageDefinition(
                id = "cut.abc",
                name = "切开 ABC",
                kind = KeyboardPageKind.ENGLISH,
                rows = KeyboardPagePresets.englishQwerty.rows,
                split = LayoutSplitConfiguration(rowBreaks = listOf(5, 4, 3)),
            )
        val materialised = legacy.materialized()
        assertEquals(9, materialised.rows[2].keys.size)
        assertEquals(listOf(5, 4, 4), materialised.split?.rowBreaks)
        materialised.validate()

        val legacyNumeric =
            KeyboardPageDefinition(
                id = "cut.num",
                name = "切开数字",
                kind = KeyboardPageKind.NUMERIC,
                rows = KeyboardPagePresets.numericRowsPage(false).rows,
                split = LayoutSplitConfiguration(rowBreaks = listOf(5, 4, 3)),
            )
        val numeric = legacyNumeric.materialized()
        // The cut moves with the inserted page switch; the appended Delete never moves it.
        assertEquals(listOf(5, 4, 4), numeric.split?.rowBreaks)
        assertEquals(9, numeric.rows[2].keys.size)
        numeric.validate()
    }

    // MARK: - Deletion, movement and fidelity

    @Test
    fun deletingFunctionKeysIsNotUndoneByMaterialisation() {
        val edited = KeyboardPagePresets.englishQwerty.materialized()
        val chrome = requireNotNull(edited.chrome)
        val withFewer =
            edited.withChrome(chrome.copy(bottom = chrome.bottom.dropLast(1))).withRows(
                edited.rows.dropLast(1) + LayoutRow(edited.rows[2].keys.dropLast(1)),
            )
        val again = withFewer.materialized()
        assertEquals(withFewer, again)
        assertEquals(5, requireNotNull(again.chrome).bottom.size)
        assertFalse(again.rows[2].keys.any { it.action.type == LayoutActionKind.BACKSPACE })
        again.validate()

        val emptied =
            KeyboardPagePresets
                .numericRowsPage(false)
                .materialized()
                .withChrome(KeyboardPageChrome())
        assertEquals(emptied, emptied.materialized())
        emptied.validate()
    }

    @Test
    fun keysMoveAcrossRowsFooterAndRailsWithoutLoss() {
        var page = KeyboardPagePresets.englishQwerty.materialized()
        val chrome = requireNotNull(page.chrome)
        val enter = chrome.bottom.last()
        page =
            page.withChrome(
                chrome.copy(bottom = chrome.bottom.dropLast(1), trailing = listOf(enter)),
            )
        assertEquals(enter, page.keyAt(KeyboardPageKeyPath.Chrome(KeyboardPageChrome.Region.TRAILING, 0)))
        assertNull(page.keyAt(KeyboardPageKeyPath.Chrome(KeyboardPageChrome.Region.BOTTOM, 5)))
        assertEquals(6, page.chromeKeys.size)
        assertEquals(page.rows.flatMap { it.keys }.size + 6, page.allKeys.size)

        val renamed = requireNotNull(page.keyAt(KeyboardPageKeyPath.Row(0, 0))).withLabel("我的 Q")
        page = page.replacingKeyAt(KeyboardPageKeyPath.Row(0, 0), renamed)
        assertEquals(LayoutKeyTitleMode.CUSTOM, page.keyAt(KeyboardPageKeyPath.Row(0, 0))?.titleMode)
        assertEquals(LayoutActionKind.LITERAL, page.keyAt(KeyboardPageKeyPath.Row(0, 0))?.action?.type)
        assertNull(page.keyAt(KeyboardPageKeyPath.Chrome(KeyboardPageChrome.Region.BOTTOM, 99)))
        assertEquals(page, page.replacingKeyAt(KeyboardPageKeyPath.Row(99, 0), renamed))
        assertEquals(page, page.materialized())
        page.validate()
    }

    @Test
    fun allKeysIncludeCategoryContent() {
        val category =
            KeyboardPageCategory(
                id = "cate.1",
                name = "常用",
                keys = listOf(LayoutKey("⏎", action = LayoutAction(LayoutActionKind.ENTER))),
            )
        val page = KeyboardPagePresets.symbolsCategorized.withCategories(listOf(category))
        val materialised = page.materialized()
        assertEquals(1, materialised.categories?.size)
        assertEquals(1 + materialised.chromeKeys.size, materialised.allKeys.size)
        assertTrue(materialised.allKeys.any { it.action.type == LayoutActionKind.ENTER })
        materialised.validate()
    }

    @Test
    fun functionKeyContentSurvivesMaterialisation() {
        var page = KeyboardPagePresets.numericAutomatic.materialized()
        val bottom = requireNotNull(page.chrome).bottom.toMutableList()
        val option = LayoutOption("小键盘", LayoutAction.pageSwitch(LayoutPageTarget.SYMBOLS))
        bottom[0] = bottom[0].withLongPress(listOf(option))
        bottom[1] = bottom[1].withLabel("翻页")
        page = page.withChrome(requireNotNull(page.chrome).copy(bottom = bottom))
        val materialised = page.materialized()
        assertEquals(listOf(option), requireNotNull(materialised.chrome).bottom[0].longPress)
        assertEquals(LayoutKeyTitleMode.CUSTOM, requireNotNull(materialised.chrome).bottom[1].titleMode)
        assertEquals("翻页", requireNotNull(materialised.chrome).bottom[1].label)
        materialised.validate()
    }

    @Test
    fun duplicateMaterialisesAndAssignsANewIdentity() {
        val original = KeyboardPagePresets.numericAutomatic
        val copy = original.duplicated("copy.1", "我的数字盘", numericForm = KeyboardNumericForm.PHONE_DIGIT_PAD)
        assertNotEquals(original.id, copy.id)
        assertEquals("我的数字盘", copy.name)
        assertEquals(KeyboardNumericPresentation.KEYPAD, copy.numericPresentation)
        assertTrue(copy.chrome != null)
        assertEquals(copy, copy.materialized())
        assertNull(original.chrome)
        copy.validate()

        val authored = KeyboardPagePresets.englishQwerty.materialized()
        val authoredCopy = authored.duplicated("copy.2", "我的 ABC")
        assertNotEquals(authored.id, authoredCopy.id)
        assertEquals(authored.chrome, authoredCopy.chrome)
        assertEquals(authored.rows, authoredCopy.rows)
    }

    // MARK: - Validation

    @Test
    fun authoredFunctionAreaRejectsDataTheRuntimeCannotHonour() {
        val categorized =
            KeyboardPagePresets.symbolsCategorized.withChrome(
                KeyboardPageChrome(leading = listOf(literal("x"))),
            )
        assertThrows(LayoutException::class.java) { categorized.validate() }

        val page = KeyboardPagePresets.numericAutomatic.materialized()
        for (width in listOf(0.0, Double.POSITIVE_INFINITY, Double.NaN)) {
            val invalid =
                page.withChrome(requireNotNull(page.chrome).copy(leadingWidth = width))
            assertThrows(LayoutException::class.java) { invalid.validate() }
        }
        val crowded =
            page.withChrome(
                requireNotNull(page.chrome).copy(
                    trailing = List(LayoutResourceBudget.KEYS_PER_REGION + 1) { literal("x") },
                ),
            )
        assertThrows(LayoutException::class.java) { crowded.validate() }

        val invalidSwitch =
            page.withChrome(
                requireNotNull(page.chrome).copy(
                    bottom =
                        requireNotNull(page.chrome).bottom.toMutableList().also {
                            it[1] = LayoutKey("123", action = LayoutAction(LayoutActionKind.PAGE_SWITCH, "somewhere"))
                        },
                ),
            )
        assertThrows(LayoutException::class.java) { invalidSwitch.validate() }

        val valuedKind =
            page.withChrome(
                requireNotNull(page.chrome).copy(
                    bottom =
                        requireNotNull(page.chrome).bottom.toMutableList().also {
                            it[1] = LayoutKey("⇧", action = LayoutAction(LayoutActionKind.SHIFT, "x"))
                        },
                ),
            )
        assertThrows(LayoutException::class.java) { valuedKind.validate() }

        assertThrows(LayoutException::class.java) {
            KeyboardPageDefinition(
                id = "keypad.no.chrome",
                name = "无数据数字盘",
                kind = KeyboardPageKind.NUMERIC,
                rows = KeyboardPagePresets.numericKeypad.rows,
                numericPresentation = KeyboardNumericPresentation.KEYPAD,
            ).validate()
        }

        assertThrows(LayoutException::class.java) {
            KeyboardPagePresets.numericAutomatic.withChrome(KeyboardPageChrome()).validate()
        }
    }

    @Test
    fun authoredEmptyStructureIsDeliberate() {
        val empty =
            KeyboardPageDefinition(
                id = "empty.page",
                name = "空白页",
                kind = KeyboardPageKind.ENGLISH,
                rows = emptyList(),
                chrome = KeyboardPageChrome(),
            )
        empty.validate()
        assertEquals(empty, empty.materialized())
        assertTrue(empty.allKeys.isEmpty())
    }

    @Test
    fun automaticPageRejectsAuthoredContent() {
        KeyboardPagePresets.numericAutomatic.validate()
        assertThrows(LayoutException::class.java) {
            KeyboardPageDefinition(
                id = "auto-on-symbols",
                name = "自动符号",
                kind = KeyboardPageKind.SYMBOLS,
                rows = emptyList(),
                numericPresentation = KeyboardNumericPresentation.AUTOMATIC,
            ).validate()
        }
        assertThrows(LayoutException::class.java) {
            KeyboardPageDefinition(
                id = "auto-with-rows",
                name = "自动 123",
                kind = KeyboardPageKind.NUMERIC,
                rows = listOf(LayoutRow(listOf(literal("1")))),
                numericPresentation = KeyboardNumericPresentation.AUTOMATIC,
            ).validate()
        }
        assertThrows(LayoutException::class.java) {
            KeyboardPageDefinition(
                id = "auto-with-symbol-presentation",
                name = "自动 123",
                kind = KeyboardPageKind.NUMERIC,
                rows = emptyList(),
                symbolPresentation = KeyboardSymbolPresentation.ROWS,
                numericPresentation = KeyboardNumericPresentation.AUTOMATIC,
            ).validate()
        }
    }

    @Test
    fun pageKeysMustNotFeedTheEngine() {
        val english = KeyboardPagePresets.englishQwerty
        assertThrows(LayoutException::class.java) {
            english.withRows(listOf(LayoutRow(listOf(LayoutKey("a", action = LayoutAction.code("a")))))).validate()
        }
        val engineAlternate =
            LayoutKey(
                label = "。",
                action = LayoutAction.literal("。"),
                longPress = listOf(LayoutOption("a", LayoutAction.code("a"))),
            )
        assertThrows(LayoutException::class.java) {
            english.withRows(listOf(LayoutRow(listOf(engineAlternate)))).validate()
        }
        assertThrows(LayoutException::class.java) {
            KeyboardPageDefinition(
                id = "bad.kind",
                name = "分类在数字页",
                kind = KeyboardPageKind.NUMERIC,
                rows = KeyboardPagePresets.numericRowsPage(false).rows,
                categories =
                    listOf(
                        KeyboardPageCategory("c", "常用", keys = listOf(literal("。", "。"))),
                    ),
            ).validate()
        }
    }

    // MARK: - Wire form

    @Test
    fun jsonRoundTripsAndLegacyPagesKeepTheirMissingFields() {
        for (page in KeyboardPagePresets.all) {
            val json = LayoutJson.format.encodeToString(KeyboardPageDefinition.serializer(), page)
            assertEquals(page, LayoutJson.format.decodeFromString(KeyboardPageDefinition.serializer(), json))
        }
        // A legacy page must not gain chrome on the wire; materialising a value never writes it.
        val legacy = KeyboardPagePresets.englishQwerty
        val encoded = LayoutJson.format.encodeToString(KeyboardPageDefinition.serializer(), legacy)
        assertFalse(encoded.contains("\"chrome\""))
        val decoded = LayoutJson.format.decodeFromString(KeyboardPageDefinition.serializer(), encoded)
        assertEquals(legacy, decoded)
        assertNull(decoded.chrome)
        assertEquals(legacy.id, decoded.materialized().id)

        val legacyJson =
            "{\"id\":\"old-browser\",\"name\":\"分类符号\",\"kind\":\"symbols\"," +
                "\"rows\":[],\"symbolPresentation\":\"categorized\"}"
        val browser = LayoutJson.format.decodeFromString(KeyboardPageDefinition.serializer(), legacyJson)
        assertNull(browser.categories)
        browser.validate()
    }

    @Test
    fun unknownPageFieldsAreRejected() {
        val json = "{\"id\":\"p\",\"name\":\"n\",\"kind\":\"numeric\",\"newField\":1}"
        assertThrows(IllegalArgumentException::class.java) {
            LayoutJson.format.decodeFromString(KeyboardPageDefinition.serializer(), json)
        }
    }

    @Test
    fun aCopyOfAnAutomaticPageKeepsFollowingTheInputLanguageUnlessAskedOtherwise() {
        assertNull(KeyboardPagePresets.numericAutomatic.duplicated("mine", "我的").defaultSymbolMarks)
        assertNull(KeyboardPagePresets.symbolsSystem.duplicated("mine", "我的").defaultSymbolMarks)
        assertEquals(
            KeyboardNumericMarks.ASCII,
            KeyboardPagePresets.numericAutomatic
                .duplicated("mine", "我的", numericMarks = KeyboardNumericMarks.ASCII)
                .defaultSymbolMarks,
        )
    }

    @Test
    fun everyMarkOfTheBilingualRowsTypesBothBranchesAndShowsTheBranch() {
        for (kind in listOf(KeyboardPageKind.NUMERIC, KeyboardPageKind.SYMBOLS)) {
            val chinese =
                if (kind ==
                    KeyboardPageKind.NUMERIC
                ) {
                    KeyboardPagePresets.numericRowsPage(false)
                } else {
                    KeyboardPagePresets.symbolRowsPage(false)
                }
            val ascii =
                if (kind ==
                    KeyboardPageKind.NUMERIC
                ) {
                    KeyboardPagePresets.numericRowsPage(true)
                } else {
                    KeyboardPagePresets.symbolRowsPage(true)
                }
            val chineseKeys = chinese.rows.flatMap { it.keys }
            val asciiKeys = ascii.rows.flatMap { it.keys }
            assertEquals(chineseKeys.map { it.action }, asciiKeys.map { it.action })
            for ((c, a) in chineseKeys.zip(asciiKeys)) {
                assertEquals(c.label, c.action.literalValue(false))
                assertEquals(a.label, a.action.literalValue(true))
            }
        }
    }
}

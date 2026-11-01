package app.songyan.ime.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The built-in categorized symbol page's data, plus the category rules ported from LayoutKitTests/KeyboardPageCategoryTests. */
class SymbolPageCategoriesTest {
    private fun key(
        text: String = "！",
        width: Double = 1.0,
    ) = LayoutKey(
        label = text,
        width = width,
        action = LayoutAction.literal(text),
        longPress = listOf(LayoutOption("!", LayoutAction.literal("!"))),
    )

    private fun page(categories: List<KeyboardPageCategory>) =
        KeyboardPageDefinition(
            id = "custom-symbols",
            name = "我的符号",
            kind = KeyboardPageKind.SYMBOLS,
            rows = emptyList(),
            symbolPresentation = KeyboardSymbolPresentation.CATEGORIZED,
            categories = categories,
        )

    private fun invalid(
        code: LayoutInvalidCode,
        categories: List<KeyboardPageCategory>,
    ) = assertEquals(code, assertThrows(LayoutException.Invalid::class.java) { page(categories).validate() }.code)

    @Test
    fun theDisplayOrderIsCommonRecentWidthsThenTheLibrary() {
        val library = listOf(SymbolPageCategories.LibrarySet("faces", "颜文字", listOf("(｡･ω･｡)", "(╯°□°)╯")))
        val shown = SymbolPageCategories.display(ascii = false, recents = listOf("★"), library = library)
        assertEquals(listOf("common", "recent", "chinese", "english", "brackets", "library-faces"), shown.map { it.id })
        // Without recents the tab is not there at all; the order of the others holds.
        assertEquals(
            listOf("common", "chinese", "english", "brackets"),
            SymbolPageCategories.display(ascii = false).map { it.id },
        )
    }

    @Test
    fun commonFollowsThePunctuationWidthAndEveryTabIsDuplicateFree() {
        val chinese = SymbolPageCategories.display(ascii = false).first()
        val ascii = SymbolPageCategories.display(ascii = true).first()
        assertEquals("，", chinese.symbols.first())
        assertEquals(",", ascii.symbols.first())
        for (category in SymbolPageCategories.display(ascii = false, recents = listOf("★", "★", ""))) {
            assertEquals(category.id, category.symbols.distinct(), category.symbols)
            assertTrue(category.id, category.symbols.none { it.isEmpty() })
        }
    }

    @Test
    fun aLibrarySetThatIsAlreadyCoveredIsNotRepeated() {
        val covered = SymbolPageCategories.LibrarySet("punct", "标点", listOf("，", "。", "？"))
        val fresh = SymbolPageCategories.LibrarySet("math", "数学", listOf("，", "∑"))
        val ids = SymbolPageCategories.display(false, library = listOf(covered, fresh)).map { it.id }
        assertFalse("library-punct" in ids)
        assertTrue("library-math" in ids)
    }

    @Test
    fun wideEntriesTakeTwoColumnsAndEverythingElseFour() {
        val faces = SymbolPageCategories.Category("faces", "颜文字", listOf("(｡･ω･｡)"))
        val marks = SymbolPageCategories.Category("marks", "符号", listOf("★", "☆", "※"))
        assertEquals(2, faces.columns)
        assertEquals(4, marks.columns)
    }

    @Test
    fun theSnapshotKeepsRecentLiveAndTheQuickSymbolsInCommon() {
        val snapshot =
            SymbolPageCategories.snapshot(
                recents = emptyList(),
                library = emptyList(),
                quickSymbols = listOf("@", "☆"),
            )
        val recent = snapshot.single { it.source == KeyboardPageCategory.Source.RECENT }
        assertEquals(1, snapshot.indexOf(recent))
        assertTrue(recent.keys.isEmpty())
        val common = snapshot.first { it.id == "common" }
        // "@" is already a fixed mark of 常用; the quick symbol it lacks is appended once.
        assertEquals(1, common.keys.count { it.action.value == "@" })
        assertEquals(
            "☆",
            common.keys
                .last()
                .action.value,
        )
        // The data is what a copy of the page stores, so it must be valid as it is.
        page(snapshot).validate()
        // A recent list that exists is not frozen into keys either.
        val withRecents = SymbolPageCategories.snapshot(listOf("★"), emptyList(), emptyList())
        assertEquals(1, withRecents.count { it.source == KeyboardPageCategory.Source.RECENT })
        assertTrue(withRecents.single { it.source == KeyboardPageCategory.Source.RECENT }.keys.isEmpty())
    }

    @Test
    fun resolvingFillsOnlyTheRecentTab() {
        val categories =
            listOf(
                KeyboardPageCategory("fixed", "自定", keys = listOf(key("。"))),
                KeyboardPageCategory("recent", "最近", keys = emptyList(), source = KeyboardPageCategory.Source.RECENT),
            )
        val resolved = SymbolPageCategories.resolved(categories, listOf("★", "☆"))
        assertEquals(categories[0], resolved[0])
        assertEquals(listOf("★", "☆"), resolved[1].keys.map { it.label })
    }

    @Test
    fun aCategorizedCopyKeepsEditableContentAndIndependentValueSemantics() {
        val original =
            page(
                listOf(
                    KeyboardPageCategory(
                        "punctuation",
                        "标点",
                        columns = 2,
                        keys = listOf(key(width = 2.0), key("？")),
                        split = LayoutSplitConfiguration.of(LayoutSplitConfiguration.Activation.ALWAYS, listOf(1), 0.2),
                    ),
                    KeyboardPageCategory(
                        "recent",
                        "最近",
                        keys = listOf(key("。")),
                        source = KeyboardPageCategory.Source.RECENT,
                    ),
                ),
            )
        val copy = original.duplicated("copy-id", "我的符号 副本")
        assertNotEquals(original.id, copy.id)
        assertEquals(KeyboardSymbolPresentation.CATEGORIZED, copy.symbolPresentation)
        assertEquals(original.categories, copy.categories)
        assertTrue(copy.rows.isEmpty())
        val renamed =
            copy.withCategories(
                copy.categories!!.mapIndexed { i, c ->
                    if (i ==
                        0
                    ) {
                        c.withName("常用标点").withKeys(c.keys.reversed())
                    } else {
                        c
                    }
                },
            )
        assertEquals("标点", original.categories!![0].name)
        assertEquals(
            2.0,
            original.categories!![0]
                .keys
                .first()
                .widthWeight,
            0.0,
        )
        assertEquals("常用标点", renamed.categories!![0].name)
        original.validate()
        copy.validate()
    }

    @Test
    fun categoriesAndTheRecentSourceSurviveACatalogSaveAndReload() {
        val symbols =
            page(
                listOf(
                    KeyboardPageCategory(
                        "fixed",
                        "自定",
                        columns = 3,
                        keys = listOf(key("。"), key(width = 2.0), key("？"), key("：")),
                        split =
                            LayoutSplitConfiguration.of(
                                LayoutSplitConfiguration.Activation.ALWAYS,
                                listOf(1, 2),
                                0.18,
                            ),
                    ),
                    KeyboardPageCategory(
                        "recent",
                        "刚用过",
                        columns = 2,
                        keys = emptyList(),
                        source = KeyboardPageCategory.Source.RECENT,
                    ),
                ),
            )
        val registry =
            LayoutRegistry
                .create()
                .withPage(symbols)
                .withComposition(
                    KeyboardComposition(mainLayoutID = BuiltinLayouts.qwerty26.id, symbolPageID = symbols.id),
                    "first:qwerty26",
                ).withComposition(KeyboardComposition(mainLayoutID = BuiltinLayouts.qwerty26.id), "second:qwerty26")
        val directory =
            java.nio.file.Files
                .createTempDirectory("categories-")
                .toString()
        LayoutStore(directory).save(registry)
        val loaded = LayoutStore(directory).load()
        assertEquals(symbols, loaded.page(symbols.id))
        assertEquals(symbols.id, loaded.compositions["first:qwerty26"]!!.symbolPageID)
        assertEquals(KeyboardPageCategory.Source.RECENT, loaded.page(symbols.id)!!.categories!![1].source)
        assertEquals(
            listOf(3, 1),
            loaded
                .page(symbols.id)!!
                .categories!![0]
                .rows
                .map { it.keys.size },
        )
    }

    @Test
    fun theRecentSourceKeepsItsFixedKeysWhenSwitchedBack() {
        val fixed = KeyboardPageCategory("c", "常用", columns = 2, keys = listOf(key(width = 2.0), key("？")))
        val recent = fixed.withSource(KeyboardPageCategory.Source.RECENT)
        val text = LayoutJson.format.encodeToString(KeyboardPageCategory.serializer(), recent)
        val restored = LayoutJson.format.decodeFromString(KeyboardPageCategory.serializer(), text).withSource(null)
        assertEquals(fixed.keys, restored.keys)
        restored.validate()
    }

    @Test
    fun recentSplitsDoNotDependOnTheFixedKeyBackup() {
        var recent =
            KeyboardPageCategory(
                "r",
                "最近",
                columns = 4,
                keys = emptyList(),
                split =
                    LayoutSplitConfiguration.of(
                        LayoutSplitConfiguration.Activation.ALWAYS,
                        listOf(1, 2, 3, 1, 2),
                        0.2,
                    ),
                source = KeyboardPageCategory.Source.RECENT,
            )
        page(listOf(recent)).validate()
        recent = recent.withKeys(listOf(key("。")))
        page(listOf(recent)).validate()
        // Without the recent source the stored cuts must fit the fixed keys, which are one row.
        assertThrows(LayoutException.Invalid::class.java) { page(listOf(recent.withSource(null))).validate() }
        val tooManyRows = List(LayoutResourceBudget.RECENT_CATEGORY_KEYS / 4 + 1) { 1 }
        val overflow =
            recent.withSplit(
                LayoutSplitConfiguration.of(LayoutSplitConfiguration.Activation.ALWAYS, tooManyRows, 0.2),
            )
        assertThrows(LayoutException.Invalid::class.java) { page(listOf(overflow)).validate() }
    }

    @Test
    fun browserAndRowPagesDecodeWithoutCategories() {
        val browser =
            LayoutJson.format.decodeFromString(
                KeyboardPageDefinition.serializer(),
                """{"id":"old-browser","name":"分类符号","kind":"symbols","rows":[],"symbolPresentation":"categorized"}""",
            )
        assertNull(browser.categories)
        browser.validate()
        val row = KeyboardPagePresets.numericRowsPage(false)
        val restored =
            LayoutJson.format.decodeFromString(
                KeyboardPageDefinition.serializer(),
                LayoutJson.format.encodeToString(KeyboardPageDefinition.serializer(), row),
            )
        assertEquals(row, restored)
        restored.validate()
    }

    @Test
    fun categoryValidationRejectsEmptyDuplicateAndInvalidContent() {
        invalid(LayoutInvalidCode.PAGE_CATEGORIES_REQUIRED, emptyList())
        val fixed = KeyboardPageCategory("same", "常用", keys = listOf(key()))
        invalid(LayoutInvalidCode.PAGE_CATEGORY_IDS_DUPLICATED, listOf(fixed, fixed))
        invalid(
            LayoutInvalidCode.PAGE_CATEGORY_KEYS_REQUIRED,
            listOf(KeyboardPageCategory("empty", "空分类", keys = emptyList())),
        )
        page(
            listOf(
                KeyboardPageCategory("recent", "最近", keys = emptyList(), source = KeyboardPageCategory.Source.RECENT),
            ),
        ).validate()
        for (columns in listOf(0, LayoutResourceBudget.CATEGORY_COLUMNS + 1)) {
            invalid(
                LayoutInvalidCode.PAGE_CATEGORY_COLUMNS_OUT_OF_RANGE,
                listOf(KeyboardPageCategory("c", "列数", columns = columns, keys = listOf(key()))),
            )
        }
        for (width in listOf(0.0, -1.0, Double.POSITIVE_INFINITY, Double.NaN)) {
            invalid(
                LayoutInvalidCode.KEY_REQUIRES_LABEL_AND_POSITIVE_FINITE_WIDTH,
                listOf(KeyboardPageCategory("w", "宽度", keys = listOf(key(width = width)))),
            )
        }
        val engineKey = LayoutKey("a", action = LayoutAction.code("a"))
        invalid(
            LayoutInvalidCode.PAGE_KEY_MUST_NOT_SEND_A_CODE,
            listOf(KeyboardPageCategory("e", "输入", keys = listOf(engineKey))),
        )
        val engineAlternate =
            LayoutKey(
                "。",
                action = LayoutAction.literal("。"),
                longPress = listOf(LayoutOption("a", LayoutAction.code("a"))),
            )
        invalid(
            LayoutInvalidCode.PAGE_KEY_MUST_NOT_SEND_A_CODE,
            listOf(KeyboardPageCategory("l", "长按", keys = listOf(engineAlternate))),
        )
    }

    @Test
    fun fullCategoriesUseTheirOwnBudgetAndRejectOverflow() {
        page(listOf(KeyboardPageCategory("full", "主题", keys = List(300) { key() }))).validate()
        val tooMany =
            KeyboardPageCategory("big", "过多", keys = List(LayoutResourceBudget.CATEGORY_KEYS + 1) { key() })
        assertThrows(LayoutException.Invalid::class.java) { page(listOf(tooMany)).validate() }
        val crowded =
            List(
                3,
            ) { KeyboardPageCategory("full-$it", "主题", keys = List(LayoutResourceBudget.CATEGORY_KEYS) { key() }) }
        assertThrows(LayoutException.Invalid::class.java) { page(crowded).validate() }
        val categories =
            List(
                LayoutResourceBudget.PAGE_CATEGORIES + 1,
            ) { KeyboardPageCategory("category-$it", "主题", keys = listOf(key())) }
        assertThrows(LayoutException.Invalid::class.java) { page(categories).validate() }
        assertThrows(LayoutException.Invalid::class.java) {
            page(listOf(KeyboardPageCategory("x".repeat(257), "主题", keys = listOf(key())))).validate()
        }
    }
}

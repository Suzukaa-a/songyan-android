package app.songyan.ime.layout

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

/** Ported from LayoutKitTests: page/catalog persistence, pairings and preset restoration. */
class KeyboardPageRegistryTest {
    private fun tempDirectory(): String = Files.createTempDirectory("layout-page-").toString()

    private fun pageJson(pages: List<KeyboardPageDefinition>): String =
        LayoutJson.format.encodeToString(ListSerializer(KeyboardPageDefinition.serializer()), pages)

    private fun layoutsJson(layouts: List<LayoutDefinition>): String =
        LayoutJson.format.encodeToString(ListSerializer(LayoutDefinition.serializer()), layouts)

    private fun compositionsJson(compositions: Map<String, KeyboardComposition>): String =
        LayoutJson.format.encodeToString(
            MapSerializer(String.serializer(), KeyboardComposition.serializer()),
            compositions,
        )

    private fun catalogText(
        layouts: List<LayoutDefinition> = listOf(BuiltinLayouts.qwerty26),
        pages: List<KeyboardPageDefinition>? = null,
        pageOrder: List<String>? = null,
        compositions: Map<String, KeyboardComposition>? = null,
    ): String {
        val parts =
            mutableListOf<Pair<String, kotlinx.serialization.json.JsonElement>>(
                "layouts" to
                    LayoutJson.format.encodeToJsonElement(
                        ListSerializer(LayoutDefinition.serializer()),
                        layouts,
                    ),
            )
        if (pages != null) {
            parts.add(
                "pages" to
                    LayoutJson.format.encodeToJsonElement(ListSerializer(KeyboardPageDefinition.serializer()), pages),
            )
        }
        if (pageOrder != null) {
            parts.add(
                "pageOrder" to LayoutJson.format.encodeToJsonElement(ListSerializer(String.serializer()), pageOrder),
            )
        }
        if (compositions != null) {
            parts.add(
                "compositions" to
                    LayoutJson.format.encodeToJsonElement(
                        MapSerializer(String.serializer(), KeyboardComposition.serializer()),
                        compositions,
                    ),
            )
        }
        return JsonObject(parts.toMap()).toString()
    }

    private fun loadCatalog(text: String): LayoutRegistry {
        val directory = tempDirectory()
        try {
            val store = LayoutStore(directory)
            Files.createDirectories(Paths.get(directory))
            Files.writeString(Paths.get(store.catalogPath), text)
            return store.load()
        } finally {
            Paths.get(directory).toFile().deleteRecursively()
        }
    }

    private fun roundTrip(registry: LayoutRegistry): LayoutRegistry {
        val directory = tempDirectory()
        try {
            val store = LayoutStore(directory)
            store.save(registry)
            return store.load()
        } finally {
            Paths.get(directory).toFile().deleteRecursively()
        }
    }

    private fun userPage(id: String): KeyboardPageDefinition =
        KeyboardPageDefinition(
            id = id,
            name = "我的页",
            kind = KeyboardPageKind.NUMERIC,
            rows = listOf(LayoutRow(listOf(LayoutKey("1", action = LayoutAction.literal("1"))))),
        )

    // MARK: - Wire compatibility

    @Test
    fun legacyCatalogLoadsDefaultsWithoutRewritingLayouts() {
        val registry = loadCatalog(catalogText())
        registry.validate()
        assertEquals(listOf(BuiltinLayouts.qwerty26), registry.layouts)
        assertEquals(KeyboardPagePresets.all, registry.pages)
        assertTrue(registry.compositions.isEmpty())
    }

    private val retiredIds =
        listOf("page.numeric.chinese", "page.numeric.english", "page.symbols.chinese", "page.symbols.english")

    private fun retiredCopy(
        id: String,
        kind: KeyboardPageKind,
        ascii: Boolean,
    ): KeyboardPageDefinition {
        val rows =
            if (kind == KeyboardPageKind.NUMERIC) {
                KeyboardPagePresets.numericRowsPage(ascii).rows
            } else {
                KeyboardPagePresets.symbolRowsPage(ascii).rows
            }
        return KeyboardPageDefinition(
            id = id,
            name =
                when {
                    kind == KeyboardPageKind.NUMERIC -> if (ascii) "英文 123" else "中文 123"
                    else -> if (ascii) "英文符号" else "中文符号"
                },
            kind = kind,
            rows = rows,
            defaultSymbolMarks = if (ascii) KeyboardNumericMarks.ASCII else KeyboardNumericMarks.CHINESE,
        )
    }

    @Test
    fun theLibraryListsOneBilingualPagePerShapeAndNoRetiredPage() {
        val ids = LayoutRegistry.create().pages.map { it.id }
        assertTrue(ids.none { it in retiredIds })
        val numeric = LayoutRegistry.create().pages.filter { it.kind == KeyboardPageKind.NUMERIC }
        assertEquals(
            listOf(KeyboardPagePresets.NUMERIC_AUTOMATIC_ID, KeyboardPagePresets.NUMERIC_KEYPAD_ID),
            numeric.map { it.id },
        )
        assertNull(KeyboardPagePresets.numericAutomatic.defaultSymbolMarks)
        assertNull(KeyboardPagePresets.symbolsSystem.defaultSymbolMarks)
    }

    @Test
    fun aPairingThatNamesARetiredPageResolvesToTheMergedPage() {
        val stored =
            listOf(
                retiredCopy("page.numeric.chinese", KeyboardPageKind.NUMERIC, ascii = false),
                retiredCopy("page.numeric.english", KeyboardPageKind.NUMERIC, ascii = true),
                retiredCopy("page.symbols.chinese", KeyboardPageKind.SYMBOLS, ascii = false),
                retiredCopy("page.symbols.english", KeyboardPageKind.SYMBOLS, ascii = true),
            )
        val loaded =
            loadCatalog(
                catalogText(
                    pages = stored,
                    pageOrder = retiredIds + KeyboardPagePresets.all.map { it.id },
                    compositions =
                        mapOf(
                            "quanpin:qwerty26" to
                                KeyboardComposition(
                                    mainLayoutID = BuiltinLayouts.qwerty26.id,
                                    numericPageID = "page.numeric.english",
                                    symbolPageID = "page.symbols.chinese",
                                ),
                        ),
                ),
            )
        loaded.validate()
        assertTrue(loaded.pages.none { it.id in retiredIds })
        val pairing = loaded.compositions.getValue("quanpin:qwerty26")
        // The Chinese ids resolve to the plain pages; the English id keeps its English marks.
        assertEquals(KeyboardPagePresets.SYMBOLS_SYSTEM_ID, pairing.symbolPageID)
        val numeric = loaded.page(pairing.numericPageID)!!
        assertEquals(KeyboardNumericPresentation.AUTOMATIC, numeric.numericPresentation)
        assertEquals(KeyboardNumericMarks.ASCII, numeric.defaultSymbolMarks)
        assertTrue(loaded.page(pairing.symbolPageID)!!.defaultSymbolMarks == null)
    }

    @Test
    fun aPairingThatNamedTheRetiredEnglishSymbolPageKeepsEnglishMarks() {
        val loaded =
            loadCatalog(
                catalogText(
                    pages = listOf(retiredCopy("page.symbols.english", KeyboardPageKind.SYMBOLS, ascii = true)),
                    compositions =
                        mapOf(
                            "quanpin:qwerty26" to
                                KeyboardComposition(
                                    mainLayoutID = BuiltinLayouts.qwerty26.id,
                                    symbolPageID = "page.symbols.english",
                                ),
                        ),
                ),
            )
        loaded.validate()
        val page = loaded.page(loaded.compositions.getValue("quanpin:qwerty26").symbolPageID)!!
        assertEquals(KeyboardSymbolPresentation.AUTOMATIC, page.symbolPresentation)
        assertEquals(KeyboardNumericMarks.ASCII, page.defaultSymbolMarks)
    }

    @Test
    fun aStoredCopyOfThePreviousKeypadGivesWayToTheCurrentOne() {
        val previous =
            KeyboardPagePresets.numericKeypad.copy(
                chrome = KeyboardPageDefinition.previousKeypadChrome(KeyboardNumericMarks.CHINESE),
            )
        val edited = previous.withName("我的数字盘")
        for ((stored, expected) in listOf(previous to KeyboardPagePresets.numericKeypad, edited to edited)) {
            val loaded =
                loadCatalog(
                    catalogText(
                        pages = listOf(stored),
                        compositions =
                            mapOf(
                                "quanpin:qwerty26" to
                                    KeyboardComposition(
                                        mainLayoutID = BuiltinLayouts.qwerty26.id,
                                        numericPageID = KeyboardPagePresets.NUMERIC_KEYPAD_ID,
                                    ),
                            ),
                    ),
                )
            loaded.validate()
            // The untouched old arrangement is replaced; a page the user edited stays theirs.
            assertEquals(expected, loaded.page(KeyboardPagePresets.NUMERIC_KEYPAD_ID))
            assertEquals(
                KeyboardPagePresets.NUMERIC_KEYPAD_ID,
                loaded.compositions.getValue("quanpin:qwerty26").numericPageID,
            )
        }
    }

    @Test
    fun aRetiredPageTheUserEditedStaysTheirOwn() {
        val edited = retiredCopy("page.symbols.english", KeyboardPageKind.SYMBOLS, ascii = true).withName("我的英文符号")
        val loaded =
            loadCatalog(
                catalogText(
                    pages = listOf(edited),
                    compositions =
                        mapOf(
                            "quanpin:qwerty26" to
                                KeyboardComposition(
                                    mainLayoutID = BuiltinLayouts.qwerty26.id,
                                    symbolPageID = edited.id,
                                ),
                        ),
                ),
            )
        loaded.validate()
        assertEquals(edited, loaded.page(edited.id))
        assertEquals(edited.id, loaded.compositions.getValue("quanpin:qwerty26").symbolPageID)
    }

    @Test
    fun oldCatalogWithExplicitPagesGainsTheAutomaticPage() {
        val oldPages =
            KeyboardPagePresets.all.filter {
                it.id != KeyboardPagePresets.NUMERIC_AUTOMATIC_ID && it.id != KeyboardPagePresets.NUMERIC_KEYPAD_ID
            }
        assertEquals(3, oldPages.size)
        val myPage = KeyboardPagePresets.numericAutomatic.duplicated("user.numeric", "我的数字页")
        val editedSymbols = KeyboardPagePresets.symbolsSystem.withName("我改过名字的符号页")
        val storedPages = oldPages.filter { it.id != editedSymbols.id } + editedSymbols + myPage
        val oldComposition =
            KeyboardComposition(
                mainLayoutID = BuiltinLayouts.qwerty26.id,
                numericPageID = "page.numeric.chinese",
            )
        val loaded =
            loadCatalog(
                catalogText(
                    layouts = listOf(BuiltinLayouts.qwerty26, BuiltinLayouts.merged14),
                    pages = storedPages,
                    compositions = mapOf("quanpin:qwerty26" to oldComposition),
                ),
            )
        loaded.validate()
        assertNotNull(loaded.page(KeyboardPagePresets.NUMERIC_AUTOMATIC_ID))
        assertEquals(myPage, loaded.page(myPage.id))
        assertEquals("我改过名字的符号页", loaded.page(KeyboardPagePresets.SYMBOLS_SYSTEM_ID)?.name)
        // The retired Chinese 123 page is the automatic page now; the pairing follows it.
        assertEquals(
            oldComposition.copy(numericPageID = KeyboardPagePresets.NUMERIC_AUTOMATIC_ID),
            loaded.compositions["quanpin:qwerty26"],
        )

        // A pairing saved now can reference the automatic page; the write must survive a reload.
        val writable =
            loaded.withComposition(
                KeyboardComposition(mainLayoutID = BuiltinLayouts.merged14.id),
                "quanpin:merged14",
            )
        val reloaded = roundTrip(writable)
        val numericId = reloaded.compositions["quanpin:merged14"]?.numericPageID
        assertEquals(KeyboardPagePresets.NUMERIC_AUTOMATIC_ID, numericId)
        assertEquals(
            KeyboardNumericPresentation.AUTOMATIC,
            reloaded.page(requireNotNull(numericId))?.numericPresentation,
        )
        assertEquals(
            KeyboardPagePresets.NUMERIC_AUTOMATIC_ID,
            reloaded.compositions["quanpin:qwerty26"]?.numericPageID,
        )
        assertEquals(myPage, reloaded.page(myPage.id))
    }

    @Test
    fun catalogAtThePageBudgetStillLoadsAfterTheAutomaticPageIsMerged() {
        val oldPresets = KeyboardPagePresets.all.filter { it.id != KeyboardPagePresets.NUMERIC_AUTOMATIC_ID }
        val userPages =
            (0 until (LayoutResourceBudget.PAGES - oldPresets.size)).map { index ->
                userPage("my-page-" + index)
            }
        val storedPages = oldPresets + userPages
        assertEquals(LayoutResourceBudget.PAGES, storedPages.size)
        val loaded = loadCatalog(catalogText(pages = storedPages))
        loaded.validate()
        assertNotNull(loaded.page(KeyboardPagePresets.NUMERIC_AUTOMATIC_ID))
        assertEquals(LayoutResourceBudget.PAGES + 1, loaded.pages.size)
        assertEquals(userPages[0], loaded.page(userPages[0].id))
        val reloaded = roundTrip(loaded)
        assertEquals(LayoutResourceBudget.PAGES + 1, reloaded.pages.size)
        assertEquals(userPages[0], reloaded.page(userPages[0].id))
    }

    @Test
    fun wireOmitsUnchangedPresetsAndKeepsAModifiedOne() {
        val user = userPage("user.1")
        val modifiedPreset = KeyboardPagePresets.symbolsSystem.withName("我改过的符号页")
        val registry =
            LayoutRegistry
                .create()
                .withPage(user)
                .withPage(modifiedPreset)
        val text = LayoutRegistryCodec.encode(registry)
        val wirePageIds =
            kotlinx.serialization.json.Json
                .parseToJsonElement(text)
                .let { it as kotlinx.serialization.json.JsonObject }["pages"]
                .let { it as kotlinx.serialization.json.JsonArray }
                .map { element ->
                    val id = (element as kotlinx.serialization.json.JsonObject)["id"]
                    (id as kotlinx.serialization.json.JsonPrimitive).content
                }
        assertEquals(setOf(user.id, modifiedPreset.id), wirePageIds.toSet())

        val directory = tempDirectory()
        try {
            val store = LayoutStore(directory)
            store.save(registry)
            val reloaded = store.load()
            assertEquals(registry, reloaded)
            assertEquals("我改过的符号页", reloaded.page(modifiedPreset.id)?.name)
            assertEquals(
                KeyboardPagePresets.all.size,
                reloaded.pages.count { KeyboardPagePresets.codeOwnedIds.contains(it.id) },
            )
        } finally {
            Paths.get(directory).toFile().deleteRecursively()
        }
    }

    // MARK: - Pairings

    @Test
    fun twoKeyboardsKeepIndependentPageSelectionsAcrossStoreRoundTrip() {
        val custom =
            KeyboardPagePresets
                .numericRowsPage(false)
                .duplicated("user.numeric", "我的数字")
                .withRows(
                    listOf(
                        LayoutRow(
                            listOf(
                                LayoutKey(
                                    "一",
                                    width = 2.0,
                                    action = LayoutAction.literal("1"),
                                    longPress = listOf(LayoutOption("①", LayoutAction.literal("①"))),
                                ),
                                LayoutKey("二", action = LayoutAction.literal("2")),
                            ),
                        ),
                    ),
                ).withSplit(LayoutSplitConfiguration.of(rowBreaks = listOf(1), gapFraction = 0.2))
        val registry = LayoutRegistry.create().withPage(custom)
        val original = KeyboardComposition(mainLayoutID = BuiltinLayouts.qwerty26.id)
        val changed = original.copy(numericPageID = custom.id)
        val saved =
            registry
                .withComposition(original, "first:qwerty26")
                .withComposition(changed, "second:qwerty26")
        val loaded = roundTrip(saved)
        assertEquals(original, loaded.compositions["first:qwerty26"])
        assertEquals(changed, loaded.compositions["second:qwerty26"])
        assertEquals(custom, loaded.page(custom.id))
        assertEquals(custom.split, loaded.page(custom.id)?.layoutDefinition?.split)
    }

    @Test
    fun invalidCompositionAndReferencedRemovalLeaveCatalogIntact() {
        val custom = KeyboardPagePresets.symbolsSystem.duplicated("user.symbols", "我的符号")
        val registry = LayoutRegistry.create().withPage(custom)
        val composition =
            KeyboardComposition(
                mainLayoutID = BuiltinLayouts.qwerty26.id,
                symbolPageID = custom.id,
            )
        val saved = registry.withComposition(composition, "test:qwerty26")
        assertEquals(composition, saved.compositions["test:qwerty26"])

        assertThrows(LayoutException::class.java) {
            saved.withComposition(composition.copy(numericPageID = custom.id), "other:qwerty26")
        }
        assertThrows(LayoutException::class.java) { saved.withoutPage(custom.id) }
        assertThrows(LayoutException::class.java) { saved.remove(composition.mainLayoutID) }
        assertThrows(LayoutException::class.java) {
            saved.withComposition(composition.copy(mainLayoutID = "missing"), "other:qwerty26")
        }
        assertEquals(composition, saved.compositions["test:qwerty26"])
        assertEquals(custom, saved.page(custom.id))
    }

    @Test
    fun symbolBrowsersRemainSelectableAndCopiesRetainTheirPresentation() {
        val registry = LayoutRegistry.create()
        assertEquals(
            KeyboardSymbolPresentation.AUTOMATIC,
            registry.page(KeyboardPagePresets.SYMBOLS_SYSTEM_ID)?.symbolPresentation,
        )
        assertEquals(
            KeyboardSymbolPresentation.CATEGORIZED,
            registry.page(KeyboardPagePresets.SYMBOLS_CATEGORIZED_ID)?.symbolPresentation,
        )
        val copy = KeyboardPagePresets.symbolsCategorized.duplicated("user.categorized", "我的分类符号")
        assertNotEquals(KeyboardPagePresets.SYMBOLS_CATEGORIZED_ID, copy.id)
        assertEquals(KeyboardSymbolPresentation.CATEGORIZED, copy.symbolPresentation)
        assertNull(copy.categories)
        assertEquals(KeyboardPagePresets.symbolsCategorized.rows, copy.rows)
        copy.validate()
    }

    @Test
    fun boundLayoutsCanOnlyBeCombinedWithTheirOwnInputMethod() {
        val registry = LayoutRegistry.create()
        val layout =
            LayoutDefinition(
                id = "bound",
                name = "我的布局",
                input = LayoutInput(schemaID = "my_method"),
                rows = BuiltinLayouts.qwerty26.rows,
            )
        val installed = registry.install(layout.encoded())
        val composition = KeyboardComposition(mainLayoutID = layout.id)
        assertNotNull(installed.withComposition(composition, "my_method:qwerty26"))
        assertThrows(LayoutException::class.java) {
            installed.withComposition(composition, "other:qwerty26")
        }
    }

    @Test
    fun pagePairingsArePrunedOnlyWhenTheInputMethodLostEveryProvider() {
        fun registryWith(ids: List<String>): LayoutRegistry {
            var registry = LayoutRegistry.create()
            for (id in ids) {
                val separator = id.lastIndexOf(':')
                val methodId = id.substring(0, separator)
                val kind =
                    when (id.substring(separator + 1)) {
                        "qwerty26" -> LayoutInput.Builtin.QWERTY26
                        "merged14" -> LayoutInput.Builtin.MERGED14
                        else -> error("unsupported keyboard id " + id)
                    }
                val main =
                    requireNotNull(
                        BuiltinLayouts.all.first { it.supportsComposition(methodId, kind) },
                    )
                registry = registry.withComposition(KeyboardComposition(mainLayoutID = main.id), id)
            }
            return registry
        }

        val removedRegistry = registryWith(listOf("removed_method:qwerty26"))
        val (pruned, removed) = removedRegistry.withPrunedCompositions { it == "removed_method" }
        assertEquals(1, removed)
        assertNull(pruned.compositions["removed_method:qwerty26"])

        val kept = registryWith(listOf("shared_method:qwerty26", "shared_method:merged14"))
        val providers = setOf("shared_method")
        assertEquals(0, kept.withPrunedCompositions { it !in providers }.second)
        assertEquals(2, kept.compositions.size)

        val untouched = registryWith(listOf("present_method:qwerty26"))
        val (same, none) = untouched.withPrunedCompositions { false }
        assertEquals(0, none)
        assertEquals(untouched, same)

        // The same input method on another layout goes only when that method has no provider.
        val both = registryWith(listOf("gone_method:qwerty26", "gone_method:merged14"))
        val (cleaned, count) = both.withPrunedCompositions { it == "gone_method" }
        assertEquals(2, count)
        assertTrue(cleaned.compositions.isEmpty())
    }

    @Test
    fun pageAndLayoutBecomeRemovableAfterCleanup() {
        val page = KeyboardPagePresets.numericAutomatic.duplicated("user.numeric", "我的数字")
        val layout = BuiltinLayouts.qwerty26.copyWithId("user.layout", "我的布局")
        var registry = LayoutRegistry.create().withPage(page).install(layout.encoded())
        registry =
            registry.withComposition(
                KeyboardComposition(mainLayoutID = layout.id, numericPageID = page.id),
                "gone_method:qwerty26",
            )
        assertThrows(LayoutException::class.java) { registry.withoutPage(page.id) }
        assertThrows(LayoutException::class.java) { registry.remove(layout.id) }

        val (cleaned, removed) = registry.withPrunedCompositions { it == "gone_method" }
        assertEquals(1, removed)
        val afterPage = cleaned.withoutPage(page.id)
        val afterLayout = afterPage.remove(layout.id)
        assertNull(afterLayout.page(page.id))
        assertNull(afterLayout.layout(layout.id))
    }

    // MARK: - Preset restoration

    @Test
    fun restoringPresetsKeepsPairingsEnableStateAndUserCopies() {
        val directory = tempDirectory()
        try {
            val store = LayoutStore(directory)
            val mine = userPage("user.copy")
            val myLayout = BuiltinLayouts.qwerty26.copyWithId("user.layout", "我的 26 键")
            var registry =
                LayoutRegistry
                    .create()
                    .withPage(mine)
                    .install(myLayout.encoded())
            // A retired preset the user edited stays as their own page, under its id, with its pairing.
            val editedRetired =
                KeyboardPagePresets.numericRowsPage(false).copy(id = "page.numeric.chinese", name = "旧版中文 123")
            registry = registry.withPage(editedRetired)
            val pairing =
                KeyboardComposition(
                    mainLayoutID = BuiltinLayouts.merged14.id,
                    numericPageID = editedRetired.id,
                )
            registry = registry.withComposition(pairing, "some_pinyin:merged14")
            store.save(registry)
            val loaded = store.load()
            assertEquals(editedRetired, loaded.page(editedRetired.id))
            assertEquals(pairing, loaded.compositions["some_pinyin:merged14"])
            assertEquals(mine, loaded.page(mine.id))
            assertNotEquals(KeyboardPagePresets.numericRowsPage(false), mine)
        } finally {
            Paths.get(directory).toFile().deleteRecursively()
        }
    }

    // MARK: - Limits

    @Test
    fun presetIdsDoNotSpendTheUserPageAllowanceButOverflowIsRejected() {
        val userPages = (0 until LayoutResourceBudget.PAGES).map { userPage("user.tiny." + it) }
        val registry = LayoutRegistry.create(pages = userPages + KeyboardPagePresets.all)
        registry.validate()
        assertEquals(LayoutResourceBudget.PAGES + KeyboardPagePresets.all.size, registry.pages.size)
        val error =
            assertThrows(LayoutException::class.java) {
                registry.withPage(userPage("user.over"))
            }
        assertEquals(LayoutInvalidCode.REGISTRY_PAGE_LIMIT, (error as LayoutException.Invalid).code)
    }

    @Test
    fun repeatedPageIdsStayVisibleAndAreRejected() {
        val page = userPage("dup.page")
        val encodedPage = pageJson(listOf(page)).removePrefix("[").removeSuffix("]")
        for (includeOrder in listOf(false, true)) {
            val text =
                catalogText().removeSuffix("}") +
                    ",\"pages\":[" + encodedPage + "," + encodedPage + "]" +
                    (if (includeOrder) ",\"pageOrder\":[\"dup.page\"]" else "") +
                    "}"
            val decoded = LayoutRegistryCodec.decode(text)
            assertEquals(2, decoded.pages.count { it.id == "dup.page" })
            assertThrows(LayoutException::class.java) { decoded.validate() }
        }
    }

    @Test
    fun pagesMustKeepAUniqueId() {
        val page = userPage("same.page")
        assertThrows(LayoutException::class.java) {
            LayoutRegistry.create(pages = KeyboardPagePresets.all + page + page)
        }
    }
}

/** Ported from LayoutKitTests/KeyboardPageCategoryTests. */
class KeyboardPageCategoryTest {
    private fun key(
        text: String = "！",
        width: Double = 1.0,
    ): LayoutKey =
        LayoutKey(
            text,
            width = width,
            action = LayoutAction.literal(text),
            longPress = listOf(LayoutOption("!", LayoutAction.literal("!"))),
        )

    private fun page(categories: List<KeyboardPageCategory>?): KeyboardPageDefinition =
        KeyboardPageDefinition(
            id = "custom-symbols",
            name = "我的符号",
            kind = KeyboardPageKind.SYMBOLS,
            rows = emptyList(),
            symbolPresentation = KeyboardSymbolPresentation.CATEGORIZED,
            categories = categories,
        )

    @Test
    fun categorizedCopyKeepsEditableContentAndIndependentValueSemantics() {
        val original =
            page(
                listOf(
                    KeyboardPageCategory(
                        id = "punctuation",
                        name = "标点",
                        columns = 2,
                        keys = listOf(key(width = 2.0), key("？")),
                        split = LayoutSplitConfiguration.of(rowBreaks = listOf(1), gapFraction = 0.2),
                    ),
                    KeyboardPageCategory(
                        id = "recent",
                        name = "最近",
                        keys = listOf(key("。")),
                        source = KeyboardPageCategory.Source.RECENT,
                    ),
                ),
            )
        val copy = original.duplicated("copy.symbols", "我的符号副本")
        assertNotEquals(original.id, copy.id)
        assertEquals(KeyboardSymbolPresentation.CATEGORIZED, copy.symbolPresentation)
        assertEquals(original.categories, copy.categories)
        assertTrue(copy.rows.isEmpty())
        val editedCategory = requireNotNull(copy.categories)[0].withName("常用标点")
        assertEquals("标点", requireNotNull(original.categories)[0].name)
        assertEquals(2.0, requireNotNull(original.categories)[0].keys.first().widthWeight, 0.0)
        assertEquals("常用标点", editedCategory.name)
        original.validate()
        copy.validate()
    }

    @Test
    fun categoriesAndRecentSourceSurviveCatalogSaveAndReload() {
        val symbols =
            page(
                listOf(
                    KeyboardPageCategory(
                        id = "fixed",
                        name = "自定",
                        columns = 3,
                        keys = listOf(key("。"), key(width = 2.0), key("？"), key("：")),
                        split = LayoutSplitConfiguration.of(rowBreaks = listOf(1, 2), gapFraction = 0.18),
                    ),
                    KeyboardPageCategory(
                        id = "recent",
                        name = "刚用过",
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
                    KeyboardComposition(
                        mainLayoutID = BuiltinLayouts.qwerty26.id,
                        symbolPageID = symbols.id,
                    ),
                    "first:qwerty26",
                )
        val directory = Files.createTempDirectory("layout-category-").toString()
        try {
            val store = LayoutStore(directory)
            store.save(registry)
            val loaded = store.load()
            assertEquals(symbols, loaded.page(symbols.id))
            assertEquals(symbols.id, loaded.compositions["first:qwerty26"]?.symbolPageID)
            // A keyboard without a saved pairing keeps the default symbol page.
            assertEquals(
                KeyboardPagePresets.SYMBOLS_SYSTEM_ID,
                loaded.composition(KeyboardCompositionKey("second", LayoutInput.Builtin.QWERTY26)).symbolPageID,
            )
            assertEquals(
                KeyboardPageCategory.Source.RECENT,
                loaded
                    .page(symbols.id)
                    ?.categories
                    ?.get(1)
                    ?.source,
            )
            assertEquals(
                listOf(3, 1),
                loaded
                    .page(symbols.id)
                    ?.categories
                    ?.get(0)
                    ?.rows
                    ?.map { it.keys.size },
            )
        } finally {
            Paths.get(directory).toFile().deleteRecursively()
        }
    }

    @Test
    fun recentSourcePreservesFixedKeysWhenSwitchedBack() {
        val category =
            KeyboardPageCategory(
                name = "常用",
                id = "cate.recent",
                columns = 2,
                keys = listOf(key(width = 2.0), key("？")),
            )
        val authored = category.keys
        val restored = category.withSource(KeyboardPageCategory.Source.RECENT)
        assertEquals(authored, restored.withSource(KeyboardPageCategory.Source.FIXED).keys)
        restored.withSource(KeyboardPageCategory.Source.FIXED).validate()
    }

    @Test
    fun recentSplitsDoNotDependOnFixedKeyBackup() {
        val recent =
            KeyboardPageCategory(
                id = "cate.recent",
                name = "最近",
                columns = 4,
                keys = emptyList(),
                split = LayoutSplitConfiguration.of(rowBreaks = listOf(1, 2, 3, 1, 2), gapFraction = 0.2),
                source = KeyboardPageCategory.Source.RECENT,
            )
        page(listOf(recent)).validate()
        val withKey = recent.withKeys(listOf(key("。")))
        page(listOf(withKey)).validate()
        assertEquals(listOf(1, 2, 3, 1, 2), withKey.split?.rowBreaks)
        // A fixed category with recent-only splits is rejected.
        assertThrows(LayoutException::class.java) {
            page(listOf(withKey.withSource(KeyboardPageCategory.Source.FIXED))).validate()
        }
        assertThrows(LayoutException::class.java) {
            page(
                listOf(
                    withKey.withSplit(LayoutSplitConfiguration(rowBreaks = listOf(4))),
                ),
            ).validate()
        }
    }

    @Test
    fun categoryValidationRejectsEmptyDuplicateAndInvalidContent() {
        assertThrows(LayoutException::class.java) { page(emptyList()).validate() }
        val fixed = KeyboardPageCategory(id = "same", name = "常用", keys = listOf(key()))
        assertThrows(LayoutException::class.java) { page(listOf(fixed, fixed)).validate() }
        assertThrows(LayoutException::class.java) {
            page(listOf(KeyboardPageCategory(id = "empty", name = "空分类", keys = emptyList()))).validate()
        }
        page(
            listOf(
                KeyboardPageCategory(
                    id = "recent",
                    name = "最近",
                    keys = emptyList(),
                    source = KeyboardPageCategory.Source.RECENT,
                ),
            ),
        ).validate()
        for (columns in listOf(0, LayoutResourceBudget.CATEGORY_COLUMNS + 1)) {
            assertThrows(LayoutException::class.java) {
                page(
                    listOf(KeyboardPageCategory(id = "cols", name = "列数", columns = columns, keys = listOf(key()))),
                ).validate()
            }
        }
        for (width in listOf(0.0, -1.0, Double.POSITIVE_INFINITY, Double.NaN)) {
            assertThrows(LayoutException::class.java) {
                page(listOf(KeyboardPageCategory(id = "w", name = "宽度", keys = listOf(key(width = width))))).validate()
            }
        }
        assertThrows(LayoutException::class.java) {
            page(
                listOf(
                    KeyboardPageCategory(
                        id = "engine",
                        name = "输入",
                        keys = listOf(LayoutKey("a", action = LayoutAction.code("a"))),
                    ),
                ),
            ).validate()
        }
    }

    @Test
    fun fullCategoriesUseTheirOwnBudgetAndRejectOverflow() {
        val full =
            KeyboardPageCategory(
                id = "full",
                name = "主题",
                keys = List(300) { key() },
            )
        page(listOf(full)).validate()
        assertFalse(full.rows.isEmpty())
        assertThrows(LayoutException::class.java) {
            page(
                listOf(
                    KeyboardPageCategory(
                        id = "over",
                        name = "过多",
                        keys = List(LayoutResourceBudget.CATEGORY_KEYS + 1) { key() },
                    ),
                ),
            ).validate()
        }
        val tooManyCategories =
            (0..LayoutResourceBudget.PAGE_CATEGORIES).map { index ->
                KeyboardPageCategory(id = "category-" + index, name = "主题", keys = listOf(key()))
            }
        assertThrows(LayoutException::class.java) { page(tooManyCategories).validate() }
        val longId =
            KeyboardPageCategory(id = "x".repeat(257), name = "主题", keys = listOf(key()))
        assertThrows(LayoutException::class.java) { page(listOf(longId)).validate() }
    }
}

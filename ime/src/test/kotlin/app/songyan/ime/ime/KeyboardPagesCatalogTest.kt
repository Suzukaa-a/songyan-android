package app.songyan.ime.ime

import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.layout.BuiltinLayouts
import app.songyan.ime.layout.KeyboardComposition
import app.songyan.ime.layout.KeyboardPageCategory
import app.songyan.ime.layout.KeyboardPageKind
import app.songyan.ime.layout.KeyboardPagePresets
import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutDefinition
import app.songyan.ime.layout.LayoutInput
import app.songyan.ime.layout.LayoutKey
import app.songyan.ime.layout.LayoutPageJump
import app.songyan.ime.layout.LayoutPageTarget
import app.songyan.ime.layout.LayoutRegistry
import app.songyan.ime.layout.LayoutRow
import app.songyan.ime.layout.LayoutSplitConfiguration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pairing's layout and pages reach the keyboard; the bundled ones stay as they were. */
class KeyboardPagesCatalogTest {
    @After
    fun restoreTheBundledCatalog() {
        KeyboardLayoutCatalog.install(LayoutRegistry.create())
    }

    private fun config(
        pairing: KeyboardComposition?,
        english: Boolean = false,
    ) = KeyboardPages.Config(
        layout = KeyboardLayoutKind.QWERTY26,
        englishDirectInput = english,
        symbolMarksAscii = false,
        languageSwitch = true,
        lettersReachable = true,
        catalogRevision = KeyboardLayoutCatalog.snapshot.value.revision,
        pairing = pairing,
    )

    private fun key(label: String) = LayoutKey(label, action = LayoutAction.literal(label))

    @Test
    fun anEditedMainFooterIsDrawnEvenAfterItsSpaceKeyIsRemoved() {
        val footer = listOf(key("底行"), LayoutKey("⏎", action = LayoutAction(LayoutActionKind.ENTER)))
        val mine =
            BuiltinLayouts.qwerty26.copyWithId("mine", "我的").let {
                it.copy(rows = it.rows.dropLast(1) + LayoutRow(footer))
            }
        KeyboardLayoutCatalog.install(LayoutRegistry.create().install(mine.encoded()))
        val pairing = KeyboardComposition(mainLayoutID = "mine")
        val definition = KeyboardPages.definition(null, config(pairing))
        assertEquals(4, definition.rows.size)
        assertEquals(footer, definition.rows.last().keys)
        assertEquals(
            "q",
            definition.rows
                .first()
                .keys
                .first()
                .label,
        )
        for (field in listOf(FieldKind.EMAIL, FieldKind.URL, FieldKind.PASSWORD)) {
            val native = KeyboardPages.definition(null, config(null).copy(field = field)).rows.last()
            assertEquals(native, KeyboardPages.definition(null, config(pairing).copy(field = field)).rows.last())
        }
    }

    @Test
    fun aUserLayoutProvidesTheLetterRowsAndKeepsTheNativeFooter() {
        val mine =
            BuiltinLayouts.qwerty26.copyWithId("mine", "我的").let { layout ->
                layout.copy(rows = listOf(LayoutRow(listOf(key("甲"), key("乙")))) + layout.rows.last())
            }
        KeyboardLayoutCatalog.install(LayoutRegistry.create().install(mine.encoded()))
        val pairing = KeyboardComposition(mainLayoutID = "mine")
        val definition = KeyboardPages.definition(null, config(pairing))
        assertEquals(
            listOf("甲", "乙"),
            definition.rows[0]
                .keys
                .filter {
                    it.label.length == 1 && it.label != "⇧"
                }.map { it.label }
                .take(2),
        )
        // Shift and Delete are folded into the last letter row, and the footer is still the native one.
        assertEquals(
            LayoutActionKind.SHIFT,
            definition.rows[0]
                .keys
                .first()
                .action.type,
        )
        assertEquals(
            listOf("123", "中/英", "空格", "，", "⏎"),
            definition.rows
                .last()
                .keys
                .map { it.label },
        )
    }

    @Test
    fun aUserLayoutThatCarriesItsOwnShiftAndDeleteKeepsThem() {
        val shift = LayoutKey("⇧", action = LayoutAction(LayoutActionKind.SHIFT))
        val delete = LayoutKey("⌫", action = LayoutAction(LayoutActionKind.BACKSPACE))
        val mine =
            BuiltinLayouts.qwerty26.copyWithId("mine", "我的").let { layout ->
                layout.copy(rows = listOf(LayoutRow(listOf(shift, key("甲"), delete))) + layout.rows.last())
            }
        KeyboardLayoutCatalog.install(LayoutRegistry.create().install(mine.encoded()))
        val definition = KeyboardPages.definition(null, config(KeyboardComposition(mainLayoutID = "mine")))
        assertEquals(
            listOf(
                LayoutActionKind.SHIFT,
                LayoutActionKind.LITERAL,
                LayoutActionKind.BACKSPACE,
            ),
            definition.rows[0].keys.map {
                it.action.type
            },
        )
    }

    @Test
    fun theBundledLayoutIsUsedWhenNoPairingIsSaved() {
        val definition = KeyboardPages.definition(null, config(null))
        assertEquals("q", definition.rows[0].keys[0].label)
        assertEquals(4, definition.rows.size)
    }

    @Test
    fun theLayoutsSplitReachesTheDefinitionAndPagesFollowIt() {
        val mine =
            BuiltinLayouts.qwerty26
                .copyWithId("mine", "我的")
                .withSplit(
                    LayoutSplitConfiguration.of(
                        LayoutSplitConfiguration.Activation.ALWAYS,
                        rowBreaks = listOf(5, 5, 4, 3),
                    ),
                )
        KeyboardLayoutCatalog.install(LayoutRegistry.create().install(mine.encoded()))
        val pairing = KeyboardComposition(mainLayoutID = "mine")
        assertEquals(listOf(5, 5, 4, 3), KeyboardPages.definition(null, config(pairing)).split!!.rowBreaks)
        // A page without a split of its own follows the main layout's gutter, with cuts of its own.
        val numeric = KeyboardPages.definition(KeyboardPageKind.NUMERIC, config(pairing))
        assertEquals(LayoutSplitConfiguration.Activation.ALWAYS, numeric.split!!.activation)
        assertTrue(numeric.split!!.rowBreaks.isEmpty())
    }

    @Test
    fun anAuthoredNumericPageIsDrawnFromItsOwnRowsAndFooter() {
        val page =
            KeyboardPagePresets.numericAutomatic.duplicated("my-digits", "我的数字").let { copy ->
                copy.withRows(listOf(LayoutRow(listOf(key("7"), key("8"), key("9")))))
            }
        KeyboardLayoutCatalog.install(LayoutRegistry.create().withPage(page))
        val pairing = KeyboardComposition(mainLayoutID = "qwerty26", numericPageID = "my-digits")
        val definition = KeyboardPages.definition(KeyboardPageKind.NUMERIC, config(pairing))
        assertEquals(listOf("7", "8", "9"), definition.rows[0].keys.map { it.label })
        // The page's footer is its own data: a space bar and a return key, no native footer.
        val footer = definition.rows.last().keys
        assertTrue(footer.any { it.action.type == LayoutActionKind.SPACE })
        assertTrue(footer.any { it.action.type == LayoutActionKind.ENTER })
    }

    @Test
    fun anAuthoredPageCarriesItsRailsAndAPageWithoutRailsCarriesNone() {
        val base = KeyboardPagePresets.numericAutomatic.duplicated("my-digits", "我的数字")
        val plain = base.withRows(listOf(LayoutRow(listOf(key("7"), key("8"), key("9")))))
        val chrome = requireNotNull(plain.chrome)
        val railed =
            plain.copy(
                id = "my-rails",
                chrome =
                    chrome.copy(
                        leading = listOf(key("a"), LayoutKey("b", action = LayoutAction(LayoutActionKind.GLOBE))),
                        trailing = listOf(key("c")),
                        trailingSpansBottom = true,
                    ),
            )
        KeyboardLayoutCatalog.install(LayoutRegistry.create().withPage(plain).withPage(railed))
        val without = KeyboardComposition(mainLayoutID = "qwerty26", numericPageID = "my-digits")
        assertNull(KeyboardPages.rails(KeyboardPageKind.NUMERIC, config(without)))
        val with = KeyboardComposition(mainLayoutID = "qwerty26", numericPageID = "my-rails")
        val rails = requireNotNull(KeyboardPages.rails(KeyboardPageKind.NUMERIC, config(with)))
        // The globe key is the navigation bar's job, so it is never drawn in a rail.
        assertEquals(listOf("a"), rails.chrome.leading.map { it.label })
        assertEquals(listOf("c"), rails.chrome.trailing.map { it.label })
        assertEquals(1, rails.contentRows)
        assertTrue(rails.hasFooter)
        // The rows themselves do not change: the rails are drawn beside them.
        val definition = KeyboardPages.definition(KeyboardPageKind.NUMERIC, config(with))
        assertEquals(2, definition.rows.size)
    }

    @Test
    fun aJumpKeyIsDrawnOnlyWhileItsLayoutCanBeUsed() {
        val toNineKey =
            LayoutKey(
                "123",
                action =
                    LayoutAction.layoutPageSwitch(
                        LayoutPageJump(LayoutInput.Builtin.PHONE9, LayoutPageTarget.NUMERIC),
                    ),
            )
        val toFourteen =
            LayoutKey(
                "符号",
                action =
                    LayoutAction.layoutPageSwitch(
                        LayoutPageJump(LayoutInput.Builtin.MERGED14, LayoutPageTarget.SYMBOLS),
                    ),
            )
        val base = KeyboardPagePresets.symbolsSystem.duplicated("my-symbols", "我的符号")
        val page =
            base.withRows(listOf(LayoutRow(listOf(key("@"), toNineKey)), LayoutRow(listOf(toNineKey)))).let {
                it.copy(chrome = requireNotNull(it.chrome).copy(trailing = listOf(key("c"), toNineKey, toFourteen)))
            }
        KeyboardLayoutCatalog.install(LayoutRegistry.create().withPage(page))
        val pairing = KeyboardComposition(mainLayoutID = "qwerty26", symbolPageID = "my-symbols")
        val usable = config(pairing)
        val drawn = KeyboardPages.definition(KeyboardPageKind.SYMBOLS, usable)
        assertEquals(listOf("@", "123"), drawn.rows[0].keys.map { it.label })
        assertEquals(listOf("123"), drawn.rows[1].keys.map { it.label })
        assertEquals(
            listOf("c", "123", "符号"),
            requireNotNull(KeyboardPages.rails(KeyboardPageKind.SYMBOLS, usable)).chrome.trailing.map { it.label },
        )
        // The running input method has no 9-key layout: the key is left out, never drawn as something else,
        // and a row it filled alone goes with it.
        val withoutNineKey = usable.copy(jumpLayouts = setOf(KeyboardLayoutKind.QWERTY26, KeyboardLayoutKind.MERGED14))
        val hidden = KeyboardPages.definition(KeyboardPageKind.SYMBOLS, withoutNineKey)
        assertEquals(listOf("@"), hidden.rows[0].keys.map { it.label })
        assertEquals(drawn.rows.size - 1, hidden.rows.size)
        assertTrue(
            hidden.rows.none { row ->
                row.keys.any {
                    it.action.type == LayoutActionKind.LAYOUT_PAGE_SWITCH &&
                        it.label == "123"
                }
            },
        )
        val rails = requireNotNull(KeyboardPages.rails(KeyboardPageKind.SYMBOLS, withoutNineKey))
        assertEquals(listOf("c", "符号"), rails.chrome.trailing.map { it.label })
        assertEquals(hidden.rows.size - 1, rails.contentRows)
    }

    @Test
    fun theAutomaticPagesStayNative() {
        val automatic = KeyboardComposition(mainLayoutID = "qwerty26")
        val numeric = KeyboardPages.definition(KeyboardPageKind.NUMERIC, config(automatic))
        assertNull(numeric.split)
        assertNotNull(numeric.rows.firstOrNull())
        // Untouched presets chosen explicitly are also the native pages: they follow the language.
        val preset =
            KeyboardComposition(mainLayoutID = "qwerty26", numericPageID = KeyboardPagePresets.NUMERIC_AUTOMATIC_ID)
        assertEquals(numeric.rows.size, KeyboardPages.definition(KeyboardPageKind.NUMERIC, config(preset)).rows.size)
    }

    @Test
    fun thePairedKeypadPresetIsDrawnAsThePadInBothLanguages() {
        val pairing =
            KeyboardComposition(mainLayoutID = "qwerty26", numericPageID = KeyboardPagePresets.NUMERIC_KEYPAD_ID)
        for (english in listOf(false, true)) {
            val definition = KeyboardPages.definition(KeyboardPageKind.NUMERIC, config(pairing, english))
            assertEquals(
                listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9")),
                definition.rows.take(3).map { row -> row.keys.map { it.label } },
            )
            val rails = requireNotNull(KeyboardPages.rails(KeyboardPageKind.NUMERIC, config(pairing, english)))
            assertTrue(
                definition.rows.last().keys.any {
                    it.action == LayoutAction.pageSwitch(LayoutPageTarget.SYMBOLS)
                },
            )
            assertEquals(3, rails.chrome.leading.size)
            // The unchanged preset is drawn by the nine-key shell's own digit pad on every layout.
            assertTrue(KeyboardPages.pairsKeypadPreset(config(pairing, english)))
        }
        val copy =
            KeyboardPagePresets.numericKeypad.duplicated("my-pad", "我的数字盘")
        KeyboardLayoutCatalog.install(LayoutRegistry.create().withPage(copy))
        assertFalse(KeyboardPages.pairsKeypadPreset(config(pairing.copy(numericPageID = copy.id))))
        assertFalse(KeyboardPages.pairsKeypadPreset(config(KeyboardComposition(mainLayoutID = "qwerty26"))))
    }

    @Test
    fun theKeypadPairingsExtraSymbolPageIsTheSymbolPagesGridFilledWithMarks() {
        val pairing =
            KeyboardComposition(mainLayoutID = "qwerty26", numericPageID = KeyboardPagePresets.NUMERIC_KEYPAD_ID)
        for (ascii in listOf(false, true)) {
            val keypad = config(pairing).copy(symbolMarksAscii = ascii)
            val extra = KeyboardPages.definition(KeyboardPageKind.SYMBOLS, keypad)
            val usual = KeyboardPages.definition(KeyboardPageKind.SYMBOLS, keypad.copy(everydayMarksPage = false))
            // Same rows, same widths, same controls and footer: only the marks differ.
            assertEquals(
                usual.rows.map { row ->
                    row.keys.map { it.widthWeight }
                },
                extra.rows.map { row -> row.keys.map { it.widthWeight } },
            )
            assertEquals(usual.rows.last(), extra.rows.last())
            val marks =
                extra.rows.dropLast(1).map { row ->
                    row.keys.filter { it.action.type == LayoutActionKind.LITERAL }
                }
            assertTrue("no row without marks: $marks", marks.all { it.isNotEmpty() })
            val typed = marks.flatten().map { it.action.literalValue(ascii) }
            assertEquals(typed.toString(), typed.size, typed.toSet().size)
            assertTrue(typed.toString(), typed.none { it!!.any(Char::isDigit) })
            assertEquals(
                KeyboardPages.missingMarks(KeyboardLayoutKind.QWERTY26, pairing, ascii),
                typed.take(KeyboardPages.missingMarks(KeyboardLayoutKind.QWERTY26, pairing, ascii).size),
            )
            // The third-row switch turns to the usual rows; Delete stays where it is.
            val third = extra.rows[2].keys
            assertEquals(LayoutPageTarget.SYMBOLS, third.first().action.pageSwitchTarget)
            assertEquals(LayoutActionKind.BACKSPACE, third.last().action.type)
            assertEquals(usual.rows[2].keys.last(), third.last())
        }
        // The full 123 page reaches every mark: no extra page.
        val full = config(KeyboardComposition(mainLayoutID = "qwerty26"))
        assertEquals(
            KeyboardPages.definition(KeyboardPageKind.SYMBOLS, full.copy(everydayMarksPage = false)),
            KeyboardPages.definition(KeyboardPageKind.SYMBOLS, full),
        )
    }

    @Test
    fun onlyAnAuthoredNumericPageTakesOverTheNineKeyShell() {
        fun nineKey(numericPageID: String) =
            config(KeyboardComposition(mainLayoutID = "phone9", numericPageID = numericPageID))
                .copy(layout = KeyboardLayoutKind.PHONE9)
        val mine =
            KeyboardPagePresets.numericRowsPage(ascii = false).duplicated("my-digits", "我的数字").let { copy ->
                copy.withRows(listOf(LayoutRow(listOf(key("7"), key("8"), key("9")))))
            }
        KeyboardLayoutCatalog.install(LayoutRegistry.create().withPage(mine))
        assertTrue(KeyboardPages.pairsAuthoredNumericPage(nineKey("my-digits")))
        // The shell's own digit pad is the automatic page, and the unchanged keypad preset is that pad.
        assertFalse(KeyboardPages.pairsAuthoredNumericPage(nineKey(KeyboardPagePresets.NUMERIC_AUTOMATIC_ID)))
        assertFalse(KeyboardPages.pairsAuthoredNumericPage(nineKey(KeyboardPagePresets.NUMERIC_KEYPAD_ID)))
        assertFalse(KeyboardPages.pairsAuthoredNumericPage(nineKey("page.numeric.chinese")))
    }

    @Test
    fun anAuthoredEnglishPageReplacesTheAbcLetters() {
        val page =
            KeyboardPagePresets.englishQwerty.duplicated("my-abc", "我的 ABC").let { copy ->
                copy.withRows(listOf(LayoutRow(listOf(key("x"), key("y")))))
            }
        KeyboardLayoutCatalog.install(LayoutRegistry.create().withPage(page))
        val pairing = KeyboardComposition(mainLayoutID = "qwerty26", englishPageID = "my-abc")
        val definition = KeyboardPages.definition(KeyboardPageKind.ENGLISH, config(pairing, english = true))
        assertEquals(listOf("x", "y"), definition.rows[0].keys.map { it.label })
    }

    // The categorized symbol page.

    private fun withSymbolsPage(page: app.songyan.ime.layout.KeyboardPageDefinition): KeyboardComposition {
        KeyboardLayoutCatalog.install(LayoutRegistry.create().withPage(page))
        return KeyboardComposition(mainLayoutID = "phone9", symbolPageID = page.id)
    }

    @Test
    fun aNineKeyKeyboardOpensTheCategorizedPageUnlessItsPairingAuthoredRows() {
        // Nothing chosen, or the automatic page: the nine-key digit pad has no rows to share, so it opens the categories.
        assertNotNull(KeyboardPages.categorizedSymbols(KeyboardLayoutKind.PHONE9, null))
        assertNull(KeyboardPages.categorizedSymbols(KeyboardLayoutKind.PHONE9, null)!!.categories)
        val automatic = withSymbolsPage(KeyboardPagePresets.symbolsSystem)
        assertNotNull(KeyboardPages.categorizedSymbols(KeyboardLayoutKind.PHONE9, automatic))
        assertNull(KeyboardPages.categorizedSymbols(KeyboardLayoutKind.QWERTY26, automatic))
        assertNull(KeyboardPages.categorizedSymbols(KeyboardLayoutKind.QWERTY26, null))
        // An authored row page is kept on every layout, nine-key included.
        val rows = withSymbolsPage(KeyboardPagePresets.symbolRowsPage(true).duplicated("my-rows", "我的行"))
        assertNull(KeyboardPages.categorizedSymbols(KeyboardLayoutKind.PHONE9, rows))
    }

    @Test
    fun aCategorizedPageOpensOnEveryLayoutWithItsOwnCategories() {
        val mine =
            KeyboardPagePresets.symbolsCategorized
                .copy(
                    id = "my-categories",
                    name = "我的分类",
                    categories = listOf(KeyboardPageCategory("one", "一", columns = 3, keys = listOf(key("甲")))),
                )
        val pairing = withSymbolsPage(mine)
        val chosen = requireNotNull(KeyboardPages.categorizedSymbols(KeyboardLayoutKind.QWERTY26, pairing))
        assertEquals(listOf("一"), chosen.categories!!.map { it.name })
        // The bundled categorized page carries none of its own: the built-in categories are meant.
        val bundled = withSymbolsPage(KeyboardPagePresets.symbolsCategorized)
        assertNull(requireNotNull(KeyboardPages.categorizedSymbols(KeyboardLayoutKind.MERGED14, bundled)).categories)
    }

    private fun numericPairing(page: app.songyan.ime.layout.KeyboardPageDefinition): KeyboardComposition {
        KeyboardLayoutCatalog.install(LayoutRegistry.create().withPage(page))
        return KeyboardComposition(mainLayoutID = "qwerty26", numericPageID = page.id)
    }

    private fun bilingual(opening: app.songyan.ime.layout.KeyboardNumericMarks?) =
        KeyboardPagePresets.numericAutomatic
            .duplicated("my-digits", "我的数字")
            .withDefaultSymbolMarks(opening)

    @Test
    fun theMarksFollowTheInputLanguageUnlessTheUserOrThePageChoseABranch() {
        // English input opens the English marks, Chinese input the Chinese ones.
        assertTrue(KeyboardPages.marksAscii(null, null, englishInput = true, editorAscii = false))
        assertEquals(false, KeyboardPages.marksAscii(null, null, englishInput = false, editorAscii = false))
        assertTrue(KeyboardPages.marksAscii(null, null, englishInput = false, editorAscii = true))
        // The page's own choice beats the input language, and the 中/英 key beats both.
        assertEquals(false, KeyboardPages.marksAscii(null, false, englishInput = true, editorAscii = false))
        assertTrue(KeyboardPages.marksAscii(null, true, englishInput = false, editorAscii = false))
        assertTrue(KeyboardPages.marksAscii(true, false, englishInput = false, editorAscii = false))
        assertEquals(false, KeyboardPages.marksAscii(false, true, englishInput = true, editorAscii = true))
    }

    @Test
    fun aPageOpensInTheBranchItsPairedPageChose() {
        assertNull(KeyboardPages.openingMarks(KeyboardPageKind.NUMERIC, null))
        assertNull(KeyboardPages.openingMarks(KeyboardPageKind.NUMERIC, numericPairing(bilingual(null))))
        assertEquals(
            true,
            KeyboardPages.openingMarks(
                KeyboardPageKind.NUMERIC,
                numericPairing(bilingual(app.songyan.ime.layout.KeyboardNumericMarks.ASCII)),
            ),
        )
        assertEquals(
            false,
            KeyboardPages.openingMarks(
                KeyboardPageKind.NUMERIC,
                numericPairing(bilingual(app.songyan.ime.layout.KeyboardNumericMarks.CHINESE)),
            ),
        )
    }

    @Test
    fun theBuiltInPagesDrawTheMarksOfTheBranchTheyAreGiven() {
        fun typed(
            page: KeyboardPageKind,
            ascii: Boolean,
        ) = KeyboardPages
            .definition(page, config(null).copy(symbolMarksAscii = ascii))
            .rows
            .flatMap { it.keys }
            .filter { it.action.type == LayoutActionKind.LITERAL }
        for (page in listOf(KeyboardPageKind.NUMERIC, KeyboardPageKind.SYMBOLS)) {
            for (ascii in listOf(false, true)) {
                for (key in typed(page, ascii)) {
                    assertEquals(key.label, key.action.literalValue(ascii))
                    assertEquals(key.label, key.face(ascii))
                }
            }
        }
        val comma =
            KeyboardPages
                .definition(KeyboardPageKind.NUMERIC, config(null).copy(symbolMarksAscii = true))
                .rows
                .last()
                .keys
                .first { it.action.literalValue(true) == "," }
        assertEquals(",", comma.label)
    }

    @Test
    fun aCopiedBilingualKeyShowsWhatItTypesInTheCurrentBranch() {
        val key = bilingual(null).rows.flatMap { it.keys }.first { it.action.asciiValue != null }
        assertEquals(key.action.value, key.face(false))
        assertEquals(key.action.asciiValue, key.face(true))
        // A title the user typed by hand wins.
        assertEquals("甲", key.withLabel("甲").face(true))
    }

    @Test
    fun aRenamedCopyOfQwerty26SplitsLikeTheBundledLayout() {
        fun leftHalves(page: LayoutDefinition): List<String> {
            val (cut, breaks) = splitColumns(page.rows.map { it.keys }, page.split!!)
            return cut.zip(breaks).take(3).map { (keys, at) ->
                keys
                    .take(at)
                    .filter { it.action.type == LayoutActionKind.CODE }
                    .joinToString("") { it.label }
            }
        }
        val on = LayoutSplitConfiguration.of(LayoutSplitConfiguration.Activation.ALWAYS)
        val mine = BuiltinLayouts.qwerty26.copyWithId("mine", "我的").withSplit(on)
        KeyboardLayoutCatalog.install(LayoutRegistry.create().install(mine.encoded()))
        val copy = KeyboardPages.definition(null, config(KeyboardComposition(mainLayoutID = "mine")))
        KeyboardLayoutCatalog.install(
            LayoutRegistry.create().replace(BuiltinLayouts.qwerty26.withSplit(on)),
        )
        val bundled = KeyboardPages.definition(null, config(KeyboardComposition(mainLayoutID = "qwerty26")))
        assertEquals(listOf("qwert", "asdfg", "zxcvb"), leftHalves(bundled))
        assertEquals(leftHalves(bundled), leftHalves(copy))
    }

    @Test
    fun aLayoutsOwnSplitArrangementReachesTheLettersPageWithShiftDeleteAndFooter() {
        fun letter(c: Char) = LayoutKey(c.toString(), action = LayoutAction.code(c.toString()))
        val base = BuiltinLayouts.qwerty26
        val split = LayoutSplitConfiguration.of(LayoutSplitConfiguration.Activation.ALWAYS)
        val halves = split.derivedArrangement(base.rows).toMutableList()
        halves[0] = halves[0].copy(left = halves[0].left + letter('y'))
        halves[1] = halves[1].copy(right = listOf(letter('g')) + halves[1].right)
        halves[2] = halves[2].copy(right = listOf(letter('v')) + halves[2].right)
        val mine = base.copyWithId("mine", "我的").withSplit(split.copy(halves = halves))
        KeyboardLayoutCatalog.install(LayoutRegistry.create().install(mine.encoded()))
        val page = KeyboardPages.definition(null, config(KeyboardComposition(mainLayoutID = "mine")))
        // The whole rows are the stock ones.
        assertEquals(
            base.rows.dropLast(1).map { row -> row.keys.map { it.label } },
            page.rows.dropLast(1).map { row ->
                row.keys.filter { it.action.type == LayoutActionKind.CODE }.map { it.label }
            },
        )
        val arranged = page.split!!.halves!!
        assertEquals(page.rows.size, arranged.size)

        fun codes(keys: List<LayoutKey>) =
            keys.filter { it.action.type == LayoutActionKind.CODE }.joinToString("") { it.label }
        assertEquals(listOf("qwerty", "yuiop"), listOf(codes(arranged[0].left), codes(arranged[0].right)))
        assertEquals(listOf("asdfg", "ghjkl"), listOf(codes(arranged[1].left), codes(arranged[1].right)))
        assertEquals(listOf("zxcv", "vbnm"), listOf(codes(arranged[2].left), codes(arranged[2].right)))
        // Shift opens the left half of the last letter row and Delete closes the right one.
        assertEquals(
            LayoutActionKind.SHIFT,
            arranged[2]
                .left
                .first()
                .action.type,
        )
        assertEquals(
            LayoutActionKind.BACKSPACE,
            arranged[2]
                .right
                .last()
                .action.type,
        )
        // The footer's space bar is one half under each thumb.
        assertEquals(
            LayoutActionKind.SPACE,
            arranged
                .last()
                .left
                .last()
                .action.type,
        )
        assertEquals(
            LayoutActionKind.SPACE,
            arranged
                .last()
                .right
                .first()
                .action.type,
        )
        // The arrangement is what the view cuts the page by, and the English page ignores it.
        val (cut, breaks) = splitColumns(page.rows.map { it.keys }, page.split!!)
        assertEquals(arranged.map { it.left.size }, breaks)
        assertEquals(arranged.map { it.keys.size }, cut.map { it.size })
        assertNull(
            KeyboardPages
                .definition(
                    null,
                    config(KeyboardComposition(mainLayoutID = "mine"), english = true),
                ).split
                ?.halves,
        )
    }
}

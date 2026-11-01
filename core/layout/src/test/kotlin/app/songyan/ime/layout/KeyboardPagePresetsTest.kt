package app.songyan.ime.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from LayoutKitTests: KeyboardPageRowsTests plus the preset/merge rules. */
class KeyboardPagePresetsTest {
    private fun labelRows(page: KeyboardPageDefinition): List<List<String>> =
        page.rows.map { row -> row.keys.map { it.label } }

    private fun markLabels(
        rows: List<List<BuiltinLayouts.BilingualPageMark>>,
        ascii: Boolean,
    ): List<List<String>> = rows.map { row -> row.map { if (ascii) it.ascii else it.chinese } }

    @Test
    fun bothCanonicalPagesKeepTenColumnRowsWithASevenMarkThirdRow() {
        for (ascii in listOf(false, true)) {
            val pages =
                listOf(
                    BuiltinLayouts.numericPageRows(ascii),
                    BuiltinLayouts.symbolPageRows(ascii),
                )
            for (rows in pages) {
                assertEquals(3, rows.size)
                assertEquals(listOf(10, 10, 7), rows.map { it.size })
                assertTrue(rows.all { row -> row.all { it.isNotEmpty() } })
                if (ascii) {
                    // Only the third row is checked: the mark rows keep the accepted currency marks.
                    assertTrue(
                        "the ASCII third row must not offer a full-width mark",
                        rows[2].all { mark -> mark.all { it.code < 128 } },
                    )
                }
            }
        }
    }

    /** Switch + seven marks + Delete fills a ten-cap letter row, both sides at the ABC page's width. */
    @Test
    fun thirdRowPlusSwitchAndDeleteFillsTheTenColumnWidth() {
        val spacing = 4.0
        for (width in listOf(280.0, 320.0, 382.0, 422.0, 768.0, 1024.0)) {
            val splits =
                listOf(
                    LayoutSplitConfiguration(),
                    LayoutSplitConfiguration(
                        activation = LayoutSplitConfiguration.Activation.ALWAYS,
                        rowBreaks = listOf(5, 4, 3),
                        gapFraction = 0.24,
                    ),
                )
            for (split in splits) {
                val geometry = StandardKeyRowGeometry.resolve(width, split.resolvedForWidth(width), spacing)
                val contentWidth = width - geometry.gap
                val letterRow = geometry.keyWidth * 10 + spacing * 9
                val marks = geometry.keyWidth * 7 + spacing * 6
                val fullRow = geometry.thirdRowSideWidth * 2 + marks + spacing * 2 + geometry.gap
                assertEquals(contentWidth, letterRow, 0.001)
                assertEquals(letterRow + geometry.gap, fullRow, 0.001)
            }
        }
    }

    /** The comma is in the utility slot of the letter page and of both row pages, never repeated. */
    @Test
    fun rowPagesCarryNoCommaOrDuplicateBottomRowMarks() {
        for (ascii in listOf(false, true)) {
            val comma = if (ascii) "," else "，"
            for (rows in listOf(BuiltinLayouts.numericPageRows(ascii), BuiltinLayouts.symbolPageRows(ascii))) {
                assertFalse("the bottom row types the comma", rows.flatten().contains(comma))
            }
        }
    }

    @Test
    fun noCharacterRepeatsWithinAPage() {
        for (ascii in listOf(false, true)) {
            val pages =
                mapOf(
                    "123" to BuiltinLayouts.numericPageRows(ascii),
                    "#+=" to BuiltinLayouts.symbolPageRows(ascii),
                )
            for ((name, rows) in pages) {
                val characters = rows.flatten()
                assertEquals("the " + name + " page repeats a character", characters.size, characters.toSet().size)
            }
        }
    }

    @Test
    fun editablePresetsUseTheCanonicalRows() {
        assertEquals(
            markLabels(BuiltinLayouts.numericPageBilingualRows(), ascii = false),
            labelRows(KeyboardPagePresets.numericRowsPage(false)),
        )
        assertEquals(
            markLabels(BuiltinLayouts.numericPageBilingualRows(), ascii = true),
            labelRows(KeyboardPagePresets.numericRowsPage(true)),
        )
        assertEquals(
            markLabels(BuiltinLayouts.symbolPageBilingualRows(), ascii = false),
            labelRows(KeyboardPagePresets.symbolRowsPage(false)),
        )
        assertEquals(
            markLabels(BuiltinLayouts.symbolPageBilingualRows(), ascii = true),
            labelRows(KeyboardPagePresets.symbolRowsPage(true)),
        )
        assertEquals(labelRows(KeyboardPagePresets.symbolRowsPage(false)), labelRows(KeyboardPagePresets.symbolsSystem))
    }

    @Test
    fun everyPresetValidatesAndIsCodeOwned() {
        for (page in KeyboardPagePresets.all) {
            page.validate()
            assertTrue(KeyboardPagePresets.codeOwnedIds.contains(page.id))
            assertEquals(page, KeyboardPagePresets.preset(page.id))
            assertTrue(KeyboardPagePresets.isUnmodifiedPreset(page))
        }
        assertEquals(5, KeyboardPagePresets.all.size)
        assertEquals(5, KeyboardPagePresets.codeOwnedIds.size)
        assertNull(KeyboardPagePresets.preset("missing"))
    }

    @Test
    fun mergingIntoKeepsStoredPagesAndAppendsMissingPresets() {
        val custom =
            KeyboardPageDefinition(
                id = "user.page",
                name = "我的页",
                kind = KeyboardPageKind.NUMERIC,
                rows = listOf(LayoutRow(listOf(LayoutKey("1", action = LayoutAction.literal("1"))))),
            )
        val merged = KeyboardPagePresets.mergingInto(listOf(custom, KeyboardPagePresets.numericAutomatic))
        assertEquals("user.page", merged[0].id)
        assertEquals(merged.count { it.id == "user.page" }, 1)
        assertEquals(merged.count { it.id == KeyboardPagePresets.numericAutomatic.id }, 1)
        assertEquals(merged.size, KeyboardPagePresets.all.size + 1)
        // A modified preset keeps its stored content instead of being replaced by the shipped one.
        val edited = KeyboardPagePresets.symbolsSystem.withName("我改过名字的符号页")
        val withEdited = KeyboardPagePresets.mergingInto(listOf(edited))
        assertEquals("我改过名字的符号页", withEdited.first { it.id == edited.id }.name)
        assertFalse(KeyboardPagePresets.isUnmodifiedPreset(edited))
    }

    @Test
    fun bilingualPairsKeepTheSameSlotInBothTables() {
        val chinese = BuiltinLayouts.numericPageRows(ascii = false)
        val ascii = BuiltinLayouts.numericPageRows(ascii = true)
        val pairs = BuiltinLayouts.numericPageBilingualRows()
        assertEquals(chinese.size, pairs.size)
        for ((index, row) in pairs.withIndex()) {
            assertEquals(chinese[index].size, row.size)
            for ((column, mark) in row.withIndex()) {
                assertEquals(chinese[index][column], mark.chinese)
                assertEquals(ascii[index][column], mark.ascii)
            }
        }
        // Identical slots need no ASCII counterpart; the authored pair keeps the Chinese value.
        val digits = KeyboardPagePresets.numericRowsPage(false).rows[0].keys
        assertTrue(digits.all { it.action.asciiValue == null })
        val dollar = KeyboardPagePresets.numericRowsPage(true).rows[1].keys[6]
        assertEquals("¥", dollar.action.value)
        assertEquals("$", dollar.action.asciiValue)
        assertEquals("$", dollar.label)
    }
}

/** Ported from LayoutKitTests/KeyboardCompositionTests: the single page resolution rule. */
class KeyboardPageResolverTest {
    private val chineseRowsInput =
        KeyboardPageResolutionInput(usesAsciiPunctuation = false, mainLayoutIsPhoneKeypad = false)
    private val asciiRowsInput =
        KeyboardPageResolutionInput(usesAsciiPunctuation = true, mainLayoutIsPhoneKeypad = false)
    private val phoneInput = KeyboardPageResolutionInput(usesAsciiPunctuation = false, mainLayoutIsPhoneKeypad = true)

    @Test
    fun automaticNumericPageFollowsTheLiveKeyboard() {
        val page = KeyboardPagePresets.numericAutomatic
        assertEquals(
            KeyboardPageResolution.AutomaticNumeric(
                KeyboardNumericForm.ROWS,
                KeyboardNumericMarks.CHINESE,
            ),
            KeyboardPageResolver.resolve(page, KeyboardPageKind.NUMERIC, chineseRowsInput),
        )
        assertEquals(
            KeyboardPageResolution.AutomaticNumeric(KeyboardNumericForm.ROWS, KeyboardNumericMarks.ASCII),
            KeyboardPageResolver.resolve(page, KeyboardPageKind.NUMERIC, asciiRowsInput),
        )
        // A 9-key keyboard keeps its digit pad in either punctuation width.
        for (ascii in listOf(false, true)) {
            val input =
                KeyboardPageResolutionInput(usesAsciiPunctuation = ascii, mainLayoutIsPhoneKeypad = true)
            assertEquals(
                KeyboardPageResolution.AutomaticNumeric(
                    KeyboardNumericForm.PHONE_DIGIT_PAD,
                    if (ascii) KeyboardNumericMarks.ASCII else KeyboardNumericMarks.CHINESE,
                ),
                KeyboardPageResolver.resolve(page, KeyboardPageKind.NUMERIC, input),
            )
        }
        assertTrue(page.resolvesAtRuntime)
        assertNull(
            KeyboardPageResolver.resolve(page, KeyboardPageKind.NUMERIC, chineseRowsInput).page,
        )
    }

    @Test
    fun unpairedKindsKeepTheBuiltInPageArea() {
        assertEquals(
            KeyboardPageResolution.AutomaticNumeric(KeyboardNumericForm.ROWS, KeyboardNumericMarks.CHINESE),
            KeyboardPageResolver.resolve(null, KeyboardPageKind.NUMERIC, chineseRowsInput),
        )
        assertEquals(
            KeyboardPageResolution.AutomaticNumeric(
                KeyboardNumericForm.PHONE_DIGIT_PAD,
                KeyboardNumericMarks.CHINESE,
            ),
            KeyboardPageResolver.resolve(null, KeyboardPageKind.NUMERIC, phoneInput),
        )
        assertEquals(
            KeyboardPageResolution.AutomaticSymbolPage,
            KeyboardPageResolver.resolve(null, KeyboardPageKind.SYMBOLS, chineseRowsInput),
        )
        assertEquals(
            KeyboardPageResolution.BuiltIn,
            KeyboardPageResolver.resolve(null, KeyboardPageKind.ENGLISH, chineseRowsInput),
        )
    }

    @Test
    fun explicitPagesKeepTheirOwnContent() {
        val numeric = KeyboardPagePresets.numericRowsPage(false)
        for (ascii in listOf(false, true)) {
            val input =
                KeyboardPageResolutionInput(usesAsciiPunctuation = ascii, mainLayoutIsPhoneKeypad = false)
            val resolution = KeyboardPageResolver.resolve(numeric, KeyboardPageKind.NUMERIC, input)
            assertTrue(resolution is KeyboardPageResolution.Authored)
            val authored = requireNotNull(resolution.page)
            assertEquals(numeric, authored)
            assertEquals(KeyboardPagePresets.numericRowsPage(false).rows, authored.rows)
            assertTrue(authored.rows.flatMap { it.keys }.any { it.action.value == "。" })
        }
        val symbols =
            KeyboardPageResolver.resolve(
                KeyboardPagePresets.symbolRowsPage(false),
                KeyboardPageKind.SYMBOLS,
                phoneInput,
            )
        assertTrue(symbols is KeyboardPageResolution.Authored)
        val english =
            KeyboardPageResolver.resolve(
                KeyboardPagePresets.englishQwerty,
                KeyboardPageKind.ENGLISH,
                asciiRowsInput,
            )
        assertTrue(english is KeyboardPageResolution.Authored)
    }

    @Test
    fun automaticSymbolPageResolvesToTheBuiltInSymbolPage() {
        assertEquals(
            KeyboardPageResolution.AutomaticSymbolPage,
            KeyboardPageResolver.resolve(
                KeyboardPagePresets.symbolsSystem,
                KeyboardPageKind.SYMBOLS,
                chineseRowsInput,
            ),
        )
        // A page for another slot is never used for this kind.
        assertEquals(
            KeyboardPageResolution.AutomaticSymbolPage,
            KeyboardPageResolver.resolve(
                KeyboardPagePresets.numericAutomatic,
                KeyboardPageKind.SYMBOLS,
                chineseRowsInput,
            ),
        )
    }

    @Test
    fun keypadPageIsAuthoredAndNotRuntimeResolved() {
        val keypad = KeyboardPagePresets.numericKeypad
        assertFalse(keypad.resolvesAtRuntime)
        val resolution = KeyboardPageResolver.resolve(keypad, KeyboardPageKind.NUMERIC, phoneInput)
        assertTrue(resolution is KeyboardPageResolution.Authored)
        assertNotEquals(
            KeyboardPageResolution.AutomaticNumeric(
                KeyboardNumericForm.PHONE_DIGIT_PAD,
                KeyboardNumericMarks.CHINESE,
            ),
            resolution,
        )
        assertThrows(LayoutException::class.java) {
            KeyboardPageDefinition(
                id = "auto-on-english",
                name = "自动 ABC",
                kind = KeyboardPageKind.ENGLISH,
                numericPresentation = KeyboardNumericPresentation.AUTOMATIC,
            ).validate()
        }
    }

    /** The letter page types punctuation only when its 123 page can reach the symbol page itself. */
    @Test
    fun numericPageReachesSymbolsFollowsThePageThePairingDraws() {
        // The built-in (null) and automatic pages carry the row switch unless the layout is a phone keypad.
        for (page in listOf(null, KeyboardPagePresets.numericAutomatic)) {
            assertTrue(KeyboardPageResolver.numericPageReachesSymbols(page, mainLayoutIsPhoneKeypad = false))
            assertFalse(KeyboardPageResolver.numericPageReachesSymbols(page, mainLayoutIsPhoneKeypad = true))
        }
        // Native-control rows (no chrome) always draw the switch.
        assertTrue(
            KeyboardPageResolver.numericPageReachesSymbols(
                KeyboardPagePresets.numericRowsPage(false),
                mainLayoutIsPhoneKeypad = true,
            ),
        )
        // An authored page reaches symbols only through its own symbol switch.
        val keypad = KeyboardPagePresets.numericKeypad.materialized()
        assertTrue(KeyboardPageResolver.numericPageReachesSymbols(keypad, mainLayoutIsPhoneKeypad = false))
        val chrome = requireNotNull(keypad.chrome)
        val withoutSwitch =
            keypad.withChrome(
                chrome.copy(bottom = chrome.bottom.filter { it.action.pageSwitchTarget != LayoutPageTarget.SYMBOLS }),
            )
        assertFalse(KeyboardPageResolver.numericPageReachesSymbols(withoutSwitch, mainLayoutIsPhoneKeypad = false))
        val withSwitch =
            withoutSwitch.withChrome(
                chrome.copy(
                    leading =
                        chrome.leading + LayoutKey("符号", action = LayoutAction.pageSwitch(LayoutPageTarget.SYMBOLS)),
                    bottom = requireNotNull(withoutSwitch.chrome).bottom,
                ),
            )
        assertTrue(KeyboardPageResolver.numericPageReachesSymbols(withSwitch, mainLayoutIsPhoneKeypad = false))
    }

    /**
     * The pad's switch is the row 123 page's switch: it names the symbol page, never a specific one,
     * so the pairing decides (row-shaped or categorized) and each symbol page returns to 123.
     */
    @Test
    fun keypadSymbolSwitchOpensThePairedSymbolPageAndReturnsToThePad() {
        val padSwitch =
            requireNotNull(KeyboardPagePresets.numericKeypad.materialized().chrome).bottom.single {
                it.action.pageSwitchTarget == LayoutPageTarget.SYMBOLS
            }
        val rowSwitch =
            KeyboardPagePresets
                .numericRowsPage(false)
                .materialized()
                .rows
                .last()
                .keys
                .first()
        assertEquals(rowSwitch.action, padSwitch.action)
        for (symbols in listOf(KeyboardPagePresets.symbolRowsPage(false), KeyboardPagePresets.symbolsCategorized)) {
            val resolved = KeyboardPageResolver.resolve(symbols, KeyboardPageKind.SYMBOLS, chineseRowsInput)
            val page = requireNotNull(resolved.page)
            assertEquals(symbols.id, page.id)
            assertTrue(
                symbols.id,
                page.materialized().allKeys.any { it.action.pageSwitchTarget == LayoutPageTarget.NUMERIC },
            )
        }
    }
}

package app.songyan.ime.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The everyday marks of the row 123 page that a pairing reaches nowhere, and how the symbol page takes them over. */
class KeyboardPageMarkCoverageTest {
    private val chineseKeypadMissing = listOf("-", "/", "：", "；", "（", "）", "¥", "@", "“", "”", "。", "、", "…", "%")
    private val asciiKeypadMissing = listOf("-", "/", ":", ";", "(", ")", "$", "&", "@", "\"", "'", "%", "#", "+")

    private fun missing(
        numeric: KeyboardPageDefinition?,
        ascii: Boolean,
        letters: LayoutDefinition = BuiltinLayouts.qwerty26,
        symbols: KeyboardPageDefinition? = KeyboardPagePresets.symbolsSystem,
        phone: Boolean = false,
    ): List<String> = KeyboardPageMarkCoverage.missingMarks(letters, numeric, symbols, phone, ascii)

    private fun literal(
        value: String,
        ascii: String? = null,
    ) = LayoutKey(value, action = LayoutAction.literal(value, ascii))

    private fun customKeypad(extra: List<LayoutKey>) =
        KeyboardPagePresets.numericKeypad.copy(
            id = "mine",
            rows = KeyboardPagePresets.numericKeypad.rows + LayoutRow(extra),
        )

    @Test
    fun theFullKeyboard123PairingMissesNothing() {
        for (ascii in listOf(false, true)) {
            assertEquals(emptyList<String>(), missing(KeyboardPagePresets.numericAutomatic, ascii))
            assertEquals(emptyList<String>(), missing(null, ascii))
            assertEquals(emptyList<String>(), missing(KeyboardPagePresets.numericRowsPage(ascii = false), ascii))
            // A phone keypad's own digit pad lists every mark in its rail.
            assertEquals(
                emptyList<String>(),
                missing(KeyboardPagePresets.numericAutomatic, ascii, letters = BuiltinLayouts.phone9, phone = true),
            )
        }
    }

    @Test
    fun theKeypadPairingMissesTheRowMarksInBothBranches() {
        assertEquals(chineseKeypadMissing, missing(KeyboardPagePresets.numericKeypad, ascii = false))
        assertEquals(asciiKeypadMissing, missing(KeyboardPagePresets.numericKeypad, ascii = true))
    }

    @Test
    fun aCustom123PageWithSomeMarksMissesOnlyTheRest() {
        val page = customKeypad(listOf(literal("¥", "$"), literal("@"), literal("、", "'")))
        assertEquals(chineseKeypadMissing - setOf("¥", "@", "、"), missing(page, ascii = false))
        assertEquals(asciiKeypadMissing - setOf("$", "@", "'"), missing(page, ascii = true))
    }

    @Test
    fun onlyATapReachesAMark() {
        val page =
            customKeypad(
                listOf(
                    LayoutKey(
                        "#",
                        action = LayoutAction.literal("#"),
                        longPress = listOf(LayoutOption("¥", LayoutAction.literal("¥"))),
                    ),
                ),
            )
        assertTrue("¥" in missing(page, ascii = false))
    }

    @Test
    fun everyKindOfSymbolPageIsTold() {
        val form = KeyboardPageMarkCoverage::symbolPageForm
        val rows = KeyboardPageMarkCoverage.SymbolPageForm.BUILT_IN_ROWS
        val categories = KeyboardPageMarkCoverage.SymbolPageForm.BUILT_IN_CATEGORIES
        val custom = KeyboardPageMarkCoverage.SymbolPageForm.CUSTOM
        assertEquals(rows, form(null, false))
        assertEquals(rows, form(KeyboardPagePresets.symbolsSystem, false))
        assertEquals(categories, form(KeyboardPagePresets.symbolsSystem, true))
        assertEquals(categories, form(KeyboardPagePresets.symbolsCategorized, false))
        assertEquals(custom, form(KeyboardPagePresets.symbolsSystem.duplicated("mine", "我的符号"), false))
        val edited = KeyboardPagePresets.symbolRowsPage(ascii = false).copy(id = "mine")
        assertEquals(custom, form(edited, false))
    }

    @Test
    fun theRowSymbolPageGrowsAFirstPageOnlyWhenMarksAreMissing() {
        assertEquals(
            emptyList<List<String>>(),
            KeyboardPageMarkCoverage.extraSymbolPageRows(emptyList(), ascii = false),
        )
        for ((ascii, missing) in listOf(
            false to chineseKeypadMissing,
            true to asciiKeypadMissing,
            true to listOf("'", "#"),
        )) {
            val rows = KeyboardPageMarkCoverage.extraSymbolPageRows(missing, ascii)
            // The symbol page's own grid, every slot a mark: no empty row, no digit, nothing twice.
            assertEquals(BuiltinLayouts.symbolPageRows(ascii).map { it.size }, rows.map { it.size })
            val marks = rows.flatten()
            assertEquals(marks.toString(), marks.size, marks.toSet().size)
            assertTrue(marks.toString(), marks.none { mark -> mark.any(Char::isDigit) })
            // The missing marks first, then the symbol page's own from its start.
            assertEquals(missing, marks.take(missing.size))
            assertEquals(
                BuiltinLayouts
                    .symbolPageRows(
                        ascii,
                    ).flatten()
                    .filter { it !in missing }
                    .take(marks.size - missing.size),
                marks.drop(missing.size),
            )
        }
        assertEquals(
            listOf("-", "/", "：", "；", "（", "）", "¥", "@", "“", "”"),
            KeyboardPageMarkCoverage.extraSymbolPageRows(chineseKeypadMissing, ascii = false).first(),
        )
    }

    @Test
    fun commonPutsTheMissingMarksFirstWithoutRepeatingThem() {
        assertEquals(
            listOf("¥", "@", "。", "，", "#"),
            KeyboardPageMarkCoverage.commonSymbols(listOf("，", "。", "@", "#"), leading = listOf("¥", "@", "。")),
        )
        val tabs = SymbolPageCategories.display(ascii = false, leading = listOf("¥", "。"))
        val common = tabs.first { it.id == SymbolPageCategories.COMMON_ID }.symbols
        assertEquals(listOf("¥", "。", "，", "？"), common.take(4))
        assertEquals(1, common.count { it == "。" })
        assertEquals(
            SymbolPageCategories.commonSymbols(ascii = false),
            SymbolPageCategories.display(ascii = false).first().symbols,
        )
    }

    @Test
    fun thePairingNamesWhatAUsersSymbolPageLacks() {
        val everyday = listOf(false, true).flatMap { KeyboardPageMarkCoverage.everydayMarkRows(it).flatten() }.toSet()
        val mine =
            KeyboardPagePresets.symbolRowsPage(ascii = false).copy(
                id = "mine",
                rows = listOf(LayoutRow((everyday - setOf("¥", "&")).sorted().map { literal(it) })),
            )
        assertEquals(
            listOf("¥", "&"),
            KeyboardPageMarkCoverage.pairingMissingMarks(
                BuiltinLayouts.qwerty26,
                KeyboardPagePresets.numericKeypad,
                mine,
                false,
            ),
        )
        // The full 123 page reaches everything; a built-in symbol page takes the marks over itself.
        assertEquals(
            emptyList<String>(),
            KeyboardPageMarkCoverage.pairingMissingMarks(
                BuiltinLayouts.qwerty26,
                KeyboardPagePresets.numericAutomatic,
                mine,
                false,
            ),
        )
        assertEquals(
            emptyList<String>(),
            KeyboardPageMarkCoverage.pairingMissingMarks(
                BuiltinLayouts.qwerty26,
                KeyboardPagePresets.numericKeypad,
                KeyboardPagePresets.symbolsSystem,
                false,
            ),
        )
    }
}

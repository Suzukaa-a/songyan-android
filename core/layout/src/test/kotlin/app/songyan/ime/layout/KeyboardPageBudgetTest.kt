package app.songyan.ime.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

/** Ported from LayoutKitTests/KeyboardPageBudgetUpgradeTests: the bounded function-area allowance. */
class KeyboardPageBudgetTest {
    private fun literalKeys(
        count: Int,
        label: String = "k",
    ): List<LayoutKey> = List(count) { LayoutKey(label, action = LayoutAction.literal("a")) }

    private fun roundTrip(page: KeyboardPageDefinition): KeyboardPageDefinition {
        val directory = Files.createTempDirectory("layout-budget-").toString()
        try {
            val store = LayoutStore(directory)
            store.save(LayoutRegistry.create().withPage(page))
            return requireNotNull(store.load().page(page.id))
        } finally {
            Paths.get(directory).toFile().deleteRecursively()
        }
    }

    @Test
    fun materialisedLegacyPagesAtRegionAndTotalLimitsStaySavable() {
        // 32 keys in the last row (the legacy per-region limit) becomes 34 after Shift + Delete.
        val rowLimited =
            KeyboardPageDefinition(
                id = "old.row32",
                name = "旧满行",
                kind = KeyboardPageKind.ENGLISH,
                rows =
                    listOf(
                        LayoutRow(literalKeys(10)),
                        LayoutRow(literalKeys(9)),
                        LayoutRow(literalKeys(32)),
                    ),
            )
        rowLimited.validate()
        val materialisedRow = rowLimited.materialized()
        assertEquals(34, materialisedRow.rows[2].keys.size)
        materialisedRow.validate()
        assertEquals(materialisedRow, roundTrip(materialisedRow))

        // 256 row keys (the legacy total) plus the footer and the inserted Delete.
        val rowPage =
            KeyboardPageDefinition(
                id = "old.rows256",
                name = "旧满键",
                kind = KeyboardPageKind.NUMERIC,
                rows = List(8) { LayoutRow(literalKeys(32)) },
            )
        rowPage.validate()
        val materialisedRows = rowPage.materialized()
        assertEquals(258, materialisedRows.rows.flatMap { it.keys }.size)
        materialisedRows.validate()
        materialisedRows.duplicated("copy.rows256", "副本").validate()
        assertEquals(materialisedRows, roundTrip(materialisedRows))

        // 4096 category keys (the legacy categorized page cap) plus the footer and rail.
        val categorized =
            KeyboardPageDefinition(
                id = "old.cat4096",
                name = "旧满分类",
                kind = KeyboardPageKind.SYMBOLS,
                rows = emptyList(),
                symbolPresentation = KeyboardSymbolPresentation.CATEGORIZED,
                categories =
                    listOf(
                        KeyboardPageCategory(id = "c1", name = "一", keys = literalKeys(2048)),
                        KeyboardPageCategory(id = "c2", name = "二", keys = literalKeys(2048)),
                    ),
            )
        categorized.validate()
        val materialisedCategories = categorized.materialized()
        assertEquals(8, requireNotNull(materialisedCategories.chrome).allKeys.size)
        materialisedCategories.validate()
        assertEquals(materialisedCategories, roundTrip(materialisedCategories))
    }

    @Test
    fun materialisedRowPageAtTheLegacyTextCapUsesTheAllowance() {
        val rows =
            List(8) { row ->
                LayoutRow(
                    List(32) { column ->
                        val isLast = row == 7 && column == 31
                        LayoutKey(
                            "x".repeat(250),
                            action =
                                LayoutAction.literal(
                                    if (isLast) "v".repeat(1000) else "v",
                                ),
                        )
                    },
                )
            }
        val page =
            KeyboardPageDefinition(id = "old.rowtext", name = "旧满行文本", kind = KeyboardPageKind.NUMERIC, rows = rows)
        page.validate()
        val materialised = page.materialized()
        materialised.validate()
        assertEquals(materialised, roundTrip(materialised))
    }

    @Test
    fun materialisedPageOverTheControlAllowanceStillFails() {
        fun overAllowance(page: KeyboardPageDefinition): KeyboardPageDefinition {
            val materialised = page.materialized()
            val chrome = requireNotNull(materialised.chrome)
            return materialised.withChrome(
                chrome.copy(
                    bottom =
                        chrome.bottom +
                            List(LayoutResourceBudget.PAGE_CONTROL_KEYS) {
                                LayoutKey("x" + it, action = LayoutAction(LayoutActionKind.CLEAR_INPUT))
                            },
                ),
            )
        }
        val wide =
            KeyboardPageDefinition(
                id = "old.over",
                name = "超量",
                kind = KeyboardPageKind.NUMERIC,
                rows = List(8) { LayoutRow(literalKeys(32)) },
            )
        assertThrows(LayoutException::class.java) { overAllowance(wide).validate() }

        // Chrome keys spend the page budget, so a full row page leaves no room for extra author keys.
        val full =
            KeyboardPagePresets.numericAutomatic.materialized().withRows(
                (0 until LayoutResourceBudget.KEYS step LayoutResourceBudget.KEYS_PER_REGION)
                    .map { _ -> LayoutRow(literalKeys(LayoutResourceBudget.KEYS_PER_REGION)) },
            )
        val crowded =
            full.withChrome(
                requireNotNull(full.chrome).copy(
                    bottom =
                        requireNotNull(full.chrome).bottom +
                            List(LayoutResourceBudget.PAGE_CONTROL_KEYS) {
                                LayoutKey("extra" + it, action = LayoutAction(LayoutActionKind.CLEAR_INPUT))
                            },
                ),
            )
        assertThrows(LayoutException::class.java) { crowded.validate() }

        val longLabel =
            KeyboardPagePresets.numericAutomatic.materialized().withChrome(
                requireNotNull(KeyboardPagePresets.numericAutomatic.materialized().chrome).copy(
                    bottom =
                        listOf(
                            LayoutKey("x".repeat(300), action = LayoutAction(LayoutActionKind.LANGUAGE_SWITCH)),
                        ),
                ),
            )
        assertThrows(LayoutException::class.java) { longLabel.validate() }
    }

    @Test
    fun alternateCapStillCopiesWithTheDefaultFooter() {
        val options =
            List(LayoutResourceBudget.ALTERNATES_PER_KEY) { index ->
                LayoutOption("o" + index, LayoutAction.literal("o" + index))
            }
        val keys =
            List(LayoutResourceBudget.KEYS_PER_REGION * 2) { index ->
                LayoutKey("k" + index, action = LayoutAction.literal("k" + index), longPress = options)
            }
        val page =
            KeyboardPageDefinition(
                id = "old.alt",
                name = "旧满副键",
                kind = KeyboardPageKind.NUMERIC,
                rows =
                    listOf(
                        LayoutRow(keys.take(LayoutResourceBudget.KEYS_PER_REGION)),
                        LayoutRow(keys.drop(LayoutResourceBudget.KEYS_PER_REGION)),
                    ),
            ).materialized(numericMarks = KeyboardNumericMarks.ASCII)
        // The default footer's comma adds its five sentence marks on top of the page's own list,
        // which the control allowance covers.
        assertEquals(
            LayoutResourceBudget.ALTERNATES + LayoutResourceBudget.PAGE_CONTROL_ALTERNATES,
            page.allKeys.sumOf { it.longPress?.size ?: 0 },
        )
        page.validate()
        assertEquals(page, roundTrip(page))
    }

    @Test
    fun chromeRailsExposeTheirWeightsAndRejectOutOfRangeEdits() {
        val chrome = requireNotNull(KeyboardPagePresets.numericKeypad.chrome)
        assertEquals(listOf(1.0, 1.0, 1.0), chrome.railWeights(KeyboardPageChrome.Region.LEADING))
        assertEquals(listOf(1.0, 1.0, 1.0), chrome.railWeights(KeyboardPageChrome.Region.TRAILING))
        assertNull(chrome.railWeights(KeyboardPageChrome.Region.BOTTOM))
        assertNull(
            chrome.replacingKey(
                KeyboardPageChrome.Region.BOTTOM,
                99,
                LayoutKey("x", action = LayoutAction(LayoutActionKind.SPACE)),
            ),
        )
        assertEquals(
            "x",
            requireNotNull(
                chrome.replacingKey(
                    KeyboardPageChrome.Region.TRAILING,
                    0,
                    LayoutKey("x", action = LayoutAction(LayoutActionKind.BACKSPACE)),
                ),
            ).trailing[0].label,
        )
    }

    @Test
    fun compositionHelpersAddressOnePagePerSlot() {
        val composition = KeyboardComposition(mainLayoutID = "qwerty26")
        assertEquals(KeyboardPagePresets.SYMBOLS_SYSTEM_ID, composition.pageId(KeyboardPageKind.SYMBOLS))
        assertEquals(
            KeyboardPagePresets.NUMERIC_KEYPAD_ID,
            composition.withPageId(KeyboardPagePresets.NUMERIC_KEYPAD_ID, KeyboardPageKind.NUMERIC).numericPageID,
        )
        assertEquals("phone9", composition.withMainLayoutId("phone9").mainLayoutID)
    }
}

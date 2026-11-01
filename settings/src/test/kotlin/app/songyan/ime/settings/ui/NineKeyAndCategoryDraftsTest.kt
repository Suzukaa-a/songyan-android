package app.songyan.ime.settings.ui

import app.songyan.ime.layout.BuiltinLayouts
import app.songyan.ime.layout.KeyboardPageCategory
import app.songyan.ime.layout.KeyboardPagePresets
import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutAuxiliaryArea
import app.songyan.ime.layout.LayoutKey
import app.songyan.ime.layout.LayoutResourceBudget
import app.songyan.ime.layout.LayoutRow
import app.songyan.ime.layout.PhoneKeypadRailPolicy
import app.songyan.ime.layout.SymbolPageCategories
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The nine-key layout draft (fixed grid, declared side columns, untouched control row) and the category draft of a symbol page. */
class NineKeyAndCategoryDraftsTest {
    private val leading = LayoutAuxiliaryArea.Position.LEADING
    private val trailing = LayoutAuxiliaryArea.Position.TRAILING

    private fun key(label: String) = LayoutKey(label, action = LayoutAction.literal(label))

    private fun nine() = LayoutDraft(BuiltinLayouts.phone9.copyWithId("mine", "我的九键"))

    // ---- the nine-key layout draft ----

    @Test
    fun theNineKeyDraftEditsTheThreeByThreeGridAndLeavesTheControlRowAlone() {
        val draft = nine()
        assertTrue(draft.isNineKey)
        assertEquals(3, draft.letterRows.size)
        assertEquals(KeySelection(0, 0), draft.selection)
        val moved = draft.select(KeySelection(1, 1))
        val edited = moved.replaceSelectedKey(moved.selectedKey!!.copy(label = "我的"))
        assertEquals(
            "我的",
            edited.layout.rows[1]
                .keys[1]
                .label,
        )
        // The row the keyboard replaces with its own bottom row is data that must come back unchanged.
        assertEquals(BuiltinLayouts.phone9.rows.last(), edited.layout.rows.last())
        assertEquals(BuiltinLayouts.phone9.rows.size, edited.layout.rows.size)
        edited.layout.validate()
    }

    @Test
    fun aNineKeyLayoutWithAControlRowThatHasNoSpaceKeyStillKeepsItOutOfTheGrid() {
        val preset = BuiltinLayouts.phone9
        val control =
            LayoutRow(listOf(LayoutKey("布局", action = LayoutAction(LayoutActionKind.MODE_SWITCH, "nextLayout"))))
        val draft = LayoutDraft(preset.copy(rows = preset.rows.take(3) + control))
        assertEquals(3, draft.letterRows.size)
        val edited = draft.replaceSelectedKey(draft.selectedKey!!.copy(label = "x"))
        assertEquals(control, edited.layout.rows.last())
    }

    @Test
    fun aRowLayoutIsNotANineKeyDraft() {
        assertFalse(LayoutDraft(BuiltinLayouts.qwerty26).isNineKey)
        assertFalse(LayoutDraft(BuiltinLayouts.merged14).isNineKey)
    }

    @Test
    fun theSideColumnsStartAsTheKeyboardsOwnAndDeclaringThemKeepsTheLayoutValid() {
        val draft = nine()
        assertTrue(draft.usesBundledRails)
        val declared = draft.declareRails()
        assertFalse(declared.usesBundledRails)
        assertTrue(declared.hasChanges)
        assertEquals(BuiltinLayouts.t9QuickRailValues("", false), declared.railKeys(leading).map { it.label })
        assertEquals(listOf("⌫", "重输"), declared.railKeys(trailing).map { it.label })
        declared.layout.validate()
        // Handing them back restores exactly what the preset declares.
        val back = declared.useBundledRails()
        assertTrue(back.usesBundledRails)
        assertEquals(BuiltinLayouts.phone9.auxiliaryAreas, back.layout.auxiliaryAreas)
    }

    @Test
    fun railKeysAreAddedMovedEditedAndRemovedWithinTheirColumn() {
        var draft = nine().declareRails()
        draft = draft.addRailKey(leading, key("@"))
        assertEquals(RailSelection(leading, 3), draft.rail)
        assertNull(draft.selection)
        assertEquals("@", draft.selectedKey!!.label)
        draft = draft.replaceSelectedKey(draft.selectedKey!!.copy(label = "＠", action = LayoutAction.literal("＠")))
        assertEquals("＠", draft.railKeys(leading).last().label)
        draft = draft.moveSelectedRailKey(-1)
        assertEquals(RailSelection(leading, 2), draft.rail)
        assertEquals("＠", draft.railKeys(leading)[2].label)
        // The column is full at its limit.
        assertEquals(PhoneKeypadRailPolicy.MAX_RAIL_KEYS, draft.railKeys(leading).size)
        assertEquals(draft, draft.addRailKey(leading, key("#")))
        draft = draft.removeSelectedRailKey()
        assertEquals(3, draft.railKeys(leading).size)
        assertEquals(RailSelection(leading, 2), draft.rail)
        draft.layout.validate()
    }

    @Test
    fun anEmptiedColumnStaysDeclaredAndTheReadingAreaIsNeverTouched() {
        var draft = nine().declareRails().selectRail(RailSelection(trailing, 0))
        repeat(2) { draft = draft.removeSelectedRailKey() }
        assertTrue(draft.railKeys(trailing).isEmpty())
        assertNull(draft.rail)
        assertFalse(draft.usesBundledRails)
        assertTrue(draft.layout.auxiliaryAreas!!.any { it.role == LayoutAuxiliaryArea.Role.DISAMBIGUATION })
        draft.layout.validate()
    }

    @Test
    fun selectingAGridKeyLeavesTheRailAndTheOtherWayRound() {
        val draft = nine().declareRails().selectRail(RailSelection(leading, 1))
        assertEquals(RailSelection(leading, 1), draft.rail)
        assertNull(draft.selection)
        val grid = draft.select(KeySelection(2, 2))
        assertNull(grid.rail)
        assertEquals(KeySelection(2, 2), grid.selection)
        assertEquals(draft, draft.selectRail(RailSelection(leading, 9)))
    }

    // ---- categories ----

    private fun categorized(): PageDraft {
        val categories =
            listOf(
                KeyboardPageCategory("a", "甲", columns = 3, keys = listOf(key("1"), key("2"), key("3"), key("4"))),
                KeyboardPageCategory("b", "乙", keys = listOf(key("5"))),
                KeyboardPageCategory("r", "最近", keys = emptyList(), source = KeyboardPageCategory.Source.RECENT),
            )
        return PageDraft(KeyboardPagePresets.symbolsCategorized.copy(id = "mine", name = "我的", categories = categories))
    }

    @Test
    fun aCategorizedPageIsEditedByItsCategoriesNotItsRows() {
        assertTrue(categorized().isCategorized)
        // The built-in page carries none of its own, so there is nothing to edit until it is copied.
        assertFalse(PageDraft(KeyboardPagePresets.symbolsCategorized).isCategorized)
        assertFalse(KeyboardPagePresets.symbolsCategorized.isEditablePage)
        assertTrue(categorized().page.isEditablePage)
        assertFalse(PageDraft(KeyboardPagePresets.numericAutomatic.duplicated("m", "m")).isCategorized)
    }

    @Test
    fun categoriesAreAddedDuplicatedMovedAndRemovedKeepingAtLeastOne() {
        var draft = categorized()
        draft = draft.addCategory("n", "新")
        assertEquals(listOf("甲", "乙", "最近", "新"), draft.categories.map { it.name })
        assertEquals(3, draft.categoryIndex)
        assertTrue(
            draft.page.categories!!
                .last()
                .keys
                .isNotEmpty(),
        )
        draft = draft.selectCategory(0).duplicateCategory("c", "甲 副本")
        assertEquals(listOf("甲", "甲 副本", "乙", "最近", "新"), draft.categories.map { it.name })
        assertNotEquals(draft.categories[0].id, draft.categories[1].id)
        assertEquals(1, draft.categoryIndex)
        draft = draft.moveCategory(+1)
        assertEquals(listOf("甲", "乙", "甲 副本", "最近", "新"), draft.categories.map { it.name })
        assertEquals(2, draft.categoryIndex)
        assertEquals(draft.categories, draft.selectCategory(0).moveCategory(-1).categories)
        repeat(4) { draft = draft.removeCategory() }
        assertEquals(1, draft.categories.size)
        assertEquals(draft, draft.removeCategory())
        draft.page.validate()
    }

    @Test
    fun aCategoryTakesItsNameColumnsAndKeys() {
        var draft = categorized().renameCategory("常用")
        assertEquals("常用", draft.category!!.name)
        draft = draft.setCategoryColumns(2)
        assertEquals(listOf(2, 2), draft.category!!.rows.map { it.keys.size })
        assertEquals(LayoutResourceBudget.CATEGORY_COLUMNS, draft.setCategoryColumns(99).category!!.columns)
        assertEquals(1, draft.setCategoryColumns(0).category!!.columns)
        draft = draft.selectCategoryKey(1).addCategoryKey()
        assertEquals(2, draft.categoryKey)
        assertEquals(5, draft.category!!.keys.size)
        draft = draft.replaceSelectedCategoryKey(key("新"))
        assertEquals(listOf("1", "2", "新", "3", "4"), draft.category!!.keys.map { it.label })
        draft = draft.moveCategoryKey(+1)
        assertEquals(listOf("1", "2", "3", "新", "4"), draft.category!!.keys.map { it.label })
        draft = draft.removeCategoryKey()
        assertEquals(listOf("1", "2", "3", "4"), draft.category!!.keys.map { it.label })
        draft.page.validate()
    }

    @Test
    fun theLastKeyOfAFixedCategoryStays() {
        val draft = categorized().selectCategory(1)
        assertEquals(draft, draft.removeCategoryKey())
    }

    @Test
    fun aCategoryThatWasRecentKeepsItsOwnKeysWhenSwitchedBack() {
        var draft = categorized()
        draft = draft.setCategorySource(KeyboardPageCategory.Source.RECENT)
        assertEquals(KeyboardPageCategory.Source.RECENT, draft.category!!.source)
        assertEquals(4, draft.category!!.keys.size)
        draft = draft.setCategorySource(KeyboardPageCategory.Source.FIXED)
        assertEquals(4, draft.category!!.keys.size)
        // A recent category with nothing of its own gets one key when it becomes fixed, so the page stays valid.
        draft = draft.selectCategory(2).setCategorySource(KeyboardPageCategory.Source.FIXED)
        assertEquals(1, draft.category!!.keys.size)
        draft.page.validate()
    }

    @Test
    fun aSplitStaysInsideTheRowsWhenTheColumnsChange() {
        val split =
            app.songyan.ime.layout.LayoutSplitConfiguration.of(
                app.songyan.ime.layout.LayoutSplitConfiguration.Activation.ALWAYS,
                listOf(3, 1),
                0.2,
            )
        val base = categorized()
        val withSplit =
            base.copy(
                page =
                    base.page.withCategories(
                        base.categories.mapIndexed { i, c ->
                            if (i ==
                                0
                            ) {
                                c.withSplit(split)
                            } else {
                                c
                            }
                        },
                    ),
            )
        val narrower = withSplit.setCategoryColumns(2)
        val result = narrower.category!!
        for ((row, cut) in result.split!!.rowBreaks.withIndex()) {
            assertTrue(cut in 1..result.rows[row].keys.size)
        }
        narrower.page.validate()
    }

    @Test
    fun aCopyOfTheBuiltInPageStartsFromTheSymbolsTheKeyboardShows() {
        val snapshot = SymbolPageCategories.snapshot(emptyList(), emptyList(), emptyList())
        val copy = KeyboardPagePresets.symbolsCategorized.withCategories(snapshot).duplicated("copy", "副本")
        assertEquals(snapshot, copy.categories)
        assertTrue(copy.isEditablePage)
        assertTrue(PageDraft(copy).isCategorized)
        copy.validate()
    }
}

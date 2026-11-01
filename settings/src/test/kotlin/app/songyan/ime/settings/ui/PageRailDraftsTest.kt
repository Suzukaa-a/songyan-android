package app.songyan.ime.settings.ui

import app.songyan.ime.layout.KeyboardPageCategory
import app.songyan.ime.layout.KeyboardPagePresets
import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutAuxiliaryArea.Position
import app.songyan.ime.layout.LayoutKey
import app.songyan.ime.layout.LayoutResourceBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Side columns of an authored page are edited as data; the footer stays as it was. */
class PageRailDraftsTest {
    private fun key(label: String) = LayoutKey(label, action = LayoutAction.literal(label))

    private fun draft() = PageDraft(KeyboardPagePresets.numericAutomatic.duplicated("mine", "我的"))

    @Test
    fun addingKeysFillsTheColumnAndSelectsTheNewOne() {
        val added = draft().addRailKey(Position.TRAILING, key("甲")).addRailKey(Position.TRAILING, key("乙"))
        assertEquals(listOf("甲", "乙"), added.railKeys(Position.TRAILING).map { it.label })
        assertEquals(RailSelection(Position.TRAILING, 1), added.rail)
        assertNull(added.selection)
        assertEquals("乙", added.selectedKey!!.label)
        assertTrue(added.railKeys(Position.LEADING).isEmpty())
    }

    @Test
    fun editingARailKeyChangesItsSpanAndLeavesTheFooterAlone() {
        val base = draft()
        val footer = base.page.chrome!!.bottom
        val edited =
            base
                .addRailKey(Position.LEADING, key("甲"))
                .replaceSelectedKey(key("甲").copy(width = 2.0, label = "丙"))
        assertEquals(2.0, edited.railKeys(Position.LEADING).single().widthWeight, 0.0)
        assertEquals("丙", edited.railKeys(Position.LEADING).single().label)
        assertEquals(footer, edited.page.chrome!!.bottom)
        assertEquals(base.page.rows, edited.page.rows)
    }

    @Test
    fun movingAndRemovingKeepTheSelectionOnAKey() {
        val three =
            draft()
                .addRailKey(Position.LEADING, key("a"))
                .addRailKey(Position.LEADING, key("b"))
                .addRailKey(Position.LEADING, key("c"))
        val moved = three.moveSelectedRailKey(-1)
        assertEquals(listOf("a", "c", "b"), moved.railKeys(Position.LEADING).map { it.label })
        assertEquals(1, moved.rail!!.index)
        assertEquals(moved, moved.moveSelectedRailKey(-5))
        val removed = moved.removeSelectedRailKey()
        assertEquals(listOf("a", "b"), removed.railKeys(Position.LEADING).map { it.label })
        assertEquals(1, removed.rail!!.index)
        val empty = removed.removeSelectedRailKey().removeSelectedRailKey()
        assertTrue(empty.railKeys(Position.LEADING).isEmpty())
        assertNull(empty.rail)
    }

    @Test
    fun aColumnHoldsNoMoreKeysThanTheBudget() {
        var draft = draft()
        repeat(LayoutResourceBudget.KEYS_PER_REGION + 3) { draft = draft.addRailKey(Position.TRAILING, key("x")) }
        assertEquals(LayoutResourceBudget.KEYS_PER_REGION, draft.railKeys(Position.TRAILING).size)
    }

    @Test
    fun widthsAndTheBottomRowSpanAreStoredAndFollowTheKeyboardWhenEmpty() {
        val base = draft().addRailKey(Position.TRAILING, key("a"))
        assertNull(base.railWidth(Position.TRAILING))
        assertFalse(base.trailingSpansBottom)
        val set = base.setRailWidth(Position.TRAILING, 60.0).setTrailingSpansBottom(true)
        assertEquals(60.0, set.railWidth(Position.TRAILING)!!, 0.0)
        assertTrue(set.trailingSpansBottom)
        assertNull(set.setRailWidth(Position.TRAILING, null).railWidth(Position.TRAILING))
    }

    @Test
    fun anEditedPageStaysValidAndSelectingARowKeyClearsTheColumnSelection() {
        val edited = draft().addRailKey(Position.LEADING, key("a")).setRailWidth(Position.LEADING, 48.0)
        edited.page.validate()
        val back = edited.select(KeySelection(0, 0))
        assertNull(back.rail)
        assertEquals(KeySelection(0, 0), back.selection)
    }

    @Test
    fun theCategorizedPageOffersNoColumns() {
        val categories = listOf(KeyboardPageCategory("a", "甲", columns = 3, keys = listOf(key("1"))))
        val categorized =
            PageDraft(KeyboardPagePresets.symbolsCategorized.copy(id = "m", name = "我的", categories = categories))
        assertFalse(categorized.allowsRails)
        assertEquals(categorized, categorized.addRailKey(Position.TRAILING, key("x")))
        assertTrue(draft().allowsRails)
    }
}

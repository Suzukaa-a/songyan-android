package app.songyan.ime.settings.ui

import app.songyan.ime.layout.BuiltinLayouts
import app.songyan.ime.layout.KeyboardPagePresets
import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutInput
import app.songyan.ime.layout.LayoutKey
import app.songyan.ime.layout.LayoutPageJump
import app.songyan.ime.layout.LayoutPageTarget
import app.songyan.ime.layout.LayoutRow
import app.songyan.ime.layout.LayoutSplitConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorDraftsTest {
    private fun key(label: String) = LayoutKey(label, action = LayoutAction.literal(label))

    private fun labels(rows: List<LayoutRow>) = rows.map { row -> row.keys.joinToString("") { it.label } }

    @Test
    fun aLayoutDraftEditsTheLetterRowsAndKeepsTheControlRow() {
        val draft = LayoutDraft(BuiltinLayouts.qwerty26)
        assertEquals(3, draft.letterRows.size)
        assertEquals(KeySelection(0, 0), draft.selection)
        assertEquals("q", draft.selectedKey!!.label)
        val edited = draft.replaceSelectedKey(draft.selectedKey!!.copy(label = "Q1"))
        assertTrue(edited.hasChanges)
        assertEquals(
            "Q1",
            edited.layout.rows[0]
                .keys[0]
                .label,
        )
        // The control row is still the last row and untouched.
        assertEquals(BuiltinLayouts.qwerty26.rows.last(), edited.layout.rows.last())
        assertEquals(4, edited.layout.rows.size)
    }

    @Test
    fun aStructuralEditMovesTheSelectionAndKeepsTheSplitInsideItsRows() {
        val split = LayoutSplitConfiguration.of(rowBreaks = listOf(5, 5, 4, 3))
        val draft = LayoutDraft(BuiltinLayouts.qwerty26.withSplit(split))
        val shorter = draft.apply(KeyGridEditing.removeKey(draft.letterRows, KeySelection(2, 0)))
        // Seven letters plus shift/delete were nine keys; the cut of the row stays inside it.
        val row =
            shorter.layout.rows[2]
                .keys.size
        assertTrue(shorter.layout.split!!.rowBreaks[2] in 1 until row)
        assertEquals(draft.layout.rows.size, shorter.layout.rows.size)
    }

    @Test
    fun anEditThatCannotFinishLeavesTheDraftAlone() {
        val draft = LayoutDraft(BuiltinLayouts.qwerty26)
        assertEquals(draft, draft.apply(null))
        assertFalse(draft.apply(null).hasChanges)
    }

    @Test
    fun selectingAKeyThatIsNotThereClearsTheSelection() {
        val draft = LayoutDraft(BuiltinLayouts.qwerty26)
        assertNull(draft.select(KeySelection(9, 9)).selection)
        assertEquals(KeySelection(1, 2), draft.select(KeySelection(1, 2)).selection)
    }

    @Test
    fun renamingChangesOnlyTheName() {
        val draft = LayoutDraft(BuiltinLayouts.merged14).rename("我的 14 键")
        assertEquals("我的 14 键", draft.layout.name)
        assertEquals(BuiltinLayouts.merged14.rows, draft.layout.rows)
    }

    @Test
    fun aPageDraftEditsItsRowsAndKeepsItsFooter() {
        val page = KeyboardPagePresets.numericAutomatic.duplicated("mine", "我的")
        val draft = PageDraft(page)
        val edited = draft.replaceSelectedKey(draft.selectedKey!!.copy(label = "壹"))
        assertEquals(
            "壹",
            edited.page.rows[0]
                .keys[0]
                .label,
        )
        assertEquals(page.chrome, edited.page.chrome)
        assertEquals(page.name, edited.page.name)
    }

    @Test
    fun theSplitOfADraftIsClampedToItsRows() {
        val page = KeyboardPagePresets.numericAutomatic.duplicated("mine", "我的")
        val draft = PageDraft(page).withSplit(LayoutSplitConfiguration.of(rowBreaks = listOf(50, 50, 50)))
        val rows = draft.page.rows
        for ((index, cut) in draft.page.split!!
            .rowBreaks
            .withIndex()) {
            assertTrue(cut in 1 until rows[index].keys.size)
        }
    }

    // ---- the grid edits ----

    @Test
    fun aNewKeyLandsAfterTheSelectedOneAndIsSelected() {
        val rows = listOf(LayoutRow(listOf(key("a"), key("b"))))
        val edit = KeyGridEditing.addKeyAfter(rows, KeySelection(0, 0), key("x"))!!
        assertEquals(listOf("axb"), labels(edit.rows))
        assertEquals(KeySelection(0, 1), edit.selection)
    }

    @Test
    fun removingTheLastKeyOfARowSelectsTheRowBefore() {
        val rows = listOf(LayoutRow(listOf(key("a"), key("b"))), LayoutRow(listOf(key("c"))))
        val edit = KeyGridEditing.removeKey(rows, KeySelection(1, 0))!!
        assertEquals(listOf("ab"), labels(edit.rows))
        assertEquals(KeySelection(0, 0), edit.selection)
    }

    @Test
    fun removingAKeyKeepsTheSelectionInsideTheRow() {
        val rows = listOf(LayoutRow(listOf(key("a"), key("b"), key("c"))))
        assertEquals(KeySelection(0, 1), KeyGridEditing.removeKey(rows, KeySelection(0, 2))!!.selection)
        assertEquals(KeySelection(0, 0), KeyGridEditing.removeKey(rows, KeySelection(0, 0))!!.selection)
    }

    @Test
    fun movingAKeyFollowsItWithTheSelection() {
        val rows = listOf(LayoutRow(listOf(key("a"), key("b"), key("c"))))
        val edit = KeyGridEditing.moveKey(rows, KeySelection(0, 0), +1)!!
        assertEquals(listOf("bac"), labels(edit.rows))
        assertEquals(KeySelection(0, 1), edit.selection)
        assertNull(KeyGridEditing.moveKey(rows, KeySelection(0, 0), -1))
    }

    @Test
    fun anAddedRowIsSelectedAndAMovedRowTakesTheSelectionAlong() {
        val rows = listOf(LayoutRow(listOf(key("a"))), LayoutRow(listOf(key("b"))))
        val added = KeyGridEditing.addRow(rows, key("x"))!!
        assertEquals(KeySelection(2, 0), added.selection)
        val moved = KeyGridEditing.moveRow(rows, KeySelection(0, 0), +1)!!
        assertEquals(listOf("b", "a"), labels(moved.rows))
        assertEquals(KeySelection(1, 0), moved.selection)
    }

    // ---- the action choices ----

    @Test
    fun changingTheActionKindCarriesOnlyWhatMakesSense() {
        val letter = LayoutKey("q", action = LayoutAction.code("q"))
        assertEquals(LayoutAction.literal("q"), KeyActionChoice.LITERAL.actionFor(letter))
        assertEquals(LayoutAction.code("q"), KeyActionChoice.CODE.actionFor(letter))
        assertEquals(LayoutAction.pageSwitch(LayoutPageTarget.NUMERIC), KeyActionChoice.PAGE_SWITCH.actionFor(letter))
        assertEquals(LayoutAction(LayoutActionKind.SHIFT), KeyActionChoice.SHIFT.actionFor(letter))
        assertEquals("nextLayout", KeyActionChoice.MODE_SWITCH.actionFor(letter).value)
    }

    @Test
    fun pagesNeverOfferTheEngineCodeAction() {
        assertTrue(KeyActionChoice.allowed(allowCode = true).contains(KeyActionChoice.CODE))
        assertFalse(KeyActionChoice.allowed(allowCode = false).contains(KeyActionChoice.CODE))
        assertEquals(KeyActionChoice.SPACE, KeyActionChoice.of(LayoutAction(LayoutActionKind.SPACE)))
    }

    @Test
    fun theEditorOffersTheInputMethodSwitchKey() {
        assertEquals(KeyActionChoice.GLOBE, KeyActionChoice.of(LayoutAction(LayoutActionKind.GLOBE)))
        assertTrue(KeyActionChoice.allowed(allowCode = false).contains(KeyActionChoice.GLOBE))
        val letter = LayoutKey("q", action = LayoutAction.code("q"))
        assertEquals(LayoutAction(LayoutActionKind.GLOBE), KeyActionChoice.GLOBE.actionFor(letter))
    }

    // ---- jump keys ----

    private val pageLabel: (LayoutPageTarget) -> String = {
        when (it) {
            LayoutPageTarget.NUMERIC -> "123"
            LayoutPageTarget.SYMBOLS -> "符号"
            LayoutPageTarget.LETTERS -> "字母"
        }
    }

    @Test
    fun onlyPagesOfferTheJumpKey() {
        assertTrue(KeyActionChoice.allowed(allowCode = false, allowJump = true).contains(KeyActionChoice.JUMP))
        assertFalse(KeyActionChoice.allowed(allowCode = true).contains(KeyActionChoice.JUMP))
        assertFalse(KeyActionChoice.allowed(allowCode = false).contains(KeyActionChoice.JUMP))
        val jump = LayoutAction.layoutPageSwitch(LayoutPageJump(LayoutInput.Builtin.PHONE9, LayoutPageTarget.NUMERIC))
        assertEquals(KeyActionChoice.JUMP, KeyActionChoice.of(jump))
        // A key that already jumps keeps its target; any other key starts at the first target listed.
        assertEquals(jump, KeyActionChoice.JUMP.actionFor(LayoutKey("x", action = jump)))
        assertEquals(LayoutPageJump.all.first(), KeyActionChoice.JUMP.actionFor(key("x")).layoutPageJump)
    }

    @Test
    fun aJumpKeyIsNamedAfterItsPageUntilTheUserNamesIt() {
        val toNineKeyDigits = LayoutPageJump(LayoutInput.Builtin.PHONE9, LayoutPageTarget.NUMERIC)
        val toNineKeySymbols = LayoutPageJump(LayoutInput.Builtin.PHONE9, LayoutPageTarget.SYMBOLS)
        val fresh = JumpKeyEditing.withTarget(key("·").copy(label = ""), toNineKeyDigits, pageLabel)
        assertEquals("123", fresh.label)
        assertEquals(toNineKeyDigits, fresh.action.layoutPageJump)
        // The generated name follows the target's page.
        assertEquals("符号", JumpKeyEditing.withTarget(fresh, toNineKeySymbols, pageLabel).label)
        // A name the user wrote stays.
        val named = fresh.copy(label = "九键数字")
        val retargeted = JumpKeyEditing.withTarget(named, toNineKeySymbols, pageLabel)
        assertEquals("九键数字", retargeted.label)
        assertEquals(toNineKeySymbols, retargeted.action.layoutPageJump)
    }
}

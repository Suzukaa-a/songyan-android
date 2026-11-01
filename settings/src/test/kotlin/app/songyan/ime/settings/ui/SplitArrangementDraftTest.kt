package app.songyan.ime.settings.ui

import app.songyan.ime.layout.BuiltinLayouts
import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutKey
import app.songyan.ime.layout.LayoutRow
import app.songyan.ime.layout.LayoutSplitConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitArrangementDraftTest {
    private fun letter(c: Char) = LayoutKey(c.toString(), action = LayoutAction.code(c.toString()))

    private fun text(rows: List<LayoutRow>) = rows.map { row -> row.keys.joinToString("") { it.label } }

    private fun LayoutDraft.add(
        row: Int,
        key: Int,
        c: Char,
    ): LayoutDraft =
        select(KeySelection(row, key)).let {
            it.apply(KeyGridEditing.addKeyAfter(it.editRows, KeySelection(row, key), letter(c)))
        }

    private val start = LayoutDraft(BuiltinLayouts.qwerty26).withSplitView(true)

    @Test
    fun theSplitViewShowsTheRowsCutAtTheirBreaksUntilTheFirstEdit() {
        assertEquals(text(start.letterRows), text(start.splitLetterRows))
        assertEquals(listOf(5, 5, 4), start.splitCuts)
        assertFalse(start.hasExplicitSplit)
        assertFalse(start.hasChanges)
    }

    @Test
    fun theFirstEditInTheSplitViewMakesTheArrangementExplicitAndLeavesTheWholeRowsAlone() {
        // y after the fifth key of the first row lands in the left half, where the cut is.
        val added = start.add(0, 4, 'y')
        assertTrue(added.hasExplicitSplit)
        val halves = added.layout.split!!.halves!!
        assertEquals("qwerty", halves[0].left.joinToString("") { it.label })
        assertEquals("yuiop", halves[0].right.joinToString("") { it.label })
        assertEquals(BuiltinLayouts.qwerty26.rows, added.layout.rows)
        // One entry per row of the layout, the control row's two space halves included.
        assertEquals(BuiltinLayouts.qwerty26.rows.size, halves.size)
        added.layout.validate()
    }

    @Test
    fun aKeyAddedBeforeTheCutIsInTheLeftHalfAndAfterItIsInTheRight() {
        val left =
            start.select(KeySelection(1, 0)).let {
                it.apply(KeyGridEditing.addKeyAfter(it.editRows, KeySelection(1, 0), letter('1')))
            }
        assertEquals(6, left.splitCuts[1])
        val right =
            start.select(KeySelection(1, 5)).let {
                it.apply(KeyGridEditing.addKeyAfter(it.editRows, KeySelection(1, 5), letter('2')))
            }
        assertEquals(5, right.splitCuts[1])
    }

    @Test
    fun aDraftCannotRemoveTheOnlyKeyOfAHalf() {
        var draft = start
        // Row 2 is zxcv | bnm: take the right half down to one key, then try to remove that one.
        for (i in 0..1) {
            draft = draft.select(KeySelection(2, 4)).apply(KeyGridEditing.removeKey(draft.editRows, KeySelection(2, 4)))
        }
        val edit = KeyGridEditing.removeKey(draft.editRows, KeySelection(2, 4))
        assertEquals(draft, draft.select(KeySelection(2, 4)).apply(edit))
        assertEquals(1, draft.splitLetterRows[2].keys.size - draft.splitCuts[2])
    }

    @Test
    fun movingACutWithoutAnArrangementEditsTheBreakAndWithOneMovesAKeyAcross() {
        val moved = start.moveSplitCut(0, -1)
        assertFalse(moved.hasExplicitSplit)
        assertEquals(4, moved.layout.split!!.breakIndex(0, 10))
        val explicit = start.add(0, 0, 'y')
        val across = explicit.moveSplitCut(0, +1)
        assertEquals(explicit.splitCuts[0] + 1, across.splitCuts[0])
        assertEquals(text(explicit.splitLetterRows), text(across.splitLetterRows))
    }

    @Test
    fun restoringTheAutomaticSplitDropsTheArrangement() {
        val explicit = start.add(0, 0, 'y')
        val restored = explicit.restoreAutomaticSplit()
        assertNull(restored.layout.split!!.halves)
        assertEquals(listOf(5, 5, 4), restored.splitCuts)
    }

    @Test
    fun aRowAddedInTheWholeViewFollowsIntoTheSavedArrangement() {
        val explicit = start.add(0, 0, 'y')
        val whole = explicit.withSplitView(false)
        val grown = whole.apply(KeyGridEditing.addRow(whole.editRows, letter('9')))
        val halves = grown.layout.split!!.halves!!
        assertEquals(grown.layout.rows.size, halves.size)
        grown.layout.validate()
        // The control row is still last, with its two space halves.
        assertEquals("9", halves[3].keys.single().label)
    }

    @Test
    fun theGapDefaultsToAutomatic() {
        assertTrue(
            LayoutDraft(BuiltinLayouts.qwerty26)
                .withSplit(LayoutSplitConfiguration())
                .layout.split!!
                .automaticGap,
        )
    }
}

package app.songyan.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorSelectionTest {
    private fun typed(vararg preedits: String): EditorSelection =
        EditorSelection().apply {
            reset(0, 0)
            var composing = false
            for (preedit in preedits) {
                afterUpdate(emptyList(), preedit, composing)
                composing = preedit.isNotEmpty()
            }
        }

    @Test
    fun aLateReportOfAnEarlierKeystrokeIsExpected() {
        // n, i, h typed; the editor reports the cursor after `n` only now.
        val selection = typed("n", "ni", "nih")
        assertEquals(3, selection.start)
        assertTrue(selection.isExpected(0, 0, 1, 1))
        assertTrue(selection.isExpected(1, 1, 2, 2))
        assertTrue(selection.isExpected(2, 2, 3, 3))
    }

    @Test
    fun aCursorTheUserMovedIsNotExpected() {
        val selection = typed("ni")
        // The old values match what the keyboard expected and the cursor jumped elsewhere.
        assertFalse(selection.isExpected(2, 2, 0, 0))
        // A cursor beyond anything the keyboard wrote.
        assertFalse(selection.isExpected(1, 1, 7, 7))
        // A selection is never a catch-up report of a collapsed cursor.
        assertFalse(selection.isExpected(1, 1, 0, 2))
    }

    @Test
    fun unknownSelectionNeverClaimsAnUpdateIsExpected() {
        assertFalse(EditorSelection().isExpected(0, 0, 1, 1))
    }

    @Test
    fun aCommitReplacesTheComposingRegion() {
        val selection = typed("nihao")
        selection.afterUpdate(listOf("你好"), "", true)
        assertEquals(2, selection.start)
        assertEquals(EditorSelection.UNKNOWN, selection.composingStart)
        selection.afterUpdate(listOf("，"), "z", false)
        assertEquals(3, selection.composingStart)
        assertEquals(4, selection.end)
    }

    @Test
    fun theEditorsComposingRegionBeatsTheEstimate() {
        val selection = typed("ni")
        selection.reportedComposing(5, 7)
        assertEquals(5, selection.composingStart)
        assertEquals(7, selection.composingEnd)
        // -1 is unknown, not an ended composition.
        selection.reportedComposing(-1, -1)
        assertEquals(5, selection.composingStart)
    }

    @Test
    fun removingTheCompositionShiftsTheSelection() {
        assertEquals(3, EditorSelection.afterRemoving(5, 1, 3))
        assertEquals(1, EditorSelection.afterRemoving(2, 1, 3))
        assertEquals(0, EditorSelection.afterRemoving(0, 1, 3))
    }
}
